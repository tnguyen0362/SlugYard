package com.sluggyard.tv.core.debrid

import com.sluggyard.tv.domain.model.DebridSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebridProvidersTest {
    @Test
    fun `configured services include every configured supported provider`() {
        val settings = DebridSettings(
            enabled = true,
            torboxApiKey = "tb",
            realDebridApiKey = "rd"
        )

        val services = DebridProviders.configuredServices(settings)

        assertEquals(
            listOf(DebridProviders.Torbox, DebridProviders.RealDebrid),
            services.map { it.provider }
        )
        assertTrue(DebridProviders.isVisible(DebridProviders.TORBOX_ID))
        assertTrue(DebridProviders.isVisible(DebridProviders.REAL_DEBRID_ID))
    }
}
