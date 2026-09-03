package com.daniel.tvdeinsight.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.daniel.tvdeinsight.logging.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Localização aproximada do motorista no instante em que a oferta é guardada. */
data class DeviceLocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val address: String?
)

/**
 * Obtém apenas uma posição recente já disponível no telefone. Assim, o cartão e
 * a decisão nunca aguardam GPS ou rede; se não houver posição recente, o campo
 * simplesmente não é incluído no histórico.
 */
@Singleton
class DeviceLocationProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun captureRecentLocation(): DeviceLocationSnapshot? {
        if (!hasLocationPermission()) return null
        val location = recentLastKnownLocation() ?: return null
        val address = reverseGeocode(location)
        AppLogger.debug(
            "Localização guardada no histórico: " +
                "lat=${location.latitude}, lon=${location.longitude}, morada=${!address.isNullOrBlank()}"
        )
        return DeviceLocationSnapshot(
            latitude = location.latitude,
            longitude = location.longitude,
            address = address
        )
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun recentLastKnownLocation(): Location? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val minimumTimestamp = System.currentTimeMillis() - MAX_LOCATION_AGE_MS
        return listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.filter { location ->
            location.time >= minimumTimestamp && location.latitude.isFinite() && location.longitude.isFinite()
        }.maxByOrNull { location -> location.time }
    }

    @Suppress("DEPRECATION")
    private fun reverseGeocode(location: Location): String? = runCatching {
        if (!Geocoder.isPresent()) return null
        Geocoder(context, PORTUGUESE_LOCALE)
            .getFromLocation(location.latitude, location.longitude, 1)
            ?.firstOrNull()
            ?.getAddressLine(0)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }.onFailure { error ->
        AppLogger.warn("Não foi possível obter a morada da localização: ${error.message}")
    }.getOrNull()

    private companion object {
        const val MAX_LOCATION_AGE_MS = 2 * 60 * 1_000L
        val PORTUGUESE_LOCALE = Locale("pt", "PT")
    }
}
