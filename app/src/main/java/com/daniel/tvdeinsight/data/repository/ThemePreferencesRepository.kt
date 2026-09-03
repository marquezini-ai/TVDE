package com.daniel.tvdeinsight.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.daniel.tvdeinsight.ui.theme.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.appearanceDataStore by preferencesDataStore(name = "appearance_settings")

@Singleton
class ThemePreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
    }

    val themeMode: Flow<ThemeMode> = context.appearanceDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { preferences ->
            preferences[Keys.THEME_MODE]
                ?.let { saved -> ThemeMode.entries.firstOrNull { it.name == saved } }
                ?: ThemeMode.AUTOMATIC
        }

    suspend fun saveThemeMode(mode: ThemeMode) {
        context.appearanceDataStore.edit { preferences ->
            preferences[Keys.THEME_MODE] = mode.name
        }
    }
}
