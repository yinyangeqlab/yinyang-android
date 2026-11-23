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
        private const val CHANNEL = "playback"
        private const val NOTIFICATION = 1
        data class Status(val message: String, val busy: Boolean = false, val running: Boolean = false,
                          val activePresetId: String? = null)
        @Volatile var status = Status("Phone EQ is off.")
            private set

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
            .daemon(false).processNameSuffix("phone_eq_audio").version(3)
    }
    private var bound = false
    private var generation = 0
    private var stopping = false
    private var config: String? = null
    private var name = "Phone EQ"
    private var presetId: String? = null
    private var remote: IAudioLoopbackService? = null
    private val binderDead = Shizuku.OnBinderDeadListener {
        handler.post { if (!stopping) finishPlayback("Shizuku stopped. Phone EQ is off.") }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(component: ComponentName, binder: IBinder) {
            if (stopping) return
            remote = IAudioLoopbackService.Stub.asInterface(binder)
            applyPreset()
        }

        override fun onServiceDisconnected(component: ComponentName) {
            remote = null
            if (!stopping) finishPlayback("The audio connection ended. Phone EQ is off.")
        }
    }
    private val timeout = Runnable {
        if (remote == null && !stopping) finishPlayback("Could not connect to the Shizuku audio service.")
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
        name = PresetInput.title(intent.getStringExtra(NAME) ?: "Phone EQ")
        startForeground(NOTIFICATION, notification("Starting Phone EQ…"))
        if (config == null || !authorized()) {
            finishPlayback("Start Shizuku with Android debugging and grant access first.")
            return START_NOT_STICKY
        }
        if (!notificationsEnabled(this)) {
            finishPlayback("Enable playback notifications to use Phone EQ.")
            return START_NOT_STICKY
        }
        if (stopping) return START_NOT_STICKY
        status = status.copy(message = "Starting Phone EQ…", busy = true)
        if (remote != null) applyPreset()
        else if (!bound) {
            try {
                Shizuku.bindUserService(args, connection)
                bound = true
                handler.postDelayed(timeout, 10000)
            } catch (_: Exception) {
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
        val attempt = ++generation
        handler.removeCallbacks(timeout)
        worker.execute {
            val applied = runCatching {
                service.registerClientToken(client)
                val listening = JSONObject(service.statusJson).optBoolean("running")
                if (listening) {
                    service.updateDspConfig(preset)
                } else service.startLoopback(preset)
            }.getOrDefault(false)
            val problem = if (applied) "" else runCatching {
                JSONObject(service.statusJson).optString("error", "")
            }.getOrDefault("")
            handler.post {
                if (attempt != generation || stopping) return@post
                if (applied) {
                    status = Status("Playing · $activeName", running = true, activePresetId = activeId)
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(activeName))
                    poll(service, attempt)
                } else finishPlayback(
                    if (problem.isBlank() || problem == "null") "Audio routing could not start on this device."
                    else "Audio routing could not start. $problem")
            }
        }
    }

    private fun poll(service: IAudioLoopbackService, attempt: Int) {
        handler.postDelayed({
            if (attempt != generation || stopping) return@postDelayed
            if (!notificationsEnabled(this) || !authorized()) {
                finishPlayback("Playback access changed. Phone EQ is off.")
                return@postDelayed
            }
            worker.execute {
                val running = runCatching { JSONObject(service.statusJson).optBoolean("running") }.getOrDefault(false)
                handler.post {
                    if (attempt != generation || stopping) return@post
                    if (running) poll(service, attempt)
                    else finishPlayback("Audio processing stopped. Phone EQ is off.")
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
