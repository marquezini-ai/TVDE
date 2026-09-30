package com.daniel.tvdeinsight.service.notification

import android.content.Intent
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.os.SystemClock
import com.daniel.tvdeinsight.logging.AppLogger

/**
 * Sinal de baixo consumo: recebe a publicação de uma notificação da Bolt e
 * acorda uma única leitura da árvore de acessibilidade. A notificação nunca é
 * usada como fonte dos valores da viagem; o card Bolt continua a ser a fonte
 * estruturada e validada.
 */
class BoltOfferNotificationListener : NotificationListenerService() {

    private var lastOfferNotificationFingerprint: String? = null
    private var lastOfferNotificationAt = 0L
    private var lastPostedCallbackFingerprint: String? = null
    private var lastPostedCallbackAt = 0L

    override fun onListenerConnected() {
        super.onListenerConnected()
        AppLogger.info("Listener de notificações Bolt conectado")
    }

    override fun onListenerDisconnected() {
        AppLogger.warn("Listener de notificações Bolt desconectado pelo Android")
        requestRebind(ComponentName(this, BoltOfferNotificationListener::class.java))
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(notification: StatusBarNotification) {
        if (notification.packageName != BOLT_PACKAGE_NAME) return

        val item = notification.notification
        val extras = item.extras
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)
            ?.toString()
            ?.sanitizeForLog()
            .orEmpty()
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)
            ?.toString()
            ?.sanitizeForLog()
            .orEmpty()
        val bigText = extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)
            ?.toString()
            ?.sanitizeForLog()
            .orEmpty()
        val isOngoing = item.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0
        val signalId = "${notification.key}:${notification.postTime}"
        val channelId = item.channelId.orEmpty()
        val now = SystemClock.elapsedRealtime()
        val callbackFingerprint = listOf(
            notification.key,
            notification.id,
            notification.postTime,
            channelId,
            title,
            text,
            bigText,
            item.flags
        ).joinToString("|")
        // Samsung may deliver the exact same StatusBarNotification callback
        // more than once. Coalesce before logging and before offer filtering.
        if (callbackFingerprint == lastPostedCallbackFingerprint &&
            now - lastPostedCallbackAt < POSTED_CALLBACK_COALESCE_MS
        ) return
        lastPostedCallbackFingerprint = callbackFingerprint
        lastPostedCallbackAt = now

        // Este registo permite validar no dispositivo o texto real e a ordem
        // temporal da notificação sem escrever extras completos ou actions.
        AppLogger.info(
            "Notificação Bolt publicada: id=${notification.id}, canal=$channelId, " +
                "ongoing=$isOngoing, categoria=${item.category.orEmpty()}, título='$title', " +
                "texto='$text', grande='$bigText'"
        )

        // A Bolt usa o mesmo canal ongoing para o estado online e para a
        // oferta real ("Novo pedido de viagem"). Classificar antes de filtrar
        // ongoing evita descartar justamente o gatilho da oferta.
        if (!isOfferNotification(channelId, title, text, bigText)) {
            if (isOngoing) {
                AppLogger.debug("Notificação Bolt contínua ignorada sem conteúdo de oferta: canal=$channelId, id=${notification.id}")
                return
            }
            AppLogger.debug("Notificação Bolt ignorada como não-oferta: canal=$channelId, id=${notification.id}")
            return
        }

        // A Bolt atualiza a mesma notificação várias vezes durante a abertura
        // do cartão (mesmo id/chave e texto genérico). Essas atualizações não
        // representam novas viagens e não devem iniciar leituras repetidas.
        // A mesma notificação é republicada várias vezes pelo Android com o
        // mesmo id/texto. O postTime distingue uma oferta nova que reutiliza
        // esse id de uma duplicação do mesmo evento.
        val fingerprint = listOf(notification.key, notification.id, notification.postTime, title, text, bigText)
            .joinToString("|")
        if (
            fingerprint == lastOfferNotificationFingerprint &&
                now - lastOfferNotificationAt < OFFER_NOTIFICATION_UPDATE_DEBOUNCE_MS
        ) {
            AppLogger.debug("Notificação Bolt duplicada ignorada: id=${notification.id}")
            return
        }
        lastOfferNotificationFingerprint = fingerprint
        lastOfferNotificationAt = now

        sendBroadcast(
            Intent(BoltNotificationSignal.ACTION_BOLT_NOTIFICATION_POSTED)
                .setPackage(packageName)
                .putExtra(BoltNotificationSignal.EXTRA_SIGNAL_ID, signalId)
                .putExtra(BoltNotificationSignal.EXTRA_POST_TIME_MS, notification.postTime)
        )
    }

    override fun onNotificationRemoved(notification: StatusBarNotification) {
        if (notification.packageName == BOLT_PACKAGE_NAME) {
            AppLogger.debug("Notificação Bolt removida: id=${notification.id}")
        }
    }

    private fun String.sanitizeForLog(): String =
        replace(Regex("\\s+"), " ").trim().take(MAX_LOG_TEXT_LENGTH)

    private fun isOfferNotification(
        channelId: String,
        title: String,
        text: String,
        bigText: String
    ): Boolean {
        if (channelId !in OFFER_CHANNELS) return false
        val content = "$title $text $bigText"
        // O canal de execução também publica o estado online. A expressão
        // genérica de oferta não pode classificar esse estado como viagem.
        if (ONLINE_STATUS_WORDING.containsMatchIn(content)) return false
        // Bolt currently uses the running channel on Android 16 and the new-order
        // channel on some versions. Require offer wording on both channels so
        // online/foreground-service status updates never wake the reader.
        return OFFER_WORDING.containsMatchIn(content)
    }

    private companion object {
        const val BOLT_PACKAGE_NAME = "ee.mtakso.driver"
        const val NEW_ORDER_CHANNEL = "new_order_notifications_v2"
        const val RUNNING_CHANNEL = "taxify_driver_running_notifications"
        const val MAX_LOG_TEXT_LENGTH = 160
        const val OFFER_NOTIFICATION_UPDATE_DEBOUNCE_MS = 750L
        const val POSTED_CALLBACK_COALESCE_MS = 2_000L
        val OFFER_CHANNELS = setOf(NEW_ORDER_CHANNEL, RUNNING_CHANNEL)
        val OFFER_WORDING = Regex(
            "pedido|viagem|oferta|corrida|order|ride|trip|request",
            RegexOption.IGNORE_CASE
        )
        val ONLINE_STATUS_WORDING = Regex(
            "est[aá]\\s+online|[àa]\\s+espera\\s+de\\s+pedidos|sem\\s+pedidos|aguardando\\s+pedidos",
            RegexOption.IGNORE_CASE
        )
    }
}

object BoltNotificationSignal {
    const val ACTION_BOLT_NOTIFICATION_POSTED =
        "com.daniel.tvdeinsight.action.BOLT_NOTIFICATION_POSTED"
    const val EXTRA_SIGNAL_ID = "signal_id"
    const val EXTRA_POST_TIME_MS = "post_time_ms"
}
