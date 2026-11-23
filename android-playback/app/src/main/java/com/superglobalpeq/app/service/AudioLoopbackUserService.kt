// Adapted from Quve v1.0.2 by nanbeimerco, GPL-3.0. See NOTICE.md.
package com.superglobalpeq.app.service

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import com.superglobalpeq.app.PresetInput
import com.superglobalpeq.app.dsp.StereoPeqProcessor
import com.superglobalpeq.app.model.ActiveDspConfig
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Runs inside Shizuku's UID 2000 (com.android.shell) process via UserService.
 * Uses ROUTE_FLAG_LOOP_BACK (without RENDER) + RULE_MATCH_UID exclusion on UID 2000
 * to intercept system media/game audio without MediaProjection or double playback,
 * processes stereo PCM through 64-bit TDF-II Biquad PEQ, and renders via UID 2000 AudioTrack.
 */
class AudioLoopbackUserService : IAudioLoopbackService.Stub() {
    companion object {
        private const val TAG = "SuperPeqLoopbackSvc"
        private const val PHONE_EQ_PACKAGE = "com.yinyangeq.android.playback"
        private const val SHELL_PKG = "com.android.shell"
        private const val RULE_MATCH_ATTRIBUTE_USAGE = 0x1
        private const val RULE_MATCH_UID = 0x1 shl 2
        private const val ROUTE_FLAG_LOOP_BACK = 0x1 shl 1
        private const val AUDIO_MANAGER_SUCCESS = 0
        private const val SAMPLE_RATE = 48000
        private const val BLOCK_SHORTS = 480 * 2 // 10 ms @ 48 kHz stereo PCM_16BIT
    }

    private class ShellServiceContext(base: Context) : ContextWrapper(base) {
        private val shellAttribution: AttributionSource =
            AttributionSource.Builder(Process.myUid())
                .setPackageName(SHELL_PKG)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        setPid(Process.myPid())
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                        setDeviceId(0)
                    }
                }
                .build()

        override fun getPackageName(): String = SHELL_PKG
        override fun getOpPackageName(): String = SHELL_PKG
        override fun getAttributionSource(): AttributionSource = shellAttribution
        override fun getApplicationContext(): Context = this
        override fun createAttributionContext(attributionTag: String?): Context = this
        override fun getDeviceId(): Int = 0
    }

    private val processor = StereoPeqProcessor(SAMPLE_RATE.toDouble())
    private val stateLock = Any()
    private val clientLock = Any()
    private val loopRunning = AtomicBoolean(false)
    private val destroying = AtomicBoolean(false)

    @Volatile
    private var linkedClientToken: IBinder? = null
    private var clientUid: Int? = null

    @Volatile
    private var clientDeathRecipient: IBinder.DeathRecipient? = null

    @Volatile
    private var audioPolicyObj: Any? = null

    @Volatile
    private var audioManager: AudioManager? = null

    @Volatile
    private var shellContext: Context? = null

    @Volatile
    private var activeRecord: AudioRecord? = null

    @Volatile
    private var activeTrack: AudioTrack? = null

    @Volatile
    private var workerThread: Thread? = null

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var startupTrace = ""

    init {
        Runtime.getRuntime().addShutdownHook(Thread({
            if (!destroying.get()) synchronized(stateLock) { stopInternal() }
        }, "peq-shutdown-hook"))
    }

    @SuppressLint("PrivateApi")
    @Synchronized
    private fun ensureShellContext(): Context {
        shellContext?.let { return it }
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper()
        }
        val atClass = Class.forName("android.app.ActivityThread")
        var at = atClass.getMethod("currentActivityThread").invoke(null)
        if (at == null) {
            at = atClass.getMethod("systemMain").invoke(null)
        }
        val sysCtx = atClass.getMethod("getSystemContext").invoke(at) as Context
        val pkgCtx = sysCtx.createPackageContext(SHELL_PKG, 0)
        check(pkgCtx.applicationInfo.uid == Process.myUid()) { "Shizuku shell context has the wrong UID." }
        val shell = ShellServiceContext(pkgCtx)
        shellContext = shell
        audioManager = shell.getSystemService(AudioManager::class.java)
        return shell
    }

    private fun requirePhoneEqCaller(): Int {
        check(Process.myUid() == 2000) { "Start Shizuku with Android debugging." }
        val caller = Binder.getCallingUid()
        if (ensureShellContext().packageManager.getPackagesForUid(caller)?.contains(PHONE_EQ_PACKAGE) != true) {
            throw SecurityException("Phone EQ did not authorize this caller.")
        }
        return caller
    }

    private fun requireActiveClient(): Int {
        val caller = requirePhoneEqCaller()
        synchronized(clientLock) {
            if (caller != clientUid || linkedClientToken?.isBinderAlive != true || destroying.get())
                throw SecurityException("No active Phone EQ client.")
        }
        return caller
    }

    private fun parseConfig(json: String): ActiveDspConfig {
        require(json.length <= PresetInput.LIMIT)
        return ActiveDspConfig.fromJsonString(json).also { PresetInput.validate(it.preset) }
    }

    override fun startLoopback(dspConfigJson: String): Boolean {
        val caller = requireActiveClient()
        // Android checks local AudioPolicy calls against the current Binder caller.
        // This Shizuku UserService performs routing under its own authorized shell identity.
        val identity = Binder.clearCallingIdentity()
        return try {
            startAuthorizedLoopback(caller, dspConfigJson)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun startAuthorizedLoopback(clientAppUid: Int, dspConfigJson: String): Boolean {
        synchronized(stateLock) {
            if (destroying.get()) return false
            stopInternal()
            lastError = null
            startupTrace = ""
            var phase = "Preset validation"
            startupEvent("Starting audio route (API ${Build.VERSION.SDK_INT}).")
            return try {
                val parsed = parseConfig(dspConfigJson)
                processor.resetStates()
                processor.updateConfig(parsed)

                phase = "Audio policy setup"
                val ctx = ensureShellContext()
                val myUid = Process.myUid()

                val mixingRuleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
                val mixingRuleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
                val audioMixClass = Class.forName("android.media.audiopolicy.AudioMix")
                val audioMixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
                val audioPolicyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
                val audioPolicyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")

                val ruleBuilder = mixingRuleBuilderClass.getConstructor().newInstance()
                val addRule = mixingRuleBuilderClass.getMethod(
                    "addRule", AudioAttributes::class.java, Int::class.javaPrimitiveType
                )
                val usages = intArrayOf(
                    AudioAttributes.USAGE_MEDIA,
                    AudioAttributes.USAGE_GAME
                )
                for (usage in usages) {
                    val attr = AudioAttributes.Builder().setUsage(usage).build()
                    addRule.invoke(ruleBuilder, attr, RULE_MATCH_ATTRIBUTE_USAGE)
                }

                val excludeMixRule = mixingRuleBuilderClass.getMethod(
                    "excludeMixRule", Int::class.javaPrimitiveType, Any::class.java
                )
                for (uid in intArrayOf(myUid, clientAppUid)) {
                    excludeMixRule.invoke(ruleBuilder, RULE_MATCH_UID, Integer.valueOf(uid))
                }
                val mixingRule = mixingRuleBuilderClass.getMethod("build").invoke(ruleBuilder)

                val mixFormat = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()

                val mixBuilder = audioMixBuilderClass.getConstructor(mixingRuleClass).newInstance(mixingRule)
                audioMixBuilderClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, mixFormat)
                audioMixBuilderClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType)
                    .invoke(mixBuilder, ROUTE_FLAG_LOOP_BACK)
                val audioMix = checkNotNull(audioMixBuilderClass.getMethod("build").invoke(mixBuilder))

                val policyBuilder = audioPolicyBuilderClass.getConstructor(Context::class.java).newInstance(ctx)
                audioPolicyBuilderClass.getMethod("setLooper", Looper::class.java)
                    .invoke(policyBuilder, Looper.getMainLooper())
                audioPolicyBuilderClass.getMethod("addMix", audioMixClass).invoke(policyBuilder, audioMix)
                val policy = checkNotNull(audioPolicyBuilderClass.getMethod("build").invoke(policyBuilder))

                val regStatic = AudioManager::class.java.getDeclaredMethod("registerAudioPolicyStatic", audioPolicyClass)
                regStatic.isAccessible = true
                val regRes = regStatic.invoke(null, policy) as Int
                if (regRes != AUDIO_MANAGER_SUCCESS) {
                    return failStartup("Android rejected the audio route.", "Audio policy registration returned $regRes.")
                }
                audioPolicyObj = policy
                startupEvent("Audio policy registered.")

                phase = "Output initialization"
                val track = createOutputTrack(ctx)
                activeTrack = track
                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    return failStartup("Android could not open the playback output.", "Output state ${track.state}.")
                }
                startupEvent("Playback output initialized.")

                phase = "Capture startup"
                val capturing = startCapture(Build.VERSION.SDK_INT) { route ->
                    tryStartCapture(route) {
                        when (route) {
                            CaptureRoute.SHELL_CONTEXT -> createRecordForRegisteredMix(ctx, audioMixClass, audioMix, mixFormat)
                            CaptureRoute.POLICY -> audioPolicyClass.getMethod("createAudioRecordSink", audioMixClass)
                                .invoke(policy, audioMix) as? AudioRecord
                        }
                    }
                }
                if (!capturing) {
                    return failStartup("Android could not start playback capture.", "No capture route started.")
                }
                val record = checkNotNull(activeRecord)

                phase = "Output startup"
                track.play()
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    return failStartup("Android could not start the playback output.", "Output play state ${track.playState}.")
                }
                phase = "Processing startup"
                loopRunning.set(true)
                val t = Thread({ runAudioLoop(record, track) }, "peq-audio-loop")
                workerThread = t
                t.start()
                startupEvent("Playback processing started.")
                true
            } catch (e: Throwable) {
                val message = when (phase) {
                    "Output initialization" -> "Android could not open the playback output."
                    "Output startup" -> "Android could not start the playback output."
                    else -> "Android could not start the media playback route."
                }
                failStartup(message, "$phase failed (${failureType(e)}).")
            }
        }
    }

    private fun tryStartCapture(route: CaptureRoute, open: () -> AudioRecord?): Boolean {
        var record: AudioRecord? = null
        var started = false
        var operation = "open"
        startupEvent("${route.label} capture: opening.")
        try {
            record = open()
            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                startupEvent("${route.label} capture: initialization state ${record?.state ?: "missing"}.", failed = true)
                return false
            }
            operation = "start"
            record.startRecording()
            val state = record.recordingState
            started = state == AudioRecord.RECORDSTATE_RECORDING
            if (started) activeRecord = record
            startupEvent("${route.label} capture: recording state $state.", failed = !started)
            return started
        } catch (e: Exception) {
            startupEvent("${route.label} capture: $operation failed (${failureType(e)}).", failed = true)
            return false
        } finally {
            if (!started) runCatching { record?.release() }
        }
    }

    private fun failureType(e: Throwable): String = (e.cause ?: e).javaClass.simpleName

    private fun failStartup(message: String, detail: String): Boolean {
        startupEvent(detail, failed = true)
        lastError = message
        stopInternal()
        return false
    }

    private fun startupEvent(detail: String, failed: Boolean = false) {
        startupTrace += if (startupTrace.isEmpty()) detail else "\n$detail"
        if (failed) Log.w(TAG, detail) else Log.i(TAG, detail)
    }

    @SuppressLint("MissingPermission", "DiscouragedPrivateApi")
    private fun createRecordForRegisteredMix(
        context: Context, mixType: Class<*>, mix: Any, mixFormat: AudioFormat
    ): AudioRecord {
        // Build the same registered mix's recorder with the service's own shell attribution.
        val registration = mixType.getMethod("getRegistration").invoke(mix) as String
        check(registration.isNotBlank()) { "Android did not register the playback mix." }
        val attributes = AudioAttributes.Builder()
        val attributesType = AudioAttributes.Builder::class.java
        attributesType.getMethod("setInternalCapturePreset", Int::class.javaPrimitiveType)
            .invoke(attributes, MediaRecorder.AudioSource.REMOTE_SUBMIX)
        val addTag = attributesType.getMethod("addTag", String::class.java)
        addTag.invoke(attributes, "addr=$registration")
        addTag.invoke(attributes, AudioRecord::class.java.getField("SUBMIX_FIXED_VOLUME").get(null))
        val format = AudioFormat.Builder(mixFormat).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
        val minBuffer = AudioRecord.getMinBufferSize(format.sampleRate, format.channelMask, format.encoding)
        val builder = AudioRecord.Builder().setContext(context).setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuffer, BLOCK_SHORTS * 2 * 4))
        AudioRecord.Builder::class.java.getMethod("setAudioAttributes", AudioAttributes::class.java)
            .invoke(builder, attributes.build())
        return builder.build()
    }

    @SuppressLint("MissingPermission")
    private fun createOutputTrack(ctx: Context): AudioTrack {
        val minOutBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val outBufBytes = maxOf(minOutBuf, BLOCK_SHORTS * 2 * 4)
        val builder = AudioTrack.Builder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            builder.setContext(ctx)
        }
        return builder
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(outBufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    private fun runAudioLoop(record: AudioRecord, track: AudioTrack) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            val buf = ShortArray(BLOCK_SHORTS)
            while (loopRunning.get()) {
                val read = record.read(buf, 0, buf.size)
                if (read < 0) {
                    if (loopRunning.get()) {
                        lastError = "AudioRecord.read error: $read"
                    }
                    break
                }
                if (read == 0) continue

                processor.processInterleavedPcm16(buf, read)

                val written = track.write(buf, 0, read)
                if (written < 0) {
                    if (loopRunning.get()) {
                        lastError = "AudioTrack.write error: $written"
                    }
                    break
                }

            }
        } catch (t: Throwable) {
            if (loopRunning.get()) {
                Log.e(TAG, "Audio loop error", t)
                lastError = "Android could not open the media playback route."
            }
        } finally {
            loopRunning.set(false)
        }
    }

    override fun updateDspConfig(dspConfigJson: String): Boolean {
        requireActiveClient()
        return synchronized(stateLock) {
            if (!loopRunning.get() || destroying.get()) return@synchronized false
            runCatching { processor.updateConfig(parseConfig(dspConfigJson)) }.isSuccess
        }
    }

    override fun stopLoopback() {
        requireActiveClient()
        synchronized(stateLock) { stopInternal() }
    }

    private fun stopInternal() {
        loopRunning.set(false)

        try {
            activeRecord?.let { r ->
                if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    r.stop()
                }
                r.release()
            }
        } catch (_: Throwable) {
        }
        activeRecord = null

        try {
            activeTrack?.let { t ->
                if (t.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    t.stop()
                }
                t.release()
            }
        } catch (_: Throwable) {
        }
        activeTrack = null

        try {
            workerThread?.join(1200L)
        } catch (_: Throwable) {
        }
        workerThread = null

        audioPolicyObj?.let { policy ->
            try {
                val audioPolicyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
                val am = audioManager
                if (am != null) {
                    AudioManager::class.java
                        .getMethod("unregisterAudioPolicy", audioPolicyClass)
                        .invoke(am, policy)
                } else {
                    val unregStatic = AudioManager::class.java
                        .getDeclaredMethod("unregisterAudioPolicyAsyncStatic", audioPolicyClass)
                    unregStatic.isAccessible = true
                    unregStatic.invoke(null, policy)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "unregisterAudioPolicy error", t)
            }
        }
        audioPolicyObj = null
    }

    override fun getStatusJson(): String {
        requireActiveClient()
        return JSONObject().put("running", loopRunning.get())
            .put("error", lastError ?: JSONObject.NULL).put("startupTrace", startupTrace).toString()
    }

    override fun registerClientToken(clientToken: IBinder?) {
        val caller = requirePhoneEqCaller()
        requireNotNull(clientToken)
        synchronized(clientLock) {
            check(!destroying.get())
            if (clientUid != null && clientUid != caller) throw SecurityException("A different Phone EQ client is active.")
            if (linkedClientToken === clientToken) return
            val recipient = IBinder.DeathRecipient { destroyService() }
            clientToken.linkToDeath(recipient, 0)
            unlinkClientTokenLocked()
            linkedClientToken = clientToken
            clientDeathRecipient = recipient
            clientUid = caller
        }
    }

    private fun unlinkClientTokenLocked() {
        val token = linkedClientToken
        val recipient = clientDeathRecipient
        linkedClientToken = null
        clientUid = null
        clientDeathRecipient = null
        if (token != null && recipient != null) {
            try {
                token.unlinkToDeath(recipient, 0)
            } catch (_: Throwable) {
            }
        }
    }

    override fun destroy() {
        if (Binder.getCallingUid() != Process.myUid()) requirePhoneEqCaller()
        destroyService()
    }

    private fun destroyService() {
        if (!destroying.compareAndSet(false, true)) return
        Log.i(TAG, "Destroying AudioLoopbackUserService cleanly")
        synchronized(clientLock) {
            unlinkClientTokenLocked()
        }
        synchronized(stateLock) { stopInternal() }
        exitProcess(0)
    }
}
