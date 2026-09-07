package com.brazilmr.ui

import android.graphics.*
import com.brazilmr.core.input.PointerAction
import com.brazilmr.core.performance.*
import com.brazilmr.core.permission.Capability
import com.brazilmr.core.session.EnvironmentMode
import com.brazilmr.core.tracking.HandData
import com.brazilmr.core.window.*
import com.brazilmr.platform.*
import com.brazilmr.render.RenderFrame
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

enum class HitKind { BUTTON, MOVE, RESIZE, EXTERNAL, BLOCK }
class UiTarget(val id: Int, val key: String, val label: String, val rect: RectF, val kind: HitKind = HitKind.BUTTON, val windowId: Int = -1, val action: (() -> Unit)? = null)

/** Native Canvas UI. Drawn only when dirty, then shared by both eye viewports as a single texture. */
class XrUiScene(val state: PlatformState, private val actions: UiActions) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val scratch = RectF()
    private val bold = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val normal = Typeface.create("sans-serif", Typeface.NORMAL)
    private val mono = Typeface.create("monospace", Typeface.NORMAL)
    private val targets = ArrayList<UiTarget>(128)
    val visibleTargets: List<UiTarget> get() = targets
    private val ids = HashMap<String, Int>()
    private var nextId = 1
    private var canvas = Canvas()
    private var hover = -1
    private var captured: UiTarget? = null
    private var downX = 0f; private var downY = 0f; private var downTime = 0L
    private var windowX = 0f; private var windowY = 0f; private var windowWidth = 0f; private var windowHeight = 0f
    private val timeFormat = SimpleDateFormat("HH:mm", Locale("pt", "BR"))
    private val dateFormat = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("pt", "BR"))
    var lastPointerX = .5f; private set
    var lastPointerY = .5f; private set
    var pointerOnUi = false; private set
    var onTargetsChanged: (() -> Unit)? = null

    fun draw(target: Canvas) {
        canvas = target; canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        targets.clear()
        if (!state.session.uiVisible) {
            button("recover", "Reabrir interface", 653f, 785f, 294f, 66f, "eye", true) { state.session.setUiVisible(true); actions.recenter(); state.dirty = true }
            onTargetsChanged?.invoke(); return
        }
        panel(14f, 14f, 1572f, 872f, 28f, BG, BORDER)
        panel(14f, 14f, 196f, 872f, 28f, SIDEBAR)
        line(210f, 40f, 210f, 862f, BORDER)
        logo(43f, 46f, 36f)
        text("BRAZIL MR", 89f, 69f, 21f, WHITE, bold)
        text("XR WORKSPACE", 43f, 104f, 12f, MUTED, mono)
        for ((index, page) in Page.entries.withIndex()) {
            val top = 146f + index * 72f
            val active = state.page == page
            if (active) panel(30f, top, 164f, 54f, 13f, SELECTED)
            icon(page.icon, 52f, top + 27f, 21f, if (active) PURPLE else MUTED)
            text(page.title, 76f, top + 33f, 16f, if (active) WHITE else MUTED, if (active) bold else normal)
            hit("nav.${page.name}", page.title, 30f, top, 164f, 58f) { state.navigate(page) }
        }
        text("FOUNDATION  0.1", 39f, 813f, 11f, MUTED, mono)
        circle(44f, 840f, 3f, if (state.cameraActive) GREEN else MUTED)
        text(if (state.cameraActive) "Câmera em uso" else "No seu controle", 57f, 845f, 12f, MUTED)
        header()
        when (state.page) {
            Page.HOME -> home()
            Page.APPS -> apps()
            Page.WINDOWS -> windowsPage()
            Page.TRACKING -> tracking()
            Page.SETTINGS -> settings()
            Page.PERMISSIONS -> permissions()
            Page.DEVELOPER -> developer()
            Page.NOTIFICATIONS -> notifications()
        }
        dock()
        if (state.quickSettings) quickSettings()
        onTargetsChanged?.invoke()
    }
    private fun header() {
        val title = when (state.page) {
            Page.HOME -> "Um novo ponto de vista."
            Page.APPS -> "O que vamos abrir?"
            Page.WINDOWS -> "Tudo no seu espaço."
            Page.TRACKING -> "Interação, na sua mão."
            Page.SETTINGS -> "Do seu jeito."
            Page.PERMISSIONS -> "Você está no controle."
            Page.DEVELOPER -> "Brazil MR Developer"
            Page.NOTIFICATIONS -> "O que acontece por aqui."
        }
        text("WORKSPACE / ${state.page.name}", 247f, 56f, 12f, PURPLE, mono)
        text(title, 246f, 105f, 34f, WHITE, bold)
        pill(if (state.session.mode == EnvironmentMode.VR) "VR" else "MR", 1210f, 40f, 74f, true)
        text(if (state.settings.sbs) "SBS ATIVO" else "MONO", 1302f, 63f, 12f, MUTED, mono)
        button("header.quick", "Ajustes", 1400f, 35f, 150f, 52f, "settings") { state.quickSettings = !state.quickSettings; state.dirty = true }
        text(if (state.fps > 0) "${state.fps} FPS" else "INICIANDO", 1301f, 100f, 12f, if (state.fps >= 45) GREEN else MUTED, mono)
        text(if (state.batteryPercent >= 0) "${state.batteryPercent}% bateria" else "Android XR", 1404f, 100f, 12f, MUTED)
        line(246f, 130f, 1551f, 130f, BORDER)
    }
    private fun home() {
        val mr = state.session.mode == EnvironmentMode.MR
        overviewCard(246f, "AMBIENTE", if (mr) "Mixed Reality" else "Virtual Reality", if (state.cameraActive && mr) "Passthrough pela câmera" else if (mr) (if (state.cameraGranted) "Passthrough pausado" else "Câmera ainda não autorizada") else "Espaço virtual · ${if (state.settings.sbs) "dois olhos" else "mono"}", if (mr) "camera" else "vr") {
            if (!state.cameraGranted) actions.requestCamera() else actions.toggleMode()
        }
        val hands = (if (state.hands.left.present) 1 else 0) + (if (state.hands.right.present) 1 else 0)
        overviewCard(689f, "HAND TRACKING", if (hands > 0) "$hands ${if (hands == 1) "mão detectada" else "mãos detectadas"}" else "Suas mãos. Seu input.", if (hands > 0) "${state.hands.inferenceMillis.toInt()} ms de inferência · One Euro" else state.trackingStatus, "hand") { state.navigate(Page.TRACKING) }
        overviewCard(1132f, "DESEMPENHO", when (state.settings.performanceMode) { PerformanceMode.ECONOMY -> "Econômico"; PerformanceMode.BALANCED -> "Balanceado"; PerformanceMode.PERFORMANCE -> "Desempenho" }, "Escala ${(state.effectiveRenderScale*100).toInt()}% · ${state.spatialStatus}", "pulse") { state.settingsTab = 4; state.navigate(Page.SETTINGS) }
        text("Janelas no seu espaço", 247f, 337f, 24f, WHITE, bold)
        text("${state.windows.windows.count { !it.minimized }} abertas  /  Multi Window sempre ativo", 247f, 365f, 14f, MUTED)
        button("home.tile", "Organizar", 1230f, 315f, 155f, 52f, "windows") { state.windows.tile(); state.dirty = true }
        button("home.add", "Abrir app", 1400f, 315f, 150f, 52f, "plus", true) { state.navigate(Page.APPS) }
        workspace()
    }
    private fun overviewCard(x: Float, eyebrow: String, title: String, subtitle: String, glyph: String, action: () -> Unit) {
        panel(x, 156f, 418f, 131f, 18f, PANEL, BORDER)
        icon(glyph, x+31, 186f, 24f, PURPLE)
        text(eyebrow, x+54, 192f, 11f, MUTED, mono)
        text(title, x+23, 232f, 25f, WHITE, bold, 365f)
        text(subtitle, x+23, 261f, 13f, MUTED, maxWidth = 369f)
        hit("overview.$eyebrow", "$title. $subtitle", x, 156f, 418f, 131f, action = action)
    }
    private fun workspace() {
        panel(WORK.left, WORK.top, WORK.width(), WORK.height(), 20f, 0xff0d0b13.toInt(), BORDER)
        // Very light spatial reference marks, not a full animated grid.
        for (x in 0..14) circle(WORK.left + 38 + x*87, WORK.bottom-16, 1f, 0xff332c44.toInt())
        if (state.windows.windows.none { !it.minimized }) {
            icon("windows", 900f, 530f, 50f, PURPLE)
            text("Um espaço livre para começar.", 687f, 593f, 25f, WHITE, bold)
            button("workspace.empty", "Explorar apps", 777f, 622f, 240f, 64f, "grid", true) { state.navigate(Page.APPS) }
        }
        for (window in state.windows.windows) if (!window.minimized) drawWindow(window)
    }
    private fun drawWindow(window: XRWindow) {
        val bounds = bounds(window)
        val titleHeight = titleHeight(window)
        hit("window.${window.id}.body", window.title, bounds.left, bounds.top, bounds.width(), bounds.height(), HitKind.BLOCK, window.id)
        panel(bounds.left, bounds.top, bounds.width(), bounds.height(), 15f, 0xff15121e.toInt(), if (window.focused) 0xff7853b1.toInt() else BORDER)
        canvas.save(); canvas.clipRect(bounds.left+1, bounds.top+1, bounds.right-1, bounds.bottom-1)
        panel(bounds.left, bounds.top, bounds.width(), titleHeight, 13f, if (window.focused) 0xff231a33.toInt() else 0xff1b1726.toInt())
        circle(bounds.left+20, bounds.top+titleHeight/2, 3.5f, if (window.focused) PURPLE else MUTED)
        text(window.title, bounds.left+34, bounds.top+titleHeight/2+6, 17f, WHITE, bold, bounds.width()-186)
        text(if (window.appType == AppType.GAME) "GAME" else "", bounds.right-160, bounds.top+titleHeight/2+5, 10f, PURPLE, mono)
        hit("window.${window.id}.move", "Mover ${window.title}", bounds.left, bounds.top, bounds.width()-102, titleHeight, HitKind.MOVE, window.id)
        icon("minus", bounds.right-75, bounds.top+titleHeight/2, 17f, MUTED)
        icon("close", bounds.right-27, bounds.top+titleHeight/2, 17f, MUTED)
        hit("window.${window.id}.min", "Minimizar ${window.title}", bounds.right-100, bounds.top, 50f, titleHeight, windowId = window.id) { actions.minimizeWindow(window.id) }
        hit("window.${window.id}.close", "Fechar ${window.title}", bounds.right-50, bounds.top, 50f, titleHeight, windowId = window.id) { actions.closeWindow(window.id) }
        val content = contentBounds(window)
        canvas.save(); canvas.clipRect(content)
        when (window.content) {
            WindowContent.NOTES -> {
                paragraph(window.text, content.left+13, content.top+28, content.width()-26, 19f, 29f, MUTED, 8)
                hit("notes.${window.id}", "Editar notas", content.left, content.top, content.width(), content.height(), windowId = window.id) { actions.editNotes(window.id) }
                text("TOQUE PARA EDITAR", content.left+13, content.bottom-10, 10f, PURPLE, mono)
            }
            WindowContent.CLOCK -> {
                val date = Date()
                text(timeFormat.format(date), content.left+22, content.top+104, 78f, WHITE, bold)
                text(dateFormat.format(date).replaceFirstChar { it.titlecase() }, content.left+26, content.top+142, 17f, MUTED, maxWidth = content.width()-40)
                line(content.left+26, content.top+170, content.right-26, content.top+170, BORDER)
                text("PRESENTE NO SEU ESPAÇO", content.left+26, content.top+199, 11f, PURPLE, mono)
            }
            WindowContent.DIAGNOSTICS -> {
                paragraph("${state.fps} FPS renderizados\n${state.hands.inferenceMillis.toInt()} ms · inferência\n${state.effectiveTrackingFps} FPS · teto de tracking\n${state.spatialStatus}\nTérmico: ${thermalLabel()}", content.left+18, content.top+34, content.width()-30, 21f, 37f, MUTED)
            }
            WindowContent.LUA -> {
                for (item in state.elements.elements) if (item.windowId == window.id && item.visible) {
                    val x = content.left + item.x * content.width(); val y = content.top + item.y * content.height()
                    val w = item.width * content.width(); val h = item.height * content.height()
                    if (item.kind != "text") panel(x, y, w, h, if (item.kind == "button") 12f else 6f, item.background)
                    text(item.text, x+if (item.kind == "text") 0 else 15, y+h/2+item.fontSize*.34f, item.fontSize, item.color, if (item.kind == "button") bold else normal, w-15)
                    if (item.kind == "button") {
                        val clipped = RectF(x, y, x+w, y+h); if (clipped.intersect(content)) hit("lua.${item.id}", item.text, clipped.left, clipped.top, clipped.width(), clipped.height(), windowId = window.id) { actions.clickLuaElement(item.id) }
                    }
                }
                if (state.elements.elements.none { it.windowId == window.id }) text(window.status.ifEmpty { "Runtime Lua · inicializando" }, content.left+16, content.top+45, 16f, MUTED, maxWidth = content.width()-30)
            }
            WindowContent.ANDROID, WindowContent.CAPTURE -> {
                icon(if (window.content == WindowContent.CAPTURE) "cast" else "phone", content.centerX(), content.top+65, 40f, PURPLE)
                paragraph(window.status.ifEmpty { "Aguardando conteúdo do app..." }, content.left+20, content.top+110, content.width()-40, 17f, 27f, MUTED, 3)
                if (window.displayId >= 0 || (window.content == WindowContent.CAPTURE && state.captureActive)) {
                    // External surfaces are rendered BEFORE this UI texture. A transparent aperture
                    // preserves occlusion by every window drawn after this one (including native/Lua windows).
                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                }
                hit("external.${window.id}", "Conteúdo de ${window.title}", content.left, content.top, content.width(), content.height(), HitKind.EXTERNAL, window.id)
            }
        }
        canvas.restore()
        icon("resize", bounds.right-13, bounds.bottom-13, 15f, if (window.focused) PURPLE else MUTED)
        hit("resize.${window.id}", "Redimensionar ${window.title}", bounds.right-39, bounds.bottom-39, 39f, 39f, HitKind.RESIZE, window.id)
        canvas.restore()
    }
    private fun apps() {
        text("FEITOS PARA O SEU WORKSPACE", 249f, 178f, 12f, PURPLE, mono)
        val builtins = listOf(Triple("Notas", "note", WindowContent.NOTES), Triple("Relógio", "clock", WindowContent.CLOCK), Triple("Diagnóstico", "pulse", WindowContent.DIAGNOSTICS))
        for ((i, app) in builtins.withIndex()) appCard(248f+i*260, 200f, app.first, "WINDOW · nativo", app.second) { actions.openBuiltin(app.third) }
        appCard(1028f, 200f, "Olá, espaço", "WINDOW · Lua", "code") { actions.runScript(state.scripts[0]) }
        appCard(1288f, 200f, "Orbit", "GAME · Lua", "orbit") { actions.runScript(state.scripts[1]) }
        text("APPS ANDROID", 249f, 394f, 12f, PURPLE, mono)
        text("Compatibilidade verificada pelo sistema. Apps protegidos podem não permitir exibição.", 249f, 423f, 15f, MUTED)
        val start = state.appPage * 8
        for (i in 0..7) {
            val app = state.installedApps.getOrNull(start+i) ?: break
            appCard(248f + (i%4)*325, 455f + (i/4)*145, app.label, app.packageName, "phone", 305f, 124f) { actions.openAndroid(app) }
        }
        if (state.installedApps.isEmpty()) paragraph("Nenhum app com launcher encontrado.\nOs apps internos e exemplos Lua continuam disponíveis.", 270f, 510f, 960f, 21f, 34f, MUTED)
        button("apps.prev", "Anterior", 1150f, 747f, 145f, 46f) { state.appPage = (state.appPage-1).coerceAtLeast(0); state.dirty = true }
        button("apps.next", "Próxima", 1310f, 747f, 145f, 46f) { if ((state.appPage+1)*8 < state.installedApps.size) state.appPage++; state.dirty = true }
    }
    private fun appCard(x: Float, y: Float, label: String, description: String, glyph: String, width: Float = 240f, height: Float = 155f, action: () -> Unit) {
        panel(x, y, width, height, 18f, PANEL, BORDER)
        panel(x+19, y+18, 46f, 46f, 12f, SELECTED)
        icon(glyph, x+42, y+41, 25f, PURPLE)
        text(label, x+20, y+height-50, 21f, WHITE, bold, width-35)
        text(description, x+20, y+height-23, 12f, MUTED, maxWidth = width-35)
        hit("app.$x.$y", "$label. $description", x, y, width, height, action = action)
    }
    private fun windowsPage() {
        text("${state.windows.windows.size} JANELAS  /  ${state.windows.windows.count { it.minimized }} MINIMIZADAS", 249f, 179f, 12f, PURPLE, mono)
        val minimized = state.windows.windows.filter { it.minimized }
        if (minimized.isEmpty()) text("Minimizar não encerra o app. Cada janela mantém seu próprio estado.", 249f, 228f, 20f, MUTED)
        for ((index, window) in minimized.take(4).withIndex()) button("restore.${window.id}", window.title, 249f+index*325, 204f, 307f, 78f, "windows") { state.windows.focus(window.id); actions.windowResized(window.id); state.dirty = true }
        button("windows.tile", "Organizar lado a lado", 247f, 315f, 290f, 52f, "windows", true) { state.windows.tile(); state.dirty = true }
        button("windows.apps", "Abrir outra janela", 552f, 315f, 252f, 52f, "plus") { state.navigate(Page.APPS) }
        workspace()
    }
    private fun tracking() {
        panel(247f, 159f, 790f, 527f, 20f, PANEL, BORDER)
        text("MEDIAPIPE / LANDMARKS", 272f, 196f, 12f, PURPLE, mono)
        text(state.trackingStatus, 272f, 228f, 17f, MUTED, maxWidth = 728f)
        line(645f, 259f, 645f, 603f, BORDER)
        hand(state.hands.left, RectF(279f, 264f, 615f, 578f))
        hand(state.hands.right, RectF(673f, 264f, 1007f, 578f))
        text("ESQUERDA", 296f, 620f, 13f, MUTED, mono); text("DIREITA / PONTEIRO", 690f, 620f, 13f, PURPLE, mono)
        text("${state.effectiveTrackingFps} FPS teto  ·  ${state.hands.inferenceMillis.toInt()} ms inferência  ·  buffers reutilizados", 272f, 660f, 13f, MUTED)
        panel(1060f, 159f, 491f, 162f, 18f, PANEL, BORDER)
        text("01   DIREITA → VR / MR", 1085f, 197f, 13f, PURPLE, mono)
        paragraph("Forme a arma, estabilize e dobre\no indicador para alternar o ambiente.", 1085f, 237f, 441f, 18f, 29f, WHITE)
        text("ESTADO  ${state.gestures.rightGun.state}", 1085f, 301f, 12f, MUTED, mono)
        panel(1060f, 340f, 491f, 154f, 18f, PANEL, BORDER)
        text("02   ESQUERDA → OCULTAR UI", 1085f, 379f, 13f, PURPLE, mono)
        paragraph("O mesmo gesto esconde a interface.\nO ambiente continua disponível.", 1085f, 419f, 441f, 18f, 29f, WHITE)
        panel(1060f, 514f, 491f, 172f, 18f, PANEL, BORDER)
        text("03   PUNHO FECHADO → REABRIR", 1085f, 553f, 13f, PURPLE, mono)
        text("Mão esquerda fechada por cerca de 5 s.", 1085f, 592f, 18f, WHITE)
        text("A UI reaparece ao lado da sua mão.", 1085f, 620f, 16f, MUTED)
        panel(1085f, 650f, 433f, 6f, 3f, BORDER)
        panel(1085f, 650f, 433f * state.gestures.leftFist.progress, 6f, 3f, PURPLE)
        paragraph("CAMERA  →  MEDIAPIPE  →  ONE EURO  →  GESTOS  →  INPUT\nPinça com a mão direita: pressionar, clicar e arrastar. Toque continua disponível.", 254f, 729f, 960f, 14f, 31f, MUTED)
        button("tracking.camera", if (state.cameraGranted) "Ajustar tracking" else "Permitir câmera", 1275f, 720f, 274f, 62f, "hand", true) { if (!state.cameraGranted) actions.requestCamera() else { state.settingsTab = 0; state.navigate(Page.SETTINGS) } }
    }
    private fun hand(hand: HandData, region: RectF) {
        if (!hand.present) {
            icon("hand", region.centerX(), region.centerY()-15, 74f, 0xff4f405f.toInt())
            text("Aguardando mão", region.left+78, region.centerY()+57, 18f, MUTED)
            return
        }
        for (i in BONES.indices step 2) {
            val a = BONES[i]; val b = BONES[i+1]
            line(region.left + hand.x(a)*region.width(), region.top + hand.y(a)*region.height(), region.left + hand.x(b)*region.width(), region.top + hand.y(b)*region.height(), 0xff8d68c2.toInt(), 2.2f)
        }
        for (i in 0..20) circle(region.left+hand.x(i)*region.width(), region.top+hand.y(i)*region.height(), if (i == 8) 5f else 3f, if (i == 8) WHITE else PURPLE)
    }
    private fun settings() {
        val names = listOf("Tracking", "VR / SBS", "MR", "Interface", "Performance")
        for ((index, name) in names.withIndex()) button("settings.tab.$index", name, 248f+index*262, 161f, 246f, 53f, primary = index == state.settingsTab) { state.settingsTab = index; state.dirty = true }
        val s = state.settings
        var n = 0
        fun item(label: String, value: String, description: String = "", minus: (() -> Unit)? = null, plus: (() -> Unit)? = null, toggle: (() -> Unit)? = null) {
            val x = 248f + (n%2)*663; val y = 243f + (n/2)*108; n++
            panel(x, y, 640f, 94f, 14f, PANEL, BORDER)
            text(label, x+19, y+29, 16f, WHITE, bold)
            text(description, x+19, y+72, 12f, MUTED, maxWidth = 322f)
            text(value, x+343, y+57, 24f, PURPLE, bold, 153f)
            if (toggle != null) button("setting.$label.toggle", "Alterar", x+508, y+19, 112f, 55f, action = toggle)
            if (minus != null) button("setting.$label.minus", "−", x+508, y+19, 51f, 55f, action = minus)
            if (plus != null) button("setting.$label.plus", "+", x+569, y+19, 51f, 55f, action = plus)
        }
        fun set(value: XrSettings) = actions.updateSettings(value)
        when (state.settingsTab) {
            0 -> {
                item("Hand tracking", onOff(s.trackingEnabled), "MediaPipe · CPU / assíncrono", toggle = { set(s.copy(trackingEnabled = !s.trackingEnabled)) })
                item("FPS de tracking", "${s.trackingFps}", "Teto; reduzido em idle e calor", { set(s.copy(trackingFps = (s.trackingFps-5).coerceAtLeast(5))) }, { set(s.copy(trackingFps = (s.trackingFps+5).coerceAtMost(60))) })
                item("Resolução de análise", "${s.trackingWidth}px", "CameraX usa o formato compatível", toggle = { set(s.copy(trackingWidth = when (s.trackingWidth) { 320 -> 640; 640 -> 960; else -> 320 })) })
                item("Mão direita", onOff(s.rightHand), "Indicador, pinça e VR / MR", toggle = { set(s.copy(rightHand = !s.rightHand)) })
                item("Mão esquerda", onOff(s.leftHand), "Ocultar e recuperar interface", toggle = { set(s.copy(leftHand = !s.leftHand)) })
                item("Minimum cutoff", fmt(s.filter.minimumCutoff), "One Euro · Hz", { set(s.copy(filter = s.filter.copy(minimumCutoff = (s.filter.minimumCutoff-.2f).coerceAtLeast(.2f)))) }, { set(s.copy(filter = s.filter.copy(minimumCutoff = (s.filter.minimumCutoff+.2f).coerceAtMost(8f)))) })
                item("Beta", fmt(s.filter.beta, 2), "Mais beta = mais responsividade", { set(s.copy(filter = s.filter.copy(beta = (s.filter.beta-.02f).coerceAtLeast(0f)))) }, { set(s.copy(filter = s.filter.copy(beta = (s.filter.beta+.02f).coerceAtMost(1f)))) })
                item("Derivative cutoff", fmt(s.filter.derivativeCutoff), "One Euro · Hz", { set(s.copy(filter = s.filter.copy(derivativeCutoff = (s.filter.derivativeCutoff-.2f).coerceAtLeast(.2f)))) }, { set(s.copy(filter = s.filter.copy(derivativeCutoff = (s.filter.derivativeCutoff+.2f).coerceAtMost(8f)))) })
                item("Inverter identificação", onOff(s.swapHands), "Calibração de lateralidade da câmera", toggle = { set(s.copy(swapHands = !s.swapHands)) })
            }
            1 -> {
                item("Side-by-Side", onOff(s.sbs), "Dois olhos, mesma cena", toggle = { set(s.copy(sbs = !s.sbs)) })
                item("IPD", "${s.ipdMm.toInt()} mm", "Separação geométrica dos olhos", { set(s.copy(ipdMm = (s.ipdMm-1).coerceAtLeast(50f))) }, { set(s.copy(ipdMm = (s.ipdMm+1).coerceAtMost(78f))) })
                item("FOV", "${s.fovDegrees.toInt()}°", "FOV virtual; não altera a lente", { set(s.copy(fovDegrees = (s.fovDegrees-5).coerceAtLeast(45f))) }, { set(s.copy(fovDegrees = (s.fovDegrees+5).coerceAtMost(110f))) })
                item("Escala de render", "${(s.renderScale*100).toInt()}%", "Teto para resolução dinâmica", { set(s.copy(renderScale = (s.renderScale-.05f).coerceAtLeast(.5f))) }, { set(s.copy(renderScale = (s.renderScale+.05f).coerceAtMost(1f))) })
                item("Resolução máxima", "${s.renderWidth}px", "Largura total / ambos os olhos", toggle = { set(s.copy(renderWidth = when (s.renderWidth) {1280 -> 1920; 1920 -> 2560; else -> 1280})) })
                item("FPS alvo", "${s.targetFps}", "Limitado pela tela, perfil e temperatura", toggle = { set(s.copy(targetFps = when(s.targetFps) {30 -> 60; 60 -> 90; 90 -> 120; else -> 30})) })
            }
            2 -> {
                item("Câmera", if (s.frontCamera) "Frontal" else "Traseira", "ARCore utiliza a câmera traseira", toggle = { set(s.copy(frontCamera = !s.frontCamera)) })
                item("Passthrough", onOff(s.passthrough), "Sem gravação ou envio de imagens", toggle = { set(s.copy(passthrough = !s.passthrough)) })
                item("Tracking espacial", onOff(s.spatialTracking), "ARCore opcional · fallback 3DoF", toggle = { set(s.copy(spatialTracking = !s.spatialTracking)) })
                item("Permissão de câmera", if (state.cameraGranted) "Concedida" else "Pendente", "Solicitação oficial do Android", toggle = actions::requestCamera)
                text(state.spatialStatus, 266f, 518f, 19f, MUTED)
                paragraph("O passthrough SBS duplica uma câmera: não é visão estéreo real.\nSem ARCore, sensores oferecem orientação 3DoF, não posicionamento 6DoF.", 266f, 574f, 1220f, 18f, 34f, MUTED)
            }
            3 -> {
                item("Escala da UI", "${(s.uiScale*100).toInt()}%", "Tamanho do plano de interface", { set(s.copy(uiScale = (s.uiScale-.1f).coerceAtLeast(.5f))) }, { set(s.copy(uiScale = (s.uiScale+.1f).coerceAtMost(1.5f))) })
                item("Distância", "${fmt(s.uiDistance)} m", "Plano espacial em VR / SBS", { set(s.copy(uiDistance = (s.uiDistance-.1f).coerceAtLeast(.6f))) }, { set(s.copy(uiDistance = (s.uiDistance+.1f).coerceAtMost(4f))) })
                item("Opacidade", "${(s.uiOpacity*100).toInt()}%", "Sem blur, sombras ou pós-processamento", { set(s.copy(uiOpacity = (s.uiOpacity-.05f).coerceAtLeast(.4f))) }, { set(s.copy(uiOpacity = (s.uiOpacity+.05f).coerceAtMost(1f))) })
                item("Posição horizontal", fmt(s.uiOffsetX), "Deslocamento do plano XR", { set(s.copy(uiOffsetX = (s.uiOffsetX-.1f).coerceAtLeast(-1f))) }, { set(s.copy(uiOffsetX = (s.uiOffsetX+.1f).coerceAtMost(1f))) })
                item("Posição vertical", fmt(s.uiOffsetY), "Deslocamento do plano XR", { set(s.copy(uiOffsetY = (s.uiOffsetY-.1f).coerceAtLeast(-1f))) }, { set(s.copy(uiOffsetY = (s.uiOffsetY+.1f).coerceAtMost(1f))) })
                item("Recentrar", "Origem", "Redefinir orientação e posição", toggle = actions::recenter)
            }
            else -> {
                item("Perfil", when(s.performanceMode) {PerformanceMode.ECONOMY -> "Econômico"; PerformanceMode.BALANCED -> "Balanceado"; PerformanceMode.PERFORMANCE -> "Máximo"}, "O sistema térmico tem prioridade", toggle = { set(s.copy(performanceMode = PerformanceMode.entries[(s.performanceMode.ordinal+1)%3])) })
                item("Resolução dinâmica", onOff(s.dynamicResolution), "Ajuste gradual com histerese", toggle = { set(s.copy(dynamicResolution = !s.dynamicResolution)) })
                item("FPS alvo", "${s.targetFps}", "Frame pacing via Choreographer", toggle = { set(s.copy(targetFps = if (s.targetFps == 60) 30 else 60)) })
                item("Estado térmico", thermalLabel(), "Severo: reduz carga / crítico: pausa")
                item("Render efetivo", "${(state.effectiveRenderScale*100).toInt()}%", "Escala efetiva do framebuffer")
                item("Tracking efetivo", "${state.effectiveTrackingFps} FPS", "Sem mãos: backoff para 5 FPS")
            }
        }
    }
    private fun permissions() {
        text("PERMISSÕES ANDROID", 249f, 177f, 12f, PURPLE, mono)
        permissionCard(248f, "Câmera", if (state.cameraGranted) "Concedida" else "Sob demanda", "Passthrough e tracking local.\nNenhum frame sai do telefone.", "camera", actions::requestCamera)
        permissionCard(690f, "Acessibilidade", if (state.accessibilityEnabled && state.accessibilityConsent) "Autorizada nesta sessão" else "Opcional · desativada", "Somente seus gestos explícitos\nem apps e displays autorizados.", "hand", actions::requestAccessibility)
        permissionCard(1132f, "Compartilhamento", if (state.captureActive) "Ativo · toque para parar" else "Consentimento por sessão", "MediaProjection oficial.\nRespeita conteúdo protegido.", "cast", actions::requestCapture)
        text("CAPABILITIES LUA / POR CÓDIGO", 249f, 424f, 12f, PURPLE, mono)
        val app = state.scripts[state.selectedScript]
        text(app.title, 250f, 469f, 26f, WHITE, bold)
        text("${app.type}  ·  SHA-256 ${app.principal.id.substringAfter('@').take(16)}…", 250f, 499f, 13f, MUTED, mono)
        button("permissions.prev", "‹", 1420f, 436f, 56f, 56f) { actions.selectScript((state.selectedScript-1+state.scripts.size)%state.scripts.size) }
        button("permissions.next", "›", 1490f, 436f, 56f, 56f) { actions.selectScript((state.selectedScript+1)%state.scripts.size) }
        if (app.requested.isEmpty()) {
            panel(249f, 530f, 1300f, 138f, 17f, PANEL, BORDER)
            icon("shield", 288f, 575f, 30f, GREEN)
            text("Este app não solicita permissões privilegiadas.", 319f, 583f, 23f, WHITE, bold)
            text("Ele pode criar e controlar somente suas próprias janelas e elementos.", 273f, 630f, 17f, MUTED)
        }
        for ((i, capability) in app.requested.withIndex()) {
            val y = 521f + i * 62
            panel(249f, y, 1300f, 54f, 12f, PANEL, BORDER)
            text(capability.wireName, 268f, y+34, 17f, if (capability == Capability.UNSAFE_EXECUTION) PURPLE else WHITE, mono)
            text(capabilityDescription(capability), 517f, y+34, 14f, MUTED, maxWidth = 690f)
            button("cap.${capability.name}", if (state.permissions.has(app.principal, capability)) "Revogar" else "Permitir", 1380f, y+4, 153f, 46f, primary = !state.permissions.has(app.principal, capability)) { actions.changeCapability(app, capability) }
        }
        text("Uma declaração não é consentimento. Alterar o código exige novas concessões.", 251f, 784f, 15f, MUTED)
    }
    private fun permissionCard(x: Float, title: String, status: String, description: String, glyph: String, action: () -> Unit) {
        panel(x, 198f, 418f, 190f, 18f, PANEL, BORDER)
        icon(glyph, x+36, 233f, 28f, PURPLE); text(title, x+63, 241f, 22f, WHITE, bold)
        text(status, x+24, 279f, 14f, PURPLE)
        paragraph(description, x+24, 316f, 370f, 16f, 26f, MUTED)
        hit("permission.$title", "$title. $status. $description", x, 198f, 418f, 190f, action = action)
    }
    private fun developer() {
        val app = state.scripts[state.selectedScript]
        text("SDK LUA / ${app.type}", 250f, 177f, 12f, PURPLE, mono)
        text(app.title, 250f, 217f, 26f, WHITE, bold)
        button("dev.edit", "Editar", 773f, 161f, 126f, 57f, "code") { actions.editScript() }
        button("dev.run", "Executar", 915f, 161f, 158f, 57f, "play", true) { actions.runScript(app) }
        panel(248f, 240f, 826f, 400f, 17f, 0xff0d0b12.toInt(), BORDER)
        val lines = app.source.lines()
        for ((index, value) in lines.take(15).withIndex()) {
            val y = 274f + index*24
            text((index+1).toString().padStart(2,'0'), 269f, y, 13f, 0xff554661.toInt(), mono)
            text(value, 307f, y, 14f, if (value.trim().startsWith("--")) 0xff82778f.toInt() else if (value.contains("zxr.")) 0xffc2a0ff.toInt() else 0xffd3ccdf.toInt(), mono, 746f)
        }
        text("EXEMPLOS PARA COMEÇAR", 1103f, 177f, 12f, PURPLE, mono)
        for ((index, script) in state.scripts.take(6).withIndex()) {
            val y = 203f + index*70
            panel(1101f, y, 449f, 59f, 12f, if (index == state.selectedScript) SELECTED else PANEL, BORDER)
            icon(if (script.type == AppType.GAME) "orbit" else "code", 1127f, y+29, 23f, PURPLE)
            text(script.title, 1155f, y+36, 18f, WHITE, bold, 299f)
            text(script.type.name, 1471f, y+35, 10f, MUTED, mono)
            hit("script.$index", script.title, 1101f, y, 449f, 59f) { actions.selectScript(index) }
        }
        panel(248f, 660f, 1303f, 130f, 15f, PANEL, BORDER)
        text("CONSOLE", 267f, 689f, 12f, PURPLE, mono)
        val logs = state.logs.takeLast(3)
        if (logs.isEmpty()) text("Pronto para executar. Sem scripts em segundo plano por padrão.", 268f, 735f, 15f, MUTED, mono)
        for ((i, line) in logs.withIndex()) text(line, 268f, 715f+i*23, 14f, MUTED, mono, 1002f)
        button("dev.docs", "API reference", 1288f, 676f, 240f, 48f, "code") { actions.showDocumentation() }
        button("dev.stop", "Parar scripts", 1288f, 735f, 240f, 43f) { actions.stopScripts() }
    }
    private fun notifications() {
        text("EVENTOS DO BRAZIL MR", 250f, 178f, 12f, PURPLE, mono)
        text("Não acessamos as notificações de outros aplicativos.", 250f, 211f, 17f, MUTED)
        button("notices.clear", "Limpar", 1385f, 159f, 160f, 53f, "close") { state.notices.clear(); state.dirty = true }
        if (state.notices.isEmpty()) { icon("bell", 891f, 442f, 64f, PURPLE); text("Tudo em dia.", 805f, 520f, 29f, WHITE, bold) }
        for ((index, notice) in state.notices.take(6).withIndex()) {
            val y = 243f+index*88
            panel(249f, y, 1301f, 76f, 14f, PANEL, BORDER)
            circle(270f, y+25, 3f, PURPLE)
            text(notice.title, 285f, y+32, 19f, WHITE, bold, 1130f)
            text(notice.message, 285f, y+58, 14f, MUTED, maxWidth = 1210f)
            text(timeFormat.format(Date(notice.time)), 1480f, y+29, 12f, MUTED, mono)
        }
    }
    private fun dock() {
        panel(581f, 816f, 764f, 59f, 17f, 0xff1b1625.toInt(), BORDER)
        val labels = listOf("Home", "Apps", if (state.session.mode == EnvironmentMode.MR) "Entrar VR" else "Voltar MR", "Recentrar", "Ocultar UI", "Ajustes")
        val icons = listOf("home", "grid", "vr", "target", "eye", "settings")
        for (i in labels.indices) {
            val x = 595f+i*124
            icon(icons[i], x+26, 845f, 22f, if (i == 2) PURPLE else MUTED)
            text(labels[i], x+47, 850f, 12f, WHITE)
            hit("dock.$i", labels[i], x, 818f, 122f, 55f) {
                when(i) { 0 -> state.navigate(Page.HOME); 1 -> state.navigate(Page.APPS); 2 -> actions.toggleMode(); 3 -> actions.recenter(); 4 -> actions.hideUi(); 5 -> { state.quickSettings = !state.quickSettings; state.dirty = true } }
            }
        }
    }
    private fun quickSettings() {
        panel(215f, 135f, 1353f, 673f, 0f, 0x88000000.toInt())
        hit("quick.dismiss", "Fechar ajustes rápidos", 215f, 135f, 1353f, 673f) { state.quickSettings = false; state.dirty = true }
        panel(942f, 148f, 614f, 646f, 23f, 0xff1a1525.toInt(), 0xff584071.toInt())
        hit("quick.block", "Ajustes rápidos", 942f, 148f, 614f, 646f, HitKind.BLOCK)
        text("Ajustes rápidos", 972f, 196f, 27f, WHITE, bold)
        text("Seu ambiente, sem sair do espaço.", 972f, 225f, 16f, MUTED)
        button("quick.mode", if (state.session.mode == EnvironmentMode.MR) "Entrar em VR" else "Voltar ao MR", 972f, 258f, 551f, 71f, "vr", true, actions::toggleMode)
        button("quick.sbs", "SBS  ·  ${onOff(state.settings.sbs)}", 972f, 345f, 266f, 87f, "windows") { actions.updateSettings(state.settings.copy(sbs = !state.settings.sbs)) }
        button("quick.hands", "Tracking  ·  ${onOff(state.settings.trackingEnabled)}", 1254f, 345f, 269f, 87f, "hand") { actions.updateSettings(state.settings.copy(trackingEnabled = !state.settings.trackingEnabled)) }
        button("quick.camera", if (state.cameraGranted) "Passthrough" else "Permitir câmera", 972f, 449f, 266f, 87f, "camera") { if (state.cameraGranted) actions.updateSettings(state.settings.copy(passthrough = !state.settings.passthrough)) else actions.requestCamera() }
        button("quick.center", "Recentrar", 1254f, 449f, 269f, 87f, "target", action = actions::recenter)
        button("quick.hide", "Ocultar interface", 972f, 552f, 551f, 68f, "eye", action = actions::hideUi)
        text("${state.fps} FPS  /  TÉRMICO: ${thermalLabel().uppercase()}", 978f, 662f, 13f, PURPLE, mono)
        text("Mão esquerda fechada por 5 s reabre a UI.", 978f, 695f, 16f, MUTED)
        button("quick.all", "Todas as configurações", 973f, 727f, 549f, 45f, "settings") { state.navigate(Page.SETTINGS) }
    }
    fun pointer(action: PointerAction, u: Float, v: Float, time: Long, source: String = "touch") {
        lastPointerX = u; lastPointerY = v
        val x = u*WIDTH; val y = v*HEIGHT
        val target = hitTest(x,y)
        pointerOnUi = target != null
        val receiver = if (action == PointerAction.DOWN) target else captured ?: target
        if (receiver != null && receiver.windowId >= 0) {
            val window = state.windows.get(receiver.windowId)
            if (window != null) {
                val content = contentBounds(window)
                if (content.contains(x,y) || (captured != null && (action == PointerAction.UP || action == PointerAction.CANCEL))) {
                    actions.windowPointer(window.id, ((x-content.left)/content.width()).coerceIn(0f,1f), ((y-content.top)/content.height()).coerceIn(0f,1f), action.name.lowercase(), source)
                }
            }
        }
        if (hover != (target?.id ?: -1)) { hover = target?.id ?: -1; state.dirty = true }
        when(action) {
            PointerAction.DOWN -> {
                captured = target; downX = x; downY = y; downTime = time
                if (target != null && target.windowId >= 0) state.windows.get(target.windowId)?.let {
                    windowX = it.x; windowY = it.y; windowWidth = it.width; windowHeight = it.height
                    state.windows.focus(it.id); state.dirty = true
                }
            }
            PointerAction.MOVE -> captured?.let { capture ->
                when (capture.kind) {
                    HitKind.MOVE -> { state.windows.move(capture.windowId, windowX+(x-downX)/WORK.width(), windowY+(y-downY)/WORK.height()); state.dirty = true }
                    HitKind.RESIZE -> { state.windows.resize(capture.windowId, windowWidth+(x-downX)/WORK.width(), windowHeight+(y-downY)/WORK.height()); state.dirty = true }
                    else -> Unit
                }
            }
            PointerAction.UP -> {
                val capture = captured; captured = null
                if (capture != null) when(capture.kind) {
                    HitKind.BUTTON -> if (capture.id == target?.id) capture.action?.invoke()
                    HitKind.RESIZE -> actions.windowResized(capture.windowId)
                    HitKind.EXTERNAL -> {
                        val rect = capture.rect
                        actions.externalGesture(capture.windowId, ((downX-rect.left)/rect.width()).coerceIn(0f,1f), ((downY-rect.top)/rect.height()).coerceIn(0f,1f), ((x-rect.left)/rect.width()).coerceIn(0f,1f), ((y-rect.top)/rect.height()).coerceIn(0f,1f), time-downTime)
                    }
                    else -> Unit
                }
                state.dirty = true
            }
            PointerAction.CANCEL -> { captured = null; state.dirty = true }
        }
    }
    fun target(id: Int) = targets.firstOrNull { it.id == id }
    fun hitTest(x: Float, y: Float): UiTarget? {
        for (i in targets.indices.reversed()) if (targets[i].rect.contains(x,y)) return targets[i]
        return null
    }
    fun activate(id: Int): Boolean {
        val target = target(id) ?: return false
        if (target.kind == HitKind.BUTTON) { target.action?.invoke(); state.dirty = true; return true }
        if (target.kind == HitKind.MOVE) { state.windows.focus(target.windowId); state.dirty = true; return true }
        return false
    }
    fun fillExternalLayers(frame: RenderFrame) {
        frame.externalCount = 0
        if (!state.session.uiVisible || (state.page != Page.HOME && state.page != Page.WINDOWS)) return
        val windows = state.windows.windows
        for (index in windows.indices) {
            val window = windows[index]
            if (window.minimized || (window.displayId < 0 && !(window.content == WindowContent.CAPTURE && state.captureActive))) continue
            if (window.content != WindowContent.ANDROID && window.content != WindowContent.CAPTURE) continue
            if (frame.externalCount >= 4) break
            val i = frame.externalCount++
            frame.externalIds[i] = window.id
            frame.externalRects[i*4] = (WORK.left+window.x*WORK.width()+10)/WIDTH
            frame.externalRects[i*4+1] = (WORK.top+window.y*WORK.height()+titleHeight(window)+8)/HEIGHT
            frame.externalRects[i*4+2] = (WORK.left+(window.x+window.width)*WORK.width()-10)/WIDTH
            frame.externalRects[i*4+3] = (WORK.top+(window.y+window.height)*WORK.height()-14)/HEIGHT
        }
    }
    private fun hit(key: String, label: String, x: Float, y: Float, width: Float, height: Float, kind: HitKind = HitKind.BUTTON, windowId: Int = -1, action: (() -> Unit)? = null) {
        targets.add(UiTarget(ids.getOrPut(key) { nextId++ }, key, label, RectF(x,y,x+width,y+height), kind, windowId, action))
    }
    private fun button(key: String, label: String, x: Float, y: Float, w: Float, h: Float, glyph: String = "", primary: Boolean = false, action: () -> Unit) {
        val active = ids[key] == hover
        panel(x, y, w, h, 12f, if (primary) (if (active) 0xff9464ed.toInt() else 0xff794ad0.toInt()) else if (active) 0xff332440.toInt() else 0xff241c31.toInt(), if (primary) 0xffa379ec.toInt() else BORDER)
        if (glyph.isNotEmpty()) icon(glyph, x+26, y+h/2, 20f, if (primary) WHITE else PURPLE)
        text(label, x+if (glyph.isEmpty()) 17 else 46, y+h/2+6, 16f, WHITE, bold, w-if (glyph.isEmpty()) 30 else 53)
        hit(key, label, x, y, w, h, action = action)
    }
    private fun panel(x: Float, y: Float, w: Float, h: Float, radius: Float, fill: Int, border: Int? = null) {
        if (w <= 0 || h <= 0) return
        scratch.set(x,y,x+w,y+h); paint.style = Paint.Style.FILL; paint.color = fill; canvas.drawRoundRect(scratch,radius,radius,paint)
        if (border != null) { paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.2f; paint.color = border; canvas.drawRoundRect(scratch,radius,radius,paint); paint.style = Paint.Style.FILL }
    }
    private fun text(value: String, x: Float, y: Float, size: Float = 18f, color: Int = WHITE, face: Typeface = normal, maxWidth: Float = Float.MAX_VALUE) {
        paint.style = Paint.Style.FILL; paint.color = color; paint.textSize = size; paint.typeface = face
        val length = paint.breakText(value, true, maxWidth.coerceAtLeast(0f), null)
        val shown = if (length < value.length && length > 2) value.take(length-1)+"…" else value.take(length)
        canvas.drawText(shown,x,y,paint)
    }
    private fun paragraph(value: String, x: Float, y: Float, width: Float, size: Float = 18f, lineHeight: Float = 28f, color: Int = MUTED, maxLines: Int = 12) {
        var row = 0
        for (line in value.lines()) {
            var remaining = line
            do {
                if (row >= maxLines) return
                paint.textSize = size; paint.typeface = normal
                var count = paint.breakText(remaining,true,width,null)
                if (count < remaining.length && count > 0) { val space = remaining.lastIndexOf(' ',count-1); if (space > count/2) count = space }
                if (count == 0 && remaining.isNotEmpty()) return
                text(remaining.take(count),x,y+row*lineHeight,size,color); row++
                remaining = remaining.drop(count).trimStart()
            } while (remaining.isNotEmpty())
        }
    }
    private fun line(x0: Float,y0: Float,x1: Float,y1: Float,color: Int,width: Float = 1f) { paint.color=color; paint.strokeWidth=width; paint.style=Paint.Style.STROKE; canvas.drawLine(x0,y0,x1,y1,paint); paint.style=Paint.Style.FILL }
    private fun circle(x: Float,y: Float,radius: Float,color: Int) { paint.style=Paint.Style.FILL;paint.color=color;canvas.drawCircle(x,y,radius,paint) }
    private fun pill(value: String, x: Float, y: Float, width: Float, active: Boolean) { panel(x,y,width,35f,10f,if(active) SELECTED else PANEL); circle(x+14,y+17,3f,PURPLE);text(value,x+25,y+23,13f,PURPLE,bold) }
    private fun logo(x: Float,y: Float,size: Float) {
        paint.color=PURPLE;paint.style=Paint.Style.STROKE;paint.strokeWidth=2.6f;path.reset()
        path.moveTo(x+size/2,y);path.lineTo(x+size,y+size*.25f);path.lineTo(x+size,y+size*.75f);path.lineTo(x+size/2,y+size);path.lineTo(x,y+size*.75f);path.lineTo(x,y+size*.25f);path.close();canvas.drawPath(path,paint)
        line(x,y+size*.25f,x+size/2,y+size*.5f,PURPLE,2.6f);line(x+size,y+size*.25f,x+size/2,y+size*.5f,PURPLE,2.6f);line(x+size/2,y+size*.5f,x+size/2,y+size,WHITE,2.6f)
    }
    private fun icon(name: String, cx: Float, cy: Float, size: Float, color: Int) {
        canvas.save();canvas.translate(cx-size/2,cy-size/2);canvas.scale(size/24,size/24)
        paint.color=color;paint.style=Paint.Style.STROKE;paint.strokeWidth=1.8f;paint.strokeCap=Paint.Cap.ROUND;paint.strokeJoin=Paint.Join.ROUND
        path.reset()
        fun l(x: Float,y: Float,a: Float,b: Float) { canvas.drawLine(x,y,a,b,paint) }
        fun r(x: Float,y: Float,a: Float,b: Float) { canvas.drawRoundRect(x,y,a,b,2f,2f,paint) }
        when(name) {
            "home" -> { path.moveTo(3f,10f);path.lineTo(12f,3f);path.lineTo(21f,10f);canvas.drawPath(path,paint);r(5f,10f,19f,21f);l(10f,21f,10f,15f);l(14f,21f,14f,15f) }
            "grid" -> { r(3f,3f,9f,9f);r(15f,3f,21f,9f);r(3f,15f,9f,21f);r(15f,15f,21f,21f) }
            "windows" -> { r(3f,7f,18f,21f);path.moveTo(7f,3f);path.lineTo(22f,3f);path.lineTo(22f,17f);canvas.drawPath(path,paint);l(3f,11f,18f,11f) }
            "vr" -> { r(2f,7f,22f,19f);canvas.drawCircle(7f,13f,2.5f,paint);canvas.drawCircle(17f,13f,2.5f,paint);l(9.5f,13f,14.5f,13f);l(6f,4f,18f,4f) }
            "camera" -> { r(2f,7f,22f,21f);canvas.drawCircle(12f,14f,4f,paint);l(8f,7f,9f,3f);l(9f,3f,15f,3f);l(15f,3f,16f,7f) }
            "phone" -> { r(6f,2f,18f,22f);l(10f,18f,14f,18f) }
            "shield" -> { path.moveTo(12f,2f);path.lineTo(21f,6f);path.lineTo(19f,16f);path.lineTo(12f,22f);path.lineTo(5f,16f);path.lineTo(3f,6f);path.close();canvas.drawPath(path,paint);l(8f,12f,11f,15f);l(11f,15f,17f,9f) }
            "code" -> { l(8f,6f,2f,12f);l(2f,12f,8f,18f);l(16f,6f,22f,12f);l(22f,12f,16f,18f);l(14f,3f,10f,21f) }
            "hand" -> { path.moveTo(7f,13f);path.lineTo(7f,6f);path.quadTo(9f,2f,11f,6f);path.lineTo(11f,12f);path.moveTo(11f,9f);path.lineTo(11f,3f);path.quadTo(13f,0f,15f,3f);path.lineTo(15f,12f);path.lineTo(15f,6f);path.quadTo(18f,3f,18f,7f);path.lineTo(18f,13f);path.lineTo(19f,10f);path.quadTo(22f,8f,22f,12f);path.lineTo(21f,17f);path.quadTo(19f,23f,12f,22f);path.lineTo(7f,20f);path.lineTo(2f,14f);path.quadTo(0f,10f,4f,11f);path.lineTo(7f,13f);canvas.drawPath(path,paint) }
            "settings" -> { canvas.drawCircle(12f,12f,7f,paint);canvas.drawCircle(12f,12f,2.5f,paint);for(i in 0..7){val a=i*PI/4;l(12+cos(a).toFloat()*8,12+sin(a).toFloat()*8,12+cos(a).toFloat()*11,12+sin(a).toFloat()*11)} }
            "bell" -> { path.moveTo(4f,18f);path.lineTo(6f,15f);path.lineTo(6f,9f);path.quadTo(6f,2f,12f,2f);path.quadTo(18f,2f,18f,9f);path.lineTo(18f,15f);path.lineTo(20f,18f);path.close();canvas.drawPath(path,paint);l(10f,22f,14f,22f) }
            "eye" -> { path.moveTo(1f,12f);path.quadTo(12f,-1f,23f,12f);path.quadTo(12f,25f,1f,12f);canvas.drawPath(path,paint);canvas.drawCircle(12f,12f,3.5f,paint) }
            "target" -> { canvas.drawCircle(12f,12f,7f,paint);l(12f,1f,12f,6f);l(12f,18f,12f,23f);l(1f,12f,6f,12f);l(18f,12f,23f,12f) }
            "pulse" -> { path.moveTo(1f,12f);path.lineTo(6f,12f);path.lineTo(9f,4f);path.lineTo(14f,21f);path.lineTo(18f,10f);path.lineTo(23f,10f);canvas.drawPath(path,paint) }
            "clock" -> { canvas.drawCircle(12f,12f,9f,paint);l(12f,6f,12f,12f);l(12f,12f,17f,15f) }
            "note" -> { r(4f,2f,20f,22f);l(8f,7f,16f,7f);l(8f,12f,16f,12f);l(8f,17f,13f,17f) }
            "cast" -> { r(3f,3f,22f,18f);l(3f,21f,3f,21f);path.moveTo(3f,15f);path.quadTo(9f,15f,9f,21f);path.moveTo(3f,10f);path.quadTo(14f,10f,14f,21f);canvas.drawPath(path,paint) }
            "orbit" -> { canvas.drawCircle(12f,12f,8f,paint);canvas.drawOval(2f,8f,22f,16f,paint);canvas.drawCircle(12f,12f,2f,paint) }
            "play" -> { path.moveTo(6f,3f);path.lineTo(21f,12f);path.lineTo(6f,21f);path.close();canvas.drawPath(path,paint) }
            "plus" -> { l(4f,12f,20f,12f);l(12f,4f,12f,20f) }
            "minus" -> l(4f,12f,20f,12f)
            "close" -> { l(5f,5f,19f,19f);l(19f,5f,5f,19f) }
            "resize" -> { l(8f,21f,21f,8f);l(15f,21f,21f,15f) }
        }
        paint.style=Paint.Style.FILL;canvas.restore()
    }
    private fun thermalLabel() = when(state.thermalStatus) {0 -> "Normal";1 -> "Leve";2 -> "Moderado";3 -> "Severo";else -> "Crítico"}
    private fun onOff(value: Boolean) = if(value) "Ativo" else "Inativo"
    private fun fmt(value: Float, digits: Int = 1) = String.format(Locale("pt","BR"),"%.${digits}f",value)
    companion object {
        const val WIDTH = 1600f; const val HEIGHT = 900f
        val WORK = RectF(246f,389f,1551f,791f)
        private val BG = 0xff0e0b14.toInt()
        private val SIDEBAR = 0xff100d17.toInt(); private val PANEL = 0xff191420.toInt(); private val BORDER = 0xff332b40.toInt()
        private val SELECTED = 0xff2b1d40.toInt(); private val PURPLE = 0xffb18aff.toInt(); private val WHITE = 0xfff2edf9.toInt(); private val MUTED = 0xffa298b0.toInt(); private val GREEN = 0xff83d4b2.toInt()
        private val BONES = intArrayOf(0,1,1,2,2,3,3,4,0,5,5,6,6,7,7,8,5,9,9,10,10,11,11,12,9,13,13,14,14,15,15,16,13,17,17,18,18,19,19,20,0,17)
        fun bounds(window: XRWindow) = RectF(WORK.left+window.x*WORK.width(), WORK.top+window.y*WORK.height(), WORK.left+(window.x+window.width)*WORK.width(), WORK.top+(window.y+window.height)*WORK.height())
        private fun titleHeight(window: XRWindow) = if(window.appType == AppType.GAME) 42f else 51f
        fun contentBounds(window: XRWindow): RectF = bounds(window).apply { left+=10;right-=10;top+=titleHeight(window)+8;bottom-=14 }
        fun capabilityDescription(capability: Capability) = when(capability) {
            Capability.UNSAFE_EXECUTION -> "Alterar a UI global e o modo XR; nunca o sandbox Android."
            Capability.SCENARIO -> "Objetos e eventos espaciais para jogos GAME."
            Capability.HAND_TRACKING -> "Ler landmarks e orientação das mãos."
            Capability.INPUT -> "Receber eventos de input dentro do próprio app."
        }
    }
}
