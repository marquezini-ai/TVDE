package com.daniel.tvdeinsight.reservations

import android.content.Context
import android.location.Geocoder
import java.util.Locale

/**
 * Calcula rapidamente a distância casa -> recolha como linha reta (raio).
 * A geocodificação fica em cache para não repetir pedidos enquanto a Bolt
 * atualiza a lista. O método é chamado apenas pelo executor em segundo plano.
 */
class PickupDistanceResolver(context: Context) {
    private val appContext = context.applicationContext
    private val cache = mutableMapOf<String, Coordinates?>()

    fun distanceKm(homeAddress: String, pickupAddress: String): Double? {
        val home = coordinates(homeAddress)
        val pickup = coordinates(pickupAddress)
        if (home == null || pickup == null) return null
        return haversineKm(home, pickup)
    }

    private fun coordinates(address: String): Coordinates? {
        val query = normalizeQuery(address)
        if (query.isBlank()) return null
        synchronized(cache) {
            if (cache.containsKey(query)) return cache[query]
        }
        val result = runCatching {
            if (!Geocoder.isPresent()) return@runCatching null
            @Suppress("DEPRECATION")
            Geocoder(appContext, PT_LOCALE).getFromLocationName(query, 1)
                ?.firstOrNull()
                ?.let { Coordinates(it.latitude, it.longitude) }
        }.getOrNull()
        synchronized(cache) { cache[query] = result }
        DiagnosticLogger.log("Geocodificação: '$query' -> ${result?.let { "${it.latitude},${it.longitude}" } ?: "não localizada"}")
        return result
    }

    private fun normalizeQuery(address: String): String = address
        .replace('\u00A0', ' ')
        .replace(Regex("(?i)^perto\\s+de\\s+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun haversineKm(first: Coordinates, second: Coordinates): Double {
        val earthRadiusKm = 6371.0088
        val latDelta = Math.toRadians(second.latitude - first.latitude)
        val lonDelta = Math.toRadians(second.longitude - first.longitude)
        val a = kotlin.math.sin(latDelta / 2) * kotlin.math.sin(latDelta / 2) +
            kotlin.math.cos(Math.toRadians(first.latitude)) *
            kotlin.math.cos(Math.toRadians(second.latitude)) *
            kotlin.math.sin(lonDelta / 2) * kotlin.math.sin(lonDelta / 2)
        return 2 * earthRadiusKm * kotlin.math.asin(kotlin.math.sqrt(a))
    }

    private data class Coordinates(val latitude: Double, val longitude: Double)

    companion object {
        private val PT_LOCALE = Locale("pt", "PT")
    }
}
