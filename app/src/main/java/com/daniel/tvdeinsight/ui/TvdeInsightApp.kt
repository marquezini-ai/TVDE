package com.daniel.tvdeinsight.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.daniel.tvdeinsight.ui.screens.HomeScreen
import com.daniel.tvdeinsight.ui.screens.HistoryScreen
import com.daniel.tvdeinsight.ui.screens.SettingsScreen
import com.daniel.tvdeinsight.ui.screens.StatisticsScreen
import com.daniel.tvdeinsight.ui.screens.MainViewModel
import com.daniel.tvdeinsight.ui.screens.ReservationsScreen
import com.daniel.tvdeinsight.ui.theme.TVDEInsightTheme

@Composable
fun TvdeInsightApp(viewModel: MainViewModel = hiltViewModel()) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle(
        initialValue = com.daniel.tvdeinsight.ui.theme.ThemeMode.AUTOMATIC
    )
    var selectedScreen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var historyReturnToListToken by rememberSaveable { mutableIntStateOf(0) }

    TVDEInsightTheme(themeMode = themeMode) {
        Scaffold(
            bottomBar = {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                    tonalElevation = 6.dp,
                    shadowElevation = 14.dp
                ) {
                    NavigationBar(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        tonalElevation = 0.dp
                    ) {
                        navigationScreens.forEach { screen ->
                            NavigationBarItem(
                                selected = selectedScreen == screen,
                                onClick = {
                                    if (screen == Screen.HISTORY) historyReturnToListToken++
                                    selectedScreen = screen
                                },
                                icon = {
                                    Icon(
                                        imageVector = screen.icon,
                                        contentDescription = screen.label
                                    )
                                },
                                label = {
                                    Text(
                                        text = screen.label,
                                        fontSize = 10.sp,
                                        fontWeight = if (selectedScreen == screen) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                        }
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { paddingValues ->
            AnimatedContent(
                targetState = selectedScreen,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(180)) +
                        slideInHorizontally(animationSpec = tween(220)) { it / 14 }) togetherWith
                        (fadeOut(animationSpec = tween(120)) +
                            slideOutHorizontally(animationSpec = tween(160)) { -it / 18 })
                },
                label = "transição entre abas"
            ) { screen ->
                when (screen) {
                    Screen.HOME -> HomeScreen(paddingValues)
                    Screen.HISTORY -> HistoryScreen(paddingValues, resetToListToken = historyReturnToListToken)
                    Screen.STATISTICS -> StatisticsScreen(paddingValues)
                    Screen.RESERVATIONS -> ReservationsScreen(paddingValues)
                    Screen.SETTINGS -> SettingsScreen(paddingValues)
                }
            }
        }
    }
}

private val navigationScreens = listOf(Screen.HOME, Screen.HISTORY, Screen.STATISTICS, Screen.RESERVATIONS, Screen.SETTINGS)

private enum class Screen(val label: String, val icon: ImageVector) {
    HISTORY("Histórico", Icons.Outlined.History),
    HOME("Home", Icons.Outlined.Home),
    STATISTICS("Estatísticas", Icons.Outlined.Insights),
    RESERVATIONS("Reservas", Icons.Outlined.CalendarMonth),
    SETTINGS("Configurações", Icons.Outlined.Settings)
}
