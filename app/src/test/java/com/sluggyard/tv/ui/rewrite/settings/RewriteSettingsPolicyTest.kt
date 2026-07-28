package com.sluggyard.tv.ui.rewrite.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RewriteSettingsPolicyTest {
    @Test
    fun lockedDownSurfaceUsesTheApprovedCategories() {
        assertEquals(
            listOf(
                RewriteSettingsCategory.Account,
                RewriteSettingsCategory.Profiles,
                RewriteSettingsCategory.Layout,
                RewriteSettingsCategory.ContentDiscovery,
                RewriteSettingsCategory.Integrations,
                RewriteSettingsCategory.Display,
                RewriteSettingsCategory.Subtitles,
                RewriteSettingsCategory.Audio,
                RewriteSettingsCategory.About,
            ),
            RewriteSettingsPolicy.categories,
        )
    }

    @Test
    fun lockedDownSurfaceDoesNotExposeExperienceOrNetworkSpeed() {
        val categoryNames = RewriteSettingsPolicy.categories.map { it.name }

        assertFalse(categoryNames.contains("Experience"))
        assertFalse(categoryNames.contains("Network Speed"))
    }

    @Test
    fun playerPolicyExposesOnlyEffectiveSettings() {
        assertTrue(RewriteSettingsPolicy.exposesPlayerSetting(RewritePlayerSetting.FrameRateMatching))
        assertTrue(RewriteSettingsPolicy.exposesPlayerSetting(RewritePlayerSetting.SubtitleLanguage))
        assertTrue(RewriteSettingsPolicy.exposesPlayerSetting(RewritePlayerSetting.AudioChannels))
        assertFalse(RewriteSettingsPolicy.exposesPlayerSetting(RewritePlayerSetting.EngineSelection))
        assertFalse(RewriteSettingsPolicy.exposesPlayerSetting(RewritePlayerSetting.DolbyVisionPolicy))
        assertFalse(RewriteSettingsPolicy.exposesPlayerSetting(RewritePlayerSetting.LibassRenderer))
        assertFalse(RewriteSettingsPolicy.exposesPlayerSetting(RewritePlayerSetting.BufferSizing))
        assertFalse(RewriteSettingsPolicy.exposesPlayerSetting(RewritePlayerSetting.Autoplay))
        assertFalse(RewriteSettingsPolicy.exposesPlayerSetting(RewritePlayerSetting.IntroSkip))
    }
}
