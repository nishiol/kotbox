package ru.nishiol.kotbox.test

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class TestClock(
    var instant: Instant,
    private val zone: ZoneId = ZoneOffset.UTC
) : Clock() {
    private val initInstant = instant

    override fun instant(): Instant = instant

    override fun withZone(zone: ZoneId): Clock = TestClock(instant, zone)

    override fun getZone(): ZoneId = zone

    fun reset() {
        instant = initInstant
    }
}