package com.sluggyard.tv.ui.rewrite.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse

class RewriteSettingsBoundaryTest {
    @Test
    fun settingsSourcesDoNotImportProtectedPlaybackImplementations() {
        val sourceRoot = sequenceOf(
            File("src/main/java/com/sluggyard/tv/ui/rewrite/settings"),
            File("app/src/main/java/com/sluggyard/tv/ui/rewrite/settings"),
        ).first { it.isDirectory }
        val protectedImports = listOf(
            "ui.rewrite.player",
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
