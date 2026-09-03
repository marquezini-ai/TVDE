package com.daniel.tvdeinsight.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.daniel.tvdeinsight.BuildConfig
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(paddingValues: PaddingValues, viewModel: MainViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsState()
    val licenseViewModel: LicenseViewModel = hiltViewModel()
    val licenseState by licenseViewModel.licenseState.collectAsState()
    val hasValidLicense = BuildConfig.IS_ADMIN_APP || licenseState.isValid
    val isRunning = settings.isAppRunning && hasValidLicense
    // Verde convida a iniciar; vermelho indica a ação de parar uma monitorização ativa.
    val stateColor = if (isRunning) Color(0xFFC94B56) else Color(0xFF2FAE70)
    val actionColor by animateColorAsState(stateColor, animationSpec = tween(320), label = "cor ativo/inativo")
    val operationSurfaceColor by animateColorAsState(
        targetValue = if (isRunning) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        animationSpec = tween(260),
        label = "superfície da operação"
    )
    val statusColor = when {
        isRunning -> Color(0xFF57D68D)
        else -> Color(0xFFE15252)
    }
    val animatedStatusColor by animateColorAsState(statusColor, animationSpec = tween(280), label = "cor do estado")
    var buttonPressed by remember { mutableStateOf(false) }
    var pendingRunningState by remember { mutableStateOf<Boolean?>(null) }
    val isTransitioning = pendingRunningState != null
    val pressScale by animateFloatAsState(
        targetValue = if (buttonPressed) 0.94f else 1f,
        animationSpec = tween(110),
        label = "toque iniciar/parar"
    )
    val pulseTransition = rememberInfiniteTransition(label = "monitorização ativa")
    val activePulseScale by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(950), RepeatMode.Reverse),
        label = "pulso da monitorização"
    )

    LaunchedEffect(buttonPressed) {
        if (buttonPressed) {
            delay(120)
            buttonPressed = false
        }
    }

    LaunchedEffect(pendingRunningState) {
        val targetState = pendingRunningState ?: return@LaunchedEffect
        delay(1_000)
        viewModel.toggleAppRunning(targetState)
        pendingRunningState = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Column {
            Text(
                text = "TVDE Insight",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.6).sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Decida com confiança antes de aceitar",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )
        }

        if (!BuildConfig.IS_ADMIN_APP) {
            if (licenseState.isValid) {
                ClientLicenseRemaining(licenseState)
            } else {
                ClientActivationInline(licenseViewModel)
            }
        }

        Surface(
            shape = MaterialTheme.shapes.large,
            color = operationSurfaceColor,
            tonalElevation = 3.dp,
            shadowElevation = 10.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = tween(180))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = animatedStatusColor.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, animatedStatusColor.copy(alpha = 0.42f))
                ) {
                    Text(
                        text = when {
                            isRunning -> "● Monitorização ativa"
                            !hasValidLicense -> "● Ativação necessária"
                            else -> "● Monitorização inativa"
                        },
                        color = animatedStatusColor,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }

                Spacer(modifier = Modifier.height(26.dp))

                Box(modifier = Modifier.size(230.dp), contentAlignment = Alignment.Center) {
                    if (!isTransitioning) {
                        Box(
                            modifier = Modifier
                                .size(206.dp)
                                .graphicsLayer {
                                    scaleX = activePulseScale
                                    scaleY = activePulseScale
                                }
                                .clip(CircleShape)
                                .background(stateColor.copy(alpha = 0.18f))
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(206.dp)
                            .graphicsLayer {
                                scaleX = pressScale
                                scaleY = pressScale
                            }
                            .shadow(20.dp, CircleShape)
                            .clip(CircleShape)
                            .background(actionColor)
                            .clickable(enabled = !isTransitioning && (isRunning || hasValidLicense)) {
                                buttonPressed = true
                                pendingRunningState = !isRunning
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            AnimatedContent(targetState = isRunning, label = "texto iniciar/parar") { running ->
                                Text(
                                    text = if (running) "PARAR" else "INICIAR",
                                    color = Color.White,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 27.sp,
                                    letterSpacing = 1.5.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(7.dp))
                            AnimatedContent(targetState = isRunning, label = "instrução iniciar/parar") { running ->
                                Text(
                                    text = when {
                                        running -> "Toque para parar"
                                        hasValidLicense -> "Toque para iniciar"
                                        else -> "Ative no topo"
                                    },
                                    color = Color.White.copy(alpha = 0.78f),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                    if (isTransitioning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(212.dp),
                            color = Color.White,
                            strokeWidth = 5.dp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))
                Text(
                    text = when {
                        isRunning -> {
                        "Uber e Bolt estão a ser monitorizadas."
                        }
                        hasValidLicense -> {
                        "Inicie o serviço para analisar novas ofertas."
                        }
                        else -> "A monitorização fica bloqueada até ativar a licença."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
        }
    }
}
