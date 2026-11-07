package com.qwen.tts.android.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import com.qwen.tts.android.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ServerState(val running: Boolean = false, val url: String? = null, val error: String? = null)

class ApiServerService : Service() {
    private var server: ApiServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        val prefs = ServerPrefs(this)
        startForeground(NOTIFICATION_ID, buildNotification(prefs), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (server == null) {
            try {
                server = ApiServer(applicationContext, prefs).also { it.start() }
                wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "qwen3tts:api").apply { acquire() }
                _state.value = ServerState(true, "http://127.0.0.1:${prefs.port}/v1", null)
            } catch (e: Exception) {
                _state.value = ServerState(false, null, e.message ?: "Could not start server")
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun shutdown() {
        server?.stop(); server = null
        wakeLock?.takeIf { it.isHeld }?.release(); wakeLock = null
        _state.value = ServerState()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun buildNotification(prefs: ServerPrefs): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Local TTS API", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ApiServerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Qwen3 TTS API работает")
            .setContentText("Порт ${prefs.port}" + if (prefs.lanAccess) " · доступ из сети" else " · только это устройство")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(0, "Стоп", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.qwen.tts.android.STOP_API"
        private const val CHANNEL = "api_server"
        private const val NOTIFICATION_ID = 42
        private val _state = MutableStateFlow(ServerState())
        val state = _state.asStateFlow()
        val isRunning: Boolean get() = _state.value.running

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ApiServerService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ApiServerService::class.java).setAction(ACTION_STOP))
        }
    }
}
