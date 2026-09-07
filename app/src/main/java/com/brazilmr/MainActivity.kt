package com.brazilmr

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.opengl.GLSurfaceView
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.brazilmr.bridge.*
import com.brazilmr.core.input.*
import com.brazilmr.core.performance.*
import com.brazilmr.core.permission.*
import com.brazilmr.core.session.EnvironmentMode
import com.brazilmr.core.spatial.CameraCoordinates
import com.brazilmr.core.spatial.SpatialProjection
import com.brazilmr.core.tracking.*
import com.brazilmr.core.window.*
import com.brazilmr.platform.*
import com.brazilmr.render.*
import com.brazilmr.scripting.AndroidLuaController
import com.brazilmr.spatial.*
import com.brazilmr.tracking.*
import com.brazilmr.ui.*
import kotlin.math.*

class MainActivity : ComponentActivity(), UiActions {
    private lateinit var state: PlatformState
    private lateinit var scene: XrUiScene
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: SBSRenderer
    private lateinit var hands: CameraHandTrackingManager
    private lateinit var camera: CameraController
    private lateinit var ar: ArCoreEnvironment
    private lateinit var head: HeadTracker
    private lateinit var lua: AndroidLuaController
    private lateinit var appBridge: AndroidAppBridge
    private lateinit var thermal: ThermalMonitor
    private lateinit var input: InputSystem
    private lateinit var textureExchange: UiTextureExchange
    private lateinit var uiCanvases: Array<Canvas>
    private val handler = Handler(Looper.getMainLooper())
    private val performance = PerformanceController()
    private val renderFrame = RenderFrame()
    private val inputProjection = SpatialProjection()
    private val uiCoordinates = FloatArray(2)
    private val cameraCoordinates = FloatArray(2)
    private val pointerHand = HandData(HandSide.RIGHT)
    private var resumed = false
    private var graphicsReady = false
    private var destroyed = false
    private var nativeDialog = false
    private var lastDraw = 0L; private var lastStats = 0L; private var lastLuaTick = 0L
    private var lastSequence = Long.MIN_VALUE
    private var lastRight = false; private var lastLeft = false
    private var previousUiMillis = 0f
    private var cameraKey = ""
    private var captureWindowId: Int? = null
    private var captureSurface: Surface? = null
    private var capturePending = false
    private var lastTrackingMessage = ""

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        state.cameraGranted = granted
        state.notice(if(granted) "Câmera autorizada" else "Câmera não autorizada", if(granted) "Passthrough e tracking são processados localmente." else "A plataforma continua disponível por toque, sem câmera.")
        configureCamera(true)
    }
    private val capturePermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        capturePending = false
        val surface = captureSurface
        if (result.resultCode == RESULT_OK && result.data != null && surface?.isValid == true) {
            try {
                ContextCompat.startForegroundService(this, Intent(this,ProjectionCaptureService::class.java).putExtra("consent",result.data).putExtra("surface",surface))
            } catch (error: Exception) { endCapture(error.message ?: "Não foi possível iniciar o compartilhamento") }
        } else endCapture("Compartilhamento cancelado; nenhum conteúdo foi capturado.")
    }
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!resumed || destroyed) return
            Choreographer.getInstance().postFrameCallback(this)
            if (!graphicsReady) return
            val now = frameTimeNanos/1_000_000
            val budget = performance.update(state.settings, renderer.lastFrameMillis+previousUiMillis, state.thermalStatus, state.hands.left.present || state.hands.right.present, now)
            hands.rateCap = budget.trackingFps; hands.thermalPaused = budget.pauseTracking || nativeDialog
            if (frameTimeNanos-lastDraw < 1_000_000_000L/budget.renderFps) return
            lastDraw = frameTimeNanos
            val begin = System.nanoTime()
            try {
                if (!nativeDialog) updateHands(now)
                if (now-lastStats >= 500) {
                    state.fps = renderer.fps; state.effectiveTrackingFps = if(budget.pauseTracking) 0 else budget.trackingFps
                    state.effectiveRenderScale = budget.renderScale
                    state.batteryPercent = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    state.accessibilityEnabled = AccessibilityBridgeService.connected != null
                    state.accessibilityConsent = AccessibilitySession.userConsented
                    state.dirty = true; lastStats = now
                }
                if (!nativeDialog && now-lastLuaTick >= 100) { lua.tick(now/1000.0); lastLuaTick = now }
                if (state.dirty) {
                    val slot = textureExchange.beginWrite()
                    if (slot >= 0) {
                        try { scene.draw(uiCanvases[slot]); textureExchange.publish(slot); state.dirty = false }
                        catch (error: Exception) { textureExchange.cancelWrite(slot); throw error }
                    }
                }
                fillRenderFrame(budget)
                renderer.publish(renderFrame); glView.requestRender()
            } catch (error: Exception) { state.log("Frame interrompido: ${error.message}"); input.reset(now) }
            previousUiMillis = (System.nanoTime()-begin)/1_000_000f
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window,false)
        state = PlatformState(this)
        state.cameraGranted = ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        head = HeadTracker(this); state.sensorAvailable = head.available
        state.spatialStatus = if(head.available) "3DoF · sensores" else "Sem giroscópio · toque disponível"
        hands = CameraHandTrackingManager(applicationContext) { status,detail -> handler.post {
            if (!destroyed) {
                state.trackingStatus = detail; state.dirty = true
                if (status in listOf(TrackingStatus.ERROR,TrackingStatus.NO_MODEL) && lastTrackingMessage != detail) { state.notice("Hand tracking",detail); lastTrackingMessage = detail }
            }
        } }
        hands.configuration = state.settings
        ar = ArCoreEnvironment(this,hands,{ status -> handler.post { if(!destroyed) { state.spatialStatus=status;state.dirty=true } } },{ error -> handler.post {
            if(!destroyed) { state.notice("Tracking espacial interrompido",error); state.saveSettings(state.settings.copy(spatialTracking=false)); configureCamera(true) }
        } })
        glView = GLSurfaceView(this).apply { setEGLContextClientVersion(2); preserveEGLContextOnPause=true }
        textureExchange = UiTextureExchange()
        uiCanvases = Array(2) { Canvas(textureExchange.bitmaps[it]) }
        appBridge = AndroidAppBridge(this)
        lua = AndroidLuaController(state,::modeChanged,::closeWindow)
        scene = XrUiScene(state,this)
        input = InputSystem(InputSink(::dispatchPointer))
        renderer = SBSRenderer(glView,textureExchange,ar,{
            if (!destroyed) { graphicsReady=true;state.dirty=true;configureCamera(true) }
        },{
            if(!destroyed) {
                appBridge.close(); endCapture("Contexto gráfico recriado; solicite novamente o compartilhamento.")
                for (window in state.windows.windows) if(window.content == WindowContent.ANDROID) { window.displayId=-1;window.status="Contexto gráfico recriado. Reabra este app." }
                state.dirty=true
            }
        },{ error ->
            graphicsReady=false
            if(!destroyed) showDialog(AlertDialog.Builder(this).setTitle("Renderer indisponível").setMessage("$error\n\nO aparelho precisa de OpenGL ES 2 com texturas externas OES.").setPositiveButton("Fechar") { _,_ -> finish() }.create())
        })
        glView.setRenderer(renderer); glView.renderMode=GLSurfaceView.RENDERMODE_WHEN_DIRTY
        camera = CameraController(this,this,hands,{ request -> renderer.provideCameraSurface(request) }) { active,detail ->
            state.cameraActive=active;state.dirty=true
            if(!active && detail != "Câmera pausada") state.notice("Câmera",detail)
        }
        val overlay = WorkspaceInputView(this,scene,renderer::readProjection) { action,x,y,time ->
            if(!nativeDialog) {
                if(!state.session.uiVisible && action == PointerAction.DOWN) { state.session.setUiVisible(true);recenter();input.reset(time) }
                else input.touch(action,x,y,time)
            }
        }
        val root = FrameLayout(this).apply {
            addView(glView,FrameLayout.LayoutParams(-1,-1));addView(overlay,FrameLayout.LayoutParams(-1,-1))
        }
        setContentView(root)
        WindowInsetsControllerCompat(window,root).apply { hide(WindowInsetsCompat.Type.systemBars());systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        thermal = ThermalMonitor(this) { status -> handler.post {
            if(!destroyed) {
                if(status >= 3 && state.thermalStatus < 3) state.notice("Proteção térmica", "A resolução e o tracking foram reduzidos. Em estado crítico, a inferência é pausada.")
                val changedTier = (state.thermalStatus>=3)!=(status>=3) || (state.thermalStatus>=4)!=(status>=4)
                state.thermalStatus=status;state.dirty=true
                if(changedTier) configureCamera(true)
            }
        } }
        CaptureState.onChanged = { active,message ->
            if(!destroyed) {
                state.captureActive=active;state.notice("Compartilhamento",message)
                if(!active) {
                    captureWindowId?.let { renderer.removeAppSurface(it);state.windows.get(it)?.status=message }
                    captureSurface=null;window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
        onBackPressedDispatcher.addCallback(this,object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    !state.session.uiVisible -> { state.session.setUiVisible(true);recenter() }
                    state.quickSettings -> { state.quickSettings=false;state.dirty=true }
                    state.page != Page.HOME -> state.navigate(Page.HOME)
                    else -> showDialog(AlertDialog.Builder(this@MainActivity).setTitle("Sair do espaço XR?").setMessage("Câmera, tracking, scripts e displays serão encerrados.").setNegativeButton("Continuar",null).setPositiveButton("Sair") { _,_ -> finish() }.create())
                }
            }
        })
        Thread({
            val apps = runCatching { appBridge.installedApps() }.getOrDefault(emptyList())
            handler.post { if(!destroyed) { state.installedApps.clear();state.installedApps.addAll(apps);state.dirty=true } }
        },"BrazilMR-AppDiscovery").start()
    }
    override fun onResume() {
        super.onResume();resumed=true;lastDraw=0
        glView.onResume();head.displayRotation=displayRotation();head.start();thermal.start()
        state.cameraGranted = ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        state.accessibilityEnabled=AccessibilityBridgeService.connected != null
        Choreographer.getInstance().removeFrameCallback(frameCallback);Choreographer.getInstance().postFrameCallback(frameCallback)
        configureCamera(true)
    }
    override fun onPause() {
        resumed=false;Choreographer.getInstance().removeFrameCallback(frameCallback)
        input.reset(System.nanoTime()/1_000_000);state.gestures.reset();hands.stop();camera.stop();cameraKey=""
        head.close();thermal.close();glView.onPause();ar.close()
        super.onPause()
    }
    override fun onDestroy() {
        destroyed=true;CaptureState.onChanged=null
        ProjectionCaptureService.stop(this);AccessibilitySession.clear()
        appBridge.close();lua.close();hands.close();ar.close();head.close();thermal.close();renderer.release();state.plugins.close()
        super.onDestroy()
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig);head.displayRotation=displayRotation();configureCamera(true);recenter()
    }
    @Suppress("DEPRECATION") private fun displayRotation() = windowManager.defaultDisplay.rotation
    private fun configureCamera(force: Boolean = false) {
        if(!::camera.isInitialized || !resumed || !graphicsReady || destroyed) return
        hands.configuration=state.settings
        val s=state.settings
        val critical=state.thermalStatus>=4
        val severe=state.thermalStatus>=3
        val needsCamera=(s.trackingEnabled && !critical) || (s.spatialTracking && !severe) || (s.passthrough && state.session.mode == EnvironmentMode.MR)
        val key="${state.cameraGranted}:${s.frontCamera}:${s.spatialTracking}:${s.trackingEnabled}:${s.trackingWidth}:$needsCamera:$critical:$severe:${displayRotation()}"
        if(!force && key==cameraKey) return
        cameraKey=key;camera.stop();ar.close()
        if(!state.cameraGranted || !needsCamera) { hands.stop();state.cameraActive=false;return }
        if(s.trackingEnabled) hands.start() else hands.stop()
        if(s.spatialTracking && !s.frontCamera && !severe && ar.start(this)) state.cameraActive=true
        else {
            state.spatialStatus=if(s.spatialTracking) "Fallback 3DoF · ARCore indisponível" else if(head.available) "3DoF · sensores" else "Sem sensor · toque disponível"
            camera.start(if(critical) s.copy(trackingEnabled=false) else s,displayRotation())
        }
        state.dirty=true
    }
    private fun updateHands(now: Long) {
        hands.readInto(state.hands)
        val raw=state.hands
        if(raw.sequence==lastSequence && raw.right.present==lastRight && raw.left.present==lastLeft) return
        lastSequence=raw.sequence;lastRight=raw.right.present;lastLeft=raw.left.present
        val previousMode=state.session.mode;val previousVisibility=state.session.uiVisible
        val gesture=state.gestures.update(raw,now)
        state.session.apply(gesture)
        if(gesture.showUi) { head.recenter();ar.recenter() }
        if(previousMode!=state.session.mode) modeChanged()
        if(previousVisibility!=state.session.uiVisible) { input.cancelHand(now);state.dirty=true }
        pointerHand.copyFrom(raw.right)
        renderer.readProjection(inputProjection)
        CameraCoordinates.map(raw.right.x(8),raw.right.y(8),hands.sourceAspect,inputProjection.eyeAspect,state.settings.frontCamera,cameraCoordinates)
        pointerHand.landmarks[24]=cameraCoordinates[0];pointerHand.landmarks[25]=cameraCoordinates[1]
        pointerHand.present=pointerHand.present && cameraCoordinates[0] in 0f..1f && cameraCoordinates[1] in 0f..1f
        input.hand(pointerHand,state.gestures.rightFeatures,state.gestures.suppressPinch || !state.session.uiVisible,now)
    }
    private fun dispatchPointer(event: PointerEvent) {
        if(nativeDialog) return
        renderer.readProjection(inputProjection)
        val eye = if(inputProjection.sbs && event.source != InputSource.HAND && event.x >= .5f) 1 else 0
        val x = if(inputProjection.sbs && event.source != InputSource.HAND) event.x*2-eye else event.x
        val inside=inputProjection.rayToUi(x,event.y,eye,uiCoordinates)
        // rayToUi still supplies the plane intersection outside its bounds, so an ongoing drag can clamp gracefully.
        if(inside || event.action != PointerAction.DOWN) scene.pointer(event.action,uiCoordinates[0],uiCoordinates[1],event.timeMillis)
        input.hovered=inside && scene.pointerOnUi
    }
    private fun fillRenderFrame(budget: RenderBudget) {
        val s=state.settings;val p=renderFrame.projection
        p.sbs=s.sbs;p.spatial=s.sbs || state.session.mode==EnvironmentMode.VR
        p.ipdMetres=s.ipdMm/1000;p.fovDegrees=s.fovDegrees;p.distance=s.uiDistance
        p.planeWidth=1.95f*s.uiScale;p.planeHeight=p.planeWidth/(16f/9f)
        p.centerX=s.uiOffsetX+state.session.uiAnchorX;p.centerY=s.uiOffsetY+state.session.uiAnchorY
        p.positionX=0f;p.positionY=0f;p.positionZ=0f;head.readInto(p)
        renderFrame.vr=state.session.mode==EnvironmentMode.VR
        renderFrame.camera=state.cameraActive && s.passthrough;renderFrame.mirror=s.frontCamera
        renderFrame.opacity=s.uiOpacity;renderFrame.scale=budget.renderScale;renderFrame.maxWidth=s.renderWidth;renderFrame.displayRotation=displayRotation()
        renderFrame.cursorVisible=input.cursorState!=CursorState.DISABLED && state.session.uiVisible && !nativeDialog
        if(renderFrame.cursorVisible) {
            renderer.readProjection(inputProjection)
            inputProjection.rayToUi(input.cursorX,input.cursorY,0,uiCoordinates)
            renderFrame.cursorX=uiCoordinates[0];renderFrame.cursorY=uiCoordinates[1];renderFrame.cursorState=input.cursorState.ordinal
        }
        scene.fillExternalLayers(renderFrame)
        renderFrame.objectCount=0
        val objects=state.scenario.objects
        for(index in objects.indices) {
            if(index>=64) break
            val obj=objects[index];val i=renderFrame.objectCount++
            renderFrame.objects[i*4]=obj.x;renderFrame.objects[i*4+1]=obj.y;renderFrame.objects[i*4+2]=obj.z;renderFrame.objects[i*4+3]=obj.size;renderFrame.objectColors[i]=obj.color
        }
    }
    override fun requestCamera() {
        if(state.cameraGranted) {
            showDialog(AlertDialog.Builder(this).setTitle("Câmera já autorizada").setMessage("O Brazil MR processa imagens localmente e não as grava. Você pode revogar a permissão nos ajustes Android.")
                .setNegativeButton("Fechar",null).setPositiveButton("Ajustes Android") { _,_ -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName"))) }.create())
        } else showDialog(AlertDialog.Builder(this).setTitle("Ativar câmera para XR?").setMessage("A câmera permite ver o ambiente real e rastrear suas mãos. Os frames são processados localmente, sem gravação ou envio. Você pode continuar usando touch sem conceder acesso.")
            .setNegativeButton("Agora não",null).setPositiveButton("Continuar") { _,_ -> cameraPermission.launch(Manifest.permission.CAMERA) }.create())
    }
    override fun updateSettings(settings: XrSettings) {
        state.saveSettings(settings);hands.configuration=settings;input.reset(System.nanoTime()/1_000_000)
        window.attributes=window.attributes.apply { preferredRefreshRate=min(settings.targetFps,if(settings.performanceMode==PerformanceMode.ECONOMY)30 else settings.targetFps).toFloat() }
        configureCamera()
    }
    override fun toggleMode() { state.session.toggleMode();modeChanged() }
    private fun modeChanged() {
        if(state.session.mode==EnvironmentMode.VR && !state.settings.sbs) state.saveSettings(state.settings.copy(sbs=true))
        input.reset(System.nanoTime()/1_000_000);head.recenter();ar.recenter()
        state.notice("Ambiente ${state.session.mode}",if(state.session.mode==EnvironmentMode.VR) "SBS ativo. Use um headset compatível, ajuste o IPD e permaneça em um local seguro." else "Mixed Reality. Passthrough monocular; não substitui visão direta do ambiente.")
        lua.modeChanged(state.session.mode.name);configureCamera()
    }
    override fun recenter() {
        head.recenter();ar.recenter();state.session.recenter()
        if(state.settings.uiOffsetX!=0f || state.settings.uiOffsetY!=0f) state.saveSettings(state.settings.copy(uiOffsetX=0f,uiOffsetY=0f))
        state.dirty=true
    }
    override fun hideUi() { state.session.setUiVisible(false);input.reset(System.nanoTime()/1_000_000);state.dirty=true }
    override fun openBuiltin(content: WindowContent) {
        safeAction {
            val title=when(content) { WindowContent.NOTES -> "Notas do espaço";WindowContent.CLOCK -> "Agora";else -> "Diagnóstico XR" }
            val win=state.windows.open("brazilmr.${content.name.lowercase()}",title,content=content)
            if(content==WindowContent.NOTES) win.text=state.sessionPrefs.getString("notes","Suas ideias, neste espaço.")!!
            state.navigate(Page.HOME)
        }
    }
    override fun openAndroid(app: LauncherApp) {
        @Suppress("DEPRECATION")
        var type=if(runCatching { packageManager.getApplicationInfo(app.packageName,0).category==ApplicationInfo.CATEGORY_GAME }.getOrDefault(false)) AppType.GAME else AppType.WINDOW
        showDialog(AlertDialog.Builder(this).setTitle(app.label).setMessage(null)
            .setSingleChoiceItems(arrayOf("WINDOW · janela tradicional","GAME · barra mínima"),if(type==AppType.GAME)1 else 0) { _,which -> type=if(which==1)AppType.GAME else AppType.WINDOW }
            .setNeutralButton("Abrir no Android") { _,_ -> if(!appBridge.launchOutside(app)) state.notice("App indisponível",app.label) }
            .setNegativeButton("Cancelar",null).setPositiveButton("Tentar janela XR") { _,_ -> safeAction {
                val win=state.windows.open(app.packageName,app.label,type,WindowContent.ANDROID)
                win.status="Solicitando display oficial. Alguns apps/OEMs não permitem essa execução."
                state.navigate(Page.HOME)
                renderer.createAppSurface(win.id,1280,720) { surface ->
                    if(destroyed || state.windows.get(win.id)==null) { renderer.removeAppSurface(win.id);return@createAppSurface }
                    if(surface==null) { win.status="Limite de superfícies ou GPU indisponível";state.dirty=true;return@createAppSurface }
                    when(val result=appBridge.launchOnSurface(win.id,app,surface)) {
                        is AppLaunchResult.Display -> { win.displayId=result.displayId;win.status="Display ${result.displayId} · aguardando o app";state.notice("Solicitação de janela enviada", "${app.label}. Exibição, resize e input dependem do app e do Android/OEM.") }
                        is AppLaunchResult.Unsupported -> { win.status=result.reason;renderer.removeAppSurface(win.id);state.notice("Limitação de compatibilidade",result.reason) }
                    }
                    state.dirty=true
                }
            } }.create())
    }
    override fun closeWindow(id: Int) {
        val win=state.windows.close(id)
        state.elements.removeWindow(id);appBridge.close(id);renderer.removeAppSurface(id);lua.closeWindow(id)
        if(win?.content==WindowContent.CAPTURE) { ProjectionCaptureService.stop(this);captureWindowId=null;captureSurface=null;capturePending=false;window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        state.dirty=true
    }
    override fun minimizeWindow(id: Int) { state.windows.minimize(id);appBridge.setMinimized(id,true);state.dirty=true }
    override fun windowResized(id: Int) {
        val win=state.windows.get(id) ?: return
        if(win.content==WindowContent.ANDROID && win.displayId>=0) {
            val rect=XrUiScene.contentBounds(win);val w=(rect.width()*1.4f).toInt().coerceIn(320,1920);val h=(rect.height()*1.4f).toInt().coerceIn(240,1080)
            renderer.resizeAppSurface(id,w,h);appBridge.resize(id,w,h);appBridge.setMinimized(id,false)
        }
        state.dirty=true
    }
    override fun editNotes(id: Int) {
        val win=state.windows.get(id) ?: return
        val edit=EditText(this).apply { setText(win.text);setTextColor(android.graphics.Color.WHITE);inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE;minLines=6;maxLines=12;setPadding(28,20,28,20) }
        showDialog(AlertDialog.Builder(this).setTitle("Notas do espaço").setView(edit).setNegativeButton("Cancelar",null).setPositiveButton("Salvar") { _,_ ->
            val text=edit.text.toString().take(4000);state.windows.get(id)?.text=text;state.sessionPrefs.edit().putString("notes",text).apply();state.dirty=true
        }.create())
    }
    override fun runScript(app: ScriptApp) { safeAction { lua.run(app);state.navigate(Page.HOME) } }
    override fun stopScripts() { lua.stopAll();state.notice("Scripts encerrados","UI e objetos pertencentes aos runtimes foram removidos.") }
    override fun selectScript(index: Int) { state.selectedScript=index.coerceIn(0,state.scripts.lastIndex);state.developerSource=state.scripts[state.selectedScript].source;state.developerType=state.scripts[state.selectedScript].type;state.dirty=true }
    override fun editScript() {
        val app=state.scripts[state.selectedScript]
        val layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(26,12,26,12) }
        val type=Spinner(this).apply { adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item, arrayOf("WINDOW","GAME"));setSelection(app.type.ordinal.let { if(app.type==AppType.GAME)1 else 0 }) }
        layout.addView(TextView(this).apply { text="Código local de desenvolvimento. Salvar não executa e não concede permissões.";setPadding(0,0,0,12) });layout.addView(type)
        val checks=Capability.entries.map { cap -> CheckBox(this).apply { text=cap.wireName;isChecked=cap in app.requested;layout.addView(this) } }
        val code=EditText(this).apply { setText(app.source);typeface=android.graphics.Typeface.MONOSPACE;textSize=13f;gravity=Gravity.TOP;inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;minLines=8 }
        val scroll=ScrollView(this).apply { addView(code) };layout.addView(scroll,LinearLayout.LayoutParams(-1,340))
        showDialog(AlertDialog.Builder(this).setTitle("Brazil MR · editor Lua").setView(layout).setNegativeButton("Cancelar",null).setPositiveButton("Salvar rascunho") { _,_ -> safeAction {
            val source=code.text.toString();require(source.toByteArray(Charsets.UTF_8).size<=65536) { "Limite de 64 KiB de código" }
            val draft=ScriptApp("developer.local","Meu experimento",if(type.selectedItemPosition==1)AppType.GAME else AppType.WINDOW,source,Capability.entries.filterIndexed { index,_ -> checks[index].isChecked }.toSet())
            var index=state.scripts.indexOfFirst { it.id==draft.id }
            if(index<0) { state.scripts.add(draft);index=state.scripts.lastIndex } else state.scripts[index]=draft
            state.permissions.register(draft.principal,draft.requested);selectScript(index)
            state.notice("Rascunho salvo", "Hash ${draft.principal.id.substringAfter('@').take(12)}. Permissões precisam de consentimento na Central.")
        } }.create())
    }
    override fun showDocumentation() {
        val content=runCatching { assets.open("API_REFERENCE.md").bufferedReader().use { it.readText() } }.getOrDefault("Consulte sdk/API_REFERENCE.md no repositório Brazil MR.")
        val text=TextView(this).apply { this.text=content;typeface=android.graphics.Typeface.MONOSPACE;textSize=13f;setPadding(28,18,28,18);setTextIsSelectable(true) }
        showDialog(AlertDialog.Builder(this).setTitle("Brazil MR Developer · API v0.1").setView(ScrollView(this).apply { addView(text) }).setPositiveButton("Fechar",null).create())
    }
    override fun changeCapability(app: ScriptApp, capability: Capability) {
        val granted=state.permissions.has(app.principal,capability)
        showDialog(AlertDialog.Builder(this).setTitle(if(granted) "Revogar ${capability.wireName}?" else "Permitir ${capability.wireName}?")
            .setMessage("${app.title}\nSHA-256 ${app.principal.id.substringAfter('@').take(24)}\n\n${XrUiScene.capabilityDescription(capability)}\n\nSem root, shell, acesso arbitrário a arquivos ou desativação das proteções Android.")
            .setNegativeButton("Cancelar",null).setPositiveButton(if(granted) "Revogar" else "Conceder explicitamente") { _,_ ->
                state.permissions.decideFromUser(app.principal,capability,!granted)
                if(granted && capability==Capability.SCENARIO) state.scenario.clear(app.principal.id)
                lua.permissionsChanged(app.principal);state.plugins.enforceRevocations()
                state.notice("Capability ${if(granted) "revogada" else "concedida"}","${app.title} · ${capability.wireName}")
            }.create())
    }
    override fun requestAccessibility() {
        val granted=AccessibilitySession.userConsented
        showDialog(AlertDialog.Builder(this).setTitle("Ponte de acessibilidade · opcional")
            .setMessage("Encaminha somente cliques e arrastos feitos por você a apps iniciados em displays autorizados nesta sessão. Verifica pacote, foco e limites da janela; não lê textos, senhas ou histórico. Não executa ações autônomas.\n\nApps em displays secundários exigem Android 11+ para input. Cliques e arrastos são enviados ao soltar o ponteiro; não há injeção privilegiada de MotionEvent.\n\nVocê pode revogar esta autorização a qualquer momento.")
            .setNegativeButton("Cancelar",null).setNeutralButton("Ajustes Android") { _,_ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .setPositiveButton(if(granted) "Revogar sessão" else "Autorizar sessão") { _,_ ->
                AccessibilitySession.userConsented=!granted;state.accessibilityConsent=!granted;state.dirty=true
                if(!granted && AccessibilityBridgeService.connected==null) startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.create())
    }
    override fun requestCapture() {
        if(state.captureActive) { ProjectionCaptureService.stop(this);return }
        if(capturePending) return
        showDialog(AlertDialog.Builder(this).setTitle("Compartilhar um app com XR?")
            .setMessage("A permissão oficial do Android será solicitada agora. No Android 14+, prefira compartilhar um único app. Compartilhamento não equivale a execução independente ou controle do app.\n\nConteúdo protegido fica oculto. A própria interface Brazil MR é protegida durante a captura para impedir espelhamento recursivo. Em captura de tela inteira, isso pode resultar em uma janela preta ao voltar ao XR.")
            .setNegativeButton("Cancelar",null).setPositiveButton("Selecionar conteúdo") { _,_ -> safeAction {
                capturePending=true
                val existing=captureWindowId?.let { state.windows.get(it) }
                val win=existing ?: state.windows.open("brazilmr.capture","Compartilhamento Android",content=WindowContent.CAPTURE).also { captureWindowId=it.id }
                state.windows.focus(win.id);win.status="Aguardando consentimento do Android";state.navigate(Page.HOME)
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                renderer.createAppSurface(win.id,1280,720) { surface ->
                    if(surface==null || destroyed) { endCapture("Surface indisponível");return@createAppSurface }
                    captureSurface=surface
                    val manager=getSystemService(MediaProjectionManager::class.java)
                    val intent=if(Build.VERSION.SDK_INT>=34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice()) else manager.createScreenCaptureIntent()
                    capturePermission.launch(intent)
                }
            } }.create())
    }
    private fun endCapture(message: String) {
        if(!::state.isInitialized) return
        capturePending=false;ProjectionCaptureService.stop(this);state.captureActive=false
        captureWindowId?.let { state.windows.get(it)?.status=message;if(::renderer.isInitialized) renderer.removeAppSurface(it) }
        captureSurface=null;window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);state.dirty=true
    }
    override fun clickLuaElement(id: Int) { lua.click(id) }
    override fun externalGesture(id: Int,x0: Float,y0: Float,x1: Float,y1: Float,duration: Long) {
        appBridge.gesture(id,x0,y0,x1,y1,if(abs(x1-x0)+abs(y1-y0)<.01f) min(duration,600) else duration)?.let { state.notice("Input não encaminhado",it) }
    }
    private fun showDialog(dialog: AlertDialog) {
        if(destroyed || isFinishing) return
        nativeDialog=true;input.reset(System.nanoTime()/1_000_000)
        dialog.setOnDismissListener { nativeDialog=false;state.dirty=true;input.reset(System.nanoTime()/1_000_000) }
        dialog.show()
    }
    private fun safeAction(action: () -> Unit) { try { action() } catch(error: Exception) { state.notice("Operação indisponível",error.message ?: "Tente novamente") } }
}
