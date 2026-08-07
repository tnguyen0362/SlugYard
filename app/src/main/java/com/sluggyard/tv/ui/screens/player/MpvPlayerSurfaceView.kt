package com.sluggyard.tv.ui.screens.player

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import com.sluggyard.tv.data.local.AudioOutputChannels
import com.sluggyard.tv.data.local.MpvHardwareDecodeMode
import com.sluggyard.tv.data.local.SubtitleStyleSettings
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * libmpv-backed [SurfaceView] player surface.
 *
 * Wraps the bundled [BaseMPVView] and exposes a focused, TV-friendly API for
 * the SlugYard player runtime: media loading with start position, track
 * selection (audio/subtitle), subtitle styling, aspect modes, hardware decode
 * modes, audio amplification, and a bounded retry loop for embedded subtitle
 * language preferences (MKV track discovery can complete after the first pass).
 */
class MpvPlayerSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : BaseMPVView(context, attrs) {

    @Volatile private var initialized = false
    private var mpvSurfaceAttached = false
    private var hasQueuedInitialMedia = false
    private var lastMediaRequestKey: String? = null
    private var pendingInitialMediaUrl: String? = null
    private var pendingInitialStartOption: String? = null
    private var preferredSubtitleLanguages: List<String> = emptyList()
    private var preferAssSubtitles: Boolean = false
    /** Once the user picks a track/off, stop preferAss retry and auto re-selection. */
    @Volatile
    private var userPinnedSubtitleSelection: Boolean = false
    private var subtitleSelectionRetryCount = 0
    private var cachePausePropertySupported: Boolean? = null

    private val subtitleSelectionRunnable = object : Runnable {
        override fun run() {
            if (!initialized || preferredSubtitleLanguages.isEmpty()) return
            // Manual pick wins over the delayed preferAss / slang retry loop.
            if (userPinnedSubtitleSelection) return
            // MKV track discovery can complete after the first preference pass.
            // Do not treat an arbitrary auto-selected track as success: anime
            // releases often expose an early forced/Japanese track before the
            // requested ASS/English track is indexed. Keep the bounded retry
            // only until the selected track actually matches the preference.
            if (hasSelectedPreferredSubtitleTrack()) return
            runCatching {
                selectPreferredSubtitleTrack()
            }.onFailure { Log.w(TAG, "Failed to apply deferred subtitle preference: ${it.message}") }
            subtitleSelectionRetryCount += 1
            if (subtitleSelectionRetryCount < SUBTITLE_SELECTION_RETRY_COUNT) {
                postDelayed(this, SUBTITLE_SELECTION_RETRY_DELAY_MS)
            }
        }
    }

    private var hardwareDecodeMode: MpvHardwareDecodeMode = MpvHardwareDecodeMode.AUTO_SAFE
    private var currentAspectMode: AspectMode = AspectMode.ORIGINAL
    private var pendingAspectRetryCount = 0
    private val aspectReapplyRunnable = Runnable {
        applyAspectModeInternal(currentAspectMode, allowRetry = true)
    }

    fun ensureInitialized() {
        if (initialized) return
        Utils.copyAssets(context)
        cachePausePropertySupported = null
        initialize(configDir = context.filesDir.path, cacheDir = context.cacheDir.path)
        initialized = true
    }

    fun setMedia(url: String, headers: Map<String, String>, startPositionMs: Long = 0L) {
        ensureInitialized()
        // New media: auto preferAss / slang may run again until the user picks a track.
        userPinnedSubtitleSelection = false
        val requestKey = buildMediaRequestKey(url, headers) + "#start=${startPositionMs.coerceAtLeast(0L)}"
        if (hasQueuedInitialMedia && requestKey == lastMediaRequestKey) return

        applyHeaders(headers)
        val startOption = startPositionMs
            .takeIf { it > 0L }
            ?.let { String.format(Locale.US, "start=%.3f", it / 1000.0) }

        when {
            else -> {
                // Keep media ownership in this subclass. BaseMPVView.playFile() stores a private
                // path, while this view also has a resume-aware pending request; mixing the two
                // queues can reach an attached, idle MPV without ever issuing loadfile.
                pendingInitialMediaUrl = url
                pendingInitialStartOption = startOption
                hasQueuedInitialMedia = true
                if (holder.surface?.isValid == true) {
                    ensureSurfaceAttachedIfAlreadyAvailable()
                    issuePendingMediaLoadIfAttached()
                }
            }
        }
        lastMediaRequestKey = requestKey
        applyDefaultTrackSelectionForNewLoad()
        scheduleAspectModeRefresh(resetRetryCount = true)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        super.surfaceCreated(holder)
        mpvSurfaceAttached = true
        issuePendingMediaLoadIfAttached()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        mpvSurfaceAttached = false
        super.surfaceDestroyed(holder)
    }

    fun setMediaUsingLoadfile(url: String, headers: Map<String, String>) {
        ensureInitialized()
        val requestKey = buildMediaRequestKey(url, headers)
        applyHeaders(headers)
        pendingInitialMediaUrl = url
        pendingInitialStartOption = null
        if (holder.surface?.isValid == true) {
            ensureSurfaceAttachedIfAlreadyAvailable()
            issuePendingMediaLoadIfAttached()
        }
        hasQueuedInitialMedia = true
        lastMediaRequestKey = requestKey
        applyDefaultTrackSelectionForNewLoad()
        scheduleAspectModeRefresh(resetRetryCount = true)
    }

    private fun ensureSurfaceAttachedIfAlreadyAvailable() {
        if (!initialized) return
        val currentHolder = holder
        val currentSurface = currentHolder.surface ?: return
        if (!currentSurface.isValid) return
        if (mpvSurfaceAttached) return
        // Some fallback transitions initialize mpv after the Surface is already
        // alive; in that path the SurfaceHolder callback may not fire again, so
        // force attach.
        runCatching { surfaceCreated(currentHolder) }
            .onFailure { Log.w(TAG, "Failed to force MPV surface attach: ${it.message}") }
    }

    private fun issuePendingMediaLoadIfAttached() {
        if (!initialized || !mpvSurfaceAttached) return
        val url = pendingInitialMediaUrl ?: return
        val startOption = pendingInitialStartOption
        pendingInitialMediaUrl = null
        pendingInitialStartOption = null
        runCatching {
            // mpv 0.38 added the playlist index as the third argument. Per-file options now
            // occupy the fourth argument, so resume loads must pass -1 before start=seconds.
            if (startOption != null) mpv.command("loadfile", url, "replace", "-1", startOption)
            else mpv.command("loadfile", url, "replace")
            Log.d(TAG, "Issued loadfile after surface attach start=${startOption != null}")
        }.onFailure { failure ->
            Log.w(TAG, "Failed to issue loadfile: ${failure.message}")
        }
    }

    private fun applyDefaultTrackSelectionForNewLoad() {
        runCatching {
            mpv.setPropertyString("aid", "auto")
            mpv.setPropertyString("sid", "auto")
            mpv.setPropertyBoolean("sub-visibility", true)
        }.onFailure { Log.w(TAG, "Failed to reset default A/V track selection: ${it.message}") }
    }

    fun setPaused(paused: Boolean) {
        if (!initialized) return
        mpv.setPropertyBoolean("pause", paused)
    }

    fun isPlayingNow(): Boolean {
        if (!initialized) return false
        return mpv.getPropertyBoolean("pause") == false
    }

    fun isPausedForCacheNow(): Boolean {
        if (!initialized || cachePausePropertySupported == false) return false
        return runCatching { mpv.getPropertyBoolean("paused-for-cache") }
            .fold(
                onSuccess = { value ->
                    cachePausePropertySupported = value != null
                    value == true
                },
                onFailure = {
                    // Older libmpv Android builds may omit this property. Probe once,
                    // then keep the normal pause/core-idle fallback without log spam.
                    cachePausePropertySupported = false
                    false
                },
            )
    }

    fun isCoreIdleNow(): Boolean {
        if (!initialized) return false
        return mpv.getPropertyBoolean("core-idle") == true
    }

    fun seekToMs(positionMs: Long) {
        if (!initialized) return
        mpv.setPropertyDouble("time-pos", positionMs.coerceAtLeast(0L) / 1000.0)
    }

    fun currentPositionMs(): Long {
        if (!initialized) return 0L
        val seconds = mpv.getPropertyDouble("time-pos") ?: 0.0
        return (seconds * 1000.0).roundToLong().coerceAtLeast(0L)
    }

    fun durationMs(): Long {
        if (!initialized) return 0L
        val seconds = mpv.getPropertyDouble("duration") ?: 0.0
        return (seconds * 1000.0).roundToLong().coerceAtLeast(0L)
    }

    fun hasVideoTrackSelectedNow(): Boolean {
        if (!initialized) return false
        val vid = mpv.getPropertyString("vid")?.trim()
        return !vid.isNullOrBlank() && !vid.equals("no", ignoreCase = true)
    }

    fun setPlaybackSpeed(speed: Float) {
        if (!initialized) return
        mpv.setPropertyDouble("speed", speed.toDouble())
    }

    fun applyAudioAmplificationDb(db: Int) {
        if (!initialized) return
        val clampedDb = db.coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
        val linearScale = 10.0.pow(clampedDb / 20.0)
        val targetVolumePercent = (100.0 * linearScale).coerceIn(0.0, MPV_MAX_VOLUME_PERCENT)
        runCatching { mpv.setPropertyDouble("volume", targetVolumePercent) }
            .onFailure { Log.w(TAG, "Failed to apply audio amplification on mpv (db=$clampedDb): ${it.message}") }
    }

    fun applyAudioLanguagePreferences(languages: List<String>) {
        if (!initialized) return
        val normalized = languages.mapNotNull { it.trim().takeIf(String::isNotBlank) }.distinct()
        runCatching {
            // Empty value resets language preference back to default behavior.
            mpv.setPropertyString("alang", normalized.joinToString(","))
            // Re-run automatic audio selection with the latest preferences.
            mpv.setPropertyString("aid", "auto")
        }.onFailure { Log.w(TAG, "Failed to set audio language preference: ${it.message}") }
    }

    fun applyAudioOutputSettings(
        downmixEnabled: Boolean,
        outputChannels: AudioOutputChannels,
        maintainOriginalMix: Boolean,
    ) {
        if (!initialized) return
        runCatching {
            mpv.setPropertyString(
                "audio-channels",
                if (downmixEnabled) outputChannels.ffmpegLayoutName else "auto-safe",
            )
            mpv.setPropertyBoolean(
                "audio-normalize-downmix",
                downmixEnabled && !maintainOriginalMix,
            )
        }.onFailure {
            Log.w(TAG, "Failed to apply mpv audio output settings: ${it.message}")
        }
    }

    fun applyHardwareDecodeMode(mode: MpvHardwareDecodeMode) {
        hardwareDecodeMode = mode
        if (!initialized) return
        runCatching { mpv.setPropertyString("hwdec", mode.toMpvHwdecValue()) }
            .onFailure { Log.w(TAG, "Failed to apply mpv hardware decode mode ($mode): ${it.message}") }
    }

    fun setSubtitleDelayMs(delayMs: Int) {
        if (!initialized) return
        runCatching { mpv.setPropertyDouble("sub-delay", delayMs / 1000.0) }
            .onFailure { Log.w(TAG, "Failed to set subtitle delay on mpv: ${it.message}") }
    }

    fun applyAspectMode(mode: AspectMode) {
        currentAspectMode = mode
        pendingAspectRetryCount = 0
        removeCallbacks(aspectReapplyRunnable)
        applyAspectModeInternal(mode, allowRetry = true)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == oldw && h == oldh) return
        pendingAspectRetryCount = 0
        removeCallbacks(aspectReapplyRunnable)
        post { applyAspectModeInternal(currentAspectMode, allowRetry = true) }
    }

    private fun applyAspectModeInternal(mode: AspectMode, allowRetry: Boolean) {
        val viewAspect = readViewAspectRatio(width, height)
        val videoAspect = readVideoAspectRatio()
        val scale = resolveAspectScale(mode = mode, viewAspect = viewAspect, videoAspect = videoAspect)
        scaleX = scale.scaleX
        scaleY = scale.scaleY
        if (allowRetry && aspectModeNeedsVideoAspect(mode) &&
            (viewAspect <= 0f || videoAspect == null || videoAspect <= 0f)
        ) {
            scheduleAspectModeRefresh(resetRetryCount = false)
        }
    }

    private fun scheduleAspectModeRefresh(resetRetryCount: Boolean) {
        if (resetRetryCount) pendingAspectRetryCount = 0
        removeCallbacks(aspectReapplyRunnable)
        if (pendingAspectRetryCount >= MAX_ASPECT_RETRY_COUNT) return
        val delayMs = if (pendingAspectRetryCount == 0) 0L else ASPECT_RETRY_DELAY_MS
        pendingAspectRetryCount += 1
        postDelayed(aspectReapplyRunnable, delayMs)
    }

    fun applySubtitleStyle(style: SubtitleStyleSettings) {
        if (!initialized) return
        runCatching {
            val scale = (style.size / 100.0).coerceIn(0.5, 3.0)
            val clampedOffset = style.verticalOffset.coerceIn(SUBTITLE_VERTICAL_OFFSET_MIN, SUBTITLE_VERTICAL_OFFSET_MAX)
            // mpv sub-pos: 100 = absolute bottom, 0 = top. 0% offset uses a normal
            // resting inset (~5%) to match Exo, not flush-to-bezel.
            val subPos = (MPV_SUB_POS_AT_ZERO - clampedOffset.toDouble()).coerceIn(
                MPV_SUB_POS_AT_ZERO - SUBTITLE_VERTICAL_OFFSET_MAX,
                MPV_SUB_POS_AT_ZERO - SUBTITLE_VERTICAL_OFFSET_MIN
            )
            val outlineSize = when {
                !style.outlineEnabled -> 0.0
                else -> style.outlineWidth.coerceIn(1, 6).toDouble()
            }
            val backgroundAlpha = (style.backgroundColor ushr 24) and 0xFF
            val borderStyle = if (backgroundAlpha > 0) "opaque-box" else "outline-and-shadow"

            // ASS/SSA embeds its own Style (often heavy Bold + Outline). With
            // sub-ass-override=no those completely ignore Settings / in-player
            // size, color, bold, and outline — so user tweaks looked broken.
            // force applies sub-* properties to every subtitle type including ASS.
            mpv.setPropertyString("sub-ass-override", "force")
            mpv.setPropertyDouble("sub-scale", scale)
            mpv.setPropertyBoolean("sub-bold", style.bold)
            mpv.setPropertyDouble("sub-outline-size", outlineSize)
            mpv.setPropertyDouble("sub-pos", subPos)
            // Position is fully owned by sub-pos; do not add a second vertical lift.
            mpv.setPropertyInt("sub-margin-y", 0)
            mpv.setPropertyDouble("sub-shadow-offset", 0.0)
            mpv.setPropertyString("sub-border-style", borderStyle)
            mpv.setPropertyString("sub-color", toMpvColor(style.textColor))
            mpv.setPropertyString("sub-back-color", toMpvColor(style.backgroundColor))
            mpv.setPropertyString("sub-outline-color", toMpvColor(style.outlineColor))
        }.onFailure { Log.w(TAG, "Failed to apply subtitle style on mpv: ${it.message}") }
    }

    fun selectAudioTrackById(trackId: Int): Boolean {
        if (!initialized) return false
        return runCatching { mpv.setPropertyInt("aid", trackId); true }
            .onFailure { Log.w(TAG, "Failed to select audio track id=$trackId: ${it.message}") }
            .getOrDefault(false)
    }

    /**
     * Call when the user (or an intentional restore of their sticky pick) chooses a
     * specific subtitle track / addon / Off. Cancels the delayed preferAss retry that
     * otherwise keeps stomping non-ASS and external (OpenSubtitles) picks.
     */
    fun pinUserSubtitleSelection() {
        userPinnedSubtitleSelection = true
        removeCallbacks(subtitleSelectionRunnable)
    }

    fun selectSubtitleTrackById(trackId: Int): Boolean {
        if (!initialized) return false
        return runCatching {
            mpv.setPropertyBoolean("sub-visibility", true)
            mpv.setPropertyInt("sid", trackId)
            true
        }.onFailure { Log.w(TAG, "Failed to select subtitle track id=$trackId: ${it.message}") }
            .getOrDefault(false)
    }

    fun disableSubtitles(): Boolean {
        if (!initialized) return false
        return runCatching {
            mpv.setPropertyString("sid", "no")
            mpv.setPropertyBoolean("sub-visibility", false)
            true
        }.onFailure { Log.w(TAG, "Failed to disable subtitles: ${it.message}") }
            .getOrDefault(false)
    }

    fun addAndSelectExternalSubtitle(
        url: String,
        title: String? = null,
        language: String? = null
    ): Boolean {
        if (!initialized) return false
        if (url.isBlank()) return false
        return runCatching {
            // "cached" avoids duplicate re-loads for the same external subtitle.
            val safeTitle = title?.takeIf { it.isNotBlank() }
            val safeLanguage = language?.takeIf { it.isNotBlank() }
            when {
                safeTitle != null && safeLanguage != null ->
                    mpv.command("sub-add", url, "cached", safeTitle, safeLanguage)
                safeTitle != null -> mpv.command("sub-add", url, "cached", safeTitle)
                else -> mpv.command("sub-add", url, "cached")
            }
            mpv.setPropertyBoolean("sub-visibility", true)
            true
        }.onFailure { Log.w(TAG, "Failed to add external subtitle: ${it.message}") }
            .getOrDefault(false)
    }

    fun applySubtitleLanguagePreferences(
        preferred: String,
        secondary: String?,
        preferAss: Boolean = false,
    ) {
        if (!initialized) return
        preferAssSubtitles = preferAss
        val languages = listOfNotNull(
            preferred.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) },
            secondary?.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
        ).ifEmpty { if (preferAss) listOf("en") else emptyList() }
        if (languages.isEmpty()) {
            preferredSubtitleLanguages = emptyList()
            removeCallbacks(subtitleSelectionRunnable)
            disableSubtitles()
            return
        }
        preferredSubtitleLanguages = languages
        subtitleSelectionRetryCount = 0
        removeCallbacks(subtitleSelectionRunnable)
        runCatching {
            selectPreferredSubtitleTrack()
        }.onFailure { Log.w(TAG, "Failed to set subtitle language preference: ${it.message}") }
        postDelayed(subtitleSelectionRunnable, SUBTITLE_SELECTION_RETRY_DELAY_MS)
    }

    private fun selectPreferredSubtitleTrack() {
        if (userPinnedSubtitleSelection) return
        mpv.setPropertyString("slang", preferredSubtitleLanguages.joinToString(","))
        mpv.setPropertyBoolean("sub-visibility", true)
        val preferredAss = if (preferAssSubtitles) {
            pickPreferredAssSubtitleTrackId()
        } else {
            null
        }
        if (preferredAss != null) {
            mpv.setPropertyInt("sid", preferredAss)
            Log.d(TAG, "preferAss selected sid=$preferredAss")
        } else {
            mpv.setPropertyString("sid", "auto")
        }
    }

    private fun pickPreferredAssSubtitleTrackId(): Int? {
        val tracks = readTrackSnapshot().subtitleTracks.filterNot { it.isExternal }
        if (tracks.isEmpty()) return null
        fun isAss(track: MpvTrack): Boolean {
            val haystack = listOfNotNull(track.codec, track.name).joinToString(" ").lowercase(Locale.US)
            return "ass" in haystack || "ssa" in haystack
        }
        fun languageScore(track: MpvTrack): Int {
            val language = track.language ?: track.name
            return preferredSubtitleLanguages.indexOfFirst { preferred ->
                PlayerSubtitleUtils.matchesLanguageCode(language, preferred)
            }.let { if (it < 0) 100 else it }
        }
        // Dialogue/full before Signs & Songs — lowest id ASS is often the signs track.
        fun dialogueScore(track: MpvTrack): Int =
            PlayerSubtitleUtils.assDialoguePreferenceScore(
                name = track.name,
                isForced = track.isForced,
            )
        val ranked = tracks
            .filter(::isAss)
            .sortedWith(
                compareBy(::languageScore)
                    .thenBy(::dialogueScore)
                    .thenBy { it.id },
            )
        return ranked.firstOrNull()?.id
    }

    private fun hasSelectedPreferredSubtitleTrack(): Boolean {
        val selectedTracks = readTrackSnapshot().subtitleTracks.filter { it.isSelected }
        if (selectedTracks.isEmpty()) return false
        if (preferAssSubtitles) {
            val selected = selectedTracks.first()
            val haystack = listOfNotNull(selected.codec, selected.name).joinToString(" ").lowercase(Locale.US)
            val isAss = "ass" in haystack || "ssa" in haystack
            if (!isAss) return false
            // Keep retrying when MPV landed on Signs & Songs while a dialogue ASS exists.
            val preferredDialogueId = pickPreferredAssSubtitleTrackId()
            if (preferredDialogueId != null && selected.id != preferredDialogueId) return false
            val language = selected.language ?: selected.name
            if (language.isBlank()) return false
            return preferredSubtitleLanguages.any { preferred ->
                PlayerSubtitleUtils.matchesLanguageCode(language, preferred)
            }
        }
        val selectedLanguages = selectedTracks.mapNotNull { it.language }
        if (selectedLanguages.isEmpty()) {
            // A selected track without a language tag cannot be evaluated safely,
            // so preserve MPV's choice rather than repeatedly toggling a valid
            // unlabeled ASS subtitle.
            return selectedTracks.isNotEmpty()
        }
        return selectedLanguages.any { language ->
            preferredSubtitleLanguages.any { preferred ->
                PlayerSubtitleUtils.matchesLanguageCode(language, preferred)
            }
        }
    }

    fun readTrackSnapshot(): MpvTrackSnapshot {
        if (!initialized) return MpvTrackSnapshot(emptyList(), emptyList())
        val nodeTracks = runCatching { mpv.getPropertyNode("track-list") }
            .getOrNull()?.asArray()
            ?: return readTrackSnapshotLegacy()

        // Node `selected` flags can lag behind sid/aid after preferAss / slang applies.
        // Cross-check active ids the same way the legacy path does so the overlay does not
        // show Off while ASS is already painting.
        val selectedAudioId = mpv.getPropertyString("aid")?.toIntOrNull()
            ?: mpv.getPropertyInt("current-tracks/audio/id")
        val selectedSubtitleId = mpv.getPropertyString("sid")?.toIntOrNull()
            ?: mpv.getPropertyInt("current-tracks/sub/id")

        val audioTracks = mutableListOf<MpvTrack>()
        val subtitleTracks = mutableListOf<MpvTrack>()

        for (node in nodeTracks) {
            val values = node.asMap() ?: continue
            val type = values.stringValue("type")?.lowercase(Locale.US) ?: continue
            if (type != "audio" && type != "sub") continue
            val id = values.intValue("id")?.toInt() ?: continue
            val language = values.stringValue("lang")
            val title = values.stringValue("title")
            val codec = values.stringValue("codec")
            val selectedByFlag = values.booleanValue("selected") == true
            val external = values.booleanValue("external") == true
            val selected = when (type) {
                "audio" -> (selectedAudioId != null && selectedAudioId == id) || selectedByFlag
                "sub" -> (selectedSubtitleId != null && selectedSubtitleId == id) || selectedByFlag
                else -> selectedByFlag
            }

            when (type) {
                "audio" -> audioTracks += MpvTrack(
                    id = id, type = type,
                    name = title ?: language ?: context.getString(com.sluggyard.tv.R.string.player_track_audio_fallback, id),
                    language = language, codec = codec,
                    channelCount = values.intValue("demux-channel-count")?.toInt()
                        ?: values.intValue("audio-channels")?.toInt()
                        ?: values.intValue("channels")?.toInt(),
                    isSelected = selected, isForced = false, isExternal = external
                )
                "sub" -> subtitleTracks += MpvTrack(
                    id = id, type = type,
                    name = title ?: language ?: context.getString(com.sluggyard.tv.R.string.player_track_subtitle_fallback, id),
                    language = language, codec = codec, channelCount = null,
                    isSelected = selected,
                    isForced = values.booleanValue("forced") == true ||
                        listOfNotNull(title, language).any { it.contains("forced", ignoreCase = true) },
                    isExternal = external
                )
            }
        }
        return MpvTrackSnapshot(audioTracks = audioTracks, subtitleTracks = subtitleTracks)
    }

    /**
     * Compatibility path for an older libmpv build that cannot expose a node
     * property. Current builds take the single-node path above, avoiding one
     * JNI round-trip per field for every embedded subtitle track.
     */
    private fun readTrackSnapshotLegacy(): MpvTrackSnapshot {
        val trackCount = runCatching { mpv.getPropertyInt("track-list/count") ?: 0 }.getOrDefault(0)
        if (trackCount <= 0) return MpvTrackSnapshot(emptyList(), emptyList())

        val selectedAudioId = mpv.getPropertyString("aid")?.toIntOrNull()
            ?: mpv.getPropertyInt("current-tracks/audio/id")
        val selectedSubtitleId = mpv.getPropertyString("sid")?.toIntOrNull()
            ?: mpv.getPropertyInt("current-tracks/sub/id")

        val audioTracks = mutableListOf<MpvTrack>()
        val subtitleTracks = mutableListOf<MpvTrack>()

        for (i in 0 until trackCount) {
            val type = mpv.getPropertyString("track-list/$i/type")?.lowercase() ?: continue
            val id = mpv.getPropertyInt("track-list/$i/id") ?: continue
            val language = mpv.getPropertyString("track-list/$i/lang")?.trim()?.takeIf { it.isNotBlank() }
            val title = mpv.getPropertyString("track-list/$i/title")?.trim()?.takeIf { it.isNotBlank() }
            val codec = mpv.getPropertyString("track-list/$i/codec")?.trim()?.takeIf { it.isNotBlank() }
            val selectedByFlag = mpv.getPropertyBoolean("track-list/$i/selected") == true
            val external = mpv.getPropertyBoolean("track-list/$i/external") == true
            val channelCount = mpv.getPropertyInt("track-list/$i/demux-channel-count")
                ?: mpv.getPropertyInt("track-list/$i/audio-channels")
                ?: mpv.getPropertyInt("track-list/$i/channels")
            val forced = (mpv.getPropertyBoolean("track-list/$i/forced") == true) ||
                listOfNotNull(title, language).any { it.contains("forced", ignoreCase = true) }
            val selected = when (type) {
                "audio" -> (selectedAudioId != null && selectedAudioId == id) || selectedByFlag
                "sub" -> (selectedSubtitleId != null && selectedSubtitleId == id) || selectedByFlag
                else -> selectedByFlag
            }

            when (type) {
                "audio" -> audioTracks += MpvTrack(
                    id = id, type = type,
                    name = title ?: language ?: context.getString(com.sluggyard.tv.R.string.player_track_audio_fallback, id),
                    language = language, codec = codec, channelCount = channelCount,
                    isSelected = selected, isForced = false, isExternal = external
                )
                "sub" -> subtitleTracks += MpvTrack(
                    id = id, type = type,
                    name = title ?: language ?: context.getString(com.sluggyard.tv.R.string.player_track_subtitle_fallback, id),
                    language = language, codec = codec, channelCount = null,
                    isSelected = selected, isForced = forced, isExternal = external
                )
            }
        }
        return MpvTrackSnapshot(audioTracks = audioTracks, subtitleTracks = subtitleTracks)
    }

    fun releasePlayer() {
        if (!initialized) return
        removeCallbacks(aspectReapplyRunnable)
        removeCallbacks(subtitleSelectionRunnable)
        runCatching { destroy() }
            .onFailure { Log.w(TAG, "Failed to destroy libmpv view cleanly: ${it.message}") }
        initialized = false
        hasQueuedInitialMedia = false
        lastMediaRequestKey = null
        pendingInitialMediaUrl = null
        pendingInitialStartOption = null
        mpvSurfaceAttached = false
    }

    override fun initOptions() {
        // Video output — gpu with Android surface context.
        setVo("gpu")
        mpv.setOptionString("gpu-context", "android")
        mpv.setOptionString("opengl-es", "yes")
        mpv.setOptionString("gpu-sw", "no")

        // HDR tone-mapping — Mobius (clip+linear) for clean SDR display on all Android TVs.
        mpv.setOptionString("tone-mapping", "mobius")
        mpv.setOptionString("tone-mapping-max-boost", "2.0")
        mpv.setOptionString("hdr-compute-peak", "no")

        // Video sync — audio-based for smooth playback without display resampling overhead.
        mpv.setOptionString("video-sync", "audio")
        mpv.setOptionString("framedrop", "vo")
        // This is a VOD player, not a live camera feed. MPV's latency hacks save
        // only a frame or two while making timing less conservative on some
        // Android TV decoders, which shows up as choppiness or A/V drift.
        mpv.setOptionString("video-latency-hacks", "no")

        // Network / user agent.
        mpv.setOptionString("user-agent", PlayerMediaSourceFactory.DEFAULT_USER_AGENT)

        // Subtitles — applySubtitleStyle sets sub-ass-override=force so Settings /
        // in-player size/color/bold/outline win over embedded ASS Style blocks.
        // Default here matches that; embedded ASS fonts are not separately selectable.
        mpv.setOptionString("sub-ass-override", "force")
        mpv.setOptionString("sub-font", "sans-serif")
        mpv.setOptionString("sub-use-margins", "yes")
        mpv.setOptionString("sub-ass-force-margins", "yes")
        // Replaces the removed `sub-ass-vsfilter-aspect-compat` option in mpv 0.41
        // while retaining the intended ASS video-aspect behavior.
        mpv.setOptionString("sub-ass-use-video-data", "aspect-ratio")
        mpv.setOptionString("sub-ass-stretch-rects", "yes")
        mpv.setOptionString("sub-auto", "fuzzy")
        mpv.setOptionString("sub-font-size", "42")

        // Hardware decoding.
        mpv.setOptionString("hwdec", hardwareDecodeMode.toMpvHwdecValue())
        // Do not force AV1 or an NV12 image path globally. On lower-end TV boxes
        // those overrides can select a fragile hardware route instead of letting
        // mpv reject an unsupported codec and use its safe fallback.

        // Audio — AAudio preferred (lowest latency), fallback chain.
        mpv.setOptionString("ao", "aaudio,audiotrack,opensles")
        mpv.setOptionString("audio-set-media-role", "yes")
        mpv.setOptionString("audio-pitch-correction", "yes")
        mpv.setOptionString("audio-buffer", "0.2")

        // TLS.
        mpv.setOptionString("tls-verify", "yes")
        mpv.setOptionString("tls-ca-file", "${context.filesDir.path}/cacert.pem")

        // Input.
        mpv.setOptionString("input-default-bindings", "yes")

        // Demuxer — use a modest, bounded startup cache. The old 256 MB forward
        // + 128 MB back cache made low-memory TV boxes wait too long before first
        // frame and increased MediaCodec resource pressure.
        val runtime = Runtime.getRuntime()
        val availableMemory = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())

        val (forwardBufferBytes, backBufferBytes, readAheadSecs) = when {
            // Keep total cache below 96 MB even on roomy devices. A six-second
            // forward window covers normal 4K bitrate bursts without delaying
            // first frame or forcing the system to reclaim a video decoder.
            availableMemory > 512 * 1024 * 1024 -> Triple(72L, 16L, 6)
            availableMemory > 256 * 1024 * 1024 -> Triple(40L, 8L, 4)
            else -> Triple(24L, 4L, 3)
        }.let { (forwardMb, backMb, seconds) ->
            Triple(forwardMb * 1024L * 1024L, backMb * 1024L * 1024L, seconds)
        }

        mpv.setOptionString("cache", "yes")
        mpv.setOptionString("cache-secs", "$readAheadSecs")
        mpv.setOptionString("demuxer-max-bytes", "$forwardBufferBytes")
        mpv.setOptionString("demuxer-max-back-bytes", "$backBufferBytes")
        mpv.setOptionString("demuxer-readahead-secs", "$readAheadSecs")

        // General.
        mpv.setOptionString("keep-open", "yes")
        mpv.setOptionString("softvol", "yes")
        mpv.setOptionString("volume-max", MPV_MAX_VOLUME_PERCENT.toInt().toString())
        mpv.setOptionString("osc", "no")
        mpv.setOptionString("terminal", "no")
        mpv.setOptionString("msg-level", "all=no")
    }

    override fun postInitOptions() {
        mpv.setOptionString("save-position-on-quit", "no")
    }

    override fun observeProperties() {
        // Progress is sampled by PlayerRuntimeController. The bundled MPV
        // property-observer API does not deliver a reliable initial state on all
        // Android TV builds, so synchronous reads remain the source of truth;
        // their cadence is deliberately bounded by the controller.
    }

    private fun applyHeaders(headers: Map<String, String>) {
        if (headers.isEmpty()) {
            mpv.setPropertyString("http-header-fields", "")
            return
        }
        val raw = headers.entries
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .sortedWith(compareBy({ it.key.lowercase(Locale.ROOT) }, { it.value }))
            .joinToString(separator = ",") { (key, value) ->
                "$key: $value".replace("\\", "\\\\").replace(",", "\\,")
            }
        mpv.setPropertyString("http-header-fields", raw)
    }

    private fun buildMediaRequestKey(url: String, headers: Map<String, String>): String {
        val normalizedHeaders = headers.entries
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .sortedWith(compareBy({ it.key.lowercase(Locale.ROOT) }, { it.value }))
            .joinToString(separator = "|") { "${it.key.trim()}:${it.value.trim()}" }
        return "$url#$normalizedHeaders"
    }

    private fun MpvHardwareDecodeMode.toMpvHwdecValue(): String = when (this) {
        MpvHardwareDecodeMode.LEGACY_DIRECT_COPY -> "mediacodec,mediacodec-copy"
        // On Android TV `auto-safe` can settle on `mediacodec-copy`, which
        // round-trips every decoded frame through a ByteBuffer before the GPU
        // can composite libass. Prefer the zero-copy MediaCodec path required by
        // vo=gpu/gpu-context=android, then retain auto-safe as a compatibility
        // fallback for devices where direct output fails.
        MpvHardwareDecodeMode.AUTO_SAFE -> "mediacodec,auto-safe"
        MpvHardwareDecodeMode.HARDWARE_COPY -> "mediacodec-copy"
        MpvHardwareDecodeMode.HARDWARE_DIRECT -> "mediacodec"
        MpvHardwareDecodeMode.DISABLED -> "no"
    }

    private fun toMpvColor(color: Int): String = String.format(Locale.US, "#%08X", color)

    private fun applyCoverAspectScale() {
        val viewAspect = readViewAspectRatio(width, height)
        val videoAspect = readVideoAspectRatio()
        if (videoAspect != null && videoAspect > 0f && viewAspect > 0f) {
            if (videoAspect > viewAspect) {
                scaleX = 1.0f; scaleY = videoAspect / viewAspect
            } else {
                scaleX = viewAspect / videoAspect; scaleY = 1.0f
            }
            return
        }
        // Fallback to a visible zoom when video metadata/aspect is unavailable.
        scaleX = MPV_COVER_FALLBACK_SCALE
        scaleY = MPV_COVER_FALLBACK_SCALE
    }

    private fun readVideoAspectRatio(): Float? {
        if (!initialized) return null
        val directAspect = runCatching {
            mpv.getPropertyDouble("video-out-params/aspect") ?: mpv.getPropertyDouble("video-params/aspect")
        }.getOrNull()
        if (directAspect != null && directAspect > 0.0) return directAspect.toFloat()

        val width = runCatching {
            mpv.getPropertyInt("video-out-params/dw") ?: mpv.getPropertyInt("video-params/w")
        }.getOrNull() ?: return null
        val height = runCatching {
            mpv.getPropertyInt("video-out-params/dh") ?: mpv.getPropertyInt("video-params/h")
        }.getOrNull() ?: return null
        if (width <= 0 || height <= 0) return null
        return width.toFloat() / height.toFloat()
    }

    companion object {
        private const val TAG = "MpvPlayerSurfaceView"
        private const val MPV_COVER_FALLBACK_SCALE = 1.15f
        private const val MPV_MAX_VOLUME_PERCENT = 400.0
        private const val ASPECT_RETRY_DELAY_MS = 120L
        private const val MAX_ASPECT_RETRY_COUNT = 10
        // Embedded subtitle tracks often appear after the Matroska index has
        // been read — and on debrid that can land several seconds after loadfile.
        // Keep trying well past the early empty window so preferAss can still
        // land once ASS tracks exist, instead of leaving sid=auto while the UI
        // still thinks subtitles are Off.
        private const val SUBTITLE_SELECTION_RETRY_DELAY_MS = 500L
        private const val SUBTITLE_SELECTION_RETRY_COUNT = 40
        private const val SUBTITLE_VERTICAL_OFFSET_MIN = -20
        private const val SUBTITLE_VERTICAL_OFFSET_MAX = 50
        /** Normal TV resting place at 0% (~5% above absolute bottom). */
        private const val MPV_SUB_POS_AT_ZERO = 95.0
    }
}

data class MpvTrackSnapshot(
    val audioTracks: List<MpvTrack>,
    val subtitleTracks: List<MpvTrack>
)

data class MpvTrack(
    val id: Int,
    val type: String,
    val name: String,
    val language: String?,
    val codec: String?,
    val channelCount: Int?,
    val isSelected: Boolean,
    val isForced: Boolean,
    val isExternal: Boolean
)

private fun Map<String, MPVNode>.stringValue(key: String): String? =
    this[key]?.asString()?.trim()?.takeIf { it.isNotBlank() }

private fun Map<String, MPVNode>.intValue(key: String): Long? = this[key]?.asInt()

private fun Map<String, MPVNode>.booleanValue(key: String): Boolean? = this[key]?.asBoolean()
