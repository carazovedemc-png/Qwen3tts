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
import com.qwen.tts.android.QwenModel
import com.qwen.tts.android.data.MiaSettings
import com.qwen.tts.android.engine.EngineHost
import com.qwen.tts.android.engine.Synth
import java.net.NetworkInterface
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ServerState(val running: Boolean = false, val url: String? = null, val error: String? = null)

/** Foreground service: keeps the process (and the loaded model) alive and optionally runs the local API. */
class ApiServerService : Service() {
    private var server: ApiServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ServerPrefs(this).enabled = false
            MiaSettings.get(this).keepAlive = false
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        val prefs = ServerPrefs(this)
        val settings = MiaSettings.get(this)
        if (!prefs.enabled && !settings.keepAlive) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification(prefs, settings), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

        server?.stop(); server = null
        if (prefs.enabled) {
            try {
                server = ApiServer(applicationContext, prefs).also { it.start() }
                val host = if (prefs.bindHost == "0.0.0.0") (localAddresses().firstOrNull() ?: "0.0.0.0") else prefs.bindHost
                _state.value = ServerState(true, "http://$host:${prefs.port}/v1", null)
            } catch (e: Exception) {
                _state.value = ServerState(false, null, e.message ?: "Could not start server")
            }
        } else {
            _state.value = ServerState()
        }
        if (wakeLock?.isHeld != true) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "qwen3tts:service").apply { acquire() }
        }
        if (settings.warmStart || settings.keepAlive) warmUp()
        return START_STICKY
    }

    private fun warmUp() = thread(isDaemon = true, name = "mia-warmup") {
        val talker = QwenModel.defaultVariant.talkerName
        if (EngineHost.loadedTalker == null && Synth.isReady(this, talker)) {
            runCatching {
                EngineHost.ensureLoaded(Synth.modelDir(this).absolutePath, talker, Synth.threads(this), Synth.backendPreference(this))
            }
        }
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

    private fun buildNotification(prefs: ServerPrefs, settings: MiaSettings): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Mia ai voice", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ApiServerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val text = buildString {
            if (settings.keepAlive) append("Модель в памяти")
            if (prefs.enabled) { if (isNotEmpty()) append(", "); append("API :${prefs.port}") }
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Mia ai voice")
            .setContentText(text)
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

        /** Starts/restarts/stops the service according to the current preferences. Call after changing them. */
        fun sync(context: Context) {
            val need = ServerPrefs(context).enabled || MiaSettings.get(context).keepAlive
            if (need) context.startForegroundService(Intent(context, ApiServerService::class.java))
            else context.startService(Intent(context, ApiServerService::class.java).setAction(ACTION_STOP))
        }

        fun localAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filter { !it.isLoopbackAddress && it.address.size == 4 }
                .mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList())
    }
}
