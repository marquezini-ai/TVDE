package com.daniel.tvdeinsight.domain.engine

import com.daniel.tvdeinsight.domain.model.RuleResult
import com.daniel.tvdeinsight.domain.model.RuleSettings
import com.daniel.tvdeinsight.domain.model.TripOffer
import com.daniel.tvdeinsight.domain.usecase.EvaluateOfferUseCase
import javax.inject.Inject

/** Classifica ofertas usando as mesmas regras consumidas pela interface e pelos serviços. */
class RuleEngine @Inject constructor(
    private val evaluateOffer: EvaluateOfferUseCase = EvaluateOfferUseCase()
) {
    fun evaluate(offer: TripOffer, settings: RuleSettings): RuleResult {
        val normalizedOffer = offer.copy(
            hasStops = offer.hasStops || containsStop(offer.additionalInfo)
        )
        return evaluateOffer(normalizedOffer, settings)
    }

    private fun containsStop(text: String): Boolean = STOP_KEYWORDS.any { keyword ->
        Regex("\\b${Regex.escape(keyword)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
    }

    private companion object {
        val STOP_KEYWORDS = setOf("paragem", "paragens", "parada", "paradas", "stop", "stops")
    }
}
