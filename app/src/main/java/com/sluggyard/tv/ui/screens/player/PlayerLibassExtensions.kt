package com.sluggyard.tv.ui.screens.player

import com.sluggyard.tv.data.local.LibassRenderType
import io.github.peerless2012.ass.media.type.AssRenderType

/** Maps the app-side libass render enum onto the upstream media library's enum. */
internal fun LibassRenderType.toAssRenderType(): AssRenderType = when (this) {
    LibassRenderType.CUES -> AssRenderType.CUES
    LibassRenderType.EFFECTS_CANVAS -> AssRenderType.EFFECTS_CANVAS
    LibassRenderType.EFFECTS_OPEN_GL -> AssRenderType.EFFECTS_OPEN_GL
    LibassRenderType.OVERLAY_CANVAS -> AssRenderType.OVERLAY_CANVAS
    LibassRenderType.OVERLAY_OPEN_GL -> AssRenderType.OVERLAY_OPEN_GL
}