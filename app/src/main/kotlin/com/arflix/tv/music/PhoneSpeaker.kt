package com.arflix.tv.music

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.arflix.tv.util.settingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The phone as a Music Assistant speaker (Joe, 2026-10-12: "besides sonos speakers I still want
 * this device, my phone"). Speaks Sendspin -- MA's own playback protocol, the one its web player
 * uses -- over a plain WebSocket to `ws://<MA host>:8927/sendspin`, so the phone shows up in every
 * zone list (Xadarr's, MA's) like any other speaker and MA streams PCM to it.
 *
 * Connected while the app is in the foreground. Once MA starts streaming, [PhoneSpeakerService]
 * keeps the process alive (and the music going) with the screen off or the app in the background;
 * it ends with the stream. Phones only -- a TV already has its room speaker.
 *
 * Self-contained like the rest of this package (also compiled into Xadarr Music).
 */
object PhoneSpeaker {
    private const val TAG = "PhoneSpeaker"
    private const val PORT = 8927
    private const val AUDIO_CHUNK = 4
    private const val HEADER = 9 // 1 byte type + int64 big-endian server timestamp (µs)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private lateinit var app: Context
    private var installed = false
    private var foreground = false
    private var socket: WebSocket? = null
    private var connectJob: Job? = null
    private var timeJob: Job? = null

    // Clock: server µs = local µs + offset, from the client/time sample with the shortest round trip.
    private val samples = ArrayDeque<Pair<Long, Long>>() // rtt to offset
    @Volatile private var offsetUs = 0L

    @Volatile var streaming = false
        private set

    /**
     * What the phone speaker is doing, in words, for the Rooms list when "This phone" isn't
     * there (Joe, 2026-10-12: it never showed up and there was no way to tell why).
     */
    val status = kotlinx.coroutines.flow.MutableStateFlow("Not started")
    @Volatile private var volume = 100
    @Volatile private var muted = false

    private val output = Output()

    private fun nowUs() = SystemClock.elapsedRealtimeNanos() / 1000

    /** Call once from Application.onCreate on phones. */
    fun install(context: Context) {
        if (installed) return
        installed = true
        app = context.applicationContext
        myClientId = clientId()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                foreground = true
                connect()
            }
            override fun onStop(owner: LifecycleOwner) {
                foreground = false
                // Keep the connection while music plays here; otherwise let go of it.
                if (!streaming) disconnect()
            }
        })
    }

    private fun connect() {
        if (socket != null || connectJob?.isActive == true) return
        connectJob = scope.launch {
            var backoff = 2_000L
            while (isActive && socket == null && (foreground || streaming)) {
                val url = maUrl()
                val host = url?.let { runCatching { Uri.parse(it).host }.getOrNull() }
                if (host == null) {
                    status.value = "No Music Assistant address set"
                    return@launch
                }
                status.value = "Connecting to $host:$PORT…"
                val req = Request.Builder().url("ws://$host:$PORT/sendspin").build()
                val opened = kotlinx.coroutines.CompletableDeferred<Boolean>()
                val ws = runCatching { client.newWebSocket(req, Listener(opened)) }
                    .onFailure { status.value = "Couldn't start: ${it.message}" }.getOrNull()
                if (ws != null && kotlinx.coroutines.withTimeoutOrNull(8_000L) { opened.await() } == true) {
                    socket = ws
                    status.value = "Connected"
                    return@launch
                }
                if (status.value.startsWith("Connecting")) status.value = "No answer from $host:$PORT -- retrying"
                ws?.cancel()
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }

    private fun disconnect() {
        connectJob?.cancel()
        timeJob?.cancel()
        socket?.let { ws ->
            runCatching { ws.send(msg("client/goodbye", JSONObject().put("reason", "user_request"))) }
            ws.close(1000, null)
        }
        socket = null
        stopStream()
        status.value = "Off (app in background)"
    }

    private suspend fun maUrl(): String? =
        app.settingsDataStore.data.first()[MA_URL_KEY]?.takeIf { it.isNotBlank() }

    const val CLIENT_ID_PREFIX = "xadarr-phone-"

    /** This phone's Sendspin client id, once [install] has run (null on TVs). */
    @Volatile var myClientId: String? = null
        private set

    private fun clientId(): String {
        val prefs = app.getSharedPreferences("phone_speaker", Context.MODE_PRIVATE)
        return prefs.getString("client_id", null) ?: (CLIENT_ID_PREFIX + UUID.randomUUID().toString()).also {
            prefs.edit().putString("client_id", it).apply()
        }
    }

    private fun deviceName(): String =
        Settings.Global.getString(app.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    private fun msg(type: String, payload: JSONObject) =
        JSONObject().put("type", type).put("payload", payload).toString()

    private fun format(rate: Int) = JSONObject().put("codec", "pcm").put("channels", 2)
        .put("sample_rate", rate).put("bit_depth", 16)

    private fun hello() = msg("client/hello", JSONObject()
        .put("client_id", clientId())
        .put("name", deviceName())
        .put("version", 1)
        .put("supported_roles", JSONArray().put("player@v1"))
        .put("device_info", JSONObject().put("product_name", Build.MODEL).put("manufacturer", Build.MANUFACTURER)
            .put("software_version", "Xadarr"))
        .put("player@v1_support", JSONObject()
            .put("supported_formats", JSONArray().put(format(48_000)).put(format(44_100)))
            .put("buffer_capacity", 2_000_000)
            .put("supported_commands", JSONArray().put("volume").put("mute"))))

    private fun sendState(initial: Boolean = false) {
        val player = JSONObject().put("volume", volume).put("muted", muted)
        // Required on the first state message: how much lead the server must give us.
        if (initial) player.put("static_delay_ms", 0).put("required_lead_time_ms", 300).put("min_buffer_ms", 500)
        socket?.send(msg("client/state", JSONObject().put("available", true).put("player", player)))
    }

    private class Listener(private val opened: kotlinx.coroutines.CompletableDeferred<Boolean>) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(hello())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return
            val p = o.optJSONObject("payload") ?: JSONObject()
            when (o.optString("type")) {
                "server/hello" -> {
                    opened.complete(true)
                    socket = webSocket
                    sendState(initial = true)
                    startTimeSync(webSocket)
                    Log.i(TAG, "connected to ${p.optString("name")} as ${deviceName()}")
                }
                "server/time" -> onTime(p)
                "stream/start" -> p.optJSONObject("player")?.let { startStream(it) }
                "stream/clear" -> output.clear()
                "stream/end" -> stopStream()
                "server/command" -> p.optJSONObject("player")?.let { cmd ->
                    when (cmd.optString("command")) {
                        "volume" -> volume = cmd.optInt("volume", volume).coerceIn(0, 100)
                        "mute" -> muted = cmd.optBoolean("mute", muted)
                    }
                    output.applyVolume()
                    sendState()
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (bytes.size <= HEADER || bytes[0].toInt() != AUDIO_CHUNK) return
            var ts = 0L
            for (i in 1 until HEADER) ts = (ts shl 8) or (bytes[i].toLong() and 0xff)
            output.enqueue(ts, bytes.substring(HEADER).toByteArray())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "connection lost: ${t.message}")
            status.value = "Couldn't connect: ${t.javaClass.simpleName} ${t.message.orEmpty()}".trim()
            opened.complete(false)
            dropped(webSocket)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            opened.complete(false)
            dropped(webSocket)
        }
    }

    private fun dropped(ws: WebSocket) {
        if (socket !== ws) return
        socket = null
        timeJob?.cancel()
        stopStream()
        if (foreground) scope.launch { delay(3_000); connect() }
    }

    private fun startTimeSync(ws: WebSocket) {
        timeJob?.cancel()
        timeJob = scope.launch {
            var n = 0
            while (isActive) {
                ws.send(msg("client/time", JSONObject().put("client_transmitted", nowUs())))
                delay(if (n++ < 8) 300L else 3_000L)
            }
        }
    }

    private fun onTime(p: JSONObject) {
        val t4 = nowUs()
        val t1 = p.optLong("client_transmitted")
        val t2 = p.optLong("server_received")
        val t3 = p.optLong("server_transmitted")
        val rtt = (t4 - t1) - (t3 - t2)
        val offset = ((t2 - t1) + (t3 - t4)) / 2
        synchronized(samples) {
            samples.addLast(rtt to offset)
            while (samples.size > 12) samples.removeFirst()
            offsetUs = samples.minByOrNull { it.first }!!.second
        }
    }

    private fun startStream(player: JSONObject) {
        val rate = player.optInt("sample_rate", 48_000)
        val channels = player.optInt("channels", 2)
        val bits = player.optInt("bit_depth", 16)
        if (player.optString("codec") != "pcm" || bits != 16) {
            Log.w(TAG, "unsupported stream ${player}")
            return
        }
        output.start(rate, channels)
        if (!streaming) {
            streaming = true
            runCatching {
                val i = Intent(app, PhoneSpeakerService::class.java)
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
            }.onFailure { Log.w(TAG, "foreground service: ${it.message}") }
        }
    }

    private fun stopStream() {
        output.stop()
        if (streaming) {
            streaming = false
            runCatching { app.stopService(Intent(app, PhoneSpeakerService::class.java)) }
            if (!foreground) disconnect()
        }
    }

    /** Called from the notification's Stop: let go of the stream and leave MA's speaker list. */
    internal fun stopFromNotification() {
        stopStream()
        disconnect()
    }

    /**
     * PCM out. Chunks carry the server time they should be heard at; the first chunk after a
     * start or clear waits for its moment, and the rest follow back to back (AudioTrack's
     * blocking write paces them).
     */
    private class Output {
        private val queue = LinkedBlockingQueue<Pair<Long, ByteArray>>()
        @Volatile private var track: AudioTrack? = null
        @Volatile private var generation = 0
        private var writer: Thread? = null

        @Synchronized
        fun start(rate: Int, channels: Int) {
            val t = track
            if (t != null && t.sampleRate == rate && t.channelCount == channels) return
            stop()
            val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
            val newTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate).setChannelMask(mask).build())
                .setBufferSizeInBytes(maxOf(min * 4, rate * channels * 2 / 2)) // ~0.5s
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = newTrack
            applyVolume()
            newTrack.play()
            val gen = ++generation
            writer = Thread({ writeLoop(newTrack, gen) }, "PhoneSpeaker-out").also { it.start() }
        }

        fun applyVolume() {
            val v = if (muted) 0f else volume / 100f
            runCatching { track?.setVolume(v * v) } // squared: closer to how loudness feels
        }

        fun enqueue(serverTs: Long, pcm: ByteArray) {
            if (track != null) queue.offer(serverTs to pcm)
        }

        @Synchronized
        fun clear() {
            queue.clear()
            generation++ // the writer re-syncs on the next chunk
            track?.let { t -> runCatching { t.pause(); t.flush(); t.play() } }
            val t = track ?: return
            val gen = generation
            writer = Thread({ writeLoop(t, gen) }, "PhoneSpeaker-out").also { it.start() }
        }

        @Synchronized
        fun stop() {
            generation++
            queue.clear()
            track?.let { t -> runCatching { t.pause(); t.flush(); t.release() } }
            track = null
        }

        private fun writeLoop(t: AudioTrack, gen: Int) {
            var synced = false
            while (generation == gen) {
                val (ts, pcm) = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                if (generation != gen) break
                if (!synced) {
                    // Wait until this chunk's moment (server clock -> ours).
                    val waitUs = ts - offsetUs - nowUs()
                    if (waitUs < -250_000) continue // already past: skip ahead to a current chunk
                    if (waitUs > 0) Thread.sleep(waitUs / 1000, ((waitUs % 1000) * 1000).toInt())
                    synced = true
                }
                var off = 0
                while (off < pcm.size && generation == gen) {
                    val n = t.write(pcm, off, pcm.size - off)
                    if (n <= 0) break
                    off += n
                }
            }
        }
    }
}

/** Keeps the phone playing with the screen off. Lives exactly as long as a stream. */
class PhoneSpeakerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("InlinedApi")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            PhoneSpeaker.stopFromNotification()
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Music on this phone", NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(
            this, 0, Intent(this, PhoneSpeakerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Playing on this phone")
            .setContentText("Music Assistant")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(ID, notification)
        }
        return START_NOT_STICKY
    }

    private companion object {
        const val CHANNEL = "phone_speaker"
        const val ID = 4127
        const val ACTION_STOP = "com.arflix.tv.music.PHONE_SPEAKER_STOP"
    }
}
