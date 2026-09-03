package com.daniel.tvdeinsight.ui.theme

import androidx.compose.ui.graphics.Color
import com.daniel.tvdeinsight.domain.model.DecisionType

data class DecisionColors(
    val background: Color,
    val border: Color,
    val badge: Color,
    val badgeContent: Color,
    val content: Color,
    val acceptedMetric: Color,
    val rejectedMetric: Color,
    val analyzedMetric: Color
) {
    fun metricColor(type: DecisionType): Color = when (type) {
        DecisionType.ACEITAR -> acceptedMetric
        DecisionType.REJEITAR -> rejectedMetric
        DecisionType.ANALISAR -> analyzedMetric
    }
}

fun decisionColors(type: DecisionType, darkTheme: Boolean): DecisionColors {
    val metricColors = if (darkTheme) {
        Triple(Color(0xFF66E39A), Color(0xFFFF7B75), Color(0xFFFFD166))
    } else {
        Triple(Color(0xFF147A3D), Color(0xFFB1262F), Color(0xFF956100))
    }
    return when (type) {
        DecisionType.ACEITAR -> if (darkTheme) {
            DecisionColors(
                background = Color(0xFF0D2818),
                border = Color(0xFF2ECC71),
                badge = Color(0xFF27AE60),
                badgeContent = Color.White,
                content = Color.White,
                acceptedMetric = metricColors.first,
                rejectedMetric = metricColors.second,
                analyzedMetric = metricColors.third
            )
        } else {
            DecisionColors(
                background = Color(0xFFE2F6E9),
                border = Color(0xFF43B873),
                badge = Color(0xFF269B58),
                badgeContent = Color.White,
                content = Color(0xFF173B25),
                acceptedMetric = metricColors.first,
                rejectedMetric = metricColors.second,
                analyzedMetric = metricColors.third
            )
        }

        DecisionType.REJEITAR -> if (darkTheme) {
            DecisionColors(
                background = Color(0xFF2B1214),
                border = Color(0xFFE53935),
                badge = Color(0xFFD32F2F),
                badgeContent = Color.White,
                content = Color.White,
                acceptedMetric = metricColors.first,
                rejectedMetric = metricColors.second,
                analyzedMetric = metricColors.third
            )
        } else {
            DecisionColors(
                background = Color(0xFFFCE5E7),
                border = Color(0xFFE05B62),
                badge = Color(0xFFD7434B),
                badgeContent = Color.White,
                content = Color(0xFF571D22),
                acceptedMetric = metricColors.first,
                rejectedMetric = metricColors.second,
                analyzedMetric = metricColors.third
            )
        }

        DecisionType.ANALISAR -> if (darkTheme) {
            DecisionColors(
                background = Color(0xFF2C1D0B),
                border = Color(0xFFF39C12),
                badge = Color(0xFFD35400),
                badgeContent = Color.White,
                content = Color.White,
                acceptedMetric = metricColors.first,
                rejectedMetric = metricColors.second,
                analyzedMetric = metricColors.third
            )
        } else {
            DecisionColors(
                background = Color(0xFFFFF0C9),
                border = Color(0xFFE4AA2B),
                badge = Color(0xFFC98400),
                badgeContent = Color.White,
                content = Color(0xFF4F3600),
                acceptedMetric = metricColors.first,
                rejectedMetric = metricColors.second,
                analyzedMetric = metricColors.third
            )
        }
    }
}
