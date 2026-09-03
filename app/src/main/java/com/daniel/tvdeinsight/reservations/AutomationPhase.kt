package com.daniel.tvdeinsight.reservations

/** Fases observáveis do fluxo da automação. */
enum class AutomationPhase(val label: String) {
    STOPPED("Parada"),
    WAITING_SCREEN("A aguardar a tela"),
    SEARCHING("A procurar viagens"),
    OPENING_RIDE("A abrir viagem"),
    ACCEPTING("A aceitar viagem"),
    CONFIRMING("A confirmar viagem"),
    CLOSING_RESULT("A fechar resultado"),
    REFRESHING("A atualizar pedidos"),
    TEST_MODE("Modo de teste"),
    ERROR("Erro recuperável")
}
