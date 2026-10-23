package com.example.kleigiftwatcher

import org.junit.Assert.*
import org.junit.Test

class ApiRatePolicyTest {
    @Test fun startsAtOneAndIncreasesOneSecondPerRestriction() {
        var increases = 0
        assertEquals(1, ApiRatePolicy.interval(1, increases))
        repeat(4) { index ->
            increases = ApiRatePolicy.nextIncrease(increases)
            assertEquals(index + 2, ApiRatePolicy.interval(1, increases))
        }
    }
    @Test fun respectsConfiguredBaseAndCapsWithoutOverflow() {
        assertEquals(63, ApiRatePolicy.interval(60, 3))
        assertEquals(3600, ApiRatePolicy.interval(Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(3600, ApiRatePolicy.nextIncrease(Int.MAX_VALUE))
        assertEquals(1, ApiRatePolicy.interval(-1, -1))
    }
    @Test fun distinguishesRestrictionsFromOrdinaryFailures() {
        for (http in listOf(403, 412, 429)) assertTrue(ApiRatePolicy.isRestricted(http = http))
        for (api in listOf(-352, -412, -509)) assertTrue(ApiRatePolicy.isRestricted(api = api))
        for (http in listOf(200, 404, 500)) assertFalse(ApiRatePolicy.isRestricted(http = http))
        for (api in listOf(0, -101, -404)) assertFalse(ApiRatePolicy.isRestricted(api = api))
    }
    @Test fun honorsRetryAfterSeconds() {
        assertEquals(600L, ApiRatePolicy.retryAfterSeconds("600", 0))
        assertEquals(0L, ApiRatePolicy.retryAfterSeconds("-1", 0))
    }
    @Test fun honorsRetryAfterHttpDateAndRoundsUp() {
        val now = java.time.Instant.parse("2026-10-24T00:00:00.500Z").toEpochMilli()
        assertEquals(60L, ApiRatePolicy.retryAfterSeconds("Sat, 24 Oct 2026 00:01:00 GMT", now))
        assertEquals(0L, ApiRatePolicy.retryAfterSeconds("Fri, 23 Oct 2026 23:59:00 GMT", now))
    }
    @Test fun neverReplacesCooldownWithOneSecondIncrement() {
        assertEquals(120L, ApiRatePolicy.cooldownSeconds(1, 0))
        assertEquals(300L, ApiRatePolicy.cooldownSeconds(2, 1))
        assertEquals(900L, ApiRatePolicy.cooldownSeconds(3, 0))
        assertEquals(1200L, ApiRatePolicy.cooldownSeconds(1, 1200))
    }
    @Test fun toleratesMissingOrInvalidRetryHeaders() {
        for (header in listOf(null, "", "later", "999999999999999999999999"))
            assertEquals(0L, ApiRatePolicy.retryAfterSeconds(header, 0))
    }
}
