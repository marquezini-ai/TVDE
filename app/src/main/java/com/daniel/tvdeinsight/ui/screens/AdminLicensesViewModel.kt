package com.daniel.tvdeinsight.ui.screens

import androidx.lifecycle.ViewModel
import com.daniel.tvdeinsight.license.AdminLicenseRecord
import com.daniel.tvdeinsight.license.AdminLicenseRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class AdminLicensesViewModel @Inject constructor(
    private val registry: AdminLicenseRegistry
) : ViewModel() {
    val records: StateFlow<List<AdminLicenseRecord>> = registry.records

    fun registerGeneratedLicense(
        fullName: String,
        phone: String,
        androidId: String,
        createdAtMillis: Long,
        expiresAtMillis: Long,
        activationKey: String
    ) = registry.add(
        fullName = fullName,
        phone = phone,
        androidId = androidId,
        createdAtMillis = createdAtMillis,
        expiresAtMillis = expiresAtMillis,
        activationKey = activationKey
    )

    fun renewGeneratedLicense(
        recordId: String,
        createdAtMillis: Long,
        expiresAtMillis: Long,
        activationKey: String
    ) = registry.renew(
        recordId = recordId,
        createdAtMillis = createdAtMillis,
        expiresAtMillis = expiresAtMillis,
        activationKey = activationKey
    )

    fun activeBackupJson(): String = registry.activeBackupJson()
}
