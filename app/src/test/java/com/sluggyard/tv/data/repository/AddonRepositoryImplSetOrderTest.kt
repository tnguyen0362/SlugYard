package com.sluggyard.tv.data.repository

import android.content.Context
import com.sluggyard.tv.data.local.AddonPreferences
import com.sluggyard.tv.data.remote.api.AddonApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AddonRepositoryImplSetOrderTest {

    @Test
    fun `setAddonOrder preserves bundled infrastructure addons omitted from the proposed list`() = runTest {
        val preferences = mockk<AddonPreferences>(relaxed = true)
        val repository = newRepository(preferences)

        repository.setAddonOrder(listOf("https://torrentio.strem.fun"))

        coVerify {
            preferences.setAddonOrder(
                match { urls ->
                    urls.contains("https://torrentio.strem.fun") &&
                        urls.contains("https://v3-cinemeta.strem.io") &&
                        urls.contains("https://opensubtitles-v3.strem.io")
                }
            )
        }
    }

    @Test
    fun `setAddonOrder does not duplicate a bundled addon already present in the proposed list`() = runTest {
        val preferences = mockk<AddonPreferences>(relaxed = true)
        val repository = newRepository(preferences)

        repository.setAddonOrder(
            listOf("https://torrentio.strem.fun", "https://v3-cinemeta.strem.io", "https://opensubtitles-v3.strem.io")
        )

        coVerify {
            preferences.setAddonOrder(
                match { urls ->
                    urls.count { it.equals("https://v3-cinemeta.strem.io", ignoreCase = true) } == 1
                }
            )
        }
    }

    private fun newRepository(preferences: AddonPreferences): AddonRepositoryImpl {
        val context = mockk<Context>(relaxed = true)
        val api = mockk<AddonApi>(relaxed = true)
        return AddonRepositoryImpl(
            api = api,
            preferences = preferences,
            context = context
        )
    }
}
