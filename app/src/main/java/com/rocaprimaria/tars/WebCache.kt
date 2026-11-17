package com.rocaprimaria.tars

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Guarda en el teléfono las librerías que la web app descarga de internet
 * (Firebase, motor de voz neural y su modelo) para que funcionen sin conexión.
 */
object WebCache {
    private val HOSTS = setOf("www.gstatic.com", "cdnjs.cloudflare.com", "cdn.jsdelivr.net", "huggingface.co")
    private const val MAX_BYTES = 120L * 1024 * 1024

    private fun dir(ctx: Context) = File(ctx.filesDir, "webcache").apply { mkdirs() }

    private fun key(url: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun mime(url: String): String {
        val p = url.substringBefore('?').lowercase()
        return when {
            p.endsWith(".js") || p.endsWith(".mjs") -> "text/javascript"
            p.endsWith(".wasm") -> "application/wasm"
            p.endsWith(".json") -> "application/json"
            else -> "application/octet-stream"
        }
    }

    private fun response(file: File, url: String): WebResourceResponse {
        val r = WebResourceResponse(mime(url), null, FileInputStream(file))
        r.responseHeaders = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "max-age=31536000"
        )
        return r
    }

    /** Devuelve la copia guardada o la descarga y la guarda; null = que WebView lo maneje. */
    fun intercept(ctx: Context, request: WebResourceRequest): WebResourceResponse? {
        if (request.method != "GET") return null
        val url = request.url.toString()
        val host = request.url.host ?: return null
        if (host !in HOSTS) return null
        if (request.requestHeaders.keys.any { it.equals("Range", true) }) return null
        val f = File(dir(ctx), key(url))
        if (f.exists() && f.length() > 0) return response(f, url)
        return try {
            var u = URL(url)
            var conn: HttpURLConnection
            var hops = 0
            while (true) {
                conn = u.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                val code = conn.responseCode
                if (code in 300..399 && hops < 6) {
                    u = URL(u, conn.getHeaderField("Location"))
                    hops++
                    conn.disconnect()
                    continue
                }
                if (code != 200) {
                    conn.disconnect()
                    return null
                }
                break
            }
            if (conn.contentLengthLong > MAX_BYTES) {
                conn.disconnect()
                return null
            }
            val tmp = File(f.path + ".tmp")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            conn.disconnect()
            tmp.renameTo(f)
            response(f, url)
        } catch (_: Exception) {
            null
        }
    }

    fun sizeMB(ctx: Context): Long = (dir(ctx).listFiles()?.sumOf { it.length() } ?: 0L) / 1_048_576
}
