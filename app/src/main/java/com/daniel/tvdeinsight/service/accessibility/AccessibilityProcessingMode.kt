package com.daniel.tvdeinsight.service.accessibility

/**
 * Até Android 12L as duas plataformas usam exclusivamente acessibilidade e
 * uma leitura válida pode ser publicada sem uma confirmação adicional.
 */
internal fun usesImmediateAccessibilityPublication(sdkInt: Int): Boolean =
    sdkInt < ANDROID_13_API_LEVEL

private const val ANDROID_13_API_LEVEL = 33
