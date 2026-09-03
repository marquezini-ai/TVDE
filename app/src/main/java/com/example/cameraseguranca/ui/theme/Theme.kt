package com.example.cameraseguranca.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.daniel.tvdeinsight.data.repository.ThemePreferencesRepository
import com.daniel.tvdeinsight.ui.theme.TVDEInsightTheme
import com.daniel.tvdeinsight.ui.theme.ThemeMode

/** O módulo de Gravação usa exatamente o tema escolhido na TVDE Insight. */
@Composable
fun CameraSafetyTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { ThemePreferencesRepository(context) }
    val themeMode by repository.themeMode.collectAsState(initial = ThemeMode.AUTOMATIC)
    TVDEInsightTheme(themeMode = themeMode, content = content)
}
