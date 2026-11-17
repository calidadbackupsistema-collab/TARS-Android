package com.rocaprimaria.tars

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Locale

/**
 * Pantalla principal: muestra la web app de TARS (GitHub Pages) y le da un puente nativo
 * para escuchar en segundo plano, hablar con la voz del teléfono y enviar respuestas al reloj.
 */
class MainActivity : Activity() {

    companion object {
        const val PAGE_HOST = "calidadbackupsistema-collab.github.io"
        const val PAGE_URL = "https://calidadbackupsistema-collab.github.io/Asistente-Personal/"
        const val EXTRA_CMD = "tars_cmd"
        const val EXTRA_FROM_WATCH = "tars_from_watch"
        private const val REQ_AUDIO = 11
        private const val PREFS = "tars"

        @Volatile var current: MainActivity? = null
        @Volatile var visible = false

        /** Envía un evento a la web app (si está abierta). */
        fun sendEvent(json: JSONObject) {
            current?.js("window.__tarsNative&&window.__tarsNative.onEvent(" + json.toString() + ")")
        }
    }

    private lateinit var web: WebView
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pageReady = false
    private var wantListen = false
    private val pending = ArrayList<Pair<String, Boolean>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#233B52"))
        setContentView(web)

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.allowFileAccess = false
        s.userAgentString = s.userAgentString + " TARSAndroid/1"

        web.addJavascriptInterface(Bridge(), "TarsNative")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.scheme == "https" && u.host == PAGE_HOST) return false
                openExternal(u)
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                flushPending()
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }
        }

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(PAGE_URL)

        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale("es", "MX")
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        js("window.__nativeSpeakDone&&window.__nativeSpeakDone()")
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        js("window.__nativeSpeakDone&&window.__nativeSpeakDone()")
                    }
                })
            }
        }

        handleIntent(intent)
        firstRunSetup()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        visible = true
    }

    override fun onPause() {
        super.onPause()
        visible = false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        if (current === this) current = null
        tts?.shutdown()
        web.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else moveTaskToBack(true)
    }

    // ---------- Comandos que llegan del servicio de escucha o del reloj ----------

    private fun handleIntent(i: Intent?) {
        val cmd = i?.getStringExtra(EXTRA_CMD) ?: return
        val fromWatch = i.getBooleanExtra(EXTRA_FROM_WATCH, false)
        i.removeExtra(EXTRA_CMD)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        deliverCommand(cmd, fromWatch)
    }

    fun deliverCommand(cmd: String, fromWatch: Boolean) {
        runOnUiThread {
            if (pageReady) runCommand(cmd, fromWatch) else pending.add(cmd to fromWatch)
        }
    }

    private fun flushPending() {
        val copy = ArrayList(pending)
        pending.clear()
        copy.forEach { runCommand(it.first, it.second) }
    }

    private fun runCommand(cmd: String, fromWatch: Boolean) {
        val c = JSONObject.quote(cmd)
        js("(function f(n){if(window.__tarsNativeCommand){window.__tarsNativeCommand($c,$fromWatch)}else if(n<60){setTimeout(function(){f(n+1)},250)}})(0)")
    }

    fun js(code: String) {
        runOnUiThread {
            try {
                web.evaluateJavascript(code, null)
            } catch (_: Exception) {
            }
        }
    }

    // ---------- Escucha ----------

    private fun startListening() {
        wantListen = true
        val need = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), REQ_AUDIO)
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, ListenService::class.java))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_AUDIO) return
        val audioOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (audioOk && wantListen) {
            ContextCompat.startForegroundService(this, Intent(this, ListenService::class.java))
        } else if (!audioOk) {
            sendEvent(
                JSONObject().put("type", "status").put("listening", false)
                    .put("text", "Sin permiso de micrófono. Actívalo en Ajustes › Apps › TARS › Permisos.")
            )
        }
    }

    // ---------- Primer uso: permisos que hacen que TARS funcione en segundo plano ----------

    private fun firstRunSetup() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("setup_done", false)) return
        prefs.edit().putBoolean("setup_done", true).apply()
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("TARS en segundo plano")
            .setMessage(
                "Para que TARS te escuche con la pantalla apagada y se abra cuando le hablas:\n\n" +
                    "1. Permite «Mostrar sobre otras apps».\n" +
                    "2. Quita la optimización de batería para TARS.\n\n" +
                    "Puedes hacerlo después desde Cerebro, voz y contactos."
            )
            .setPositiveButton("Configurar") { _, _ -> openOverlaySettings(); pendingBattery = true }
            .setNegativeButton("Después", null)
            .show()
    }

    private var pendingBattery = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && pendingBattery) {
            pendingBattery = false
            openBatterySettings()
        }
    }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            tryStart(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun openBatterySettings() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            tryStart(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    private fun tryStart(i: Intent) {
        try {
            startActivity(i)
        } catch (_: Exception) {
        }
    }

    private fun openExternal(u: Uri) {
        val i = Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(i)
        } catch (_: ActivityNotFoundException) {
            sendEvent(JSONObject().put("type", "toast").put("text", "No hay una app para abrir: $u"))
        } catch (_: Exception) {
        }
    }

    // ---------- Puente JavaScript <-> Android ----------

    inner class Bridge {
        @JavascriptInterface
        fun isNative(): Boolean = true

        @JavascriptInterface
        fun setListening(on: Boolean) {
            runOnUiThread {
                if (on) startListening() else {
                    wantListen = false
                    ListenService.stop(this@MainActivity)
                }
            }
        }

        @JavascriptInterface
        fun listenNow() {
            val svc = ListenService.instance
            if (svc != null) svc.awaitCommand() else runOnUiThread { startListening() }
        }

        @JavascriptInterface
        fun setSpeaking(on: Boolean) {
            ListenService.instance?.setPaused(on)
        }

        @JavascriptInterface
        fun setWakeWords(csv: String) {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("wake", csv).apply()
            ListenService.instance?.loadWakeWords()
        }

        @JavascriptInterface
        fun speak(text: String, lang: String) {
            val t = tts
            if (t == null || !ttsReady) {
                js("window.__nativeSpeakDone&&window.__nativeSpeakDone()")
                return
            }
            t.language = if (lang.startsWith("en")) Locale.US else Locale("es", "MX")
            t.setPitch(0.85f)
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tars-" + System.currentTimeMillis())
        }

        @JavascriptInterface
        fun stopSpeak() {
            tts?.stop()
        }

        @JavascriptInterface
        fun onReply(text: String) {
            if (ListenService.lastFromWatch) {
                ListenService.lastFromWatch = false
                WatchLink.sendReply(applicationContext, text)
            }
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            runOnUiThread { openExternal(Uri.parse(url)) }
        }

        @JavascriptInterface
        fun openSettings(what: String) {
            runOnUiThread {
                when (what) {
                    "overlay" -> openOverlaySettings()
                    "battery" -> openBatterySettings()
                    else -> tryStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            }
        }

        @JavascriptInterface
        fun status(): String {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            return JSONObject()
                .put("listening", ListenService.running)
                .put("overlay", Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this@MainActivity))
                .put("battery", pm.isIgnoringBatteryOptimizations(packageName))
                .put("version", BuildConfigInfo.version(this@MainActivity))
                .toString()
        }
    }
}

object BuildConfigInfo {
    fun version(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }
}
