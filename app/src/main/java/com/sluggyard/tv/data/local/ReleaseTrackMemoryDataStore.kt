package com.sluggyard.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sluggyard.tv.core.profile.ProfileManager
import com.sluggyard.tv.ui.app.streams.ObservedReleaseTracks
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-profile play-once track inventory keyed by torrent infoHash (optionally file index).
 * Tiny payloads; capped LRU so leanback never grows without bound.
 */
@Singleton
class ReleaseTrackMemoryDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
) {
    private companion object {
        const val FEATURE_NAME = "release_track_memory"
        const val MAX_ENTRIES = 150
        val ENTRIES_KEY = stringPreferencesKey("entries_v1")
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE_NAME)

    private fun memoryKey(infoHash: String, fileIdx: Int?): String {
        val hash = infoHash.trim().lowercase()
        return if (fileIdx != null) "$hash:$fileIdx" else hash
    }

    suspend fun remember(
        infoHash: String?,
        fileIdx: Int?,
        tracks: ObservedReleaseTracks,
    ) {
        val hash = infoHash?.trim()?.takeIf { it.isNotEmpty() } ?: return
        // Require some real inventory — empty open with zero tracks is noise.
        if (tracks.audioLangBases.isEmpty() && !tracks.hasSoftsubTrack) return
        val key = memoryKey(hash, fileIdx)
        val hashOnlyKey = memoryKey(hash, null)
        store().edit { prefs ->
            val list = parseEntries(prefs[ENTRIES_KEY]).toMutableList()
            list.removeAll { it.key == key || (fileIdx != null && it.key == hashOnlyKey) }
            list.add(
                0,
                Entry(
                    key = key,
                    hashOnlyKey = hashOnlyKey,
                    tracks = tracks,
                ),
            )
            while (list.size > MAX_ENTRIES) list.removeAt(list.lastIndex)
            prefs[ENTRIES_KEY] = serialize(list)
        }
    }

    suspend fun getForHashes(infoHashes: Collection<String>): Map<String, ObservedReleaseTracks> {
        if (infoHashes.isEmpty()) return emptyMap()
        val wanted = infoHashes.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        if (wanted.isEmpty()) return emptyMap()
        val entries = parseEntries(store().data.first()[ENTRIES_KEY])
        val out = LinkedHashMap<String, ObservedReleaseTracks>()
        for (entry in entries) {
            val hash = entry.hashOnlyKey
            if (hash in wanted && hash !in out) {
                out[hash] = entry.tracks
            }
            // Prefer file-scoped when callers used hash:file — still fill hash bucket from any match.
            val fileHash = entry.key.substringBefore(':')
            if (fileHash in wanted && fileHash !in out) {
                out[fileHash] = entry.tracks
            }
        }
        return out
    }

    suspend fun lookup(infoHash: String?, fileIdx: Int? = null): ObservedReleaseTracks? {
        val hash = infoHash?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val entries = parseEntries(store().data.first()[ENTRIES_KEY])
        if (fileIdx != null) {
            val scoped = memoryKey(hash, fileIdx)
            entries.firstOrNull { it.key == scoped }?.tracks?.let { return it }
        }
        return entries.firstOrNull { it.hashOnlyKey == hash || it.key.startsWith("$hash:") }?.tracks
    }

    private data class Entry(
        val key: String,
        val hashOnlyKey: String,
        val tracks: ObservedReleaseTracks,
    )

    private fun serialize(list: List<Entry>): String {
        val arr = JSONArray()
        for (e in list) {
            arr.put(
                JSONObject().apply {
                    put("k", e.key)
                    put("h", e.hashOnlyKey)
                    put("at", e.tracks.observedAtMs)
                    put("a", JSONArray(e.tracks.audioLangBases.toList()))
                    put("s", JSONArray(e.tracks.subtitleLangBases.toList()))
                    put("ass", e.tracks.hasAss)
                    put("pgs", e.tracks.hasPgs)
                    put("srt", e.tracks.hasSrt)
                    put("soft", e.tracks.hasSoftsubTrack)
                },
            )
        }
        return arr.toString()
    }

    private fun parseEntries(raw: String?): List<Entry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val key = o.optString("k", "").takeIf { it.isNotBlank() } ?: continue
                    val hash = o.optString("h", key.substringBefore(':')).ifBlank { key.substringBefore(':') }
                    val audio = jsonStringSet(o.optJSONArray("a"))
                    val subs = jsonStringSet(o.optJSONArray("s"))
                    add(
                        Entry(
                            key = key,
                            hashOnlyKey = hash,
                            tracks = ObservedReleaseTracks(
                                audioLangBases = audio,
                                subtitleLangBases = subs,
                                hasAss = o.optBoolean("ass", false),
                                hasPgs = o.optBoolean("pgs", false),
                                hasSrt = o.optBoolean("srt", false),
                                hasSoftsubTrack = o.optBoolean("soft", false),
                                observedAtMs = o.optLong("at", 0L),
                            ),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun jsonStringSet(arr: JSONArray?): Set<String> {
        if (arr == null) return emptySet()
        return buildSet {
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let { add(it.lowercase()) }
            }
        }
    }
}
