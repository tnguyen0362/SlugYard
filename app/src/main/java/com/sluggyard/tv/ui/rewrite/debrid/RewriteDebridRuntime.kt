package com.sluggyard.tv.ui.rewrite.debrid

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
import com.sluggyard.tv.ui.rewrite.data.RewriteConfiguredAddonUrls
import com.sluggyard.tv.ui.rewrite.data.RewriteProviderAddonConfigurator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

private const val TORBOX_DIAGNOSTIC_TAG = "RewriteTorboxFiles"

/** Connection state deliberately exposes no API keys to Compose or navigation. */
data class RewriteDebridConnection(
    val configuredServices: Set<DebridService> = emptySet(),
    val activeService: DebridService? = null,
) {
    fun isConnected(service: DebridService) = service in configuredServices
}

/**
 * Provider-neutral rewrite runtime. Provider protocol calls and profile credentials are owned by
 * the rewrite; the only retained code used below is the explicitly excluded auto-file picker.
 */
class RewriteDebridRuntime(
    private val credentials: RewriteDebridCredentialStore,
    private val torbox: RewriteTorboxTransport = RewriteTorboxTransport(),
    private val premiumize: RewritePremiumizeTransport = RewritePremiumizeTransport(),
    private val realDebrid: RewriteRealDebridTransport = RewriteRealDebridTransport(),
    private val addonConfigurator: RewriteProviderAddonConfigurator = RewriteProviderAddonConfigurator(),
) {
    val connection = credentials.state.map { RewriteDebridConnection(it.configuredServices, it.activeService) }
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

    suspend fun configuredService(): DebridService? = credentials.stateValue().activeService

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

    suspend fun configuredAddonUrls(): RewriteConfiguredAddonUrls? {
        val service = credentials.stateValue().activeService ?: return null
        val key = credentials.keyForActiveProfile(service).takeIf(String::isNotBlank) ?: return null
        return addonConfigurator.configure(service, key)
    }

    /** Lists TorBox cloud torrents for the active profile. Empty when TorBox is not connected. */
    suspend fun listTorboxCloudFiles(): List<RewriteTorboxCloudItem> {
        val key = credentials.keyForActiveProfile(DebridService.TORBOX).takeIf(String::isNotBlank)
            ?: return emptyList()
        return when (val result = torbox.listCloudTorrents(key)) {
            is RewriteTorboxResult.Success -> result.value
            else -> emptyList()
        }
    }
}

private class TorboxManualResolver(
    private val credentials: RewriteDebridCredentialStore,
    private val transport: RewriteTorboxTransport,
    private val fileSelector: TorboxFileSelector = TorboxFileSelector(),
) : DebridManualResolver {
    override val service = DebridService.TORBOX
    override suspend fun resolve(infoHash: String, fileIndex: Int?): ResolvedPlaybackSource =
        resolve(infoHash, fileIndex, null, null)

    override suspend fun resolve(infoHash: String, fileIndex: Int?, season: Int?, episode: Int?): ResolvedPlaybackSource {
        val key = credentials.requiredKey(service)
        val torrentId = transport.createCachedTorrent(key, infoHash).orUnavailable(
            "This torrent is not cached on TorBox. Pick a source marked Instant/Cached.",
        )
        val selected = fileSelector.selectFile(
            transport.torrentFiles(key, torrentId).orUnavailable("Torbox could not prepare this stream")
                .map { TorboxTorrentFileDto(it.id, it.name, null, null, it.mimeType, it.sizeBytes) },
            resolveFor(infoHash, fileIndex), season, episode,
        ) ?: throw RewriteDebridUnavailableException("Torbox did not return a playable file")
        Log.d(
            TORBOX_DIAGNOSTIC_TAG,
            "download-link requested season=$season episode=$episode fileId=${selected.id} " +
                "size=${selected.size ?: -1} name='${selected.displayName().take(180)}'",
        )
        val url = transport.downloadUrl(key, torrentId, selected.id ?: throw RewriteDebridUnavailableException("Torbox did not return a playable file"))
        .orUnavailable("TorBox did not return a download URL for this file. Try another Instant/Cached source.")
        return ResolvedPlaybackSource(url, infoHash)
    }
}

private class PremiumizeManualResolver(
    private val credentials: RewriteDebridCredentialStore,
    private val transport: RewritePremiumizeTransport,
    private val fileSelector: PremiumizeDirectDownloadFileSelector = PremiumizeDirectDownloadFileSelector(),
) : DebridManualResolver {
    override val service = DebridService.PREMIUMIZE
    override suspend fun resolve(infoHash: String, fileIndex: Int?): ResolvedPlaybackSource =
        resolve(infoHash, fileIndex, null, null)

    override suspend fun resolve(infoHash: String, fileIndex: Int?, season: Int?, episode: Int?): ResolvedPlaybackSource {
        val files = transport.directDownload(credentials.requiredKey(service), magnetFor(infoHash)).orUnavailable("Premiumize could not prepare this stream")
        val selected = fileSelector.selectFile(files.map { PremiumizeDirectDownloadFileDto(it.path, it.sizeBytes, it.url) }, resolveFor(infoHash, fileIndex), season, episode)
            ?: throw RewriteDebridUnavailableException("Premiumize did not return a playable file")
        return ResolvedPlaybackSource(selected.link ?: throw RewriteDebridUnavailableException("Premiumize did not return a playable link"), infoHash)
    }
}

private class RealDebridManualResolver(
    private val credentials: RewriteDebridCredentialStore,
    private val transport: RewriteRealDebridTransport,
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
            ?: throw RewriteDebridUnavailableException("Real-Debrid did not return a playable file")
        transport.selectFile(key, torrentId, selected.id ?: throw RewriteDebridUnavailableException("Real-Debrid did not return a playable file"))
            .orUnavailable("Real-Debrid could not select this stream")
        val after = awaitDownloaded(key, torrentId)
            ?: throw RewriteDebridUnavailableException("This stream was not ready in Real-Debrid")
        val url = transport.unrestrict(key, after.links.firstOrNull() ?: throw RewriteDebridUnavailableException("Real-Debrid did not return a download link"))
            .orUnavailable("Real-Debrid could not resolve this stream")
        return ResolvedPlaybackSource(url, infoHash)
    }

    private suspend fun awaitDownloaded(key: String, torrentId: String): RewriteRealDebridTorrent? =
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

private class TorboxCacheChecker(private val credentials: RewriteDebridCredentialStore, private val transport: RewriteTorboxTransport) : DebridProactiveCacheChecker {
    override val service = DebridService.TORBOX
    override suspend fun check(infoHashes: Set<String>): Map<String, CacheCheckResult> = cacheCheck(infoHashes, credentials, service) { key, hashes -> transport.checkCached(key, hashes) }
}

private class PremiumizeCacheChecker(private val credentials: RewriteDebridCredentialStore, private val transport: RewritePremiumizeTransport) : DebridProactiveCacheChecker {
    override val service = DebridService.PREMIUMIZE
    override suspend fun check(infoHashes: Set<String>): Map<String, CacheCheckResult> = cacheCheck(infoHashes, credentials, service) { key, hashes -> transport.checkCached(key, hashes) }
}

private suspend fun cacheCheck(
    hashes: Set<String>, credentials: RewriteDebridCredentialStore, service: DebridService,
    check: suspend (String, Set<String>) -> RewriteTorboxResult<Set<String>>,
): Map<String, CacheCheckResult> {
    if (credentials.stateValue().activeService != service) return hashes.associateWith { CacheCheckResult.Failed }
    val cached = (check(credentials.requiredKey(service), hashes) as? RewriteTorboxResult.Success)?.value
        ?: return hashes.associateWith { CacheCheckResult.Failed }
    return hashes.associateWith { CacheCheckResult.Definitive(it.lowercase() in cached) }
}

private suspend fun RewriteDebridCredentialStore.stateValue() = state.first()
private suspend fun RewriteDebridCredentialStore.requiredKey(service: DebridService): String = keyForActiveProfile(service).takeIf(String::isNotBlank)
    ?: throw RewriteDebridUnavailableException("Connect ${service.displayName} in Settings to play this stream")
private fun magnetFor(infoHash: String) = "magnet:?xt=urn:btih:${infoHash.trim()}"
private fun resolveFor(hash: String, fileIndex: Int?) = StreamClientResolve(null, hash, fileIndex, null, null, null, null, null, null, null, null, null, null, null, null, null, null)
private class RewriteDebridUnavailableException(message: String) : IllegalStateException(message)
private fun <T> RewriteTorboxResult<T>.orUnavailable(message: String): T = (this as? RewriteTorboxResult.Success)?.value ?: throw RewriteDebridUnavailableException(message)
private fun RewriteTorboxResult<Unit>.requireValidCredential(service: DebridService) = when (this) {
    is RewriteTorboxResult.Success -> Unit
    is RewriteTorboxResult.HttpFailure -> throw IllegalArgumentException("${service.displayName} rejected this API key")
    RewriteTorboxResult.InvalidResponse -> throw IllegalStateException("${service.displayName} returned an invalid response")
    is RewriteTorboxResult.NetworkFailure -> throw IllegalStateException("${service.displayName} could not be reached")
}
private val DebridService.displayName get() = name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
