package com.steamtrans.ledger

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun parseMoney(value: String): Long? = runCatching {
    val normalized = value.trim().replace(",", "")
    if (normalized.isBlank()) return null
    BigDecimal(normalized)
        .setScale(2, RoundingMode.UNNECESSARY)
        .movePointRight(2)
        .longValueExact()
}.getOrNull()

internal fun steamSaleFee(grossCents: Long): Long {
    if (grossCents <= 0) return 0
    return BigDecimal.valueOf(grossCents)
        .multiply(BigDecimal("0.15"))
        .setScale(0, RoundingMode.CEILING)
        .longValueExact()
}

fun formatMoney(cents: Long, signed: Boolean = false): String {
    val value = BigDecimal.valueOf(cents, 2)
    val format = NumberFormat.getCurrencyInstance(Locale.CHINA).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }
    val rendered = format.format(value.abs())
    return when {
        cents < 0 -> "−$rendered"
        signed && cents > 0 -> "+$rendered"
        else -> rendered
    }
}

fun formatDateTime(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(timestamp))

fun formatShortDate(timestamp: Long): String =
    SimpleDateFormat("MM-dd", Locale.CHINA).format(Date(timestamp))
