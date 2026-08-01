package com.sluggyard.tv.ui.app.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsPolicyTest {
    @Test
    fun lockedDownSurfaceUsesTheApprovedCategories() {
        assertEquals(
            listOf(
                SettingsCategory.Account,
                SettingsCategory.Profiles,
                SettingsCategory.Layout,
                SettingsCategory.Addons,
                SettingsCategory.Integrations,
                SettingsCategory.Display,
                SettingsCategory.Subtitles,
                SettingsCategory.Audio,
                SettingsCategory.About,
            ),
            SettingsPolicy.categories,
        )
    }

    @Test
    fun lockedDownSurfaceDoesNotExposeExperienceOrNetworkSpeed() {
        val categoryNames = SettingsPolicy.categories.map { it.name }

        assertFalse(categoryNames.contains("Experience"))
        assertFalse(categoryNames.contains("Network Speed"))
    }

    @Test
    fun playerPolicyExposesOnlyEffectiveSettings() {
        assertTrue(SettingsPolicy.exposesPlayerSetting(PlayerSetting.FrameRateMatching))
        assertTrue(SettingsPolicy.exposesPlayerSetting(PlayerSetting.SubtitleLanguage))
        assertTrue(SettingsPolicy.exposesPlayerSetting(PlayerSetting.AudioChannels))
        assertFalse(SettingsPolicy.exposesPlayerSetting(PlayerSetting.EngineSelection))
        assertFalse(SettingsPolicy.exposesPlayerSetting(PlayerSetting.DolbyVisionPolicy))
        assertFalse(SettingsPolicy.exposesPlayerSetting(PlayerSetting.LibassRenderer))
        assertFalse(SettingsPolicy.exposesPlayerSetting(PlayerSetting.BufferSizing))
        assertFalse(SettingsPolicy.exposesPlayerSetting(PlayerSetting.Autoplay))
        assertFalse(SettingsPolicy.exposesPlayerSetting(PlayerSetting.IntroSkip))
    }
}
