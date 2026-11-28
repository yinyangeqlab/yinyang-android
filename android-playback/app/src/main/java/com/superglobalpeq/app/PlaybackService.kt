package com.superglobalpeq.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.superglobalpeq.app.service.AudioLoopbackUserService
import com.superglobalpeq.app.service.IAudioLoopbackService
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

class PlaybackService : Service() {
    companion object {
        const val START = "com.yinyangeq.android.playback.START"
        const val STOP = "com.yinyangeq.android.playback.STOP"
        const val CONFIG = "preset"
        const val NAME = "presetName"
        const val PRESET_ID = "presetId"
        const val MODE = "playbackMode"
        private const val CHANNEL = "playback"
        private const val NOTIFICATION = 1
        private const val DIAGNOSTICS = "playbackDiagnostics"
        private const val LAST_STARTUP = "lastStartup"
        internal data class Status(val message: String, val busy: Boolean = false, val running: Boolean = false,
                                   val activePresetId: String? = null, val activeMode: PresetPlaybackMode? = null)
        @Volatile internal var status = Status("Phone EQ is off.")
            private set
        @Volatile internal var lastOutputType = -1
            private set

        fun lastStartup(context: Context): String? = context.getSharedPreferences(DIAGNOSTICS, Context.MODE_PRIVATE)
            .getString(LAST_STARTUP, null)

        fun notificationsEnabled(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java)
            return manager.areNotificationsEnabled() &&
                manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        }

        fun authorized(): Boolean = runCatching {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED &&
                Shizuku.getUid() == 2000
        }.getOrDefault(false)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val client = Binder()
    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, AudioLoopbackUserService::class.java.name))
            .daemon(false).processNameSuffix("phone_eq_audio").version(4)
    }
    private var bound = false
    private var generation = 0
    private var stopping = false
    private var config: String? = null
    private var name = "Phone EQ"
    private var presetId: String? = null
    private var mode: PresetPlaybackMode? = null
    private var remote: IAudioLoopbackService? = null
    private val binderDead = Shizuku.OnBinderDeadListener {
        handler.post {
            if (!stopping) {
                if (status.busy && !status.running) rememberStartup("Shizuku stopped during startup.")
                finishPlayback("Shizuku stopped. Phone EQ is off.")
            }
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(component: ComponentName, binder: IBinder) {
            if (stopping) return
            remote = IAudioLoopbackService.Stub.asInterface(binder)
            applyPreset()
        }

        override fun onServiceDisconnected(component: ComponentName) {
            remote = null
            if (!stopping && status.busy && !status.running) rememberStartup("Shizuku audio service disconnected during startup.")
            if (!stopping) finishPlayback("The audio connection ended. Phone EQ is off.")
        }
    }
    private val timeout = Runnable {
        if (remote == null && !stopping) {
            rememberStartup("Shizuku audio service connection timed out.")
            finishPlayback("Could not connect to the Shizuku audio service.")
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Phone EQ playback", NotificationManager.IMPORTANCE_LOW))
        Shizuku.addBinderDeadListener(binderDead)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            finishPlayback("Phone EQ is off.")
            return START_NOT_STICKY
        }
        if (intent?.action != START) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        config = intent.getStringExtra(CONFIG)
        presetId = intent.getStringExtra(PRESET_ID)
        val modeName = intent.getStringExtra(MODE)
        mode = PresetPlaybackMode.entries.firstOrNull { it.name == modeName }
        name = PresetInput.title(intent.getStringExtra(NAME) ?: "Phone EQ")
        startForeground(NOTIFICATION, notification("Starting Phone EQ…"))
        if (config == null || !authorized()) {
            rememberStartup(if (config == null) "No preset configuration provided." else "Shizuku is unavailable or unauthorized.")
            finishPlayback("Start Shizuku with Android debugging and grant access first.")
            return START_NOT_STICKY
        }
        if (!notificationsEnabled(this)) {
            rememberStartup("Playback notifications are disabled.")
            finishPlayback("Enable playback notifications to use Phone EQ.")
            return START_NOT_STICKY
        }
        if (stopping) return START_NOT_STICKY
        if (!status.running) rememberStartup("Connecting to the Shizuku audio service…")
        status = status.copy(message = "Starting Phone EQ…", busy = true)
        if (remote != null) applyPreset()
        else if (!bound) {
            try {
                Shizuku.bindUserService(args, connection)
                bound = true
                handler.postDelayed(timeout, 10000)
            } catch (e: Exception) {
                rememberStartup("Could not bind the Shizuku audio service (${e.javaClass.simpleName}).")
                finishPlayback("Could not bind the Shizuku audio service.")
            }
        }
        return START_NOT_STICKY
    }

    private fun applyPreset() {
        val service = remote ?: return
        val preset = config ?: return
        val activeName = name
        val activeId = presetId
        val activeMode = mode
        val attempt = ++generation
        handler.removeCallbacks(timeout)
        worker.execute {
            val result = runCatching {
                service.registerClientToken(client)
                val listening = JSONObject(service.statusJson).optBoolean("running")
                if (listening) {
                    service.updateDspConfig(preset)
                } else service.startLoopback(preset)
            }
            val applied = result.getOrDefault(false)
            val diagnostics = runCatching { JSONObject(service.statusJson) }.getOrNull()
            lastOutputType = diagnostics?.optInt("outputType", -1) ?: -1
            val problem = diagnostics?.optString("error").orEmpty()
            handler.post {
                if (attempt != generation || stopping) return@post
                if (!status.running || !applied) {
                    val trace = diagnostics?.optString("startupTrace").orEmpty()
                    val error = result.exceptionOrNull()?.javaClass?.simpleName
                    rememberStartup(listOfNotNull(
                        trace.takeIf { it.isNotBlank() },
                        error?.let { "Shizuku audio service call failed ($it)." },
                        when {
                            applied -> "Playback started."
                            problem.isNotBlank() && problem != "null" -> problem
                            else -> "Audio routing could not start."
                        },
                    ).joinToString("\n"))
                }
                if (applied) {
                    val playing = activeMode?.let { "$activeName · ${it.label}" } ?: activeName
                    status = Status("Playing · $playing", running = true, activePresetId = activeId, activeMode = activeMode)
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(playing))
                    poll(service, attempt)
                } else finishPlayback(
                    if (problem.isBlank() || problem == "null") "Audio routing could not start on this device."
                    else "Audio routing could not start. $problem")
            }
        }
    }

    private fun rememberStartup(trace: String) {
        Log.i("PhoneEqPlayback", trace)
        getSharedPreferences(DIAGNOSTICS, MODE_PRIVATE).edit().putString(LAST_STARTUP, trace).apply()
    }

    private fun poll(service: IAudioLoopbackService, attempt: Int) {
        handler.postDelayed({
            if (attempt != generation || stopping) return@postDelayed
            if (!notificationsEnabled(this) || !authorized()) {
                rememberStartup(listOfNotNull(lastStartup(this), "Playback access changed.").joinToString("\n"))
                finishPlayback("Playback access changed. Phone EQ is off.")
                return@postDelayed
            }
            worker.execute {
                val diagnostics = runCatching { JSONObject(service.statusJson) }.getOrNull()
                handler.post {
                    if (attempt != generation || stopping) return@post
                    if (diagnostics?.optBoolean("running") == true) {
                        lastOutputType = diagnostics.optInt("outputType", lastOutputType)
                        poll(service, attempt)
                    } else {
                        val error = diagnostics?.optString("error").orEmpty()
                        val reason = error.takeUnless { it.isBlank() || it == "null" }
                            ?: "Audio processing stopped."
                        val trace = diagnostics?.optString("startupTrace").orEmpty()
                        rememberStartup(listOf(trace, reason).filter(String::isNotBlank).joinToString("\n"))
                        finishPlayback("Audio processing stopped. Phone EQ is off.")
                    }
                }
            }
        }, 1500)
    }

    private fun finishPlayback(message: String) {
        if (stopping) return
        stopping = true
        generation++
        handler.removeCallbacksAndMessages(null)
        status = Status("Stopping Phone EQ…", busy = true)
        val service = remote
        remote = null
        worker.execute {
            runCatching { service?.stopLoopback() }
            handler.post {
                releaseConnection()
                status = Status(message)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun releaseConnection() {
        if (!bound) return
        bound = false
        runCatching { Shizuku.unbindUserService(args, connection, true) }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        Shizuku.removeBinderDeadListener(binderDead)
        val service = remote
        remote = null
        worker.execute { runCatching { service?.stopLoopback() } }
        releaseConnection()
        worker.shutdown()
        if (!stopping) status = Status("Phone EQ is off.")
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, PlaybackActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, PlaybackService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("YinYang Phone EQ").setContentText(text).setContentIntent(open)
            .setOngoing(true).addAction(Notification.Action.Builder(null, "Stop EQ", stop).build()).build()
    }
}
