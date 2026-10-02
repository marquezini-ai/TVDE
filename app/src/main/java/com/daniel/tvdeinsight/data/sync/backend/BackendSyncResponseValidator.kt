package com.daniel.tvdeinsight.data.sync.backend

data class ValidatedEventResults(
    val sent: List<String>,
    val rejected: Map<String, String>
)

object BackendSyncResponseValidator {
    fun validate(
        response: BackendSyncResponse,
        expectedEventIds: List<String>
    ): ValidatedEventResults? {
        val resultsById = response.eventResults.associateBy { it.eventId }
        if (resultsById.size != response.eventResults.size || resultsById.keys != expectedEventIds.toSet()) {
            return null
        }
        val sent = response.eventResults
            .filter { it.status == "ACCEPTED" || it.status == "DUPLICATE" }
            .map { it.eventId }
        val rejected = response.eventResults
            .filter { it.status == "REJECTED" }
            .associate { it.eventId to (it.code ?: "EVENT_REJECTED") }
        return if (sent.size + rejected.size == expectedEventIds.size) {
            ValidatedEventResults(sent, rejected)
        } else null
    }

    fun invalidatesCursor(code: String): Boolean = code == "CURSOR_EXPIRED" || code == "INVALID_CURSOR"
}
