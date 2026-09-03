package com.daniel.tvdeinsight.service.ocr

/**
 * Serializa pedidos de captura e respeita o intervalo mínimo imposto pelo Android.
 *
 * A classe não depende do Android para que as regras de concorrência e frequência
 * possam ser verificadas por testes unitários. Todos os acessos são feitos pela
 * thread principal do serviço de acessibilidade.
 */
internal class OcrCaptureGate(
    private val minimumIntervalMs: Long
) {
    sealed interface Admission {
        data object Allowed : Admission
        data object Busy : Admission
        data class TooSoon(val retryAfterMs: Long) : Admission
    }

    private var inFlight = false
    private var lastStartedAtMs: Long? = null

    fun tryAcquire(nowMs: Long): Admission {
        if (inFlight) return Admission.Busy

        val previous = lastStartedAtMs
        if (previous != null) {
            val retryAfter = minimumIntervalMs - (nowMs - previous)
            if (retryAfter > 0L) return Admission.TooSoon(retryAfter)
        }

        inFlight = true
        lastStartedAtMs = nowMs
        return Admission.Allowed
    }

    fun release() {
        inFlight = false
    }

    fun isActive(): Boolean = inFlight
}
