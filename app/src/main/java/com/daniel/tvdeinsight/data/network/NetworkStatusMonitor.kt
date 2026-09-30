package com.daniel.tvdeinsight.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.daniel.tvdeinsight.logging.AppLogger
import com.daniel.tvdeinsight.worker.SheetsSyncScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

object NetworkStatusPolicy {
    fun isOnline(hasInternetCapability: Boolean, isValidated: Boolean): Boolean =
        hasInternetCapability && isValidated

    fun shouldEnqueuePendingSync(wasOnline: Boolean, isOnline: Boolean): Boolean =
        !wasOnline && isOnline
}

/** Observa a rede validada sem polling e reprograma o trabalho pendente ao recuperar Internet. */
@Singleton
class NetworkStatusMonitor @Inject constructor(
    @ApplicationContext context: Context
) {
    private val appContext = context.applicationContext
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val _hasValidatedInternet = MutableStateFlow(readCurrentStatus())
    val hasValidatedInternet: StateFlow<Boolean> = _hasValidatedInternet.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            update(readCapabilities(network))
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            update(isValidatedInternet(capabilities))
        }

        override fun onLost(network: Network) {
            update(readCurrentStatus())
        }
    }

    init {
        runCatching {
            connectivityManager?.registerDefaultNetworkCallback(callback)
        }.onFailure { error ->
            AppLogger.warn("Não foi possível observar a conectividade: ${error.javaClass.simpleName}")
        }
        update(readCurrentStatus())
    }

    private fun update(isOnline: Boolean) {
        val wasOnline = _hasValidatedInternet.value
        if (wasOnline == isOnline) return
        _hasValidatedInternet.value = isOnline
        AppLogger.info(if (isOnline) "Ligação à Internet validada" else "Sem ligação à Internet validada")
        if (NetworkStatusPolicy.shouldEnqueuePendingSync(wasOnline, isOnline)) {
            // O trabalho fica persistido e condicionado a rede; o callback só o acorda,
            // não executa chamadas de rede no processo nem cria ciclos de polling.
            SheetsSyncScheduler.enqueuePendingSync(appContext)
        }
    }

    private fun readCurrentStatus(): Boolean {
        val manager = connectivityManager ?: return false
        val network = manager.activeNetwork ?: return false
        return readCapabilities(network)
    }

    private fun readCapabilities(network: Network): Boolean =
        connectivityManager?.getNetworkCapabilities(network)?.let(::isValidatedInternet) == true

    private fun isValidatedInternet(capabilities: NetworkCapabilities): Boolean =
        NetworkStatusPolicy.isOnline(
            hasInternetCapability = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        )
}
