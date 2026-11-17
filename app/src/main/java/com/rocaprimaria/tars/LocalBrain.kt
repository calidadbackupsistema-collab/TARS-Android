package com.rocaprimaria.tars

import android.content.Context
import android.os.StatFs
import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Cerebro local de TARS: Gemma 4 E2B con LiteRT-LM, sin internet.
 * El modelo (~2.6 GB) se descarga una sola vez y se carga bajo demanda.
 */
object LocalBrain {
    private const val MODEL_URL =
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
    private const val MODEL_NAME = "gemma-4-E2B-it.litertlm"
    private const val MIN_BYTES = 2_400_000_000L

    @Volatile var state = "absent" // absent | downloading | downloaded | loading | ready | error
    @Volatile var progress = 0
    @Volatile var error = ""
    @Volatile var backendName = ""
    @Volatile var loadMs = 0L
    @Volatile var lastMs = 0L
    @Volatile var lastTokPerSec = 0.0
    @Volatile var answered = 0

    private var engine: Engine? = null
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var downloading = false
    @Volatile private var cancelDownload = false

    fun modelFile(ctx: Context): File {
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "models")
        dir.mkdirs()
        return File(dir, MODEL_NAME)
    }

    private fun partFile(ctx: Context) = File(modelFile(ctx).path + ".part")

    fun refresh(ctx: Context) {
        state = when {
            engine != null -> "ready"
            downloading -> "downloading"
            state == "loading" -> "loading"
            modelFile(ctx).length() >= MIN_BYTES -> "downloaded"
            state == "error" -> "error"
            else -> "absent"
        }
    }

    fun statusJson(ctx: Context): String {
        refresh(ctx)
        val free = try { StatFs(modelFile(ctx).parent).availableBytes } catch (_: Exception) { -1L }
        return JSONObject()
            .put("state", state)
            .put("progress", progress)
            .put("error", error)
            .put("backend", backendName)
            .put("loadMs", loadMs)
            .put("lastMs", lastMs)
            .put("tokPerSec", lastTokPerSec)
            .put("answered", answered)
            .put("sizeMB", modelFile(ctx).length() / 1_048_576)
            .put("partialMB", partFile(ctx).length() / 1_048_576)
            .put("freeMB", if (free > 0) free / 1_048_576 else -1)
            .toString()
    }

    // ---------- Descarga con reanudación ----------

    fun download(ctx: Context, onEvent: (JSONObject) -> Unit) {
        if (downloading || modelFile(ctx).length() >= MIN_BYTES) return
        val part = partFile(ctx)
        val free = try { StatFs(part.parent).availableBytes } catch (_: Exception) { Long.MAX_VALUE }
        val need = 2_700_000_000L - part.length() + 300_000_000L
        if (free < need) {
            error = "Espacio insuficiente: se necesitan ${need / 1_048_576} MB libres."
            state = "error"
            onEvent(JSONObject().put("type", "llm_status").put("state", state).put("error", error))
            return
        }
        downloading = true
        cancelDownload = false
        state = "downloading"
        error = ""
        Thread {
            try {
                var url = URL(MODEL_URL)
                var conn: HttpURLConnection
                var hops = 0
                while (true) {
                    conn = url.openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 20000
                    conn.readTimeout = 60000
                    if (part.length() > 0) conn.setRequestProperty("Range", "bytes=${part.length()}-")
                    val code = conn.responseCode
                    if (code in 300..399 && hops < 8) {
                        url = URL(url, conn.getHeaderField("Location"))
                        hops++
                        conn.disconnect()
                        continue
                    }
                    if (code != 200 && code != 206) throw IllegalStateException("HTTP $code")
                    if (code == 200 && part.length() > 0) part.delete() // el servidor no reanudó
                    break
                }
                val already = part.length()
                val total = already + conn.contentLengthLong
                RandomAccessFile(part, "rw").use { raf ->
                    raf.seek(already)
                    conn.inputStream.use { input ->
                        val buf = ByteArray(256 * 1024)
                        var done = already
                        var lastPct = -1
                        while (true) {
                            if (cancelDownload) throw InterruptedException("cancelada")
                            val n = input.read(buf)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else 0
                            if (pct != lastPct) {
                                lastPct = pct
                                progress = pct
                                onEvent(JSONObject().put("type", "llm_progress").put("pct", pct).put("mb", done / 1_048_576))
                            }
                        }
                    }
                }
                conn.disconnect()
                if (part.length() < MIN_BYTES) throw IllegalStateException("archivo incompleto (${part.length() / 1_048_576} MB)")
                part.renameTo(modelFile(ctx))
                downloading = false
                state = "downloaded"
                onEvent(JSONObject().put("type", "llm_status").put("state", state))
            } catch (e: Exception) {
                downloading = false
                state = if (e is InterruptedException) "absent" else "error"
                error = if (e is InterruptedException) "" else "Descarga interrumpida: ${e.message}. Se reanudará donde quedó."
                onEvent(JSONObject().put("type", "llm_status").put("state", state).put("error", error))
            }
        }.start()
    }

    fun cancel() {
        cancelDownload = true
    }

    fun deleteModel(ctx: Context) {
        unload()
        modelFile(ctx).delete()
        partFile(ctx).delete()
        state = "absent"
        progress = 0
    }

    // ---------- Carga y generación ----------

    private fun ensureLoaded(ctx: Context) {
        if (engine != null) return
        val f = modelFile(ctx)
        if (f.length() < MIN_BYTES) throw IllegalStateException("El modelo local no está descargado.")
        state = "loading"
        val t0 = SystemClock.elapsedRealtime()
        var lastErr: Throwable? = null
        for ((name, backend) in listOf("GPU" to Backend.GPU(), "CPU" to Backend.CPU())) {
            try {
                val cfg = EngineConfig(modelPath = f.absolutePath, backend = backend, cacheDir = ctx.cacheDir.path)
                val e = Engine(cfg)
                runBlocking { e.initialize() }
                engine = e
                backendName = name
                loadMs = SystemClock.elapsedRealtime() - t0
                state = "ready"
                error = ""
                return
            } catch (t: Throwable) {
                lastErr = t
            }
        }
        state = "error"
        error = "No se pudo cargar Gemma: ${lastErr?.message}"
        throw IllegalStateException(error)
    }

    /** Ejecuta una petición en segundo plano y devuelve el resultado por callback. */
    fun ask(ctx: Context, id: String, prompt: String, onEvent: (JSONObject) -> Unit) {
        worker.execute {
            val out = JSONObject().put("type", "llm").put("id", id)
            try {
                ensureLoaded(ctx)
                val t0 = SystemClock.elapsedRealtime()
                val raw = runBlocking {
                    engine!!.createConversation().use { conv -> conv.sendMessage(prompt).toString() }
                }
                val text = cleanText(raw)
                val ms = SystemClock.elapsedRealtime() - t0
                lastMs = ms
                lastTokPerSec = if (ms > 0) (text.length / 4.0) / (ms / 1000.0) else 0.0
                answered++
                out.put("ok", true).put("text", text).put("ms", ms).put("backend", backendName)
                    .put("tokPerSec", lastTokPerSec)
            } catch (t: Throwable) {
                out.put("ok", false).put("error", t.message ?: t.javaClass.simpleName)
            }
            onEvent(out)
        }
    }

    /** Si la librería devuelve el objeto Message en lugar del texto, extrae el texto. */
    private fun cleanText(raw: String): String {
        val m = Regex("""text\s*=\s*"?(.+?)"?\s*[,)\]]""", RegexOption.DOT_MATCHES_ALL).find(raw)
        return if (raw.startsWith("Message(") && m != null) m.groupValues[1] else raw
    }

    fun unload() {
        try {
            engine?.close()
        } catch (_: Exception) {
        }
        engine = null
        if (state == "ready") state = "downloaded"
    }
}
