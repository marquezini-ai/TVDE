package com.daniel.tvdeinsight.service.ocr

import android.graphics.Bitmap

/**
 * Mantém a imagem integral, mas aumenta apenas capturas estreitas para que o texto
 * pequeno do card não chegue ao ML Kit com menos detalhe do que uma captura FHD.
 */
internal object OcrBitmapPreprocessor {
    /**
     * Prepara a imagem sem a prender a uma resolução de aparelho.
     *
     * A captura continua sempre na sua resolução nativa. Só é reduzida quando
     * o número total de pixels excede o orçamento de memória do OCR; a escala é
     * calculada pela área, preservando a proporção (720p, FHD, DeX e 4K).
     * Capturas pequenas nunca são ampliadas: uma imagem 167x167 é inválida e
     * deve ser rejeitada pelo serviço, não artificialmente transformada numa
     * falsa captura FHD.
     */
    fun prepare(source: Bitmap): PreparedBitmap {
        val pixels = source.width.toLong() * source.height.toLong()
        if (pixels <= MAX_OCR_PIXELS) return PreparedBitmap(source, 1f)

        val scale = kotlin.math.sqrt(MAX_OCR_PIXELS.toDouble() / pixels).toFloat()
        val targetWidth = (source.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (source.height * scale).toInt().coerceAtLeast(1)
        return PreparedBitmap(
            bitmap = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true),
            scale = scale
        )
    }

    internal data class PreparedBitmap(
        val bitmap: Bitmap,
        /** Coordenadas da imagem preparada para a imagem original. */
        val scale: Float
    ) {
        fun sourceCoordinate(value: Int): Int =
            (value / scale).toInt().coerceAtLeast(0)
    }

    // Orçamento de área, não largura fixa: 1080x2340 permanece nativo, enquanto
    // um display 4K é reduzido proporcionalmente para controlar a memória.
    private const val MAX_OCR_PIXELS = 3_000_000L
}
