package com.daniel.tvdeinsight.service.ocr

import com.google.mlkit.vision.text.Text
import com.daniel.tvdeinsight.domain.model.CategoryNameSanitizer
import com.daniel.tvdeinsight.domain.model.OfferPlatform
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Isola um único cartão Uber usando a estrutura visual do próprio cartão.
 *
 * O preço é sempre procurado imediatamente acima da âncora "Após dedução de
 * taxa de serviço". A categoria é o primeiro grupo de texto do cabeçalho,
 * junto ao ícone de passageiro. A categoria só é aceite quando pertence à
 * lista Uber suportada; texto desconhecido fica vazio, evitando categorias
 * inventadas pelo OCR.
 */
class UberOfferCardTextExtractor {

    /** Card encontrado no frame. A apresentação do overlay não depende do OCR. */
    data class ExtractedCard(
        val text: String,
        val category: String?
    )

    fun extract(visionText: Text): String? = extractCard(visionText)?.text

    fun extractCard(visionText: Text): ExtractedCard? {
        val lineBlocks = visionText.textBlocks.flatMap { block ->
            val lines = block.lines.mapNotNull { line ->
                line.boundingBox?.let { bounds ->
                    OcrBlock(line.text, bounds.top, bounds.bottom, bounds.left, bounds.right)
                }
            }
            lines.ifEmpty {
                block.boundingBox?.let { bounds ->
                    listOf(OcrBlock(block.text, bounds.top, bounds.bottom, bounds.left, bounds.right))
                }.orEmpty()
            }
        }
        val tokens = visionText.textBlocks.flatMap { block ->
            block.lines.flatMap { line ->
                line.elements.mapNotNull { element ->
                    element.boundingBox?.let { bounds ->
                        OcrToken(element.text, bounds.top, bounds.bottom, bounds.left, bounds.right)
                    }
                }
            }
        }
        val candidate = extractCandidate(lineBlocks) ?: return null
        // O ML Kit normalmente mantém o nome dentro do botão preto numa linha
        // independente. Essa linha é a fonte mais segura: reconstruir primeiro
        // pelos elementos poderia anexar um selo vizinho (por exemplo,
        // "Exclusivo") quando ambos aparecem à mesma altura.
        val category = candidate.category ?: extractCategoryFromTokens(tokens, candidate.priceBlock)
        return ExtractedCard(candidate.text, category)
    }

    /** Mantido para testes puros, sem depender do runtime do ML Kit. */
    fun extract(blocks: List<OcrBlock>): String? = extractCandidate(blocks)?.text

    internal fun extractCard(blocks: List<OcrBlock>): ExtractedCard? =
        extractCandidate(blocks)?.let { ExtractedCard(it.text, it.category) }

    private fun extractCandidate(blocks: List<OcrBlock>): Candidate? {
        if (blocks.isEmpty()) return null
        val ordered = blocks.asSequence()
            .filter { it.text.isNotBlank() }
            .sortedWith(compareBy<OcrBlock> { it.top }.thenBy { it.left }.thenBy { it.bottom })
            .toList()
        val anchored = ordered.indices
            .mapNotNull { serviceFeeAnchorAt(it, ordered) }
            .mapNotNull { candidateFor(it, ordered) }
        val candidates = anchored.ifEmpty { fallbackCandidates(ordered) }
        return candidates.maxWithOrNull(compareBy<Candidate> { it.score }.thenBy { it.text.length })
    }

    private fun candidateFor(anchor: OcrBlock, blocks: List<OcrBlock>): Candidate? {
        val price = blocks.asSequence()
            .filter {
                it.top < anchor.top &&
                    it.bottom <= anchor.top + OCR_LINE_OVERLAP_TOLERANCE_PX &&
                    anchor.top - it.bottom <= MAX_PRICE_ANCHOR_DISTANCE_PX
            }
            .mapNotNull(::priceCandidate)
            .maxByOrNull { it.block.bottom }
            ?: return null
        val belowAnchor = blocks.filter { it.top >= anchor.bottom - OCR_LINE_OVERLAP_TOLERANCE_PX }
        val pickup = belowAnchor.firstRouteSegment { isPickupSegment(it.text) } ?: return null
        val trip = belowAnchor.firstRouteSegment {
            it.bottom >= pickup.top && isPassengerTripSegment(it.text)
        } ?: return null
        return candidateForRoute(price, pickup, trip, blocks, ANCHORED_CANDIDATE_SCORE)
    }

    private fun fallbackCandidates(blocks: List<OcrBlock>): List<Candidate> =
        blocks.mapIndexedNotNull { pickupIndex, pickup ->
            if (!isPickupSegment(pickup.text)) return@mapIndexedNotNull null
            val preceding = blocks.take(pickupIndex)
            val price = preceding.asSequence()
                .filter { pickup.top - it.bottom <= MAX_FALLBACK_PRICE_DISTANCE_PX }
                .mapNotNull(::priceCandidate)
                .filterNot { isBonusAmountNear(it.block, preceding) }
                .maxByOrNull { it.block.bottom }
                ?: return@mapIndexedNotNull null
            val trip = blocks.drop(pickupIndex + 1)
                .firstRouteSegment { isPassengerTripSegment(it.text) }
                ?: return@mapIndexedNotNull null
            candidateForRoute(price, pickup, trip, blocks, FALLBACK_CANDIDATE_SCORE)
        }

    private fun candidateForRoute(
        price: PriceCandidate,
        pickup: OcrBlock,
        trip: OcrBlock,
        blocks: List<OcrBlock>,
        score: Int
    ): Candidate? {
        val destinationAddressBlocks = blocks
            .dropWhile { it.bottom <= trip.bottom }
            .take(MAX_BLOCKS_SCANNED_AFTER_TRIP)
            .takeWhile { !isCardEndMarker(it.text) }
            .filter { looksLikeAddress(it.text) }
            .take(MAX_DESTINATION_ADDRESS_LINES)
        val routeEndBottom = destinationAddressBlocks.lastOrNull()?.bottom ?: trip.bottom
        val categoryBlock = findStructuralCategoryBlock(price.block, blocks)
        val cardStartTop = categoryBlock?.top ?: price.block.top
        val selected = blocks.asSequence()
            .filter { it.top >= cardStartTop && it.bottom <= routeEndBottom }
            .filterNot {
                it.bottom <= pickup.top && (isPriorityBonus(it.text) || isTvdeOverlayText(it.text))
            }
            .toList()
        val text = selected.joinToString("\n") { block ->
            if (block == price.block) canonicalPrice(price.value) else block.text.trim()
        }.trim()
        if (!PICKUP_SEGMENT_REGEX.containsMatchIn(normalize(text))) return null
        if (!PASSENGER_TRIP_SEGMENT_REGEX.containsMatchIn(normalize(text))) return null

        val category = categoryBlock?.text?.let(::sanitizeCategory)
        return Candidate(
            text = text,
            score = score + if (category != null) CATEGORY_SCORE_BONUS else 0,
            priceBlock = price.block,
            category = category
        )
    }

    private fun findStructuralCategoryBlock(price: OcrBlock, blocks: List<OcrBlock>): OcrBlock? {
        val candidates = blocks.filter {
            it.bottom <= price.top + OCR_LINE_OVERLAP_TOLERANCE_PX &&
                it.top >= price.top - MAX_CATEGORY_HEADER_DISTANCE_PX &&
                it.left <= price.left + MAX_CATEGORY_LEFT_OFFSET_PX &&
                looksLikeCategoryHeader(it.text)
        }
        val nearest = candidates.maxByOrNull { it.bottom } ?: return null
        return candidates
            .filter { abs(it.centerY - nearest.centerY) <= CATEGORY_ROW_TOLERANCE_PX }
            .minByOrNull { it.left }
    }

    /** Extrai a categoria pela posição e valida-a contra a lista Uber. */
    private fun extractCategoryFromTokens(tokens: List<OcrToken>, price: OcrBlock): String? {
        val candidates = tokens.filter {
            it.bottom <= price.top + OCR_LINE_OVERLAP_TOLERANCE_PX &&
                it.top >= price.top - MAX_CATEGORY_HEADER_DISTANCE_PX &&
                it.left <= price.left + MAX_CATEGORY_TOKEN_LEFT_OFFSET_PX &&
                it.text.any(Char::isLetter)
        }
        val nearest = candidates.maxByOrNull { it.bottom } ?: return null
        val row = candidates
            .filter {
                abs(it.centerY - nearest.centerY) <= max(CATEGORY_ROW_TOLERANCE_PX, nearest.height / 2)
            }
            .sortedBy { it.left }
        if (row.isEmpty()) return null

        val firstIndex = row.indexOfFirst {
            it.text.any(Char::isLetter) && it.left <= price.left + MAX_CATEGORY_TOKEN_LEFT_OFFSET_PX
        }
        if (firstIndex < 0) return null
        val categoryTokens = mutableListOf<OcrToken>()
        for (token in row.drop(firstIndex)) {
            val previous = categoryTokens.lastOrNull()
            if (previous != null) {
                val gap = token.left - previous.right
                val allowed = max(
                    MIN_CATEGORY_WORD_GAP_PX,
                    (max(previous.height, token.height) * CATEGORY_WORD_GAP_FACTOR).toInt()
                )
                if (gap > allowed) break
            }
            categoryTokens += token
        }
        return sanitizeCategory(categoryTokens.joinToString(" ") { it.text })
    }

    private fun sanitizeCategory(rawText: String): String? {
        val beforeEuroSymbol = rawText.substringBefore('€')
        val eurIndex = beforeEuroSymbol.indexOf("EUR", ignoreCase = true)
        val withoutPrice = if (eurIndex >= 0) beforeEuroSymbol.substring(0, eurIndex) else beforeEuroSymbol
        val tokens = withoutPrice.replace('|', ' ')
            .split(Regex("\\s+"))
            .map(String::trim)
            .filter(String::isNotEmpty)
            .dropWhile { it.none(Char::isLetter) }
            .dropLastWhile { it in CLOSE_BUTTON_TOKENS }
        val category = tokens.joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_CATEGORY_LENGTH)
        return CategoryNameSanitizer.cleanForPlatform(category, OfferPlatform.UBER)
            ?.takeIf { it.any(Char::isLetter) && !isCategoryNoise(it) }
    }

    private fun looksLikeCategoryHeader(text: String): Boolean =
        text.length <= MAX_CATEGORY_HEADER_TEXT_LENGTH &&
            text.any(Char::isLetter) &&
            !isCategoryNoise(text)

    private fun isCategoryNoise(text: String): Boolean {
        val normalized = normalize(text)
        return CATEGORY_EXCLUDED_MARKERS.any(normalized::contains) || parsePriceValue(text) != null
    }

    private fun serviceFeeAnchorAt(index: Int, blocks: List<OcrBlock>): OcrBlock? {
        val first = blocks[index]
        val normalized = normalize(first.text)
        if (!normalized.contains("apos") && !normalized.contains("deduc")) return null
        val combined = mutableListOf<OcrBlock>()
        for (offset in 0 until MAX_SERVICE_FEE_ANCHOR_LINES) {
            val block = blocks.getOrNull(index + offset) ?: break
            combined += block
            val text = combined.joinToString(" ") { it.text }
            if (isServiceFeeAnchor(text)) {
                return OcrBlock(
                    text,
                    first.top,
                    block.bottom,
                    combined.minOf(OcrBlock::left),
                    combined.maxOf(OcrBlock::right)
                )
            }
        }
        return null
    }

    private fun isServiceFeeAnchor(text: String): Boolean {
        val normalized = normalize(text)
        return normalized.contains("apos") && normalized.contains("deduc") &&
            normalized.contains("taxa") && normalized.contains("servic")
    }

    private fun isPickupSegment(text: String): Boolean =
        PICKUP_SEGMENT_REGEX.containsMatchIn(normalize(text))

    private fun isPassengerTripSegment(text: String): Boolean =
        PASSENGER_TRIP_SEGMENT_REGEX.containsMatchIn(normalize(text))

    private fun isPriorityBonus(text: String): Boolean =
        PRIORITY_BONUS_REGEX.containsMatchIn(normalize(text))

    private fun isBonusAmountNear(block: OcrBlock, blocks: List<OcrBlock>): Boolean {
        if (isPriorityBonus(block.text)) return true
        return blocks.any {
            it != block && abs(it.centerY - block.centerY) <= BONUS_ASSOCIATION_DISTANCE_PX &&
                isPriorityBonus(it.text)
        }
    }

    fun resemblesOffer(text: String): Boolean {
        if (text.isBlank()) return false
        val normalized = normalize(text)
        val signals = listOf(
            normalized.contains("deduc") || normalized.contains("taxa de servic"),
            normalized.contains("viag"),
            normalized.contains("dist") && normalized.contains("km"),
            parsePriceValue(text) != null
        )
        return signals.count { it } >= MIN_OFFER_SIGNALS
    }

    private fun List<OcrBlock>.firstRouteSegment(matches: (OcrBlock) -> Boolean): OcrBlock? {
        forEachIndexed { index, current ->
            if (matches(current)) return current
            if (isPriorityBonus(current.text)) return@forEachIndexed
            var combined = current
            for (offset in 1 until MAX_ROUTE_SEGMENT_LINES) {
                val next = getOrNull(index + offset) ?: break
                if (next.top - combined.bottom > MAX_ROUTE_LINE_GAP_PX) break
                combined = OcrBlock(
                    "${combined.text} ${next.text}",
                    current.top,
                    next.bottom,
                    minOf(combined.left, next.left),
                    maxOf(combined.right, next.right)
                )
                if (matches(combined)) return combined
            }
        }
        return null
    }

    private fun priceCandidate(block: OcrBlock): PriceCandidate? {
        if (isPriorityBonus(block.text) || isTvdeOverlayText(block.text)) return null
        return parsePriceValue(block.text)?.let { PriceCandidate(block, it) }
    }

    /** Recupera "€705" como "€7,05", pois o preço Uber tem sempre cêntimos. */
    private fun parsePriceValue(text: String): Double? {
        val normalized = normalize(text)
        if (PRICE_EXCLUDED_MARKERS.any(normalized::contains)) return null
        val currency = CURRENCY_AMOUNT_REGEX.find(text)
        if (currency != null) return normalizeMonetaryDigits(currency.groupValues[1], true)
        val standalone = STANDALONE_AMOUNT_REGEX.find(text)?.value ?: return null
        return normalizeMonetaryDigits(standalone, false)
    }

    private fun normalizeMonetaryDigits(rawValue: String, hasCurrency: Boolean): Double? {
        val compact = rawValue.replace(" ", "").trim()
        if (compact.isBlank()) return null
        val separator = maxOf(compact.lastIndexOf(','), compact.lastIndexOf('.'))
        val normalized = if (separator >= 0) {
            val integer = compact.substring(0, separator).filter(Char::isDigit)
            val decimal = compact.substring(separator + 1).filter(Char::isDigit).take(2)
            if (integer.isBlank() || decimal.isBlank()) return null
            "$integer.$decimal"
        } else {
            val digits = compact.filter(Char::isDigit)
            when {
                !hasCurrency -> return null
                digits.length >= 3 -> "${digits.dropLast(2)}.${digits.takeLast(2)}"
                digits.isNotEmpty() -> digits
                else -> return null
            }
        }
        return normalized.toDoubleOrNull()?.takeIf { it in MIN_VALID_OFFER_PRICE..MAX_VALID_OFFER_PRICE }
    }

    private fun canonicalPrice(value: Double): String =
        String.format(Locale.US, "€ %.2f", value).replace('.', ',')

    private fun isTvdeOverlayText(text: String): Boolean {
        val normalized = normalize(text)
        return TVDE_OVERLAY_MARKERS.any(normalized::contains)
    }

    private fun isCardEndMarker(text: String): Boolean {
        val normalized = normalize(text)
        return CARD_END_MARKERS.any(normalized::contains)
    }

    private fun looksLikeAddress(text: String): Boolean {
        val normalized = normalize(text)
        return text.length >= MIN_ADDRESS_LENGTH && text.any(Char::isLetter) &&
            !ADDRESS_EXCLUDED_MARKERS.any(normalized::contains)
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(DIACRITICS_REGEX, "")
            .lowercase()
            // Corrigir falhas comuns de leitura do ML Kit
            .replace(Regex("v[li]agem"), "viagem")
            .replace("rnin", "min")
            .replace(Regex("\\b[li]\\s*h(?:e\\b)?"), "1 h")
            // Transformar letras em números antes de unidades de distância ou tempo
            .replace(Regex("\\b([0-9ilsobgaz]+)([,.][0-9ilsobgaz]+)?\\s*(?=(?:min(?:uto)?s?|km|quil|m\\b))")) { match ->
                val mapDigit = { str: String ->
                    str.replace(Regex("[il]"), "1")
                        .replace("s", "5")
                        .replace("o", "0")
                        .replace("b", "8")
                        .replace("g", "6")
                        .replace("a", "4")
                        .replace("z", "2")
                }
                val intPart = mapDigit(match.groupValues[1])
                val decPart = mapDigit(match.groupValues[2])
                "$intPart$decPart"
            }
            .replace(Regex("\\s+"), " ")
            .trim()

    data class OcrBlock(
        val text: String,
        val top: Int,
        val bottom: Int,
        val left: Int = 0,
        val right: Int = Int.MAX_VALUE
    ) {
        val centerY: Int get() = top + (bottom - top) / 2
    }

    private data class OcrToken(
        val text: String,
        val top: Int,
        val bottom: Int,
        val left: Int,
        val right: Int
    ) {
        val centerY: Int get() = top + (bottom - top) / 2
        val height: Int get() = (bottom - top).coerceAtLeast(1)
    }

    private data class PriceCandidate(val block: OcrBlock, val value: Double)
    private data class Candidate(
        val text: String,
        val score: Int,
        val priceBlock: OcrBlock,
        val category: String?
    )

    private companion object {
        val DIACRITICS_REGEX = Regex("\\p{Mn}+")
        val CURRENCY_AMOUNT_REGEX = Regex(
            "(?:€|eur)\\s*([0-9]{1,5}(?:[,.][0-9]{1,2})?)",
            RegexOption.IGNORE_CASE
        )
        val STANDALONE_AMOUNT_REGEX = Regex("(?<![\\d,.])\\d{1,3}[,.]\\d{2}(?![\\d,.])")
        val ROUTE_DURATION_PATTERN =
            "(?:(?:\\d+\\s*h(?:ora?s?)?(?:\\s*(?:e|and)?\\s*\\d+\\s*min(?:uto)?s?)?)|(?:\\d+\\s*(?:min(?:uto)?s?|m\\b)))"
        // O OCR pode omitir o "k" de "km" em ecrãs DeX/tela dividida.
        val ROUTE_DISTANCE_PATTERN =
            "\\s*[^\\d]{0,32}\\(?\\s*\\d+[,.]?\\d*\\s*(?:km|quil|m\\b)[^)]*\\)?"
        val PICKUP_SEGMENT_REGEX = Regex(
            "\\b$ROUTE_DURATION_PATTERN$ROUTE_DISTANCE_PATTERN"
        )
        val PASSENGER_TRIP_SEGMENT_REGEX = Regex(
            "\\bvia\\w{0,6}\\s*(?:de\\s*)?$ROUTE_DURATION_PATTERN$ROUTE_DISTANCE_PATTERN"
        )
        val PRIORITY_BONUS_REGEX = Regex("\\+\\s*(?:€|eur)\\s*\\d|inclu.{0,24}embarque")
        val ADDRESS_EXCLUDED_MARKERS = setOf(
            "km", "min", "foco", "aceitar", "rejeitar", "analisar", "taxa", "servic", "uber", "bolt",
            "selecionar", "carregamento", "exclusivo"
        )
        val CATEGORY_EXCLUDED_MARKERS = setOf(
            "apos", "deduc", "taxa", "servic", "viagem", "distancia", " km", " min",
            "aceitar", "rejeitar", "analisar", "por hora", "por km", "valor liquido"
        )
        val PRICE_EXCLUDED_MARKERS = setOf(
            "km", "hora", "/h", "embarque", "incluido", "incluído", "valor liquido", "valor líquido"
        )
        val TVDE_OVERLAY_MARKERS = setOf(
            "por km livre", "por hora", "valor liquido", "rejeitar (", "aceitar (", "analisar (",
            "uber |", "bolt |", "valor min."
        )
        val CARD_END_MARKERS = setOf("carregamento", "aceitar", "selecionar")
        val CLOSE_BUTTON_TOKENS = setOf("X", "x", "×", "✕", "✖")
        const val MAX_SERVICE_FEE_ANCHOR_LINES = 3
        const val MAX_BLOCKS_SCANNED_AFTER_TRIP = 8
        const val MAX_DESTINATION_ADDRESS_LINES = 4
        const val MAX_ROUTE_SEGMENT_LINES = 4
        const val MAX_ROUTE_LINE_GAP_PX = 110
        const val OCR_LINE_OVERLAP_TOLERANCE_PX = 32
        const val MAX_PRICE_ANCHOR_DISTANCE_PX = 420
        const val MAX_FALLBACK_PRICE_DISTANCE_PX = 900
        const val MAX_CATEGORY_HEADER_DISTANCE_PX = 320
        const val MAX_CATEGORY_LEFT_OFFSET_PX = 180
        const val MAX_CATEGORY_TOKEN_LEFT_OFFSET_PX = 180
        const val CATEGORY_ROW_TOLERANCE_PX = 28
        const val MIN_CATEGORY_WORD_GAP_PX = 12
        const val CATEGORY_WORD_GAP_FACTOR = 0.55
        const val BONUS_ASSOCIATION_DISTANCE_PX = 70
        const val MIN_ADDRESS_LENGTH = 3
        const val MIN_OFFER_SIGNALS = 2
        const val MAX_CATEGORY_LENGTH = 80
        const val MAX_CATEGORY_HEADER_TEXT_LENGTH = 120
        const val ANCHORED_CANDIDATE_SCORE = 20
        const val FALLBACK_CANDIDATE_SCORE = 10
        const val CATEGORY_SCORE_BONUS = 2
        const val MIN_VALID_OFFER_PRICE = 0.51
        const val MAX_VALID_OFFER_PRICE = 999.99
    }
}
