package com.daniel.tvdeinsight.service.accessibility

/** Extrai a portagem mostrada pela Bolt, por exemplo: "Portagem • 0,40 €". */
internal object TollAmountExtractor {
    private val tollRegex = Regex(
        "\\bportagem\\b[^\\d]{0,20}(\\d+(?:[,.]\\d{1,2})?)\\s*(?:€|eur(?:os)?)?",
        RegexOption.IGNORE_CASE
    )

    fun extract(cardText: String): Double = tollRegex.find(cardText)
        ?.groupValues
        ?.getOrNull(1)
        ?.replace(',', '.')
        ?.toDoubleOrNull()
        ?.takeIf { it >= 0.0 }
        ?: 0.0
}
