package com.daniel.tvdeinsight.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.daniel.tvdeinsight.data.sync.backend.BackendRegistrationManager
import com.daniel.tvdeinsight.data.sync.backend.BackendRegistrationState
import com.daniel.tvdeinsight.data.sync.backend.BackendRegistrationStore
import com.daniel.tvdeinsight.worker.BackendSyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class BackendConnectionViewModel @Inject constructor(
    application: Application,
    private val registrationManager: BackendRegistrationManager,
    registrationStore: BackendRegistrationStore
) : AndroidViewModel(application) {
    val state: StateFlow<BackendRegistrationState> = registrationStore.stateFlow

    fun configure(activationKey: String) {
        registrationManager.configureActivationKey(activationKey)
        BackendSyncScheduler.enqueueImmediate(getApplication())
    }
}
