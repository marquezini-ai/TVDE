package com.example.cameraseguranca.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.cameraseguranca.CameraSafetyDependencies
import com.example.cameraseguranca.data.CameraLens
import com.example.cameraseguranca.data.AutoDeleteInterval
import com.example.cameraseguranca.data.RecordingSettings
import com.example.cameraseguranca.data.RecordingTimeLimit
import com.example.cameraseguranca.data.SegmentDuration
import com.example.cameraseguranca.data.StorageLocation
import com.example.cameraseguranca.data.TriggerMode
import com.example.cameraseguranca.data.VideoQuality
import com.example.cameraseguranca.data.RecordingFps
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CameraSafetyDependencies.settingsRepository(application)

    val settings = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RecordingSettings()
    )

    fun setLens(value: CameraLens) = viewModelScope.launch { repository.setLens(value) }
    fun setQuality(value: VideoQuality) = viewModelScope.launch { repository.setQuality(value) }
    fun setFps(value: RecordingFps) = viewModelScope.launch { repository.setFps(value) }
    fun setSegment(value: SegmentDuration) = viewModelScope.launch { repository.setSegment(value) }
    fun setTimeLimit(value: RecordingTimeLimit) = viewModelScope.launch { repository.setTimeLimit(value) }
    fun setStorageLocation(value: StorageLocation) = viewModelScope.launch {
        repository.setStorageLocation(value)
    }
    fun setTriggerMode(value: TriggerMode) = viewModelScope.launch {
        repository.setTriggerMode(value)
    }
    fun setAudioEnabled(value: Boolean) = viewModelScope.launch {
        repository.setAudioEnabled(value)
    }
    fun setAutoDeleteInterval(value: AutoDeleteInterval) = viewModelScope.launch {
        repository.setAutoDeleteInterval(value)
    }
    fun setFloatingControlEnabled(value: Boolean) = viewModelScope.launch {
        repository.setFloatingControlEnabled(value)
    }
}
