package com.daniel.tvdeinsight.data.repository

import com.daniel.tvdeinsight.domain.model.RuleResult
import com.daniel.tvdeinsight.domain.model.OfferHistoryEntry
import com.daniel.tvdeinsight.domain.model.TripOffer
import kotlinx.coroutines.flow.StateFlow

interface OfferAnalysisStore {
    val latestDecision: StateFlow<RuleResult?>
    /** Apenas as ofertas criadas neste telemóvel; alimenta o histórico pessoal. */
    val history: StateFlow<List<OfferHistoryEntry>>
    /** Todas as ofertas disponíveis localmente, incluindo as recebidas da Sheet. */
    val globalHistory: StateFlow<List<OfferHistoryEntry>>
        get() = history

    /** Regista a oferta e devolve a chave local que identifica a viagem no histórico. */
    fun publish(offer: TripOffer, decision: RuleResult): Long

    /** Associa uma captura privada a uma viagem já registada. */
    fun attachScreenshot(entryId: Long, fileName: String)
}
