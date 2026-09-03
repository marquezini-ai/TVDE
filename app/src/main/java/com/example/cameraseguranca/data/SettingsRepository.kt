package com.example.cameraseguranca.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.recordingDataStore by preferencesDataStore(name = "recording_settings")

class SettingsRepository(private val context: Context) {
    val settings: Flow<RecordingSettings> = context.recordingDataStore.data
        .catch { error ->
            // Se o armazenamento de preferências estiver corrompido, usa o padrão seguro.
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            RecordingSettings(
                lens = preferences.readEnum(LENS, CameraLens.BACK),
                quality = preferences.readEnum(QUALITY, VideoQuality.HIGH),
                fps = preferences.readEnum(FPS, RecordingFps.FPS_30),
                segment = preferences.readEnum(SEGMENT, SegmentDuration.MINUTES_5),
                timeLimit = preferences.readEnum(TIME_LIMIT, RecordingTimeLimit.HOUR_1),
                storageLocation = preferences.readEnum(STORAGE_LOCATION, StorageLocation.LOCAL),
                triggerMode = preferences.readEnum(TRIGGER_MODE, TriggerMode.TRIPLE_TAP),
                audioEnabled = preferences[AUDIO_ENABLED] ?: false,
                autoDeleteInterval = preferences.readEnum(AUTO_DELETE_INTERVAL, AutoDeleteInterval.DAYS_7),
                floatingControlEnabled = preferences[FLOATING_CONTROL_ENABLED] ?: false
            )
        }

    suspend fun setLens(value: CameraLens) = set(LENS, value.name)
    suspend fun setQuality(value: VideoQuality) = set(QUALITY, value.name)
    suspend fun setFps(value: RecordingFps) = set(FPS, value.name)
    suspend fun setSegment(value: SegmentDuration) = set(SEGMENT, value.name)
    suspend fun setTimeLimit(value: RecordingTimeLimit) = set(TIME_LIMIT, value.name)
    suspend fun setStorageLocation(value: StorageLocation) = set(STORAGE_LOCATION, value.name)
    suspend fun setTriggerMode(value: TriggerMode) = set(TRIGGER_MODE, value.name)
    suspend fun setAudioEnabled(value: Boolean) {
        context.recordingDataStore.edit { it[AUDIO_ENABLED] = value }
    }
    suspend fun setAutoDeleteInterval(value: AutoDeleteInterval) =
        set(AUTO_DELETE_INTERVAL, value.name)
    suspend fun setFloatingControlEnabled(value: Boolean) {
        context.recordingDataStore.edit { it[FLOATING_CONTROL_ENABLED] = value }
    }

    /** Aplica um conjunto íntegro de definições vindo do backup da TVDE Insight. */
    suspend fun restore(value: RecordingSettings) {
        context.recordingDataStore.edit { preferences ->
            preferences[LENS] = value.lens.name
            preferences[QUALITY] = value.quality.name
            preferences[FPS] = value.fps.name
            preferences[SEGMENT] = value.segment.name
            preferences[TIME_LIMIT] = value.timeLimit.name
            preferences[STORAGE_LOCATION] = value.storageLocation.name
            preferences[TRIGGER_MODE] = value.triggerMode.name
            preferences[AUDIO_ENABLED] = value.audioEnabled
            preferences[AUTO_DELETE_INTERVAL] = value.autoDeleteInterval.name
            preferences[FLOATING_CONTROL_ENABLED] = value.floatingControlEnabled
        }
    }

    private suspend fun set(key: Preferences.Key<String>, value: String) {
        context.recordingDataStore.edit { it[key] = value }
    }

    private inline fun <reified T : Enum<T>> Preferences.readEnum(
        key: Preferences.Key<String>,
        fallback: T
    ): T = get(key)?.let { saved ->
        runCatching { enumValueOf<T>(saved) }.getOrDefault(fallback)
    } ?: fallback

    private companion object {
        val LENS = stringPreferencesKey("lens")
        val QUALITY = stringPreferencesKey("quality")
        val FPS = stringPreferencesKey("fps")
        val SEGMENT = stringPreferencesKey("segment")
        val TIME_LIMIT = stringPreferencesKey("time_limit")
        val STORAGE_LOCATION = stringPreferencesKey("storage_location")
        val TRIGGER_MODE = stringPreferencesKey("trigger_mode")
        val AUDIO_ENABLED = booleanPreferencesKey("audio_enabled")
        val AUTO_DELETE_INTERVAL = stringPreferencesKey("auto_delete_interval")
        val FLOATING_CONTROL_ENABLED = booleanPreferencesKey("floating_control_enabled")
    }
}
