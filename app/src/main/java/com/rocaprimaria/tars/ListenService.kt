package com.rocaprimaria.tars

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.zip.ZipInputStream

/**
 * Servicio en primer plano que escucha "TARS" + comando sin internet (Vosk, español),
 * incluso con la pantalla apagada, y entrega el comando a la app.
 */
class ListenService : Service(), RecognitionListener {

    companion object {
        @Volatile var instance: ListenService? = null
        @Volatile var running = false
        @Volatile var lastFromWatch = false

        private const val CH_LISTEN = "tars_listen"
        private const val CH_CMD = "tars_cmd"
        private const val NID = 7
        private const val NID_CMD = 8
        private const val ACTION_STOP = "com.rocaprimaria.tars.STOP"
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"
        private const val SAMPLE_RATE = 16000.0f
        private val DEFAULT_WAKE = listOf("tars", "tarz", "tar", "taras", "tarso", "thars", "stars")

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ListenService::class.java))
        }

        /** Lleva un comando a la pantalla de TARS (o avisa con una notificación si no puede abrirse sola). */
        fun dispatch(ctx: Context, cmd: String, fromWatch: Boolean) {
            lastFromWatch = fromWatch
            val act = MainActivity.current
            if (act != null && MainActivity.visible) {
                act.deliverCommand(cmd, fromWatch)
                return
            }
            val i = Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(MainActivity.EXTRA_CMD, cmd)
                .putExtra(MainActivity.EXTRA_FROM_WATCH, fromWatch)
            val canOpen = Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(ctx)
            if (canOpen) {
                try {
                    ctx.startActivity(i)
                    return
                } catch (_: Exception) {
                }
            }
            ensureChannels(ctx)
            val pi = PendingIntent.getActivity(
                ctx, cmd.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(ctx, CH_CMD)
                .setSmallIcon(R.drawable.ic_stat_tars)
                .setContentTitle("TARS")
                .setContentText("«$cmd» · toca para responder")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .build()
            try {
                NotificationManagerCompat.from(ctx).notify(NID_CMD, n)
            } catch (_: SecurityException) {
            }
        }

        fun ensureChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CH_LISTEN) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CH_LISTEN, "Escucha de TARS", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Aviso permanente mientras TARS escucha"
                        setShowBadge(false)
                    }
                )
            }
            if (nm.getNotificationChannel(CH_CMD) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CH_CMD, "Comandos de TARS", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Comandos escuchados cuando TARS no puede abrirse solo"
                    }
                )
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null
    private var tone: ToneGenerator? = null
    @Volatile private var paused = false
    @Volatile private var awaitingUntil = 0L
    private var partialBeeped = false
    @Volatile private var wake: List<String> = DEFAULT_WAKE
    private var lastStatus = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        running = true
        ensureChannels(this)
        startInForeground("Preparando la escucha…")
        loadWakeWords()
        try {
            tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
        } catch (_: Exception) {
        }
        Thread { prepareModel() }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        instance = null
        try {
            speech?.stop()
            speech?.shutdown()
        } catch (_: Exception) {
        }
        try {
            recognizer?.close()
        } catch (_: Exception) {
        }
        try {
            model?.close()
        } catch (_: Exception) {
        }
        tone?.release()
        event(JSONObject().put("type", "status").put("listening", false).put("text", ""))
        super.onDestroy()
    }

    // ---------- Notificación permanente ----------

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ListenService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH_LISTEN)
            .setSmallIcon(R.drawable.ic_stat_tars)
            .setContentTitle("TARS")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Pausar", stop)
            .build()
    }

    private fun startInForeground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NID, n)
        }
    }

    private fun status(text: String, listening: Boolean = true) {
        if (text == lastStatus) return
        lastStatus = text
        try {
            NotificationManagerCompat.from(this).notify(NID, buildNotification(text))
        } catch (_: SecurityException) {
        }
        event(JSONObject().put("type", "status").put("listening", listening).put("text", text))
    }

    private fun event(json: JSONObject) {
        main.post { MainActivity.sendEvent(json) }
    }

    // ---------- Modelo de voz (se descarga una sola vez, ~40 MB) ----------

    private fun prepareModel() {
        try {
            val dir = File(filesDir, "vosk-es")
            val ready = File(dir, "READY")
            if (!ready.exists()) {
                status("Descargando el modelo de escucha (≈40 MB, una sola vez)…")
                val zip = File(cacheDir, "vosk-es.zip")
                download(MODEL_URL, zip)
                status("Instalando el modelo de escucha…")
                dir.deleteRecursively()
                unzipStripTop(zip, dir)
                zip.delete()
                ready.createNewFile()
            }
            val m = Model(dir.absolutePath)
            model = m
            main.post { startRecognition() }
        } catch (e: Exception) {
            status("No pude preparar la escucha: ${e.message ?: "error"}. Revisa tu conexión y vuelve a activar.", false)
            event(JSONObject().put("type", "error").put("text", "No pude preparar la escucha sin conexión."))
        }
    }

    private fun download(url: String, out: File) {
        var u = URL(url)
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = u.openConnection() as HttpURLConnection
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code in 300..399 && redirects < 5) {
                u = URL(u, conn.getHeaderField("Location"))
                redirects++
                conn.disconnect()
                continue
            }
            if (code != 200) throw IllegalStateException("HTTP $code")
            break
        }
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            FileOutputStream(out).use { output ->
                val buf = ByteArray(64 * 1024)
                var read: Int
                var done = 0L
                var lastPct = -1
                while (input.read(buf).also { read = it } != -1) {
                    output.write(buf, 0, read)
                    done += read
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != lastPct && pct % 5 == 0) {
                            lastPct = pct
                            status("Descargando el modelo de escucha… $pct%")
                            event(JSONObject().put("type", "progress").put("pct", pct))
                        }
                    }
                }
            }
        }
        conn.disconnect()
    }

    private fun unzipStripTop(zip: File, dest: File) {
        dest.mkdirs()
        val root = dest.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name.substringAfter('/', "")
                if (name.isNotEmpty()) {
                    val f = File(dest, name)
                    if (!f.canonicalPath.startsWith(root)) throw SecurityException("Entrada inválida en el zip")
                    if (entry.isDirectory) f.mkdirs() else {
                        f.parentFile?.mkdirs()
                        FileOutputStream(f).use { zis.copyTo(it) }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    // ---------- Reconocimiento ----------

    private fun startRecognition() {
        val m = model ?: return
        try {
            speech?.shutdown()
            val rec = Recognizer(m, SAMPLE_RATE)
            recognizer = rec
            val s = SpeechService(rec, SAMPLE_RATE)
            speech = s
            s.startListening(this)
            if (paused) s.setPause(true)
            status("Escuchando «TARS»")
        } catch (e: Exception) {
            status("No pude abrir el micrófono: ${e.message ?: "error"}", false)
        }
    }

    fun setPaused(p: Boolean) {
        paused = p
        main.post {
            try {
                speech?.setPause(p)
            } catch (_: Exception) {
            }
        }
    }

    fun awaitCommand() {
        awaitingUntil = SystemClock.elapsedRealtime() + 8000
        beep()
        event(JSONObject().put("type", "awake"))
    }

    fun loadWakeWords() {
        val csv = getSharedPreferences("tars", Context.MODE_PRIVATE).getString("wake", null)
        val list = (csv ?: "").split(",").map { norm(it) }.filter { it.isNotEmpty() && !it.contains(' ') }
        wake = (list + DEFAULT_WAKE).distinct()
    }

    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase().trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9ñ ]"), "")
            .trim()

    private fun beep() {
        try {
            tone?.startTone(ToneGenerator.TONE_PROP_ACK, 150)
        } catch (_: Exception) {
        }
    }

    private fun textOf(json: String?, key: String): String = try {
        JSONObject(json ?: "{}").optString(key, "")
    } catch (_: Exception) {
        ""
    }

    private fun handleUtterance(text: String) {
        partialBeeped = false
        val raw = text.trim()
        if (raw.isEmpty() || paused) return
        val now = SystemClock.elapsedRealtime()
        if (now < awaitingUntil) {
            awaitingUntil = 0
            send(raw)
            return
        }
        val words = raw.split(Regex("\\s+"))
        val idx = words.indexOfFirst { norm(it) in wake }
        if (idx < 0) return
        val cmd = words.drop(idx + 1).joinToString(" ").trim()
        if (cmd.isEmpty()) {
            awaitingUntil = now + 8000
            beep()
            event(JSONObject().put("type", "awake"))
        } else {
            send(cmd)
        }
    }

    private fun send(cmd: String) {
        status("«$cmd»")
        main.post { dispatch(applicationContext, cmd, false) }
        main.postDelayed({ status("Escuchando «TARS»") }, 4000)
    }

    override fun onPartialResult(hypothesis: String?) {
        if (paused) return
        val partial = textOf(hypothesis, "partial")
        if (partial.isEmpty()) return
        if (!partialBeeped && partial.split(Regex("\\s+")).any { norm(it) in wake }) {
            partialBeeped = true
            beep()
            event(JSONObject().put("type", "awake"))
        }
        event(JSONObject().put("type", "partial").put("text", partial))
    }

    override fun onResult(hypothesis: String?) {
        handleUtterance(textOf(hypothesis, "text"))
    }

    override fun onFinalResult(hypothesis: String?) {
        handleUtterance(textOf(hypothesis, "text"))
    }

    override fun onError(exception: Exception?) {
        status("Reiniciando la escucha…")
        main.postDelayed({ if (running) startRecognition() }, 1500)
    }

    override fun onTimeout() {
        main.postDelayed({ if (running) startRecognition() }, 500)
    }
}
