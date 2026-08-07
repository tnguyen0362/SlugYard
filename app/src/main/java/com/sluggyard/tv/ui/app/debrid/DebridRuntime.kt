package com.sluggyard.tv.ui.app.debrid

import android.util.Log
import com.sluggyard.tv.core.debrid.PremiumizeDirectDownloadFileSelector
import com.sluggyard.tv.core.debrid.RealDebridFileSelector
import com.sluggyard.tv.core.debrid.TorboxFileSelector
import com.sluggyard.tv.core.streamresolution.CacheCheckResult
import com.sluggyard.tv.core.streamresolution.DebridManualResolver
import com.sluggyard.tv.core.streamresolution.DebridProactiveCacheChecker
import com.sluggyard.tv.core.streamresolution.DebridService
import com.sluggyard.tv.core.streamresolution.ManualStreamResolutionCoordinator
import com.sluggyard.tv.core.streamresolution.ResolvedPlaybackSource
import com.sluggyard.tv.data.remote.dto.PremiumizeDirectDownloadFileDto
import com.sluggyard.tv.data.remote.dto.RealDebridTorrentFileDto
import com.sluggyard.tv.data.remote.dto.TorboxTorrentFileDto
import com.sluggyard.tv.domain.model.StreamClientResolve
import com.sluggyard.tv.ui.app.data.ConfiguredAddonUrls
import com.sluggyard.tv.ui.app.data.ProviderAddonConfigurator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

private const val TORBOX_DIAGNOSTIC_TAG = "TorboxFiles"
private const val UNCACHED_TORBOX_TIMEOUT_MS = 40_000L

/** Connection state deliberately exposes no API keys to Compose or navigation. */
data class DebridConnection(
    val configuredServices: Set<DebridService> = emptySet(),
    val activeService: DebridService? = null,
) {
    fun isConnected(service: DebridService) = service in configuredServices
}

/**
 * Provider-neutral rewrite runtime. Provider protocol calls and profile credentials are owned by
 * the rewrite; the only retained code used below is the explicitly excluded auto-file picker.
 */
class DebridRuntime(
    private val credentials: DebridCredentialStore,
    private val torbox: TorboxTransport = TorboxTransport(),
    private val premiumize: PremiumizeTransport = PremiumizeTransport(),
    private val realDebrid: RealDebridTransport = RealDebridTransport(),
    private val addonConfigurator: ProviderAddonConfigurator = ProviderAddonConfigurator(),
) {
    val connection = credentials.state.map { DebridConnection(it.configuredServices, it.activeService) }
    val manualResolution = ManualStreamResolutionCoordinator(
        setOf(
            TorboxManualResolver(credentials, torbox),
            PremiumizeManualResolver(credentials, premiumize),
            RealDebridManualResolver(credentials, realDebrid),
        ),
    )
    val proactiveCacheCheckers: Set<DebridProactiveCacheChecker> = setOf(
        TorboxCacheChecker(credentials, torbox),
        PremiumizeCacheChecker(credentials, premiumize),
    )

    suspend fun configuredService(): DebridService? {
        val state = credentials.stateValue()
        return state.activeService ?: state.configuredServices.firstOrNull()
    }

    suspend fun connect(service: DebridService, apiKey: String) {
        val key = apiKey.trim()
        require(key.isNotBlank()) { "Enter a ${service.displayName} API key" }
        val result = when (service) {
            DebridService.TORBOX -> torbox.validateCredential(key)
            DebridService.PREMIUMIZE -> premiumize.validateCredential(key)
            DebridService.REAL_DEBRID -> realDebrid.validateCredential(key)
        }
        result.requireValidCredential(service)
        credentials.saveForActiveProfile(service, key)
    }

    suspend fun select(service: DebridService) = credentials.selectForActiveProfile(service)

    suspend fun disconnect(service: DebridService) = credentials.removeForActiveProfile(service)

    suspend fun configuredAddonUrls(): ConfiguredAddonUrls? {
        val state = credentials.stateValue()
        val service = state.activeService ?: state.configuredServices.firstOrNull() ?: return null
        val key = credentials.keyForActiveProfile(service).takeIf(String::isNotBlank) ?: return null
        return addonConfigurator.configure(service, key)
    }

    /** Lists TorBox cloud torrents for the active profile. Empty when TorBox is not connected. */
    suspend fun listTorboxCloudFiles(): List<TorboxCloudItem> {
        val key = credentials.keyForActiveProfile(DebridService.TORBOX).takeIf(String::isNotBlank)
            ?: return emptyList()
        return when (val result = torbox.listCloudTorrents(key)) {
            is TorboxResult.Success -> result.value
            else -> emptyList()
        }
    }

    /**
     * Resolves a TorBox cloud torrent into a direct HTTP URL for the retained player.
     * Picks the largest playable video inside the torrent (same autofile rules as stream resolve).
     */
    suspend fun resolveTorboxCloudPlayback(item: TorboxCloudItem): ResolvedPlaybackSource {
        val key = credentials.keyForActiveProfile(DebridService.TORBOX).takeIf(String::isNotBlank)
            ?: throw IllegalStateException("Connect TorBox to play cloud files")
        val files = torbox.torrentFiles(key, item.id).orUnavailable("TorBox could not list files for this torrent")
        val selected = TorboxFileSelector().selectFile(
            files.map { TorboxTorrentFileDto(it.id, it.name, null, null, it.mimeType, it.sizeBytes) },
            resolveFor("torbox-cloud-${item.id}", null),
            season = null,
            episode = null,
        ) ?: throw IllegalStateException("No playable video file in this torrent")
        val fileId = selected.id ?: throw IllegalStateException("TorBox did not return a playable file")
        val url = torbox.downloadUrl(key, item.id, fileId)
            .orUnavailable("TorBox did not return a download URL for this file")
        return ResolvedPlaybackSource(
            url = url,
            sourceId = "torbox-cloud-${item.id}-$fileId",
            streamName = item.name,
            filename = selected.displayName().ifBlank { item.name },
            videoSizeBytes = selected.size ?: item.sizeBytes,
            addonName = "TorBox Cloud",
        )
    }
}

private class TorboxManualResolver(
    private val credentials: DebridCredentialStore,
    private val transport: TorboxTransport,
    private val fileSelector: TorboxFileSelector = TorboxFileSelector(),
) : DebridManualResolver {
    override val service = DebridService.TORBOX
    override suspend fun resolve(infoHash: String, fileIndex: Int?): ResolvedPlaybackSource =
        resolve(infoHash, fileIndex, null, null)

    override suspend fun resolve(infoHash: String, fileIndex: Int?, season: Int?, episode: Int?): ResolvedPlaybackSource {
        val key = credentials.requiredKey(service)
        // Prefer Instant/Cached (add_only_if_cached). If TorBox has nothing yet, queue a real
        // download so Sources / uncached auto-play can still start a Download row.
        val torrentId = when (val cached = transport.createCachedTorrent(key, infoHash)) {
            is TorboxResult.Success -> cached.value
            else -> {
                Log.i(
                    TORBOX_DIAGNOSTIC_TAG,
                    "cached create miss hash=${infoHash.take(12)} — queuing uncached download",
                )
                val queued = transport.createTorrent(key, infoHash, onlyIfCached = false)
                    .orUnavailable("TorBox could not add this torrent. Try another source.")
                awaitTorboxReady(key, queued)
                queued
            }
        }
        val files = awaitTorboxFiles(key, torrentId)
        val selected = fileSelector.selectFile(
            files.map { TorboxTorrentFileDto(it.id, it.name, null, null, it.mimeType, it.sizeBytes) },
            resolveFor(infoHash, fileIndex), season, episode,
        ) ?: throw DebridUnavailableException("Torbox did not return a playable file")
        Log.d(
            TORBOX_DIAGNOSTIC_TAG,
            "download-link requested season=$season episode=$episode fileId=${selected.id} " +
                "size=${selected.size ?: -1} name='${selected.displayName().take(180)}'",
        )
        val url = transport.downloadUrl(
            key,
            torrentId,
            selected.id ?: throw DebridUnavailableException("Torbox did not return a playable file"),
        ).orUnavailable("TorBox did not return a download URL for this file. Try another source.")
        return ResolvedPlaybackSource(url, infoHash)
    }

    private suspend fun awaitTorboxReady(key: String, torrentId: Int) {
        val ready = withTimeoutOrNull(UNCACHED_TORBOX_TIMEOUT_MS) {
            while (true) {
                val snap = transport.torrentSnapshot(key, torrentId).orUnavailable(
                    "TorBox could not inspect this torrent",
                )
                if (snap.isFailed) {
                    throw DebridUnavailableException(
                        "TorBox failed while downloading this torrent (${snap.downloadState}). Try another source.",
                    )
                }
                if (snap.isReady) return@withTimeoutOrNull true
                // Files can land slightly before terminal state — treat as ready enough to pick.
                if (snap.files.isNotEmpty() && (snap.progress ?: 0.0) >= 0.99) {
                    return@withTimeoutOrNull true
                }
                delay(1_000L)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }
        if (ready != true) {
            throw DebridUnavailableException(
                "TorBox is still downloading this torrent. Wait a moment or pick another source.",
            )
        }
    }

    private suspend fun awaitTorboxFiles(key: String, torrentId: Int): List<TorboxFile> {
        val files = withTimeoutOrNull(15_000L) {
            while (true) {
                val snap = transport.torrentSnapshot(key, torrentId).orUnavailable(
                    "Torbox could not prepare this stream",
                )
                if (snap.files.isNotEmpty()) return@withTimeoutOrNull snap.files
                if (snap.isFailed) {
                    throw DebridUnavailableException(
                        "TorBox failed while preparing this torrent. Try another source.",
                    )
                }
                delay(750L)
            }
            @Suppress("UNREACHABLE_CODE")
            emptyList<TorboxFile>()
        }
        return files ?: throw DebridUnavailableException(
            "TorBox is still preparing files for this torrent. Try again in a moment.",
        )
    }
}

private class PremiumizeManualResolver(
    private val credentials: DebridCredentialStore,
    private val transport: PremiumizeTransport,
    private val fileSelector: PremiumizeDirectDownloadFileSelector = PremiumizeDirectDownloadFileSelector(),
) : DebridManualResolver {
    override val service = DebridService.PREMIUMIZE
    override suspend fun resolve(infoHash: String, fileIndex: Int?): ResolvedPlaybackSource =
        resolve(infoHash, fileIndex, null, null)

    override suspend fun resolve(infoHash: String, fileIndex: Int?, season: Int?, episode: Int?): ResolvedPlaybackSource {
        val files = transport.directDownload(credentials.requiredKey(service), magnetFor(infoHash)).orUnavailable("Premiumize could not prepare this stream")
        val selected = fileSelector.selectFile(files.map { PremiumizeDirectDownloadFileDto(it.path, it.sizeBytes, it.url) }, resolveFor(infoHash, fileIndex), season, episode)
            ?: throw DebridUnavailableException("Premiumize did not return a playable file")
        return ResolvedPlaybackSource(selected.link ?: throw DebridUnavailableException("Premiumize did not return a playable link"), infoHash)
    }
}

private class RealDebridManualResolver(
    private val credentials: DebridCredentialStore,
    private val transport: RealDebridTransport,
    private val fileSelector: RealDebridFileSelector = RealDebridFileSelector(),
) : DebridManualResolver {
    override val service = DebridService.REAL_DEBRID
    override suspend fun resolve(infoHash: String, fileIndex: Int?): ResolvedPlaybackSource =
        resolve(infoHash, fileIndex, null, null)

    override suspend fun resolve(infoHash: String, fileIndex: Int?, season: Int?, episode: Int?): ResolvedPlaybackSource {
        val key = credentials.requiredKey(service)
        val torrentId = transport.addMagnet(key, magnetFor(infoHash)).orUnavailable("Real-Debrid could not add this stream")
        val before = transport.torrentInfo(key, torrentId).orUnavailable("Real-Debrid could not inspect this stream")
        val selected = fileSelector.selectFile(before.files.map { RealDebridTorrentFileDto(it.id, it.path, it.bytes, null) }, resolveFor(infoHash, fileIndex), season, episode)
            ?: throw DebridUnavailableException("Real-Debrid did not return a playable file")
        transport.selectFile(key, torrentId, selected.id ?: throw DebridUnavailableException("Real-Debrid did not return a playable file"))
            .orUnavailable("Real-Debrid could not select this stream")
        val after = awaitDownloaded(key, torrentId)
            ?: throw DebridUnavailableException("This stream was not ready in Real-Debrid")
        val url = transport.unrestrict(key, after.links.firstOrNull() ?: throw DebridUnavailableException("Real-Debrid did not return a download link"))
            .orUnavailable("Real-Debrid could not resolve this stream")
        return ResolvedPlaybackSource(url, infoHash)
    }

    private suspend fun awaitDownloaded(key: String, torrentId: String): RealDebridTorrent? =
        withTimeoutOrNull(20_000L) {
            var current = transport.torrentInfo(key, torrentId)
                .orUnavailable("Real-Debrid could not inspect this stream")
            while (!current.status.equals("downloaded", ignoreCase = true)) {
                if (current.status?.lowercase() in setOf("error", "dead", "magnet_error", "virus")) {
                    return@withTimeoutOrNull null
                }
                delay(500L)
                current = transport.torrentInfo(key, torrentId)
                    .orUnavailable("Real-Debrid could not prepare this stream")
            }
            current
        }
}

private class TorboxCacheChecker(private val credentials: DebridCredentialStore, private val transport: TorboxTransport) : DebridProactiveCacheChecker {
    override val service = DebridService.TORBOX
    override suspend fun check(infoHashes: Set<String>): Map<String, CacheCheckResult> = cacheCheck(infoHashes, credentials, service) { key, hashes -> transport.checkCached(key, hashes) }
}

private class PremiumizeCacheChecker(private val credentials: DebridCredentialStore, private val transport: PremiumizeTransport) : DebridProactiveCacheChecker {
    override val service = DebridService.PREMIUMIZE
    override suspend fun check(infoHashes: Set<String>): Map<String, CacheCheckResult> = cacheCheck(infoHashes, credentials, service) { key, hashes -> transport.checkCached(key, hashes) }
}

private suspend fun cacheCheck(
    hashes: Set<String>, credentials: DebridCredentialStore, service: DebridService,
    check: suspend (String, Set<String>) -> TorboxResult<Set<String>>,
): Map<String, CacheCheckResult> {
    if (credentials.stateValue().activeService != service) return hashes.associateWith { CacheCheckResult.Failed }
    val cached = (check(credentials.requiredKey(service), hashes) as? TorboxResult.Success)?.value
        ?: return hashes.associateWith { CacheCheckResult.Failed }
    return hashes.associateWith { CacheCheckResult.Definitive(it.lowercase() in cached) }
}

private suspend fun DebridCredentialStore.stateValue() = state.first()
private suspend fun DebridCredentialStore.requiredKey(service: DebridService): String = keyForActiveProfile(service).takeIf(String::isNotBlank)
    ?: throw DebridUnavailableException("Connect ${service.displayName} in Settings to play this stream")
private fun magnetFor(infoHash: String) = "magnet:?xt=urn:btih:${infoHash.trim()}"
private fun resolveFor(hash: String, fileIndex: Int?) = StreamClientResolve(null, hash, fileIndex, null, null, null, null, null, null, null, null, null, null, null, null, null, null)
private class DebridUnavailableException(message: String) : IllegalStateException(message)
private fun <T> TorboxResult<T>.orUnavailable(message: String): T = (this as? TorboxResult.Success)?.value ?: throw DebridUnavailableException(message)
private fun TorboxResult<Unit>.requireValidCredential(service: DebridService) = when (this) {
    is TorboxResult.Success -> Unit
    is TorboxResult.HttpFailure -> throw IllegalArgumentException("${service.displayName} rejected this API key")
    TorboxResult.InvalidResponse -> throw IllegalStateException("${service.displayName} returned an invalid response")
    is TorboxResult.NetworkFailure -> throw IllegalStateException("${service.displayName} could not be reached")
}
private val DebridService.displayName get() = name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
