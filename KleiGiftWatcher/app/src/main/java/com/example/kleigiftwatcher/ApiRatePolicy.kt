package com.example.kleigiftwatcher

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object ApiRatePolicy {
    const val MAX_INTERVAL = 3600
    fun interval(base: Int, increases: Int): Int =
        (base.coerceIn(1, MAX_INTERVAL).toLong() + increases.coerceAtLeast(0)).coerceAtMost(MAX_INTERVAL.toLong()).toInt()
    fun nextIncrease(current: Int): Int = (current.coerceIn(0, MAX_INTERVAL) + 1).coerceAtMost(MAX_INTERVAL)
    fun cooldownSeconds(consecutiveBlocks: Int, serverSeconds: Long): Long =
        maxOf(when { consecutiveBlocks <= 1 -> 120L; consecutiveBlocks == 2 -> 300L; else -> 900L }, serverSeconds)
    fun isRestricted(http: Int = 0, api: Int = 0): Boolean = http in listOf(403, 412, 429) || api in listOf(-352, -412, -509)
    fun retryAfterSeconds(header: String?, nowMillis: Long = System.currentTimeMillis()): Long {
        val raw = header?.trim() ?: return 0
        raw.toLongOrNull()?.let { return it.coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()) }
        return try {
            val difference = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMillis
            if (difference <= 0) 0 else (difference / 1000 + if (difference % 1000 > 0) 1 else 0).coerceAtMost(Int.MAX_VALUE.toLong())
        } catch (_: Exception) { 0 }
    }
}

class BiliAccessException(message: String, val retryAfterSeconds: Long = 0) : IllegalStateException(message)
