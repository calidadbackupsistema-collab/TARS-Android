package com.rocaprimaria.tars

import android.content.Context
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

/** Recibe comandos del Galaxy Watch y los entrega a TARS en el celular. */
class WatchBridgeService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == WatchLink.PATH_COMMAND) {
            val cmd = String(event.data, Charsets.UTF_8).trim()
            if (cmd.isEmpty()) return
            WatchLink.lastNode = event.sourceNodeId
            ListenService.dispatch(applicationContext, cmd, true)
        }
    }
}

/** Envía la respuesta de TARS de vuelta al reloj. */
object WatchLink {
    const val PATH_COMMAND = "/tars/command"
    const val PATH_REPLY = "/tars/reply"

    @Volatile var lastNode: String? = null

    fun sendReply(ctx: Context, text: String) {
        val data = text.take(3000).toByteArray(Charsets.UTF_8)
        val client = Wearable.getMessageClient(ctx)
        val node = lastNode
        if (node != null) {
            client.sendMessage(node, PATH_REPLY, data)
        } else {
            Wearable.getNodeClient(ctx).connectedNodes.addOnSuccessListener { nodes ->
                nodes.forEach { client.sendMessage(it.id, PATH_REPLY, data) }
            }
        }
    }
}
