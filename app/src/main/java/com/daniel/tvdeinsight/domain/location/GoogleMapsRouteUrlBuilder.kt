package com.daniel.tvdeinsight.domain.location

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Cria a rota: posição no momento da oferta → recolha → destino. */
object GoogleMapsRouteUrlBuilder {
    fun build(originAtOffer: String?, pickup: String?, destination: String?): String? {
        val cleanPickup = PortugueseAddressFormatter.withoutCountry(pickup) ?: return null
        val cleanDestination = PortugueseAddressFormatter.withoutCountry(destination) ?: return null
        val cleanOrigin = PortugueseAddressFormatter.withoutCountry(originAtOffer)

        val parameters = buildList {
            add("api" to "1")
            if (cleanOrigin != null) {
                add("origin" to cleanOrigin)
                add("waypoints" to cleanPickup)
            } else {
                // Registos antigos podem não ter a localização do telefone.
                add("origin" to cleanPickup)
            }
            add("destination" to cleanDestination)
            add("travelmode" to "driving")
        }
        return BASE_URL + parameters.joinToString("&") { (name, value) ->
            "${name.encode()}=${value.encode()}"
        }
    }

    private fun String.encode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.name())

    private const val BASE_URL = "https://www.google.com/maps/dir/?"
}
