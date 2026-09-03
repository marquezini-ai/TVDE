package com.daniel.tvdeinsight.ui.screens

import androidx.lifecycle.ViewModel
import com.daniel.tvdeinsight.license.LicenseManager
import com.daniel.tvdeinsight.license.LicenseState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class LicenseViewModel @Inject constructor(
    private val licenseManager: LicenseManager
) : ViewModel() {
    val licenseState: StateFlow<LicenseState> = licenseManager.state

    fun androidId(): String = licenseManager.deviceAndroidId()

    fun activate(key: String): LicenseState = licenseManager.activate(key)

    fun refresh(): LicenseState = licenseManager.refresh()
}
