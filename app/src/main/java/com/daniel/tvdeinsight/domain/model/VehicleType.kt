package com.daniel.tvdeinsight.domain.model

enum class VehicleType(val label: String, val consumptionUnit: String, val priceLabel: String) {
    ELECTRIC("Elétrico", "kWh/100km", "Preço pago / kWh"),
    COMBUSTION("Combustão", "l/100km", "Preço pago / litro")
}
