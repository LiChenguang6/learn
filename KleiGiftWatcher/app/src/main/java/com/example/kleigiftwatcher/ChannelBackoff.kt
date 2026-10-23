package com.example.kleigiftwatcher

enum class MonitorChannel { WEB, API }

/** One instance per channel. All deadlines use elapsed time; persistence stores remaining duration. */
class ChannelBackoff {
    private var deadline = 0L
    private var blocks = 0
    @Synchronized fun remainingMillis(now: Long): Long = (deadline - now).coerceAtLeast(0)
    @Synchronized fun restore(now: Long, remaining: Long) { deadline = now + remaining.coerceAtLeast(0) }
    @Synchronized fun block(now: Long, serverSeconds: Long = 0): Long {
        blocks = (blocks + 1).coerceAtMost(3)
        val seconds = ApiRatePolicy.cooldownSeconds(blocks, serverSeconds)
        deadline = maxOf(deadline, now + seconds * 1000L)
        return remainingMillis(now)
    }
    @Synchronized fun success(now: Long) {
        if (remainingMillis(now) > 0) return
        blocks = 0
        deadline = 0
    }
}
