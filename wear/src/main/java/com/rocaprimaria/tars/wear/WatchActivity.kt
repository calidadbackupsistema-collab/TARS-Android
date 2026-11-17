package com.rocaprimaria.tars.wear

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import java.util.Locale

/** Pantalla del reloj: toca el micrófono, habla, y TARS responde desde el celular. */
class WatchActivity : Activity(), MessageClient.OnMessageReceivedListener {

    companion object {
        const val PATH_COMMAND = "/tars/command"
        const val PATH_REPLY = "/tars/reply"
        private const val REQ_SPEECH = 21
        @Volatile var lastReply: String? = null
        @Volatile var visible = false
    }

    private lateinit var status: TextView
    private lateinit var reply: TextView
    private lateinit var input: EditText
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#0D1720"))

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(dp(22f), dp(26f), dp(22f), dp(30f))

        val title = TextView(this)
        title.text = "TARS"
        title.setTextColor(Color.parseColor("#F3F8FD"))
        title.textSize = 15f
        title.letterSpacing = 0.35f
        title.gravity = Gravity.CENTER
        col.addView(title)

        val mic = TextView(this)
        mic.text = "🎙"
        mic.textSize = 26f
        mic.gravity = Gravity.CENTER
        val circle = GradientDrawable()
        circle.shape = GradientDrawable.OVAL
        circle.setColor(Color.parseColor("#1B3A4C"))
        circle.setStroke(dp(2f), Color.parseColor("#69EEF3"))
        mic.background = circle
        val micLp = LinearLayout.LayoutParams(dp(68f), dp(68f))
        micLp.topMargin = dp(10f)
        micLp.bottomMargin = dp(8f)
        mic.layoutParams = micLp
        mic.setOnClickListener { listen() }
        col.addView(mic)

        status = TextView(this)
        status.setTextColor(Color.parseColor("#A8C0CE"))
        status.textSize = 11f
        status.gravity = Gravity.CENTER
        status.text = "Toca y habla"
        col.addView(status)

        input = EditText(this)
        input.hint = "Escribe un comando"
        input.setTextColor(Color.WHITE)
        input.setHintTextColor(Color.parseColor("#7D8896"))
        input.textSize = 12f
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        input.setSingleLine(true)
        input.visibility = View.GONE
        input.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND || (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                val t = v.text.toString().trim()
                if (t.isNotEmpty()) send(t)
                v.text = ""
                true
            } else false
        }
        col.addView(input)

        reply = TextView(this)
        reply.setTextColor(Color.parseColor("#F3F8FD"))
        reply.textSize = 13f
        reply.gravity = Gravity.CENTER
        reply.setLineSpacing(0f, 1.15f)
        reply.typeface = Typeface.DEFAULT
        val rLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        rLp.topMargin = dp(10f)
        reply.layoutParams = rLp
        col.addView(reply)

        scroll.addView(col)
        root.addView(scroll)
        setContentView(root)

        lastReply?.let { reply.text = it }

        tts = TextToSpeech(this) { s ->
            ttsReady = s == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale("es", "MX")
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5)
        }
    }

    override fun onResume() {
        super.onResume()
        visible = true
        Wearable.getMessageClient(this).addListener(this)
        lastReply?.let { reply.text = it }
    }

    override fun onPause() {
        visible = false
        Wearable.getMessageClient(this).removeListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    private fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Dime…")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_SPEECH)
        } catch (_: ActivityNotFoundException) {
            status.text = "Sin dictado en el reloj: escribe el comando"
            input.visibility = View.VISIBLE
            input.requestFocus()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SPEECH || resultCode != RESULT_OK) return
        val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (!text.isNullOrEmpty()) send(text)
    }

    private fun send(text: String) {
        status.text = "«$text»\nEnviando…"
        val bytes = text.toByteArray(Charsets.UTF_8)
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    status.text = "No encuentro el celular. Revisa la conexión Bluetooth."
                    return@addOnSuccessListener
                }
                val client = Wearable.getMessageClient(this)
                nodes.forEach { client.sendMessage(it.id, PATH_COMMAND, bytes) }
                status.text = "«$text»\nPensando…"
            }
            .addOnFailureListener { status.text = "No pude enviar el comando." }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PATH_REPLY) return
        val text = String(event.data, Charsets.UTF_8)
        lastReply = text
        runOnUiThread {
            reply.text = text
            status.text = "Toca y habla"
            vibrate(this)
            if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tars-reply")
        }
    }
}

fun vibrate(ctx: Context) {
    try {
        val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        v.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
    } catch (_: Exception) {
    }
}
