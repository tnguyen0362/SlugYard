# SlugYard Bug Sweep Handoff

## Scope

This document records the current state of the Android TV bug-fix sweep. The sweep started from an audit of 47 findings spanning playback controls, Back behavior, Continue Watching, Watchlist, source and episode discovery, autoplay, subtitles, IntroDB/AniSkip, Cloud Manager, and D-pad navigation.

The work is intentionally paused at the current checkpoint. The worktree is dirty and contains existing unrelated changes and QA artifacts. No reset, cleanup, staging, commit, or revert was performed.

## Important Constraints

- The Rewrite playback path must remain independent from migrated Nuvio implementation details.
- Do not use `StreamAutoPlaySelector`, the legacy `Stream` model, or Nuvio-originated selector/repository logic in the Rewrite autoplay implementation.
- The independent scorer is based on `RewriteStreamCandidate` and lives in `RewriteStreamScoringEngine`.
- The original seeder/content-aware engine was SlugYard work found in fork commit `0a8751b0`; that code was used as behavioral context only, not copied into the Rewrite scorer.
- Verification should use the Android TV emulator, not the physical onn device.
- Preserve all existing dirty-worktree changes unless the user explicitly requests cleanup.

## Implemented Changes

### Rewrite stream scoring and autoplay

Files:

- `app/src/main/java/com/sluggyard/tv/ui/rewrite/streams/RewriteStreamScoringEngine.kt`
- `app/src/main/java/com/sluggyard/tv/ui/rewrite/streams/RewriteStreamAutoPicker.kt`
- `app/src/main/java/com/sluggyard/tv/core/addonprotocol/StremioResponseDecoder.kt`
- `app/src/main/java/com/sluggyard/tv/ui/rewrite/RewriteAppShell.kt`

Implementation:

- Added an independent Rewrite-owned scoring engine.
- Added title/type/genre-aware weights for anime, K-drama/J-drama, and mainstream content.
- Added scoring for reliability, seeders, subtitle presence, resolution, codec, audio, file size, HDR, CAM/TS indicators, metadata text, and cached/direct availability.
- Added explicit preference for subtitles and reliability for anime and Asian drama content.
- Kept DV7 candidates eligible because the player has a DV7-to-DV8.1 path.
- Avoided known DV8.6 candidates when a safer playable alternative exists.
- Added seeder, description, filename, and video-size metadata to decoded stream candidates.
- Added failed-candidate exclusion so a failed auto-pick retries the next candidate instead of stopping permanently.
- Kept direct candidates ahead of cached candidates, and cached candidates ahead of unknown fallback candidates.
- Excluded known failed candidate IDs from subsequent selection attempts.

### Progressive stream discovery

Files:

- `app/src/main/java/com/sluggyard/tv/ui/rewrite/streams/RewriteStreamsDataSource.kt`

Implementation:

- Changed stream group delivery to `channelFlow` so addon results can be emitted progressively.
- Started addon requests concurrently.
- Started proactive cache checks concurrently.
- Serialized group-state updates with a `Mutex`.
- Awaited cache jobs before discovery completion.
- Preserved direct addon results when cache lookup fails or is delayed.

### Playback checkpointing and Continue Watching

Files:

- `app/src/main/java/com/sluggyard/tv/ui/rewrite/player/RewriteRetainedPlayerHost.kt`
- `app/src/main/java/com/sluggyard/tv/ui/rewrite/data/RewritePlaybackProgressStore.kt`
- `app/src/main/java/com/sluggyard/tv/ui/rewrite/RewriteAppShell.kt`

Implementation:

- Removed the unstable checkpoint effect keyed to every timeline-position update.
- Added a stable periodic checkpoint loop with five-second polling.
- Preserved a final non-cancellable save during player exit.
- Rewinding to position zero removes stale Continue Watching state instead of retaining an old progress entry.
- On-screen Back now calls the same dismissal chain as hardware Back.
- Removed the unused `returnToHomeOnBack` route argument.
- Added retry behavior after failed autoplay resolution.

The Rewrite progress, library, and watched stores now bridge into the existing remote/Supabase sync coordinator. Numeric migrated profiles are synced and remote merged state is applied back into the Rewrite DataStore; UUID profile IDs remain blocked by the integer Supabase profile schema and are documented in `BUG_SWEEP_FINDINGS.md`.

### Watchlist/library persistence

Files:

- `app/src/main/java/com/sluggyard/tv/ui/rewrite/data/RewriteLibraryWatchStore.kt`
- `app/src/main/java/com/sluggyard/tv/ui/rewrite/RewriteAppShell.kt`

Implementation:

- Persisted full `RewriteLibraryEntry` metadata with watch state.
- Removed the dependency on currently loaded Home catalog rows for Watchlist rendering.
- Prevented Continue Watching entries from appearing in Movies, TV, and Watchlist browse filters.

Remaining limitation: the reported Watchlist crash was not reproduced through static Rewrite analysis. The legacy Trakt/library path still needs a populated-data and profile-switch runtime reproduction.

### Subtitle metadata, switching, and skip focus

Files:

- `app/src/main/java/com/sluggyard/tv/domain/model/Subtitle.kt`
- `app/src/main/java/com/sluggyard/tv/ui/rewrite/player/RewritePlayerTrackOverlay.kt`
- `app/src/main/java/com/sluggyard/tv/ui/rewrite/player/RewritePlayerSkipOverlay.kt`
- `app/src/main/java/com/sluggyard/tv/ui/screens/player/PlayerScreen.kt`
- `app/src/main/java/com/sluggyard/tv/ui/screens/player/PlayerRuntimeControllerTrackSelection.kt`
- `app/src/main/java/com/sluggyard/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt`

Implementation:

- Added optional subtitle `format` metadata.
- Derived OpenSubtitles format from the returned filename.
- Added recognition for SRT, VTT, ASS, SSA, SUB, TTML, PGS, SUP, and IDX.
- Changed addon subtitle labels to show language and format rather than provider/addon name.
- Made ExoPlayer subtitle selection prefer stable track IDs with positional fallback.
- Disabled the libass overlay for addon subtitles and for selected tracks that are not ASS/SSA.
- Wired the skip-intro focus requester into the Rewrite skip overlay and request focus when the overlay appears.

Remaining limitation: multiple embedded ASS tracks in a real anime MKV still need runtime verification, including track switching and overlap behavior.

### Player controls and D-pad behavior

Files:

- `app/src/main/java/com/sluggyard/tv/ui/rewrite/player/RewritePlayerControls.kt`
- `app/src/main/java/com/sluggyard/tv/ui/rewrite/player/RewritePlayerSkipOverlay.kt`
- `app/src/main/java/com/sluggyard/tv/ui/screens/player/PlayerScreen.kt`

Implementation:

- Consolidated transport and utility actions into one compact horizontal rail.
- Standardized control sizing at 32dp.
- Removed the permanent yellow Play/Pause fill.
- Retained accent color for focus treatment instead of persistent action fill.
- Added explicit left/right/up D-pad traversal across the rail.
- Unified on-screen Back and hardware Back dismissal behavior.

Remaining limitation: the controls have not been verified while an actual video is playing on the emulator because no working stream source is configured there.

### IntroDB, AniSkip, and Cloud Manager

Files:

- `app/src/main/java/com/sluggyard/tv/data/repository/SkipIntroRepository.kt`
- `app/src/main/java/com/sluggyard/tv/ui/rewrite/debrid/RewriteCloudManagerScreen.kt`
- `app/src/main/java/com/sluggyard/tv/ui/rewrite/debrid/RewriteDebridRuntime.kt`

Implementation:

- Removed the fake default IntroDB endpoint fallback.
- IntroDB is now disabled unless explicitly configured.
- IntroDB, AniSkip, and ARM failures log warnings with exception details instead of silently using debug-only output.
- Cloud Manager now displays provider connection state.
- Added provider selection, API-key connect/validation, activate, disconnect, loading, and error states.
- Preserved the link to the full integration settings screen.

Remaining limitation: IntroDB still needs a valid supported endpoint/API configuration before its data path can be verified.

## Verification Performed

### Build and tests

- `./gradlew.bat :app:compileFullDebugKotlin --no-daemon` succeeded after the main implementation changes.
- `./gradlew.bat :app:assembleFullDebug --no-daemon` succeeded before the latest small follow-up edits.
- The targeted `RewriteStreamAutoPickerTest` completed successfully.
- The full unit-test task ran 532 tests. Two known failures remain in Media3 `DefaultAllocatorTest` because the native library/JNI dependency could not load; these were not caused by the Rewrite changes.
- A final compile/assemble pass is still required after the latest follow-up edits.

### Emulator

- AVD: `Television_4K`
- Device: `emulator-5554`
- Installed package: `com.sluggyarddebug.com`
- Activity: `com.sluggyard.tv.MainActivity`
- Home screen opened successfully.
- Cloud Manager opened successfully.
- Play/navigation attempts reached the details/streams flow, but no playable stream was available because the emulator has no configured/working addon source.
- The actual player controls, source/episode panels, subtitle switching, and resume behavior therefore remain unverified in live playback.

QA artifacts currently present include:

- `qa_current_emulator.png`
- `qa_cloud_emulator.png`
- `qa_play_attempt_emulator.png`
- `qa_play2_emulator.png`
- `qa_streams_emulator.png`

## Still Needs To Be Done

### High priority

1. Run a final compile, full unit-test task, APK assemble, install, and emulator smoke test after the latest edits.
2. Replace the legacy internal-player source/episode dependency with a Rewrite-owned bridge or implementation.
3. Ensure the in-player Sources and Episodes panels use the same Rewrite addon registry, credentials, scoring, retry states, and candidate models as initial autoplay.
4. Verify remote Continue Watching, library, and watched-state synchronization with two numeric profiles/devices and a stale remote completion record.
5. Configure at least one working addon/debrid source on the emulator and verify an actual playback session.

### Medium priority

1. Reproduce the Watchlist crash with populated local/library data and profile switching, especially through the legacy Trakt/library path.
2. Verify multiple embedded ASS tracks, external ASS/SRT tracks, PGS/SUP behavior, format labels, and libass gating.
3. Verify on-screen Back, hardware Back, overlay dismissal, source panel navigation, episode panel navigation, and D-pad focus while playing.
4. Verify failed autoplay candidate retry and stream fallback on both direct and cached candidates.
5. Provide and configure a real IntroDB endpoint/API contract, then verify IntroDB/AniSkip/ARM failure and success paths.
6. Revisit provider-dependent episode pagination. The current addon protocol response does not provide a reliable universal pagination contract.

### Final gates

1. Run the repository compile/lint commands from `AGENTS.md`.
2. Run the designer anti-pattern/review gate for UI changes.
3. Inspect `git status` and `git diff` without staging unrelated existing work.
4. Do not claim the sweep is complete until live emulator playback verification and the final build pass succeed, or the remaining blockers are explicitly accepted.

## Resume Commands

From the repository root:

```powershell
.\gradlew.bat :app:compileFullDebugKotlin --no-daemon
.\gradlew.bat :app:testFullDebugUnitTest --no-daemon
.\gradlew.bat :app:assembleFullDebug --no-daemon
.\gradlew.bat :app:lintFullDebug --no-daemon
```

Use the running `Television_4K` AVD (`emulator-5554`) for the next UI pass. Before testing playback, configure a valid addon/debrid source in the emulator so the player can actually resolve a stream.

## Continuation Pass

The work resumed after this handoff was first written. The following additional changes are now implemented.

### Rewrite Continue Watching sync bridge

Files:

- `app/src/main/java/com/sluggyard/tv/core/sync/SyncCoordinator.kt`
- `app/src/main/java/com/sluggyard/tv/core/sync/SyncMutationRecorder.kt`
- `app/src/main/java/com/sluggyard/tv/core/di/SyncModule.kt`
- `app/src/main/java/com/sluggyard/tv/ui/rewrite/data/RewritePlaybackProgressSyncBridge.kt`
- `app/src/main/java/com/sluggyard/tv/ui/rewrite/data/RewritePlaybackProgressStore.kt`
- `app/src/main/java/com/sluggyard/tv/ui/rewrite/RewriteAppShell.kt`
- `app/src/main/java/com/sluggyard/tv/MainActivity.kt`

Implementation:

- Added a shared Hilt-provided Rewrite DataStore so the app graph and sync bridge use one DataStore instance.
- Added `RewriteProgressSyncBridge` to connect Rewrite checkpoints to the existing Supabase sync coordinator.
- Saves enqueue account-bound `CloudWatchProgress` mutations.
- Completed/rewound entries enqueue account-bound watch-progress deletes.
- Remote sync merges Rewrite-local progress with the regular local snapshot and applies the merged result back into Rewrite storage.
- Remote-only progress keeps a safe content-ID title when local title/poster metadata is unavailable.
- Pending deletes are applied to the merged local snapshot before remote rows are written back, preventing stale remote rows from resurrecting a completed item.
- Pending mutations for the same logical record replace older outbox entries, preventing the 15-second checkpoint loop from creating an unbounded queue.
- Remote progress is scoped by the stable Rewrite key `${contentId}|s${season}e${episode}` for episodic content.

### Episode-safe progress persistence

Files:

- `app/src/main/java/com/sluggyard/tv/ui/rewrite/data/RewritePlaybackProgressStore.kt`
- `app/src/test/java/com/sluggyard/tv/ui/rewrite/data/RewritePlaybackProgressStoreTest.kt`

Implementation:

- Changed local checkpoint identity from content ID only to content ID plus season/episode.
- Episode 2 no longer overwrites episode 1 for the same show.
- Completing or rewinding one episode removes only that episode's checkpoint.
- Added regression tests for independent episode checkpoints and per-episode completion removal.

### Rewrite-owned in-player Sources/Episodes path

Files:

- `app/src/main/java/com/sluggyard/tv/ui/rewrite/player/RewritePlayerSecondaryOverlayV2.kt`
- `app/src/main/java/com/sluggyard/tv/ui/rewrite/player/RewriteRetainedPlayerHost.kt`
- `app/src/main/java/com/sluggyard/tv/ui/rewrite/RewriteAppShell.kt`
- `app/src/main/java/com/sluggyard/tv/ui/screens/player/PlayerScreen.kt`
- `app/src/main/java/com/sluggyard/tv/ui/screens/player/PlayerUiState.kt`
- `app/src/main/java/com/sluggyard/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt`
- `app/src/main/java/com/sluggyard/tv/ui/screens/player/PlayerRuntimeControllerStreams.kt`

Implementation:

- Added a Rewrite-owned secondary panel for Sources, Episodes, and Episode sources.
- Player route metadata now carries addon ID, parent content ID/type, season, and episode so the in-player episode panel can reload the same Rewrite details source used before playback.
- Sources use `RewriteStreamsDataSource`, the Rewrite addon registry, the active Rewrite debrid service, Rewrite candidate metadata, and Rewrite source filters.
- Episode selection reloads the selected episode through the Rewrite details/source data path.
- Candidate selection uses `ManualStreamResolutionCoordinator` and does not call the legacy `StreamAutoPlaySelector`.
- The retained player receives only a resolved URL through a narrow `OnRewritePlaybackSourceSelected` event. It does not perform Rewrite discovery or ranking.
- Hardware/on-screen Back and D-pad key routing now understand the Rewrite-owned external secondary panel.
- The old retained player remains available as the decoder/fallback boundary; no dual-player path was removed.

### Additional verification

- `:app:compileFullDebugKotlin` succeeds after the sync and in-player source/episode changes.
- Targeted `SyncCoordinatorTest`, `RewritePlaybackProgressStoreTest`, and `RewriteStreamAutoPickerTest` pass.
- `:app:assembleFullDebug` succeeded after the first in-player source/episode implementation; a final assemble should still be rerun after the last sync-only edits.
- `Television_4K` was restarted as `emulator-5554`, the latest APK was installed successfully, and the app opened without a fatal crash.
- Emulator navigation reached Rewrite Home, Details, and the no-playable-stream state.
- Live player control/source-sheet verification remains blocked because the emulator has no configured debrid provider and all available candidates were torrent-only. The emulator log confirms candidate fallback/retry continues through many candidates and reports the expected “A debrid provider is required for this torrent” result.
- The final `:app:assembleFullDebug` rerun succeeded after the sync-only edits.
- The final full unit suite completed 534 tests with the same 2 Media3 `DefaultAllocatorTest` native/JNI failures; all other tests passed.
- `:app:lintFullDebug` was run to completion and failed on repository-wide existing lint debt: 421 errors, 1,072 warnings, and 10 hints. The first reported error is the pre-existing Media3 opt-in issue in `ui/screens/player/GainAudioProcessor.kt`; no lint baseline or unrelated mass cleanup was added.
- Focused designer review gates passed for both `ui/rewrite/player` and `ui/screens/player` with score 100/100 and zero blocking findings.

### Intro skip follow-up

Files:

- `app/src/main/java/com/sluggyard/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt`
- `app/src/test/java/com/sluggyard/tv/ui/screens/player/SkipLookupIdTest.kt`

Episode IMDb IDs such as `tt1234567:2:4` are now normalized to `tt1234567` before IntroDB/ARM lookup, while MAL and Kitsu IDs remain unchanged. The targeted skip-ID, next-episode-rule, and post-play-model tests pass.

## Updated Remaining Work

1. Configure a valid debrid provider or a direct playable addon source on `emulator-5554` and verify the new in-player Sources/Episodes panels during actual playback.
2. Verify intro/outro/recap buttons and configured auto-skip against real AniSkip/IntroDB data during playback.
3. Verify that selecting a Rewrite candidate switches the retained player without losing playback position, subtitles, or audio-track state.
4. Verify remote Continue Watching with two profiles/devices and a stale remote completion record.
5. Verify the visible next-episode popup and Play next action with a playable series stream. The popup model/rules are implemented and tested, but its live source handoff still uses the retained next-episode fallback path.
6. Rerun `:app:assembleFullDebug`, the full unit-test task, and `:app:lintFullDebug` after the latest skip-ID edit.
7. Keep the existing known Media3 `DefaultAllocatorTest` JNI failures separate from this work unless the test environment changes.

## Final continuation pass

The comprehensive `BUG_SWEEP_FINDINGS.md` pass continued after this handoff. Safe fixes include
library/watch outbox synchronization, profile-scoped mutation capture, malformed Rewrite blob
notices, cache-hash deduplication with a shared concurrency limit, bounded hero enrichment,
serialized community provisioning with UI status, provider retry/backoff, a shared Rewrite
OkHttp client, source-switch resume ordering, post-frame engine reverse failover, TV focus/back
and accessibility improvements, independent Movies/TV browse loading, catalog ordering control,
and removal of nonfunctional Rewrite source pickers from Integrations settings.

The remaining release blockers are external or require an explicit product/backend decision:
MediaFusion's third-party plaintext-token configuration endpoint, integer-only Supabase profile
identity for UUID Rewrite profiles, the retained-player provenance seam, Keystore AndroidTest
execution, real IntroDB configuration, addon pagination, fresh device/live playback smoke, and
the provenance/license audit. They are not represented as silently fixed.
