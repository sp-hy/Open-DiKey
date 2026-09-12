package com.sphy.airconcontroller.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {
    @Test
    fun newerDateStampBeatsOlder() {
        assertTrue(AppUpdater.isNewer("2026.09.12-0357", "2026.09.12-0218"))
        assertFalse(AppUpdater.isNewer("2026.09.12-0218", "2026.09.12-0357"))
    }

    @Test
    fun tagPrefixIsIgnored() {
        assertTrue(AppUpdater.isNewer("v2026.09.12-0357", "2026.09.12-0218"))
    }

    @Test
    fun legacyVersionIsAlwaysOlder() {
        assertTrue(AppUpdater.isNewer("2026.09.12-0357", "1.0"))
        assertTrue(AppUpdater.isNewer("2026.09.12-0357", "1.0-dev"))
    }

    @Test
    fun sameVersionIsNotNewer() {
        assertFalse(AppUpdater.isNewer("2026.09.12-0357", "v2026.09.12-0357"))
    }
}
