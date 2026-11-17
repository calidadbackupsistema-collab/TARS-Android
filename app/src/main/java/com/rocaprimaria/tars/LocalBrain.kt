package com.rocaprimaria.tars

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Cerebro sin internet: Gemma 4 E2B corriendo en el teléfono con LiteRT-LM.
 * El modelo (~2.6 GB) se descarga una sola vez con el administrador de descargas de Android.
 */
object LocalBrain {
    private const val FILE = "gemma-4-E2B-it.litertlm"
    private const val URL =
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
    private const val MIN_BYTES = 2_000_000_000L
    private const val NEED_FREE = 2_900_000_000L
    private const val PREFS = "tars"

    private val exec = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var engine: Engine? = null
    @Volatile private var engineState = "idle" // idle | loading | ready | error
    @Volatile private var backendName = ""
    @Volatile private var lastError = ""
    private var idleClose: ScheduledFuture<*>? = null

    private fun dir(ctx: Context): File = ctx.getExternalFilesDir(null) ?: ctx.filesDir
    private fun modelFile(ctx: Context) = File(dir(ctx), FILE)
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun dm(ctx: Context) = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    fun status(ctx: Context): String {
        val o = JSONObject()
        val id = prefs(ctx).getLong("gemma_dl", -1L)
        if (id >= 0) {
            dm(ctx).query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (c.moveToFirst()) {
                    val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    when (st) {
                        DownloadManager.STATUS_SUCCESSFUL -> prefs(ctx).edit().remove("gemma_dl").apply()
                        DownloadManager.STATUS_FAILED -> {
                            prefs(ctx).edit().remove("gemma_dl").apply()
                            return o.put("state", "error").put("text", "La descarga falló (código $reason). Intenta de nuevo con Wi-Fi.").toString()
                        }
                        else -> {
                            val waiting = st == DownloadManager.STATUS_PAUSED && reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI
                            return o.put("state", "downloading")
                                .put("done", done).put("total", if (total > 0) total else 2_588_147_712L)
                                .put("text", if (waiting) "Esperando Wi-Fi para seguir descargando." else "")
                                .toString()
                        }
                    }
                } else {
                    prefs(ctx).edit().remove("gemma_dl").apply()
                }
            }
        }
        val f = modelFile(ctx)
        if (f.exists() && f.length() > MIN_BYTES) {
            o.put("state", "ready").put("engine", engineState).put("backend", backendName).put("size", f.length())
            if (engineState == "error") o.put("text", lastError)
            return o.toString()
        }
        return o.put("state", "none").put("free", freeBytes(ctx)).toString()
    }

    private fun freeBytes(ctx: Context): Long = try {
        StatFs(dir(ctx).path).availableBytes
    } catch (_: Exception) {
        -1L
    }

    /** Inicia la descarga. Devuelve un mensaje de error o "" si arrancó. */
    fun download(ctx: Context, allowMobile: Boolean): String {
        if (prefs(ctx).getLong("gemma_dl", -1L) >= 0) return ""
        val free = freeBytes(ctx)
        if (free in 0 until NEED_FREE) {
            return "Necesito unos 2.9 GB libres y hay " + String.format("%.1f", free / 1e9) + " GB."
        }
        modelFile(ctx).delete()
        val req = DownloadManager.Request(Uri.parse(URL))
            .setTitle("Cerebro de TARS (Gemma 4)")
            .setDescription("Descarga única para pensar sin internet")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(allowMobile)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(ctx, null, FILE)
        return try {
            val id = dm(ctx).enqueue(req)
            prefs(ctx).edit().putLong("gemma_dl", id).apply()
            ""
        } catch (e: Exception) {
            "No se pudo iniciar la descarga: " + (e.message ?: "error")
        }
    }

    fun cancel(ctx: Context) {
        val id = prefs(ctx).getLong("gemma_dl", -1L)
        if (id >= 0) dm(ctx).remove(id)
        prefs(ctx).edit().remove("gemma_dl").apply()
    }

    fun delete(ctx: Context) {
        cancel(ctx)
        exec.execute {
            closeEngine()
            modelFile(ctx).delete()
        }
    }

    private fun closeEngine() {
        try {
            engine?.close()
        } catch (_: Exception) {
        }
        engine = null
        engineState = "idle"
    }

    /** Carga el motor. Intenta GPU; si alguna vez se cerró la app al intentarlo, usa CPU. */
    private fun ensureEngine(ctx: Context): Engine {
        engine?.let { return it }
        engineState = "loading"
        val path = modelFile(ctx).absolutePath
        val cache = ctx.cacheDir.path
        val p = prefs(ctx)
        val gpuOk = !p.getBoolean("gemma_gpu_bad", false)
        if (gpuOk) {
            p.edit().putBoolean("gemma_gpu_bad", true).commit() // si la app muere aquí, la próxima vez va directo a CPU
            try {
                val e = Engine(EngineConfig(modelPath = path, backend = Backend.GPU(), cacheDir = cache))
                e.initialize()
                p.edit().putBoolean("gemma_gpu_bad", false).apply()
                engine = e
                backendName = "GPU"
                engineState = "ready"
                return e
            } catch (_: Throwable) {
                // GPU no disponible: seguimos con CPU (la marca queda para no reintentar)
            }
        }
        try {
            val e = Engine(EngineConfig(modelPath = path, backend = Backend.CPU(), cacheDir = cache))
            e.initialize()
            engine = e
            backendName = "CPU"
            engineState = "ready"
            return e
        } catch (t: Throwable) {
            engineState = "error"
            lastError = "No pude cargar Gemma: " + (t.message ?: t.javaClass.simpleName)
            throw t
        }
    }

    /** Precarga el motor en segundo plano para que la primera respuesta sin internet sea más rápida. */
    fun warmUp(ctx: Context) {
        val f = modelFile(ctx)
        if (!f.exists() || f.length() < MIN_BYTES || engine != null) return
        exec.execute {
            try {
                ensureEngine(ctx)
                scheduleIdleClose()
            } catch (_: Throwable) {
            }
        }
    }

    private fun scheduleIdleClose() {
        idleClose?.cancel(false)
        idleClose = exec.schedule({ closeEngine() }, 10, TimeUnit.MINUTES)
    }

    /**
     * Genera una respuesta. historyJson: [{"role":"user|assistant","text":"..."}], el último es del usuario.
     */
    fun generate(ctx: Context, system: String, historyJson: String, cb: (Boolean, String) -> Unit) {
        exec.execute {
            try {
                val f = modelFile(ctx)
                if (!f.exists() || f.length() < MIN_BYTES) {
                    cb(false, "El cerebro sin internet no está descargado.")
                    return@execute
                }
                val e = ensureEngine(ctx)
                val arr = JSONArray(historyJson)
                val initial = ArrayList<Message>()
                var last = ""
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val text = m.optString("text")
                    if (i == arr.length() - 1) {
                        last = text
                    } else if (m.optString("role") == "user") {
                        initial.add(Message.user(text))
                    } else {
                        initial.add(Message.model(text))
                    }
                }
                val config = ConversationConfig(
                    systemInstruction = Contents.of(system),
                    initialMessages = initial,
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.6),
                )
                val conv = e.createConversation(config)
                val reply = try {
                    conv.sendMessage(last).toString()
                } finally {
                    try {
                        conv.close()
                    } catch (_: Exception) {
                    }
                }
                scheduleIdleClose()
                cb(true, reply)
            } catch (t: Throwable) {
                cb(false, t.message ?: t.javaClass.simpleName)
            }
        }
    }
}
