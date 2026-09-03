package com.daniel.tvdeinsight.domain.model

/** Aplicação usada para abrir endereços e rotas a partir do histórico. */
enum class NavigationApp(val label: String, val packageName: String) {
    GOOGLE_MAPS("Google Maps", "com.google.android.apps.maps"),
    WAZE("Waze", "com.waze")
}
