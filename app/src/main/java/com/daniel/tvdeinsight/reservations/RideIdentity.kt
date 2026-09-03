package com.daniel.tvdeinsight.reservations

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/** ID determinístico da oferta; não muda quando a lista é apenas atualizada. */
object RideIdentity {
    fun forCandidate(candidate: RideCandidate): String {
        val stableData = listOf(
            candidate.tripDate,
            candidate.timeText.ifBlank { "%02d:%02d".format(Locale.US, candidate.startMinutes / 60, candidate.startMinutes % 60) },
            candidate.displayedCategory.ifBlank { candidate.category },
            "%.4f".format(Locale.US, candidate.payout),
            "%.4f".format(Locale.US, candidate.distanceKm)
        ).joinToString("|") { AccessibilityNodeUtils.normalizeForComparison(it) }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(stableData.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
        return "RB-${digest.take(12).uppercase()}"
    }
}
