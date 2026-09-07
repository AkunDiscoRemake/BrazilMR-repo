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
import com.brazilmr.core.spatial.*
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
    private lateinit var floating: SpatialUiScene
    private lateinit var phoneInput: WorkspaceInputView
    private lateinit var spatialInput: SpatialInputView
    private lateinit var phoneExit: Button
    private lateinit var preparation: LinearLayout
    private val spatialSnapshot=PanelSnapshot()
    private val dwell=DwellSelector()
    private val gazeRay=SpatialRay()
    private var gazeActive=false
    private var gazeX=.5f;private var gazeY=.5f
    private var recoverySince=0L
    private var savedHeadsetSbs=true
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
    private val renderCadence = CadenceLimiter()
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
    private var lastWindowRevision = -1L
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
        if(granted)finishPreparation()
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
            if (!renderCadence.acquire(frameTimeNanos,budget.renderFps)) return
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
                if(state.windows.revision!=lastWindowRevision) { lua.windowStatesChanged();lastWindowRevision=state.windows.revision }
                if (!nativeDialog && now-lastLuaTick >= 100) { lua.tick(now/1000.0); lastLuaTick = now }
                if(!state.phoneTools && !nativeDialog && preparation.visibility!=View.VISIBLE) updateGaze(now)
                fillRenderFrame(budget)
                if(state.phoneTools) {
                    if(!::uiCanvases.isInitialized)uiCanvases=Array(2){Canvas(textureExchange.bitmaps[it])}
                    if(state.dirty) {
                        val slot=textureExchange.beginWrite()
                        if(slot>=0) { try { scene.draw(uiCanvases[slot]);textureExchange.publish(slot);state.dirty=false } catch(e: Exception){textureExchange.cancelWrite(slot);throw e} }
                    }
                } else { state.dirty=!floating.prepare(renderFrame) }
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
                if (status in listOf(TrackingStatus.ERROR,TrackingStatus.NO_MODEL,TrackingStatus.UNAVAILABLE) && lastTrackingMessage != detail) { state.notice("Hand tracking",detail); lastTrackingMessage = detail }
            }
        } }
        hands.configuration = state.settings
        ar = ArCoreEnvironment(this,hands,{ status -> handler.post { if(!destroyed) { state.spatialStatus=status;state.dirty=true;if(::lua.isInitialized) lua.environmentChanged(status) } } },{ error -> handler.post {
            if(!destroyed) { state.notice("Tracking espacial interrompido",error); state.saveSettings(state.settings.copy(spatialTracking=false)); configureCamera(true) }
        } })
        glView = GLSurfaceView(this).apply { setEGLContextClientVersion(2); preserveEGLContextOnPause=true }
        textureExchange = UiTextureExchange()
        appBridge = AndroidAppBridge(this)
        lua = AndroidLuaController(state,::modeChanged,::closeWindow)
        scene = XrUiScene(state,this)
        floating=SpatialUiScene(state,this)
        input = InputSystem(InputSink(::dispatchPointer))
        renderer = SBSRenderer(glView,textureExchange,ar,{
            if (!destroyed) { graphicsReady=true;state.dirty=true;configureCamera(true) }
        },{
            if(!destroyed) {
                appBridge.close(); endCapture("Contexto gráfico recriado; solicite novamente o compartilhamento.")
                for (window in state.windows.windows) if(window.content == WindowContent.ANDROID) { window.hasSurfaceFrame=false;window.displayId=-1;window.status="Contexto gráfico recriado. Reabra este app." }
                state.dirty=true
            }
        },{ error ->
            graphicsReady=false
            if(!destroyed) showDialog(AlertDialog.Builder(this).setTitle("Renderer indisponível").setMessage("$error\n\nO aparelho precisa de OpenGL ES 2 com texturas externas OES.").setPositiveButton("Fechar") { _,_ -> finish() }.create())
        })
        renderer.onAppFrame={ id -> state.windows.get(id)?.hasSurfaceFrame=true;state.dirty=true }
        glView.setRenderer(renderer); glView.renderMode=GLSurfaceView.RENDERMODE_WHEN_DIRTY
        camera = CameraController(this,this,hands,{ request -> renderer.provideCameraSurface(request) }) { active,detail ->
            state.cameraActive=active;state.dirty=true
            if(!active && detail != "Câmera pausada") state.notice("Câmera",detail)
        }
        camera.onLens=renderer::setCameraTangents
        phoneInput = WorkspaceInputView(this,scene,renderer::readProjection) { action,x,y,time ->
            if(!nativeDialog) {
                if(!state.session.uiVisible && action == PointerAction.DOWN) { state.session.setUiVisible(true);recenter();input.reset(time) }
                else input.touch(action,x,y,time)
            }
        }
        spatialInput=SpatialInputView(this,floating,renderer::readPanels) { action,x,y,time ->
            if(!nativeDialog && preparation.visibility!=View.VISIBLE) {
                if(!state.session.uiVisible && action==PointerAction.DOWN) { state.session.setUiVisible(true);recenter();input.reset(time) }
                else input.touch(action,x,y,time)
            }
        }
        phoneInput.visibility=View.GONE
        phoneExit=Button(this).apply { text="Voltar ao MR · VR Box";visibility=View.GONE;setOnClickListener { exitPhoneTools() } }
        preparation=createPreparation()
        val root = FrameLayout(this).apply {
            addView(glView,FrameLayout.LayoutParams(-1,-1));addView(phoneInput,FrameLayout.LayoutParams(-1,-1))
            addView(spatialInput,FrameLayout.LayoutParams(-1,-1))
            addView(phoneExit,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END))
            addView(preparation,FrameLayout.LayoutParams(-1,-1))
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
                if(!state.phoneTools && preparation.visibility!=View.VISIBLE) {
                    if(floating.menu!=SpatialMenu.CLOSED)floating.openMenu(SpatialMenu.CLOSED)
                    else { state.session.setUiVisible(true);recenter() }
                    return
                }
                if(state.phoneTools) { exitPhoneTools();return }
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
        super.onResume();resumed=true;lastDraw=0;renderCadence.reset()
        glView.onResume();head.displayRotation=displayRotation();head.start();thermal.start()
        state.cameraGranted = ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        state.accessibilityEnabled=AccessibilityBridgeService.connected != null
        Choreographer.getInstance().removeFrameCallback(frameCallback);Choreographer.getInstance().postFrameCallback(frameCallback)
        configureCamera(true)
    }
    override fun onPause() { super.onPause() }
    // On a secondary display another Activity can be resumed while this XR Activity remains visible.
    // Stop on STOP, not PAUSE, or launching an Android window would turn off the headset camera.
    override fun onStop() {
        resumed=false;Choreographer.getInstance().removeFrameCallback(frameCallback)
        input.reset(System.nanoTime()/1_000_000);state.gestures.reset();hands.stop();camera.stop();cameraKey=""
        head.close();thermal.close();glView.onPause();ar.close()
        floating.cancel();super.onStop()
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
        if(previousVisibility!=state.session.uiVisible) { input.cancelHand(now);if(!state.session.uiVisible)floating.cancel();dwell.reset();state.dirty=true }
        pointerHand.copyFrom(raw.right)
        renderer.readProjection(inputProjection)
        CameraCoordinates.map(raw.right.x(8),raw.right.y(8),hands.sourceAspect,inputProjection.eyeAspect,state.settings.frontCamera,cameraCoordinates)
        pointerHand.landmarks[24]=cameraCoordinates[0];pointerHand.landmarks[25]=cameraCoordinates[1]
        pointerHand.present=pointerHand.present && cameraCoordinates[0] in 0f..1f && cameraCoordinates[1] in 0f..1f
        input.hand(pointerHand,state.gestures.rightFeatures,state.gestures.suppressPinch || !state.session.uiVisible,now)
    }
    private fun dispatchPointer(event: PointerEvent) {
        if(nativeDialog) return
        if(!state.phoneTools) {
            renderer.readPanels(spatialSnapshot)
            val p=spatialSnapshot.projection
            val physical=event.source==InputSource.TOUCH || event.source==InputSource.ACCESSIBILITY
            val eye=if(physical && p.sbs) (if(event.x>=.5f)1 else 0) else -1
            var x=event.x;var y=event.y
            if(physical && p.sbs) {
                if(!LensMapping.outputToView(x*2-eye,y,eye,p.lensShift,p.lensVertical,p.lensDistortion,uiCoordinates)) {
                    if(event.action==PointerAction.CANCEL || event.action==PointerAction.UP)floating.cancel()
                    input.hovered=false;return
                }
                x=uiCoordinates[0];y=uiCoordinates[1]
            }
            floating.pointer(event.action,x,y,eye,event.source,event.timeMillis,spatialSnapshot)
            input.hovered=floating.hovered?.kind!=HitKind.BLOCK && floating.hovered!=null
            return
        }
        renderer.readProjection(inputProjection)
        val eye = if(inputProjection.sbs && event.source != InputSource.HAND && event.x >= .5f) 1 else 0
        val x = if(inputProjection.sbs && event.source != InputSource.HAND) event.x*2-eye else event.x
        val inside=inputProjection.rayToUi(x,event.y,eye,uiCoordinates)
        if (!uiCoordinates[0].isFinite() || !uiCoordinates[1].isFinite()) {
            if(event.action==PointerAction.UP || event.action==PointerAction.CANCEL) scene.pointer(PointerAction.CANCEL,scene.lastPointerX,scene.lastPointerY,event.timeMillis,event.source.name.lowercase())
            input.hovered=false;return
        }
        // rayToUi still supplies the plane intersection outside its bounds, so an ongoing drag can clamp gracefully.
        if(inside || event.action != PointerAction.DOWN) scene.pointer(event.action,uiCoordinates[0],uiCoordinates[1],event.timeMillis,event.source.name.lowercase())
        input.hovered=inside && scene.pointerOnUi
    }
    private fun fillRenderFrame(budget: RenderBudget) {
        val s=state.settings;val p=renderFrame.projection
        renderFrame.immersive=!state.phoneTools
        p.sbs=if(state.phoneTools)false else s.sbs;p.spatial=!state.phoneTools
        p.cameraAligned=!state.phoneTools && state.session.mode==EnvironmentMode.MR
        p.focalX=0f;p.focalY=0f;p.opticalX=0f;p.opticalY=0f
        p.lensShift=s.lensShift;p.lensVertical=s.lensVertical;p.lensDistortion=s.lensDistortion
        p.ipdMetres=s.ipdMm/1000;p.fovDegrees=s.fovDegrees;p.distance=s.uiDistance
        p.planeWidth=1.95f*s.uiScale;p.planeHeight=p.planeWidth/(16f/9f)
        p.centerX=s.uiOffsetX+state.session.uiAnchorX;p.centerY=s.uiOffsetY+state.session.uiAnchorY
        p.positionX=0f;p.positionY=0f;p.positionZ=0f;head.readInto(p)
        renderFrame.vr=state.session.mode==EnvironmentMode.VR
        renderFrame.camera=state.cameraActive && s.passthrough;renderFrame.mirror=s.frontCamera
        renderFrame.opacity=s.uiOpacity;renderFrame.scale=budget.renderScale;renderFrame.maxWidth=s.renderWidth;renderFrame.displayRotation=head.displayRotation
        renderFrame.cursorVisible=input.cursorState!=CursorState.DISABLED && state.session.uiVisible && !nativeDialog
        if(renderFrame.cursorVisible) {
            renderer.readProjection(inputProjection)
            inputProjection.rayToUi(input.cursorX,input.cursorY,0,uiCoordinates)
            renderFrame.cursorVisible=uiCoordinates[0].isFinite() && uiCoordinates[1].isFinite()
            renderFrame.cursorX=uiCoordinates[0];renderFrame.cursorY=uiCoordinates[1];renderFrame.cursorState=input.cursorState.ordinal
        }
        if(state.phoneTools) scene.fillExternalLayers(renderFrame) else {
            renderFrame.externalCount=0
            renderFrame.cursorVisible=state.session.uiVisible && !nativeDialog && preparation.visibility!=View.VISIBLE && (gazeActive || input.cursorState!=CursorState.DISABLED)
            if(renderFrame.cursorVisible) {
                renderer.readPanels(spatialSnapshot)
                floating.cast(spatialSnapshot,if(gazeActive)gazeX else input.cursorX,if(gazeActive)gazeY else input.cursorY,-1)
                renderFrame.cursorWorldX=floating.cursorWorld[0];renderFrame.cursorWorldY=floating.cursorWorld[1];renderFrame.cursorWorldZ=floating.cursorWorld[2]
            }
            renderFrame.dwellProgress=if(gazeActive)dwell.progress else 0f
        }
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
        input.reset(System.nanoTime()/1_000_000);dwell.reset();head.recenter();ar.recenter()
        state.notice("Ambiente ${state.session.mode}",if(state.session.mode==EnvironmentMode.VR) "SBS ativo. Use um headset compatível, ajuste o IPD e permaneça em um local seguro." else "Mixed Reality. Passthrough monocular; não substitui visão direta do ambiente.")
        lua.modeChanged(state.session.mode.name);configureCamera()
    }
    override fun recenter() {
        head.recenter();ar.recenter();state.session.recenter();dwell.reset();gazeX=.5f;gazeY=.5f
        if(::floating.isInitialized)floating.cancel()
        if(state.settings.uiOffsetX!=0f || state.settings.uiOffsetY!=0f) state.saveSettings(state.settings.copy(uiOffsetX=0f,uiOffsetY=0f))
        state.dirty=true
    }
    override fun hideUi() { state.session.setUiVisible(false);input.reset(System.nanoTime()/1_000_000);floating.cancel();dwell.reset();state.dirty=true }
    override fun openBuiltin(content: WindowContent) {
        safeAction {
            val title=when(content) { WindowContent.NOTES -> "Notas do espaço";WindowContent.CLOCK -> "Agora";else -> "Diagnóstico XR" }
            val win=state.windows.open("brazilmr.${content.name.lowercase()}",title,content=content)
            if(content==WindowContent.NOTES) win.text=state.sessionPrefs.getString("notes","Suas ideias, no ambiente.\n\nMova esta janela pela alça.\nUse Janelas para aproximar ou afastar.")!!
            if(content==WindowContent.CLOCK)HeadsetLayout.clock(win.pose)
            state.navigate(Page.HOME)
        }
    }
    override fun launchOutside(app: LauncherApp) { if(!appBridge.launchOutside(app))state.notice("App indisponível",app.label) }
    override fun openAndroid(app: LauncherApp) {
        @Suppress("DEPRECATION")
        var type=if(runCatching { packageManager.getApplicationInfo(app.packageName,0).category==ApplicationInfo.CATEGORY_GAME }.getOrDefault(false)) AppType.GAME else AppType.WINDOW
        if(!state.phoneTools) { launchAndroidWindow(app,type);return }
        showDialog(AlertDialog.Builder(this).setTitle(app.label).setMessage(null)
            .setSingleChoiceItems(arrayOf("WINDOW · janela tradicional","GAME · barra mínima"),if(type==AppType.GAME)1 else 0) { _,which -> type=if(which==1)AppType.GAME else AppType.WINDOW }
            .setNeutralButton("Abrir no Android") { _,_ -> if(!appBridge.launchOutside(app)) state.notice("App indisponível",app.label) }
            .setNegativeButton("Cancelar",null).setPositiveButton("Tentar janela XR") { _,_ -> launchAndroidWindow(app,type) }.create())
    }
    private fun launchAndroidWindow(app: LauncherApp,type: AppType) {
        safeAction {
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
        }
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
            val w=1280;val h=720
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
            state.saveDeveloperDraft(source,if(type.selectedItemPosition==1)AppType.GAME else AppType.WINDOW,Capability.entries.filterIndexed { index,_ -> checks[index].isChecked }.toSet())
            val draft=state.scripts[state.selectedScript]
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
    override fun clickLuaElement(id: Int) {
        lua.click(id)
        state.elements.elements.firstOrNull { it.id==id }?.let { lua.input(it.windowId) }
    }
    override fun windowPointer(id: Int,x: Float,y: Float,action: String,source: String) { lua.pointer(id,x,y,action,source) }
    override fun externalGesture(id: Int,x0: Float,y0: Float,x1: Float,y1: Float,duration: Long) {
        appBridge.gesture(id,x0,y0,x1,y1,if(abs(x1-x0)+abs(y1-y0)<.01f) min(duration,600) else duration)?.let { state.notice("Input não encaminhado",it) }
    }
    private fun updateGaze(now: Long) {
        renderer.readPanels(spatialSnapshot)
        spatialSnapshot.projection.ray(.5f,.5f,-1,gazeRay)
        if(!state.session.uiVisible) {
            if(gazeRay.dy<-.58f) { if(recoverySince==0L)recoverySince=now;if(now-recoverySince>1500){state.session.setUiVisible(true);recenter();recoverySince=0L} }
            else recoverySince=0L
        }
        gazeActive=state.settings.gazeEnabled && !state.hands.right.present && !input.touchOwns(now) && state.session.uiVisible
        if(!gazeActive) { dwell.reset();return }
        if(floating.updateGazeGrab(spatialSnapshot,now,gazeX,gazeY)) { dwell.reset();return }
        input.gaze(PointerAction.MOVE,now,gazeX,gazeY)
        val t=floating.hovered
        val selectable=t!=null && t.kind!=HitKind.BLOCK && t.kind!=HitKind.EXTERNAL
        if(dwell.update(if(selectable)t!!.id else -1,now)) {
            input.gaze(PointerAction.DOWN,now,gazeX,gazeY);input.gaze(PointerAction.UP,now+1,gazeX,gazeY)
        }
    }
    private fun headsetClick() {
        if(floating.gazeGrabbed>=0) { floating.finishGazeGrab();return }
        val now=System.nanoTime()/1_000_000
        input.cancelHand(now)
        input.gaze(PointerAction.MOVE,now,gazeX,gazeY);input.gaze(PointerAction.DOWN,now,gazeX,gazeY);input.gaze(PointerAction.UP,now+1,gazeX,gazeY)
        dwell.latch(floating.hovered?.id ?: -1)
    }
    override fun onKeyDown(code: Int,event: KeyEvent): Boolean = handleHeadsetKey(event) || super.onKeyDown(code,event)
    override fun onKeyUp(code: Int,event: KeyEvent): Boolean = handleHeadsetKey(event) || super.onKeyUp(code,event)
    private fun handleHeadsetKey(event: KeyEvent): Boolean {
        if(::state.isInitialized && !state.phoneTools && ::preparation.isInitialized && preparation.visibility!=View.VISIBLE && !nativeDialog) {
            when(event.keyCode) {
                KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_BUTTON_A,KeyEvent.KEYCODE_SPACE,KeyEvent.KEYCODE_VOLUME_UP->{if(event.action==KeyEvent.ACTION_UP)headsetClick();return true}
                KeyEvent.KEYCODE_VOLUME_DOWN->{if(event.action==KeyEvent.ACTION_UP){state.session.setUiVisible(true);recenter()};return true}
                KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN->{
                    if(event.action==KeyEvent.ACTION_DOWN){when(event.keyCode){KeyEvent.KEYCODE_DPAD_LEFT->gazeX-=.035f;KeyEvent.KEYCODE_DPAD_RIGHT->gazeX+=.035f;KeyEvent.KEYCODE_DPAD_UP->gazeY-=.035f;else->gazeY+=.035f};gazeX=gazeX.coerceIn(.02f,.98f);gazeY=gazeY.coerceIn(.02f,.98f)};return true
                }
            }
        }
        return false
    }
    private fun createPreparation(): LinearLayout {
        return LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(48,20,48,20);setBackgroundColor(0xff100d18.toInt())
            visibility=if(state.cameraGranted)View.GONE else View.VISIBLE
            addView(TextView(this@MainActivity).apply { text="BRAZIL MR  /  VR BOX";textSize=25f;setTextColor(0xffc5a1ff.toInt());gravity=Gravity.CENTER })
            addView(TextView(this@MainActivity).apply { text="Antes de colocar o telefone no headset:\n1. Autorize a câmera.  2. Deixe a câmera traseira descoberta.\n3. Encaixe o telefone em paisagem e ajuste as lentes.\nNo espaço: olhe para o dock abaixo e pare sobre um botão por 1 s.";textSize=17f;setTextColor(0xffeee8f5.toInt());gravity=Gravity.CENTER;setPadding(12,18,12,14) })
            addView(Button(this@MainActivity).apply { text="Autorizar câmera e entrar em MR SBS";setOnClickListener { cameraPermission.launch(Manifest.permission.CAMERA) } })
            addView(Button(this@MainActivity).apply { text="Entrar sem câmera (somente espaço virtual)";setOnClickListener { state.session.setMode(EnvironmentMode.VR);finishPreparation(EnvironmentMode.VR) } })
        }
    }
    private fun finishPreparation(mode: EnvironmentMode=EnvironmentMode.MR) {
        state.session.setMode(mode)
        if(::preparation.isInitialized)preparation.visibility=View.GONE
        state.saveSettings(state.settings.copy(sbs=true,passthrough=true,frontCamera=false))
        head.recenter();ar.recenter();state.dirty=true;configureCamera(true)
    }
    override fun openPhoneTools(page: Page) {
        savedHeadsetSbs=state.settings.sbs;state.phoneTools=true;state.navigate(page)
        phoneInput.visibility=View.VISIBLE;spatialInput.visibility=View.GONE;phoneExit.visibility=View.VISIBLE
        input.reset(System.nanoTime()/1_000_000);floating.cancel();dwell.reset()
    }
    private fun exitPhoneTools() {
        state.phoneTools=false;state.saveSettings(state.settings.copy(sbs=savedHeadsetSbs))
        phoneInput.visibility=View.GONE;spatialInput.visibility=View.VISIBLE;phoneExit.visibility=View.GONE
        state.session.setMode(EnvironmentMode.MR);state.session.setUiVisible(true);floating.openMenu(SpatialMenu.CLOSED);recenter();configureCamera()
    }
    private fun showDialog(dialog: AlertDialog) {
        if(destroyed || isFinishing) return
        nativeDialog=true;input.reset(System.nanoTime()/1_000_000)
        dialog.setOnDismissListener { nativeDialog=false;state.dirty=true;input.reset(System.nanoTime()/1_000_000) }
        dialog.show()
    }
    fun hasRenderedFrameForDiagnostics(): Boolean = graphicsReady && renderer.fps>0
    private fun safeAction(action: () -> Unit) { try { action() } catch(error: Exception) { state.notice("Operação indisponível",error.message ?: "Tente novamente") } }
}
