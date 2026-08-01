@file:androidx.annotation.OptIn(UnstableApi::class)

package com.sluggyard.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.StreamKey
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * Wraps a [MediaSource] so that video sample timestamps are shifted by a
 * dynamically-resolved audio delay (in microseconds).
 *
 * Audio is the master clock in normal playback, so shifting audio timestamps
 * tends to pull video along with it. To produce a *real* perceived audio delay
 * we instead shift the **video** timestamps in the opposite direction.
 */
internal class AudioDelayMediaSource(
    mediaSource: MediaSource,
    private val audioDelayUsProvider: () -> Long
) : WrappingMediaSource(mediaSource) {

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long
    ): MediaPeriod = AudioDelayMediaPeriod(
        mediaSource.createPeriod(id, allocator, startPositionUs),
        audioDelayUsProvider
    )

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        val wrapped = (mediaPeriod as AudioDelayMediaPeriod).delegate
        mediaSource.releasePeriod(wrapped)
    }

    override fun onChildSourceInfoRefreshed(timeline: Timeline) {
        refreshSourceInfo(timeline)
    }
}

private class AudioDelayMediaPeriod(
    val delegate: MediaPeriod,
    private val audioDelayUsProvider: () -> Long
) : MediaPeriod, MediaPeriod.Callback {

    private var preparedCallback: MediaPeriod.Callback? = null

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        preparedCallback = callback
        delegate.prepare(this, positionUs)
    }

    override fun maybeThrowPrepareError() = delegate.maybeThrowPrepareError()

    override fun getTrackGroups(): TrackGroupArray = delegate.trackGroups

    override fun getStreamKeys(trackSelections: List<ExoTrackSelection>): List<StreamKey> =
        delegate.getStreamKeys(trackSelections)

    override fun selectTracks(
        selections: Array<ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long
    ): Long {
        // Unwrap any previously-wrapped video streams before delegating.
        val childStreams = arrayOfNulls<SampleStream>(streams.size)
        for (i in streams.indices) {
            childStreams[i] = (streams[i] as? AudioDelaySampleStream)?.child ?: streams[i]
        }

        val selectedPositionUs = delegate.selectTracks(
            selections, mayRetainStreamFlags, childStreams, streamResetFlags, positionUs
        )

        for (i in streams.indices) {
            val child = childStreams[i]
            streams[i] = when {
                child == null -> null
                isVideoTrack(selections[i]) -> {
                    val existing = streams[i] as? AudioDelaySampleStream
                    if (existing?.child === child) existing
                    else AudioDelaySampleStream(child, audioDelayUsProvider)
                }
                else -> child
            }
        }
        return selectedPositionUs
    }

    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) =
        delegate.discardBuffer(positionUs, toKeyframe)

    override fun readDiscontinuity(): Long = delegate.readDiscontinuity()

    override fun seekToUs(positionUs: Long): Long = delegate.seekToUs(positionUs)

    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long =
        delegate.getAdjustedSeekPositionUs(positionUs, seekParameters)

    override fun getBufferedPositionUs(): Long = delegate.bufferedPositionUs

    override fun getNextLoadPositionUs(): Long = delegate.nextLoadPositionUs

    override fun continueLoading(loadingInfo: LoadingInfo): Boolean = delegate.continueLoading(loadingInfo)

    override fun isLoading(): Boolean = delegate.isLoading

    override fun reevaluateBuffer(positionUs: Long) = delegate.reevaluateBuffer(positionUs)

    override fun onPrepared(mediaPeriod: MediaPeriod) { preparedCallback?.onPrepared(this) }

    override fun onContinueLoadingRequested(mediaPeriod: MediaPeriod) {
        preparedCallback?.onContinueLoadingRequested(this)
    }

    private fun isVideoTrack(selection: ExoTrackSelection?): Boolean =
        selection?.selectedFormat?.sampleMimeType?.let(MimeTypes::getTrackType) == C.TRACK_TYPE_VIDEO
}

private class AudioDelaySampleStream(
    val child: SampleStream,
    private val audioDelayUsProvider: () -> Long
) : SampleStream {

    override fun isReady(): Boolean = child.isReady()

    override fun maybeThrowError() = child.maybeThrowError()

    override fun readData(formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int {
        val result = child.readData(formatHolder, buffer, readFlags)
        if (result == C.RESULT_BUFFER_READ) {
            buffer.timeUs -= audioDelayUsProvider()
        }
        return result
    }

    override fun skipData(positionUs: Long): Int =
        child.skipData((positionUs + audioDelayUsProvider()).coerceAtLeast(0L))
}