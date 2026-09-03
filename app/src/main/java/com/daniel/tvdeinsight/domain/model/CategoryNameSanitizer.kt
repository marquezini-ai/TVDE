package com.daniel.tvdeinsight.domain.model

/** Normaliza categorias sem aceitar texto arbitrário do OCR. */
object CategoryNameSanitizer {
    private val ignoredLabels = setOf(
        "ii", "exclusivo", "exclusiva", "exclusive", "exclusif", "esclusivo",
        "esclusiva", "exklusiv", "exclusief", "oferta"
    )
    private val uberAudienceLabels = setOf(
        "teen", "teens", "teenager", "teenagers", "adolescente", "adolescentes"
    )
    private val decorativeBusinessLabels = setOf("business")
    private val tokenRegex = Regex("[\\p{L}\\p{N}]+")

    private val uberCategories = listOf(
        "Assist", "Black", "Comfort", "Electric", "Pacotes", "Share", "Uber Pet",
        "UberX", "UberX Priority", "UberXL"
    )
    private val boltCategories = listOf(
        "Bolt", "Economy", "Green", "Bolt Send", "Acessibilidade", "Bolt Pet",
        "Premium", "Comfort", "Pet"
    )

    /** Limpeza genérica para dados antigos; não escolhe uma categoria. */
    fun clean(value: String?): String? = value
        ?.let { raw ->
            tokenRegex.findAll(raw)
                .map { it.value }
                .filterNot { foldToken(it) in ignoredLabels }
                .joinToString(" ")
        }
        ?.takeIf(String::isNotEmpty)

    fun cleanForPlatform(value: String?, platform: OfferPlatform): String? {
        if (value.isNullOrBlank()) return null
        val categories = when (platform) {
            OfferPlatform.UBER -> uberCategories
            OfferPlatform.BOLT -> boltCategories
            OfferPlatform.UNKNOWN -> return null
        }

        val sourceTokens = tokenRegex.findAll(value)
            .map { it.value }
            .map(::foldToken)
            .filterNot { it in ignoredLabels }
            .filterNot { platform == OfferPlatform.UBER && it in uberAudienceLabels }
            .filterNot { it in decorativeBusinessLabels }
            .toList()
        val withoutLeadingNumbers = sourceTokens.dropWhile { it.all(Char::isDigit) }
        val sourceCompact = withoutLeadingNumbers.joinToString("")
        if (sourceCompact.isBlank()) return null

        return categories
            .sortedByDescending { compact(it).length }
            .firstOrNull { category ->
                val canonicalCompact = compact(category)
                sourceCompact == canonicalCompact ||
                    sourceCompact == "uber$canonicalCompact" ||
                    (platform == OfferPlatform.BOLT && sourceCompact == "bolt$canonicalCompact")
            }
    }

    private fun compact(value: String): String =
        tokenRegex.findAll(value).joinToString("") { foldToken(it.value) }

    private fun foldToken(value: String): String =
        java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(java.util.Locale.ROOT)
}
