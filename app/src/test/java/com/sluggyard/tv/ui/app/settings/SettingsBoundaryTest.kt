package com.sluggyard.tv.ui.app.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse

class SettingsBoundaryTest {
    @Test
    fun settingsSourcesDoNotImportProtectedPlaybackImplementations() {
        val sourceRoot = sequenceOf(
            File("src/main/java/com/sluggyard/tv/ui/app/settings"),
            File("app/src/main/java/com/sluggyard/tv/ui/app/settings"),
        ).first { it.isDirectory }
        val protectedImports = listOf(
            "ui.app.player",
            "ui.screens.player",
            "androidx.media3.exoplayer",
            "libmpv",
        )

        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val source = file.readText()
                protectedImports.forEach { forbidden ->
                    assertFalse(
                        source.contains(forbidden),
                        "${file.name} must not import protected playback implementation: $forbidden",
                    )
                }
            }
    }
}
