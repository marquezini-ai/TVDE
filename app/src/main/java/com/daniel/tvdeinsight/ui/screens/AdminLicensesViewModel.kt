package com.daniel.tvdeinsight.ui.screens

import androidx.lifecycle.ViewModel
import com.daniel.tvdeinsight.license.AdminLicenseRecord
import com.daniel.tvdeinsight.license.AdminLicenseRegistry
import com.daniel.tvdeinsight.data.sync.backend.AdminLicenseIssuer
import com.daniel.tvdeinsight.data.sync.backend.ClientLicenseIssueResult
import com.daniel.tvdeinsight.license.ActivationKeyCrypto
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class AdminLicensesViewModel @Inject constructor(
    private val registry: AdminLicenseRegistry,
    private val issuer: AdminLicenseIssuer
) : ViewModel() {
    val records: StateFlow<List<AdminLicenseRecord>> = registry.records

    suspend fun issueLicense(
        fullName: String,
        phone: String,
        androidId: String,
        days: Int
    ): Result<AdminLicenseRecord> = runCatching {
        val createdAtMillis = System.currentTimeMillis()
        val expiresAtMillis = ActivationKeyCrypto.expirationFromDays(createdAtMillis, days)
        when (val result = issuer.issue(androidId, expiresAtMillis)) {
            is ClientLicenseIssueResult.Success -> registry.add(
                fullName, phone, result.response.androidId, createdAtMillis,
                result.response.expiresAtEpochMillis, result.response.activationKey
            )
            is ClientLicenseIssueResult.Failure -> error(result.code.toUserMessage())
        }
    }

    suspend fun renewLicense(record: AdminLicenseRecord, days: Int): Result<AdminLicenseRecord> = runCatching {
        val createdAtMillis = System.currentTimeMillis()
        val expiresAtMillis = ActivationKeyCrypto.expirationFromDays(createdAtMillis, days)
        when (val result = issuer.issue(record.androidId, expiresAtMillis)) {
            is ClientLicenseIssueResult.Success -> registry.renew(
                record.id, createdAtMillis, result.response.expiresAtEpochMillis, result.response.activationKey
            )
            is ClientLicenseIssueResult.Failure -> error(result.code.toUserMessage())
        }
    }

    fun activeBackupJson(): String = registry.activeBackupJson()

    private fun String.toUserMessage(): String = when (this) {
        "BACKEND_NOT_CONFIGURED", "ADMIN_BACKEND_REGISTRATION_REQUIRED" ->
            "Ligue este Admin ao backend seguro nas Definições antes de gerar licenças."
        "NETWORK_UNAVAILABLE" -> "Sem ligação ao servidor. Verifique a internet e tente novamente."
        "ROLE_FORBIDDEN" -> "Esta instalação não tem autorização de Administrador."
        "SIGNING_UNAVAILABLE" -> "O serviço de emissão está temporariamente indisponível."
        else -> "Não foi possível gerar a licença ($this)."
    }
}
