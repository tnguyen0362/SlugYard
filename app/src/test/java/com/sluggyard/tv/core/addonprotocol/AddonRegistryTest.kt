package com.sluggyard.tv.core.addonprotocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonRegistryTest {

    @Test
    fun `reorder preserves only live requested ids then appends unmentioned addons`() {
        val one = addon("one")
        val two = addon("two")
        val three = addon("three")

        val result = reduceAddonRegistry(
            AddonRegistryState(listOf(one, two, three)),
            AddonRegistryAction.Reorder(listOf("three", "removed", "three")),
        )

        assertEquals(listOf("three", "one", "two"), result.addons.map { it.manifest.id })
    }

    @Test
    fun `disabled addon remains configured but leaves enabled query set`() {
        val state = AddonRegistryState(listOf(addon("one"), addon("two")))

        val result = reduceAddonRegistry(state, AddonRegistryAction.SetEnabled("one", false))

        assertFalse(result.addons.first().enabled)
        assertEquals(listOf("two"), result.enabledAddons.map { it.manifest.id })
    }

    @Test
    fun `configuration updates use the same registry item without enabling a disabled addon`() {
        val state = AddonRegistryState(listOf(addon("one").copy(enabled = false)))

        val result = reduceAddonRegistry(
            state,
            AddonRegistryAction.SetConfiguredManifestUrl("one", "https://example.test/configured/manifest.json"),
        )

        assertFalse(result.addons.single().enabled)
        assertEquals("https://example.test/configured/manifest.json", result.addons.single().configuredManifestUrl)
    }

    @Test
    fun `adding the same manifest id replaces that addon rather than duplicating its behavior`() {
        val first = addon("one")
        val replacement = first.copy(manifestUrl = "https://replacement.test/manifest.json")

        val result = reduceAddonRegistry(
            AddonRegistryState(listOf(first)),
            AddonRegistryAction.Add(replacement),
        )

        assertEquals(1, result.addons.size)
        assertEquals(replacement.manifestUrl, result.addons.single().manifestUrl)
        assertTrue(result.addons.single().enabled)
    }

    private fun addon(id: String): ManagedAddon = ManagedAddon(
        manifestUrl = "https://$id.test/manifest.json",
        manifest = AddonManifestContract(id = id, name = id),
    )
}
