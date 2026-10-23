package com.example.kleigiftwatcher

import org.junit.Assert.*
import org.junit.Test

class ChannelBackoffTest {
    @Test fun webRestrictionLeavesApiReady() {
        val web = ChannelBackoff(); val api = ChannelBackoff()
        web.block(1000)
        assertEquals(120000L, web.remainingMillis(1000))
        assertEquals(0L, api.remainingMillis(1000))
    }
    @Test fun apiRestrictionLeavesWebReady() {
        val web = ChannelBackoff(); val api = ChannelBackoff()
        api.block(1000)
        assertEquals(0L, web.remainingMillis(1000))
        assertEquals(120000L, api.remainingMillis(1000))
    }
    @Test fun webSuccessCannotClearApiCooldown() {
        val web = ChannelBackoff(); val api = ChannelBackoff()
        api.block(1000)
        web.success(2000)
        assertEquals(119000L, api.remainingMillis(2000))
    }
    @Test fun apiSuccessCannotClearWebCooldown() {
        val web = ChannelBackoff(); val api = ChannelBackoff()
        web.block(1000)
        api.success(2000)
        assertEquals(119000L, web.remainingMillis(2000))
    }
    @Test fun restrictionCountsAreIndependent() {
        val web = ChannelBackoff(); val api = ChannelBackoff()
        web.block(1000); web.block(122000)
        api.block(122000)
        assertEquals(300000L, web.remainingMillis(122000))
        assertEquals(120000L, api.remainingMillis(122000))
    }
    @Test fun restoringOneChannelLeavesOtherUntouched() {
        val web = ChannelBackoff(); val api = ChannelBackoff()
        web.restore(1000, 60000)
        api.restore(1000, 20000)
        assertEquals(50000L, web.remainingMillis(11000))
        assertEquals(10000L, api.remainingMillis(11000))
        assertEquals(0L, api.remainingMillis(22000))
        assertEquals(39000L, web.remainingMillis(22000))
    }
    @Test fun inFlightSuccessCannotCancelOwnCooldown() {
        val backoff = ChannelBackoff()
        backoff.block(1000); backoff.success(2000)
        assertEquals(119000L, backoff.remainingMillis(2000))
        backoff.success(122000)
        assertEquals(120000L, backoff.block(123000))
    }
    @Test fun honorsServerDeadlineWithoutShorteningIt() {
        val backoff = ChannelBackoff()
        backoff.block(1000, 1200)
        backoff.block(2000, 0)
        assertEquals(1199000L, backoff.remainingMillis(2000))
    }
}
