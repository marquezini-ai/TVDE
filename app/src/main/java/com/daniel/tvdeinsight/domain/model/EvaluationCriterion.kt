package com.daniel.tvdeinsight.domain.model

/** Critérios que podem ser ativados livremente para avaliar uma oferta. */
enum class EvaluationCriterion(val label: String) {
    RECOLHA("Recolha"),
    KM("€/km"),
    HORA("€/hora"),
    VIAGEM_LONGA("Viagem longa"),
    VALOR_MINIMO("Valor mínimo")
}
