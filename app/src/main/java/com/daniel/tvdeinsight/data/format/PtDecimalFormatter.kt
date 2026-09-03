package com.daniel.tvdeinsight.data.format

import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Formatação dos números que são apresentados ao utilizador ou enviados para
 * a folha. A criação do DecimalFormat acontece por chamada para manter a
 * função segura quando o upload e a exportação ocorrem em threads diferentes.
 */
object PtDecimalFormatter {
    private val symbols = DecimalFormatSymbols(Locale("pt", "PT"))

    fun two(value: Double): String {
        if (!value.isFinite()) return ""
        return DecimalFormat("0.00", symbols).apply {
            isGroupingUsed = false
            roundingMode = RoundingMode.HALF_UP
        }.format(value)
    }
}
