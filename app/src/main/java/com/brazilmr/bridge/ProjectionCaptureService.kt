package com.brazilmr.bridge

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Surface
import com.brazilmr.MainActivity
import com.brazilmr.R

object CaptureState {
    @Volatile var active = false
    var onChanged: ((Boolean, String) -> Unit)? = null
    fun update(active: Boolean, message: String) { this.active = active; onChanged?.invoke(active, message) }
}

/** One consent token per session. No screen-capture permission is inferred from Accessibility or Lua. */
class ProjectionCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var surface: Surface? = null
    private val handler = Handler(Looper.getMainLooper())
    private var stopped = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || intent == null) { stopCapture("Compartilhamento encerrado"); return START_NOT_STICKY }
        try {
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.capture_channel), NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_brazil_mr)
                .setContentTitle(getString(R.string.capture_notification)).setContentText("Toque para voltar · use Parar para revogar a sessão")
                .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, getString(R.string.capture_stop), stop).build()).build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(42, notification)
            @Suppress("DEPRECATION") val consent = intent.getParcelableExtra<Intent>("consent") ?: error("Consentimento não recebido")
            @Suppress("DEPRECATION") val output = intent.getParcelableExtra<Surface>("surface") ?: error("Surface não recebida")
            check(projection == null) { "Uma sessão já está ativa; solicite novo consentimento para reiniciar" }
            surface = output; stopped = false
            val manager = getSystemService(MediaProjectionManager::class.java)
            val next = manager.getMediaProjection(Activity.RESULT_OK, consent)
            projection = next
            next.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopCapture("O Android encerrou o compartilhamento") }
            }, handler)
            display = next.createVirtualDisplay("Brazil MR · compartilhamento consentido", 1280, 720, 200,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, output, null, handler)
            CaptureState.update(true, "Compartilhamento ativo · conteúdo protegido permanece oculto")
        } catch (error: Exception) { stopCapture(error.message ?: "Captura indisponível") }
        return START_NOT_STICKY
    }
    private fun stopCapture(message: String) {
        if (stopped) return
        stopped = true
        display?.release(); display = null
        val previous = projection; projection = null; runCatching { previous?.stop() }
        surface?.release(); surface = null
        CaptureState.update(false, message)
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() { stopCapture("Compartilhamento encerrado"); super.onDestroy() }
    companion object {
        const val ACTION_STOP = "com.brazilmr.STOP_CAPTURE"
        private const val CHANNEL = "brazilmr.capture"
        fun stop(context: Context) { context.stopService(Intent(context, ProjectionCaptureService::class.java)) }
    }
}
