package com.brazilmr.ui

import android.graphics.*
import com.brazilmr.core.input.*
import com.brazilmr.core.performance.XrSettings
import com.brazilmr.core.spatial.*
import com.brazilmr.core.window.*
import com.brazilmr.platform.*
import com.brazilmr.render.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

/** UI content is flat, but each texture has its OWN world transform. There is no fullscreen UI quad. */
class SpatialTarget(val id: Int,val key: String,val panelId: Int,val label: String,val rect: RectF,val kind: HitKind,val action: (() -> Unit)?)
class SpatialPixels(val width: Int,val height: Int) {
    val exchange=UiTextureExchange(width,height)
    val targets=ArrayList<SpatialTarget>()
    val canvases by lazy { Array(2) { Canvas(exchange.bitmaps[it]) } }
}
enum class SpatialMenu { CLOSED, APPS, WINDOWS, SETTINGS, TRACKING, PERMISSIONS, DEVELOPER }

class SpatialUiScene(val state: PlatformState,private val actions: UiActions) {
    private val buffers=HashMap<Int,SpatialPixels>()
    val targets=ArrayList<SpatialTarget>()
    private val identities=HashMap<String,Int>();private var nextId=1
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val font=Typeface.create("sans-serif-medium",Typeface.NORMAL)
    private val clockFormat=SimpleDateFormat("HH:mm",Locale("pt","BR"))
    private val dateFormat=SimpleDateFormat("EEE, d MMM",Locale("pt","BR"))
    private var canvas=Canvas();private var panelId=0;private var width=512f;private var height=384f
    var menu=SpatialMenu.CLOSED;private set
    var hovered: SpatialTarget?=null;private set
    private var captured: SpatialTarget?=null
    private val capturedPose=PanelPose()
    private val ray=SpatialRay();private val hit=FloatArray(3);private val down=FloatArray(3);private val point=FloatArray(3)
    val cursorWorld=FloatArray(3)
    private var downU=0f;private var downV=0f;private var downTime=0L;private var originalWidth=0f
    var gazeGrabbed=-1;private set
    private var grabDistance=1.8f;private var grabMoved=false;private var grabSince=0L
    private val grabInitial=FloatArray(3);private val grabLast=FloatArray(3)
    private var page=0;private var settingsPage=0
    var onTargetsChanged: (() -> Unit)?=null
    fun openMenu(value: SpatialMenu) { menu=value;page=0;state.dirty=true }
    fun cancel() { captured=null;gazeGrabbed=-1;hovered=null;state.dirty=true }
    fun target(id: Int)=targets.firstOrNull { it.id==id }
    fun activate(id: Int): Boolean { val t=target(id) ?: return false;if(t.kind!=HitKind.BUTTON)return false;t.action?.invoke();return true }
    fun pixels(id: Int)=buffers[id]

    fun prepare(frame: RenderFrame) {
        val dirty=state.dirty
        if(dirty) targets.clear()
        frame.panels.count=0
        if(!state.session.uiVisible) { if(targets.isNotEmpty()){targets.clear();onTargetsChanged?.invoke()};return }
        for(w in state.windows.windows) if(!w.minimized) {
            val h=if(w.content==WindowContent.CLOCK)240 else 384
            val buffer=buffer(w.id,512,h)
            if(dirty) draw(buffer,w.id) { window(w) }
            append(frame,w.id,w.pose,buffer,if(w.displayId>=0 || (w.content==WindowContent.CAPTURE && state.captureActive)) w.id else -1)
        }
        val dock=buffer(HeadsetLayout.DOCK,1024,128)
        if(dirty) draw(dock,HeadsetLayout.DOCK) { dock() }
        HeadsetLayout.dock(frame.panels.poses[frame.panels.count])
        append(frame,HeadsetLayout.DOCK,frame.panels.poses[frame.panels.count],dock)
        if(menu!=SpatialMenu.CLOSED) {
            val panel=buffer(HeadsetLayout.MENU,640,640)
            if(dirty) draw(panel,HeadsetLayout.MENU) { menu() }
            HeadsetLayout.menu(frame.panels.poses[frame.panels.count])
            append(frame,HeadsetLayout.MENU,frame.panels.poses[frame.panels.count],panel)
        }
        if(dirty) {
            val iterator=buffers.keys.iterator()
            while(iterator.hasNext()) { val id=iterator.next();if(id>0 && state.windows.get(id)==null) iterator.remove() }
            val active=targets.mapTo(HashSet()) { it.key };identities.keys.retainAll(active)
            onTargetsChanged?.invoke()
        }
        for(i in frame.panels.count until frame.panelPixels.size)frame.panelPixels[i]=null
    }
    private fun buffer(id: Int,w: Int,h: Int)=buffers.getOrPut(id) { SpatialPixels(w,h) }
    private fun append(frame: RenderFrame,id: Int,p: PanelPose,pixels: SpatialPixels,video: Int=-1) {
        val index=frame.panels.count++
        frame.panels.ids[index]=id;frame.panels.poses[index].copyFrom(p)
        val pose=frame.panels.poses[index]
        pose.x+=state.settings.uiOffsetX+state.session.uiAnchorX;pose.y+=state.settings.uiOffsetY+state.session.uiAnchorY
        pose.width*=state.settings.uiScale;pose.height*=state.settings.uiScale
        frame.panelPixels[index]=pixels.exchange;frame.panelVideo[index]=video
    }
    private fun draw(buffer: SpatialPixels,id: Int,body: () -> Unit) {
        val slot=buffer.exchange.beginWrite();if(slot<0){targets.addAll(buffer.targets);return}
        val start=targets.size
        try {
            canvas=buffer.canvases[slot];width=buffer.width.toFloat();height=buffer.height.toFloat();panelId=id
            canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR)
            body();buffer.targets.clear();for(i in start until targets.size)buffer.targets.add(targets[i]);buffer.exchange.publish(slot)
        } catch(e: Exception) { buffer.exchange.cancelWrite(slot);throw e }
    }
    private fun window(w: XRWindow) {
        if(w.content==WindowContent.CLOCK) {
            rect(2f,2f,508f,236f,0x99151020.toInt(),32f)
            text(clockFormat.format(Date()),30f,117f,100f,WHITE)
            text(dateFormat.format(Date()).uppercase(),35f,162f,26f,MUTED)
            button("min", "−",414f,13f,74f,55f) { actions.minimizeWindow(w.id) }
            handle(w,240f);return
        }
        rect(2f,2f,508f,380f,BG,22f)
        stroke(3f,3f,506f,378f,if(w.focused) PURPLE else BORDER,22f)
        text(w.title,22f,40f,25f,WHITE,335f)
        button("min", "−",365f,8f,64f,48f) { actions.minimizeWindow(w.id) }
        button("close", "×",438f,8f,60f,48f) { actions.closeWindow(w.id) }
        line(18f,61f,494f,61f,BORDER)
        val body=bodyRect()
        hit("body",w.title,body,if(w.content==WindowContent.ANDROID || w.content==WindowContent.CAPTURE)HitKind.EXTERNAL else HitKind.BLOCK)
        canvas.save();canvas.clipRect(body)
        when(w.content) {
            WindowContent.NOTES -> {
                paragraph(w.text.ifEmpty { "Seu espaço, sem bloquear o mundo.\n\nUse a alça para mover esta janela." },25f,99f,460f,28f,38f,5)
                button("edit","Editar no telefone",26f,268f,455f,52f) { actions.editNotes(w.id) }
            }
            WindowContent.DIAGNOSTICS -> {
                text("${state.fps}",26f,150f,90f,WHITE);text("FPS",197f,144f,26f,PURPLE)
                text("Tracking: ${state.effectiveTrackingFps} FPS · ${state.hands.inferenceMillis.toInt()} ms",28f,199f,24f,MUTED)
                paragraph(state.spatialStatus,28f,241f,452f,23f,31f,2)
                text("Térmico ${state.thermalStatus} · ${(state.effectiveRenderScale*100).toInt()}% render",28f,315f,22f,MUTED)
            }
            WindowContent.LUA -> for(item in state.elements.elements) if(item.windowId==w.id && item.visible) {
                val x=body.left+item.x*body.width();val y=body.top+item.y*body.height();val rw=item.width*body.width();val rh=item.height*body.height()
                if(item.kind!="text") rect(x,y,rw,rh,item.background,10f)
                text(item.text,x+8,y+rh/2+item.fontSize*.35f,item.fontSize.coerceAtLeast(22f),item.color,rw-16)
                if(item.kind=="button") {
                    val r=RectF(x,y,x+rw,y+rh);if(r.intersect(body))hit("lua.${item.id}",item.text,r,action={actions.clickLuaElement(item.id)})
                }
            }
            WindowContent.ANDROID,WindowContent.CAPTURE -> {
                if(w.displayId>=0 || (w.content==WindowContent.CAPTURE && state.captureActive)) canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR)
                else paragraph(w.status.ifEmpty { "Aguardando o app…" },30f,114f,450f,25f,36f,5)
            }
            else -> Unit
        }
        canvas.restore();handle(w,384f)
    }
    private fun handle(w: XRWindow,h: Float) {
        val isGrab=gazeGrabbed==w.id
        rect(177f,h-41,158f,31f,if(isGrab)0xff7543b6.toInt() else 0xff282033.toInt(),14f)
        text(if(isGrab) "Posicionar…" else "Mover",208f,h-19,20f,if(isGrab)WHITE else MUTED,131f)
        hit("move","Mover ${w.title}",RectF(156f,h-49,354f,h),HitKind.MOVE)
        if(w.content!=WindowContent.CLOCK) {
            text("↗",469f,h-16,32f,PURPLE)
            hit("resize","Aumentar ${w.title}",RectF(449f,h-47,510f,h),HitKind.RESIZE)
        }
    }
    private fun dock() {
        rect(2f,2f,1020f,124f,0xec130f1c.toInt(),38f)
        stroke(3f,3f,1018f,122f,BORDER,38f)
        val names=arrayOf("Apps","Janelas",state.session.mode.name,"Recentrar","Ocultar")
        val glyphs=arrayOf("▦","▱","◉","◎","—")
        for(i in names.indices) {
            val x=15f+i*201
            if(hovered?.key=="${HeadsetLayout.DOCK}:dock$i") rect(x,10f,186f,108f,0xff332342.toInt(),28f)
            text(glyphs[i],x+74,58f,40f,PURPLE)
            text(names[i],x+25,98f,25f,WHITE,168f)
            hit("dock$i",names[i],RectF(x,7f,x+190,122f),action={
                when(i) {0->openMenu(if(menu==SpatialMenu.APPS)SpatialMenu.CLOSED else SpatialMenu.APPS);1->openMenu(SpatialMenu.WINDOWS);2->actions.toggleMode();3->{actions.recenter();openMenu(SpatialMenu.CLOSED)};4->actions.hideUi()}
            })
        }
    }
    private fun menu() {
        rect(2f,2f,636f,636f,BG,26f);stroke(3f,3f,634f,634f,BORDER,26f)
        text(when(menu) {SpatialMenu.APPS->"Abrir no espaço";SpatialMenu.WINDOWS->"Suas janelas";SpatialMenu.SETTINGS->"Ajustar VR Box";SpatialMenu.TRACKING->"Mãos e olhar";SpatialMenu.PERMISSIONS->"Seus acessos";else->"Developer"},25f,52f,32f,WHITE,524f)
        button("dismiss","×",558f,13f,63f,54f) { openMenu(SpatialMenu.CLOSED) }
        val tabs=arrayOf(SpatialMenu.APPS,SpatialMenu.WINDOWS,SpatialMenu.SETTINGS)
        val labels=arrayOf("Apps","Janelas","VR Box")
        for(i in tabs.indices) button("tab$i",labels[i],21f+i*207,80f,190f,54f,menu==tabs[i]) { openMenu(tabs[i]) }
        when(menu) {
            SpatialMenu.APPS -> appsMenu()
            SpatialMenu.WINDOWS -> windowsMenu()
            SpatialMenu.SETTINGS -> settingsMenu()
            SpatialMenu.TRACKING -> trackingMenu()
            SpatialMenu.PERMISSIONS -> permissionsMenu()
            SpatialMenu.DEVELOPER -> developerMenu()
            else -> Unit
        }
    }
    private fun appsMenu() {
        val builtins=arrayOf(WindowContent.NOTES,WindowContent.CLOCK,WindowContent.DIAGNOSTICS)
        val count=3+state.scripts.size+state.installedApps.size
        for(row in 0..4) {
            val index=page*5+row;if(index>=count) break
            val title=if(index<3) arrayOf("Notas","Relógio","Diagnóstico")[index] else if(index<3+state.scripts.size) state.scripts[index-3].title else state.installedApps[index-3-state.scripts.size].label
            val subtitle=if(index<3) "Nativo" else if(index<3+state.scripts.size) "Lua · ${state.scripts[index-3].type}" else "Android · sujeito à compatibilidade"
            val y=153f+row*75
            rect(20f,y,600f,66f,PANEL,12f);text(title,39f,y+31,26f,WHITE,536f);text(subtitle,40f,y+56,19f,MUTED,535f)
            hit("app$index",title,RectF(20f,y,620f,y+66),action={
                openMenu(SpatialMenu.CLOSED)
                when { index<3->actions.openBuiltin(builtins[index]);index<3+state.scripts.size->actions.runScript(state.scripts[index-3]);else->actions.openAndroid(state.installedApps[index-3-state.scripts.size]) }
            })
        }
        button("prev","‹",22f,548f,72f,62f) { page=(page-1).coerceAtLeast(0);state.dirty=true }
        text("${page+1} / ${(count+4)/5}",115f,588f,24f,MUTED)
        button("next","›",232f,548f,72f,62f) { if((page+1)*5<count)page++;state.dirty=true }
        button("more","Mãos / acessos",329f,548f,288f,62f) { openMenu(SpatialMenu.TRACKING) }
    }
    private fun windowsMenu() {
        val windows=state.windows.windows
        for(i in 0..3) {
            val w=windows.getOrNull(page*4+i) ?: break
            val y=153f+i*66
            button("focus${w.id}",w.title+(if(w.minimized)" · reabrir" else ""),22f,y,527f,57f,w.focused) { state.windows.focus(w.id);actions.windowResized(w.id);state.dirty=true }
            button("close${w.id}","×",556f,y,62f,57f) { actions.closeWindow(w.id) }
        }
        val focused=windows.firstOrNull { it.focused }
        if(focused!=null) {
            button("near","Mais perto",22f,434f,289f,58f) { state.windows.distance(focused.id,distance(focused)-.18f);state.dirty=true }
            button("far","Mais longe",327f,434f,290f,58f) { state.windows.distance(focused.id,distance(focused)+.18f);state.dirty=true }
            button("bigger","Maior +",22f,503f,289f,53f) { state.windows.scaleSpatial(focused.id,1.12f);state.dirty=true }
            button("smaller","Menor −",327f,503f,290f,53f) { state.windows.scaleSpatial(focused.id,1/1.12f);state.dirty=true }
        }
        button("arrange","Organizar no espaço",22f,573f,440f,50f,true) { state.windows.arrangeSpatial();actions.recenter();openMenu(SpatialMenu.CLOSED) }
        button("page","›",490f,573f,126f,50f) { page=if((page+1)*4<windows.size)page+1 else 0;state.dirty=true }
    }
    private fun settingsMenu() {
        val s=state.settings
        fun set(next: XrSettings) { actions.updateSettings(next);state.dirty=true }
        fun row(index: Int,label: String,value: String,minus: ()->Unit,plus: ()->Unit) {
            val y=155f+index*61
            text(label,26f,y+35,24f,WHITE,278f);text(value,309f,y+35,24f,PURPLE,153f)
            button("minus$index","−",472f,y,65f,50f,action=minus);button("plus$index","+",551f,y,65f,50f,action=plus)
        }
        if(settingsPage==0) {
            row(0,"IPD dos olhos","${s.ipdMm.toInt()} mm",{set(s.copy(ipdMm=(s.ipdMm-1).coerceAtLeast(50f)))},{set(s.copy(ipdMm=(s.ipdMm+1).coerceAtMost(78f)))})
            row(1,"Centros das lentes",fmt(s.lensShift),{set(s.copy(lensShift=(s.lensShift-.01f).coerceAtLeast(-.15f)))},{set(s.copy(lensShift=(s.lensShift+.01f).coerceAtMost(.15f)))})
            row(2,"Altura nas lentes",fmt(s.lensVertical),{set(s.copy(lensVertical=(s.lensVertical-.01f).coerceAtLeast(-.15f)))},{set(s.copy(lensVertical=(s.lensVertical+.01f).coerceAtMost(.15f)))})
            row(3,"Distorção opcional",fmt(s.lensDistortion),{set(s.copy(lensDistortion=(s.lensDistortion-.025f).coerceAtLeast(0f)))},{set(s.copy(lensDistortion=(s.lensDistortion+.025f).coerceAtMost(.4f)))})
            row(4,"Tamanho das janelas","${(s.uiScale*100).toInt()}%",{set(s.copy(uiScale=(s.uiScale-.1f).coerceAtLeast(.5f)))},{set(s.copy(uiScale=(s.uiScale+.1f).coerceAtMost(1.5f)))})
            text("Ajuste com o telefone alinhado às lentes.",27f,498f,22f,MUTED)
        } else {
            row(0,"Render","${(s.renderScale*100).toInt()}%",{set(s.copy(renderScale=(s.renderScale-.05f).coerceAtLeast(.5f)))},{set(s.copy(renderScale=(s.renderScale+.05f).coerceAtMost(1f)))})
            row(1,"FOV virtual VR","${s.fovDegrees.toInt()}°",{set(s.copy(fovDegrees=(s.fovDegrees-5).coerceAtLeast(45f)))},{set(s.copy(fovDegrees=(s.fovDegrees+5).coerceAtMost(110f)))})
            button("fps","FPS alvo: ${s.targetFps}",23f,291f,594f,57f) { set(s.copy(targetFps=if(s.targetFps==60)30 else 60)) }
            button("ar","ARCore: ${if(s.spatialTracking)"automático" else "desativado"}",23f,359f,594f,57f) { set(s.copy(spatialTracking=!s.spatialTracking)) }
            paragraph(state.spatialStatus,27f,449f,580f,22f,31f,2)
        }
        button("settingspage",if(settingsPage==0)"Performance ›" else "‹ Lentes",22f,528f,289f,56f) { settingsPage=1-settingsPage;state.dirty=true }
        button("tracking","Mãos / olhar",327f,528f,290f,56f) { openMenu(SpatialMenu.TRACKING) }
        text("MR usa a projeção da câmera; VR usa o FOV virtual.",24f,621f,19f,MUTED,592f)
    }
    private fun trackingMenu() {
        paragraph(state.trackingStatus,26f,174f,587f,23f,32f,2)
        text("${state.effectiveTrackingFps} FPS · inferência ${state.hands.inferenceMillis.toInt()} ms",27f,247f,23f,PURPLE)
        button("gaze","Selecionar pelo olhar: ${if(state.settings.gazeEnabled)"sim" else "não"}",22f,273f,595f,60f) { actions.updateSettings(state.settings.copy(gazeEnabled=!state.settings.gazeEnabled)) }
        button("hands","MediaPipe: ${if(state.settings.trackingEnabled)"ativo" else "pausado"}",22f,349f,595f,60f) { actions.updateSettings(state.settings.copy(trackingEnabled=!state.settings.trackingEnabled)) }
        paragraph("Olhar: pare sobre um botão por 1 s.\nPinça: pressione e arraste pela alça.\nControle: gatilho/Enter seleciona; voltar recentra.",28f,452f,582f,22f,33f,3)
        button("access","Permissões",22f,568f,290f,57f) { openMenu(SpatialMenu.PERMISSIONS) }
        button("developer","Developer",328f,568f,290f,57f) { openMenu(SpatialMenu.DEVELOPER) }
    }
    private fun permissionsMenu() {
        paragraph("Consentimentos Android são feitos no telefone, antes de colocá-lo no VR Box.",26f,175f,582f,24f,34f,3)
        button("camera","${if(state.cameraGranted)"Câmera autorizada" else "Autorizar câmera"}",22f,283f,595f,63f) { actions.requestCamera() }
        button("accessibility","Controle de apps Android",22f,362f,595f,63f) { actions.requestAccessibility() }
        button("capture",if(state.captureActive)"Parar compartilhamento" else "Compartilhar app Android",22f,441f,595f,63f) { actions.requestCapture() }
        button("luaaccess","Capabilities Lua · no telefone",22f,541f,595f,64f) { actions.openPhoneTools(Page.PERMISSIONS) }
    }
    private fun developerMenu() {
        paragraph("GAME e WINDOW abrem em superfícies independentes, com profundidade própria.",25f,178f,584f,24f,34f,3)
        button("hello","Executar Olá, espaço",22f,285f,595f,63f) { openMenu(SpatialMenu.CLOSED);actions.runScript(state.scripts[0]) }
        button("orbit","Executar Orbit",22f,364f,595f,63f) { openMenu(SpatialMenu.CLOSED);actions.runScript(state.scripts[1]) }
        button("editor","Editor / API no telefone",22f,443f,595f,63f) { actions.openPhoneTools(Page.DEVELOPER) }
        button("stop","Parar scripts",22f,540f,595f,63f) { actions.stopScripts();state.dirty=true }
    }

    /** Returns the exact nearest physical panel, not desktop z-order or a giant implicit hit plane. */
    fun cast(snapshot: PanelSnapshot,x: Float,y: Float,eye: Int): SpatialTarget? {
        if(!snapshot.projection.ray(x,y,eye,ray)) return null
        val index=snapshot.hit(ray,hit)
        val distance=if(index>=0)hit[2]-.003f else 2f
        ray.point(distance,cursorWorld)
        val target=if(index<0)null else find(snapshot.ids[index],hit[0],hit[1])
        if(hovered?.id!=target?.id) { hovered=target;state.dirty=true }
        return target
    }
    private fun find(id: Int,u: Float,v: Float): SpatialTarget? {
        val buffer=buffers[id] ?: return null
        for(i in targets.indices.reversed()) { val t=targets[i];if(t.panelId==id && t.rect.contains(u*buffer.width,v*buffer.height)) return t }
        return null
    }
    fun pointer(action: PointerAction,x: Float,y: Float,eye: Int,source: InputSource,time: Long,snapshot: PanelSnapshot) {
        val current=cast(snapshot,x,y,eye)
        when(action) {
            PointerAction.DOWN -> {
                captured=current;downTime=time
                val t=current ?: return
                val index=snapshot.indexOf(t.panelId);if(index<0)return
                capturedPose.copyFrom(snapshot.poses[index]);capturedPose.intersect(ray,hit,false);ray.point(hit[2],down)
                downU=hit[0];downV=hit[1];originalWidth=state.windows.get(t.panelId)?.pose?.width ?: capturedPose.width
                if(t.panelId>0) { state.windows.focus(t.panelId);state.dirty=true }
                windowInput(t,action,source,hit[0],hit[1])
            }
            PointerAction.MOVE -> captured?.let { t ->
                if(capturedPose.intersect(ray,hit,false)) {
                    windowInput(t,action,source,hit[0],hit[1])
                    if(source!=InputSource.GAZE && t.kind==HitKind.MOVE) {
                        ray.point(hit[2],point)
                        state.windows.place(t.panelId,capturedPose.x+point[0]-down[0]-rootX(),capturedPose.y+point[1]-down[1]-rootY(),capturedPose.z+point[2]-down[2],capturedPose.yaw)
                        state.dirty=true
                    } else if(source!=InputSource.GAZE && t.kind==HitKind.RESIZE) {
                        val w=state.windows.get(t.panelId) ?: return@let
                        val desired=(originalWidth*(1+(hit[0]-downU))).coerceIn(.3f,1.5f)
                        state.windows.scaleSpatial(w.id,desired/w.pose.width);state.dirty=true
                    }
                }
            }
            PointerAction.UP -> {
                val t=captured;captured=null
                if(t!=null) {
                    capturedPose.intersect(ray,hit,false)
                    if(hit[0].isFinite()) windowInput(t,action,source,hit[0],hit[1])
                    when(t.kind) {
                        HitKind.BUTTON -> if(t.id==current?.id)t.action?.invoke()
                        HitKind.MOVE -> if(source==InputSource.GAZE && current?.id==t.id) startGazeGrab(t.panelId,time)
                        HitKind.RESIZE -> { if(source==InputSource.GAZE)state.windows.scaleSpatial(t.panelId,1.12f);actions.windowResized(t.panelId) }
                        HitKind.EXTERNAL -> {
                            val b=bodyRect();val buffer=buffers[t.panelId] ?: return
                            fun u(v: Float)=((v*buffer.width-b.left)/b.width()).coerceIn(0f,1f)
                            fun v(v: Float)=((v*buffer.height-b.top)/b.height()).coerceIn(0f,1f)
                            if(hit[0].isFinite())actions.externalGesture(t.panelId,u(downU),v(downV),u(hit[0]),v(hit[1]),time-downTime)
                        }
                        else -> Unit
                    }
                    state.dirty=true
                }
            }
            PointerAction.CANCEL -> { captured=null;state.dirty=true }
        }
    }
    private fun windowInput(t: SpatialTarget,action: PointerAction,source: InputSource,u: Float,v: Float) {
        if(t.panelId<=0 || t.kind==HitKind.MOVE || t.kind==HitKind.RESIZE)return
        val b=bodyRect();val pixels=buffers[t.panelId] ?: return
        val x=(u*pixels.width-b.left)/b.width();val y=(v*pixels.height-b.top)/b.height()
        if((x in 0f..1f && y in 0f..1f) || action==PointerAction.CANCEL || action==PointerAction.UP)
            actions.windowPointer(t.panelId,x.coerceIn(0f,1f),y.coerceIn(0f,1f),action.name.lowercase(),source.name.lowercase())
    }
    private fun startGazeGrab(id: Int,time: Long) {
        val w=state.windows.get(id) ?: return
        gazeGrabbed=id;grabDistance=distance(w);grabMoved=false;grabSince=time
        grabInitial[0]=ray.dx;grabInitial[1]=ray.dy;grabInitial[2]=ray.dz;grabInitial.copyInto(grabLast)
    }
    fun updateGazeGrab(snapshot: PanelSnapshot,time: Long,x: Float=.5f,y: Float=.5f): Boolean {
        if(gazeGrabbed<0)return false
        snapshot.projection.ray(x,y,-1,ray)
        val moved=ray.dx*grabInitial[0]+ray.dy*grabInitial[1]+ray.dz*grabInitial[2]<.999f
        if(moved)grabMoved=true
        val delta=abs(ray.dx-grabLast[0])+abs(ray.dy-grabLast[1])+abs(ray.dz-grabLast[2])
        if(delta>.0025f || !grabMoved)grabSince=time
        grabLast[0]=ray.dx;grabLast[1]=ray.dy;grabLast[2]=ray.dz
        ray.point(grabDistance,point)
        state.windows.place(gazeGrabbed,point[0]-rootX(),point[1]+(state.windows.get(gazeGrabbed)?.pose?.height ?: .5f)*state.settings.uiScale*.43f-rootY(),point[2]);state.dirty=true
        if(grabMoved && time-grabSince>=1100) finishGazeGrab()
        return true
    }
    fun finishGazeGrab() { if(gazeGrabbed>=0)actions.windowResized(gazeGrabbed);gazeGrabbed=-1;state.dirty=true }
    private fun rootX()=state.settings.uiOffsetX+state.session.uiAnchorX
    private fun rootY()=state.settings.uiOffsetY+state.session.uiAnchorY
    private fun distance(w: XRWindow)=sqrt(w.pose.x*w.pose.x+w.pose.z*w.pose.z)
    private fun fmt(v: Float)=String.format(Locale.US,"%.2f",v)
    private fun hit(key: String,label: String,rect: RectF,kind: HitKind=HitKind.BUTTON,action: (() -> Unit)?=null) {
        val k="$panelId:$key";targets.add(SpatialTarget(identities.getOrPut(k){nextId++},k,panelId,label,rect,kind,action))
    }
    private fun button(key: String,label: String,x: Float,y: Float,w: Float,h: Float,primary: Boolean=false,action: () -> Unit) {
        val hover=hovered?.key=="$panelId:$key"
        rect(x,y,w,h,if(primary)0xff644296.toInt() else if(hover)0xff40304f.toInt() else PANEL,12f)
        text(label,x+16,y+h/2+9,25f,WHITE,w-27);hit(key,label,RectF(x,y,x+w,y+h),action=action)
    }
    private fun rect(x: Float,y: Float,w: Float,h: Float,color: Int,r: Float=12f) { paint.style=Paint.Style.FILL;paint.color=color;canvas.drawRoundRect(x,y,x+w,y+h,r,r,paint) }
    private fun stroke(x: Float,y: Float,w: Float,h: Float,color: Int,r: Float) { paint.style=Paint.Style.STROKE;paint.strokeWidth=2f;paint.color=color;canvas.drawRoundRect(x,y,x+w,y+h,r,r,paint);paint.style=Paint.Style.FILL }
    private fun line(x: Float,y: Float,a: Float,b: Float,color: Int) { paint.color=color;paint.strokeWidth=1.5f;canvas.drawLine(x,y,a,b,paint) }
    private fun text(value: String,x: Float,y: Float,size: Float,color: Int,maxWidth: Float=Float.MAX_VALUE) {
        paint.color=color;paint.textSize=size;paint.typeface=font;paint.style=Paint.Style.FILL
        val n=paint.breakText(value,true,maxWidth.coerceAtLeast(0f),null)
        canvas.drawText(if(n<value.length && n>2)value.take(n-1)+"…" else value.take(n),x,y,paint)
    }
    private fun paragraph(value: String,x: Float,y: Float,maxWidth: Float,size: Float,spacing: Float,maxLines: Int) {
        var row=0
        for(line in value.lines()) {
            var rest=line
            do {
                if(row>=maxLines)return
                paint.textSize=size;paint.typeface=font
                var n=paint.breakText(rest,true,maxWidth,null)
                if(n<rest.length) { val space=rest.lastIndexOf(' ',(n-1).coerceAtLeast(0));if(space>n/2)n=space }
                if(n==0 && rest.isNotEmpty())return
                text(rest.take(n),x,y+row*spacing,size,MUTED);rest=rest.drop(n).trimStart();row++
            }while(rest.isNotEmpty())
        }
    }
    companion object {
        val WHITE=0xfff5f2fc.toInt();val MUTED=0xffb8b0c6.toInt();val PURPLE=0xffb895f5.toInt()
        val BG=0xf21a1422.toInt();val PANEL=0xff2b2335.toInt();val BORDER=0xff61506f.toInt()
        fun bodyRect()=RectF(16f,64f,496f,334f)
    }
}
