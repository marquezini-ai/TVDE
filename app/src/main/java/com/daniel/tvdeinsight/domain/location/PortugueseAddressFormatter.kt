package com.daniel.tvdeinsight.domain.location

/** Regras comuns de apresentação e segmentação das moradas portuguesas. */
object PortugueseAddressFormatter {
    /** Retira apenas um país no fim, sem alterar nomes como "Portugal Norte Shopping". */
    fun withoutCountry(address: String?): String? {
        var result = address?.trim()?.takeIf(String::isNotEmpty) ?: return null
        while (TRAILING_COUNTRY.containsMatchIn(result)) {
            result = result.replace(TRAILING_COUNTRY, "").trim().trimEnd(',').trim()
        }
        return result.takeIf(String::isNotEmpty)
    }

    /**
     * Segmento definido para o ranking: depois da última vírgula e antes do
     * primeiro algarismo. Sem vírgula ou sem texto útil, a morada é descartada.
     */
    fun lastLocalityBeforeNumber(address: String?): String? {
        val addressWithoutCountry = withoutCountry(address) ?: return null
        if (',' !in addressWithoutCountry) return null

        val lastSegment = addressWithoutCountry.substringAfterLast(',').trim()
        val firstNumber = lastSegment.indexOfFirst(Char::isDigit)
        val beforeNumber = if (firstNumber >= 0) lastSegment.substring(0, firstNumber) else lastSegment
        return beforeNumber.trim().trim(',', '-', ';').trim().takeIf(String::isNotEmpty)
    }

    private val TRAILING_COUNTRY = Regex(
        "(?:\\s*,\\s*|\\s+)Portugal(?:\\s+Continental)?\\s*$",
        RegexOption.IGNORE_CASE
    )
}
