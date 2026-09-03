package com.daniel.tvdeinsight.data.sheets

import com.daniel.tvdeinsight.domain.model.DecisionType
import com.daniel.tvdeinsight.domain.model.CategoryNameSanitizer
import com.daniel.tvdeinsight.domain.model.EvaluationCriterion
import com.daniel.tvdeinsight.domain.model.OfferHistoryEntry
import com.daniel.tvdeinsight.domain.model.OfferPlatform
import com.daniel.tvdeinsight.data.format.PtDecimalFormatter

/** Mantém uma ordem de colunas estável para permitir append e import sem servidor. */
object TripSheetCodec {
    val header = listOf(
        "id", "data_hora_ms", "plataforma", "categoria", "decisao", "valor",
        "liquido", "portagem", "km", "km_bruto", "hora", "km_recolha",
        "tempo_recolha", "morada_recolha", "km_destino", "tempo_destino",
        "morada_destino", "localizacao_oferta", "latitude", "longitude",
        "custo_veiculo_aplicado", "criterios_ativos", "decisoes_criterios", "paradas", "origem_dispositivo"
    )

    fun toRow(entry: OfferHistoryEntry): List<String> = listOf(
        entry.id.toString(),
        entry.recordedAtMillis.toString(),
        entry.platform.name,
        entry.category.orEmpty(),
        entry.decisionType.name,
        entry.tripValue.ptDecimal2(),
        entry.netTripValue.ptDecimal2OrEmpty(),
        entry.tollAmount.ptDecimal2(),
        entry.valorPorKm.ptDecimal2(),
        entry.valorPorKmBruto.ptDecimal2(),
        entry.valorPorHora.ptDecimal2(),
        entry.pickupDistanceKm.ptDecimal2OrEmpty(),
        entry.pickupDurationMinutes.ptDecimal2OrEmpty(),
        entry.pickupAddress.orEmpty(),
        entry.destinationDistanceKm.ptDecimal2OrEmpty(),
        entry.destinationDurationMinutes.ptDecimal2OrEmpty(),
        entry.destinationAddress.orEmpty(),
        entry.currentLocationAddress.orEmpty(),
        entry.currentLocationLatitude.machineDecimalOrEmpty(),
        entry.currentLocationLongitude.machineDecimalOrEmpty(),
        entry.isVehicleCostPerKmApplied.toString(),
        entry.activeCriteria.joinToString(",") { it.name },
        entry.criterionDecisions.entries.joinToString("|") { (criterion, decision) ->
            "${criterion.name}=${decision.name}"
        },
        entry.isStopRejection.toString(),
        entry.sourceDeviceId
    )

    fun fromRow(row: List<String>): OfferHistoryEntry? = runCatching {
        fun value(index: Int): String = row.getOrNull(index)?.trim().orEmpty()
        fun double(index: Int): Double? = value(index).replace(',', '.').toDoubleOrNull()
        val id = value(0).toLongOrNull() ?: return null
        val timestamp = value(1).toLongOrNull() ?: id
        val tripValue = double(5) ?: return null
        val perKm = double(8) ?: return null
        val perHour = double(10) ?: return null
        val platform = OfferPlatform.entries.firstOrNull { it.name == value(2) } ?: OfferPlatform.UNKNOWN
        val activeCriteria = value(21).split(',').mapNotNull { item ->
            EvaluationCriterion.entries.firstOrNull { it.name == item }
        }.toSet()
        val criterionDecisions = value(22).split('|').mapNotNull { pair ->
            val parts = pair.split('=', limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val criterion = EvaluationCriterion.entries.firstOrNull { it.name == parts[0] } ?: return@mapNotNull null
            val decision = DecisionType.entries.firstOrNull { it.name == parts[1] } ?: return@mapNotNull null
            criterion to decision
        }.toMap()
        OfferHistoryEntry(
            id = id,
            recordedAtMillis = timestamp,
            platform = platform,
            valorPorKm = perKm,
            valorPorHora = perHour,
            valorPorKmBruto = double(9) ?: perKm,
            netTripValue = double(6),
            tollAmount = double(7) ?: 0.0,
            isVehicleCostPerKmApplied = value(20).toBoolean(),
            pickupDistanceKm = double(11),
            destinationDistanceKm = double(14),
            tripValue = tripValue,
            pickupDurationMinutes = double(12),
            destinationDurationMinutes = double(15),
            currentLocationAddress = value(17).takeIf(String::isNotBlank),
            currentLocationLatitude = double(18),
            currentLocationLongitude = double(19),
            pickupAddress = value(13).takeIf(String::isNotBlank),
            destinationAddress = value(16).takeIf(String::isNotBlank),
            category = CategoryNameSanitizer.cleanForPlatform(value(3), platform),
            decisionType = DecisionType.entries.firstOrNull { it.name == value(4) } ?: DecisionType.ANALISAR,
            activeCriteria = activeCriteria,
            criterionDecisions = criterionDecisions,
            isStopRejection = value(23).toBoolean(),
            sourceDeviceId = value(24)
        )
    }.getOrNull()

    /** Formato visível obrigatório: duas casas e vírgula decimal portuguesa. */
    private fun Double.ptDecimal2(): String =
        PtDecimalFormatter.two(this)

    private fun Double?.ptDecimal2OrEmpty(): String =
        if (this == null || !isFinite()) "" else this@ptDecimal2OrEmpty.ptDecimal2()

    /** Coordenadas mantêm seis casas e ponto para não perder precisão geográfica. */
    private fun Double?.machineDecimalOrEmpty(): String =
        if (this == null || !isFinite()) "" else String.format(java.util.Locale.ROOT, "%.6f", this)
}
