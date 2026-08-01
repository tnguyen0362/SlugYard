package com.sluggyard.tv.ui.screens.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.SubtitleParser
import com.sluggyard.tv.core.player.dvmkv.MatroskaExtractor as DvMatroskaExtractor
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.kt.withAssSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import java.util.Collections
import java.util.WeakHashMap

private val assHandlersByPlayer = Collections.synchronizedMap(WeakHashMap<ExoPlayer, AssHandler>())

/**
 * Builds an [ExoPlayer] wired up for libass ASS/SSA rendering, with a
 * compatibility shim that also swaps the vendored DV7 [DvMatroskaExtractor]
 * for the ASS-aware [AssMatroskaExtractor] so libass works on DV-capable
 * devices. For actual DV content the player is later rebuilt without libass
 * (restoring [DvMatroskaExtractor]).
 */
@OptIn(UnstableApi::class)
internal fun ExoPlayer.Builder.buildWithAssSupportCompat(
    context: Context,
    renderType: AssRenderType = AssRenderType.CUES,
    playerMediaSourceFactory: PlayerMediaSourceFactory? = null,
    dataSourceFactory: DataSource.Factory = PlayerPlaybackNetworking.createDataSourceFactory(context),
    extractorsFactory: ExtractorsFactory = DefaultExtractorsFactory(),
    renderersFactory: RenderersFactory = DefaultRenderersFactory(context)
): ExoPlayer {
    val assHandler = AssHandler(renderType)
    val subtitleParserFactory = CompatAssSubtitleParserFactory(assHandler)
    val assExtractors = extractorsFactory.withAssMkvSupportCompat(subtitleParserFactory, assHandler)

    playerMediaSourceFactory?.configureSubtitleParsing(
        extractorsFactory = assExtractors,
        subtitleParserFactory = subtitleParserFactory
    )

    val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, assExtractors)
        .apply { setSubtitleParserFactory(subtitleParserFactory) }

    val player = this
        .setMediaSourceFactory(mediaSourceFactory)
        .setRenderersFactory(renderersFactory.withAssSupport(assHandler))
        .build()

    assHandlersByPlayer[player] = assHandler
    assHandler.init(player)
    return player
}

internal fun ExoPlayer.getAssHandlerCompat(): AssHandler? = assHandlersByPlayer[this]

@OptIn(UnstableApi::class)
private class CompatAssSubtitleParserFactory(
    private val assHandler: AssHandler
) : SubtitleParser.Factory {
    private val delegate = AssSubtitleParserFactory(assHandler)

    override fun supportsFormat(format: Format): Boolean =
        delegate.supportsFormat(normalizeSsaFormat(format))

    override fun getCueReplacementBehavior(format: Format): Int =
        delegate.getCueReplacementBehavior(normalizeSsaFormat(format))

    override fun create(format: Format): SubtitleParser =
        delegate.create(normalizeSsaFormat(format))

    // Some tracks carry `codecs == TEXT_SSA` without a matching sampleMimeType;
    // the ASS parser keys off the sampleMimeType, so mirror it across.
    private fun normalizeSsaFormat(format: Format): Format {
        val ssaByCodecs = format.codecs == MimeTypes.TEXT_SSA
        val ssaByMime = format.sampleMimeType == MimeTypes.TEXT_SSA
        return if (ssaByCodecs && !ssaByMime) {
            format.buildUpon().setSampleMimeType(MimeTypes.TEXT_SSA).build()
        } else {
            format
        }
    }
}

@OptIn(UnstableApi::class)
private fun ExtractorsFactory.withAssMkvSupportCompat(
    subtitleParserFactory: SubtitleParser.Factory,
    assHandler: AssHandler
): ExtractorsFactory {
    val delegate = this
    return ExtractorsFactory {
        val extractors = delegate.createExtractors()
        extractors.forEachIndexed { index, extractor ->
            // Stock MatroskaExtractor: replace with ASS-aware variant for libass support.
            if (extractor is MatroskaExtractor) {
                extractors[index] = AssMatroskaExtractor(subtitleParserFactory, assHandler)
            }
            // The DV7 factory swaps in a vendored DvMatroskaExtractor for DV
            // conversion. AssMatroskaExtractor extends stock MatroskaExtractor
            // and cannot handle DV7 BlockAdditional RPU, but it IS required for
            // libass ASS/SSA rendering. Replace DvMatroskaExtractor with
            // AssMatroskaExtractor so libass works; for actual DV content the
            // player is later rebuilt without libass, restoring DvMatroskaExtractor.
            if (extractor is DvMatroskaExtractor) {
                extractors[index] = AssMatroskaExtractor(subtitleParserFactory, assHandler)
            }
        }
        extractors
    }
}