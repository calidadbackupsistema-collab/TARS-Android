package com.rocaprimaria.tars.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/** Si la pantalla de TARS no está abierta, muestra la respuesta como notificación en el reloj. */
class ReplyService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WatchActivity.PATH_REPLY) return
        val text = String(event.data, Charsets.UTF_8)
        WatchActivity.lastReply = text
        if (WatchActivity.visible) return

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel("tars_reply") == null) {
            nm.createNotificationChannel(NotificationChannel("tars_reply", "Respuestas de TARS", NotificationManager.IMPORTANCE_HIGH))
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, WatchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, "tars_reply")
            .setSmallIcon(R.drawable.ic_stat_tars)
            .setContentTitle("TARS")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(1, n)
        } catch (_: SecurityException) {
        }
        vibrate(this)
    }
}
