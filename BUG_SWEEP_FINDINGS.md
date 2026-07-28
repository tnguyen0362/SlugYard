# SlugYard — Comprehensive Bug Sweep Findings

**Status:** Resolution ledger for the 2026-07-28 implementation pass. Findings below are retained as historical audit entries; dispositions and evidence are recorded at the end of this file.  
**Scope:** SlugYard clean-room rewrite (`ui/rewrite`, related `core/*`, retained player boundary).  
**Sources:** Two full-repo sweeps (core/data/playback + every UI page, Back behavior, edge cases, stability, performance).  
**Last updated:** 2026-07-27

Each entry has a short **name**, **severity**, **explanation**, **where**, and **user impact**.

**Severity:** P0 = crash / data loss / security · P1 = broken core journey · P2 = focus / UX / moderate logic · P3 = polish / docs / minor

---

## Table of contents

1. [P0 — Critical](#p0--critical)
2. [P1 — Major](#p1--major)
3. [P2 — Moderate](#p2--moderate)
4. [P3 — Minor](#p3--minor)
5. [Intentional behavior (not bugs)](#intentional-behavior-not-bugs)
6. [Open release gates](#open-release-gates)

---

## P0 — Critical

### BUG-001: Back From Player Deletes Continue Watching

**Severity:** P0  
**Where:** `RewriteRetainedPlayerHost.kt`, `PlayerScreen.kt` / `PlayerRuntimeControllerLifecycle.kt`

When the user presses Back to exit playback, the rewrite host saves a final checkpoint in `onDispose`. That save checks `currentPosition <= 0` and **removes** the Continue Watching entry instead of keeping it. The exit path calls `resetPlaybackTimeline()` before dispose, which zeroes the position. So a normal Back after watching looks like “never started” and wipes local (and synced) progress.

**User impact:** Continue Watching rows disappear after every normal exit. Resume is broken.

---

### BUG-002: Debrid API Key Stored In Plaintext Torrentio URL

**Severity:** P0  
**Where:** `RewriteProviderAddonConfig.kt` (`buildTorrentioManifestUrl`), `RewriteAddonRegistryStore.kt`

Connecting a debrid account builds a Torrentio manifest URL with the API key embedded in the path (`…/realdebrid=<key>/manifest.json`). That URL is persisted in the addon registry DataStore. This bypasses the Keystore-encrypted credential store and violates the spec rule that credentials must not appear in “URLs stored in state.”

**User impact:** API keys survive in preferences, logs, and dumps even when the credential store is encrypted.

---

### BUG-003: Resolved Stream URL Embedded In Navigation Route

**Severity:** P0  
**Where:** `RewriteAppShell.kt` (`RewritePlayerRoute`)

The player route pattern is `rewrite_player/{streamUrl}/…` with the full debrid/CDN URL URL-encoded as a path segment. Signed URLs and tokens end up in the NavController back stack and process-death restore state.

**User impact:** Playback URLs/tokens leak into navigation state and diagnostics.

---

### BUG-004: Sync Apply Wipes All Local Progress Keys

**Severity:** P0  
**Where:** `RewritePlaybackProgressSyncBridge.kt` (`apply`)

On sync pull, `apply()` deletes **every** `rewrite_playback_progress_v2_*` key, then rewrites only entries whose profile ID parses as an integer. UUID or string profile IDs (common for rewrite-created profiles) are never re-imported but are deleted on every successful sync.

**User impact:** Signed-in users lose Continue Watching locally after sync, especially on non-numeric profile IDs.

---

### BUG-005: Continue Watching UI And Player Resume Use Different Stores

**Severity:** P0  
**Where:** `RewritePlaybackProgressStore.kt`, `RewriteRetainedPlayerHost.kt`, `PlayerViewModel.kt` / `PlayerRuntimeControllerStartup.kt`

The Home Continue Watching row reads from the rewrite progress store. The retained player resumes from the legacy `WatchProgressRepository`. The two are not bridged on the rewrite playback path.

**User impact:** The tile shows one position; playback starts at zero or a stale legacy position. Cross-device sync appears to work in the UI but not at play time.

---

### BUG-006: Auto-Pick Failure Is A Dead End

**Severity:** P0  
**Where:** `RewriteAppShell.kt` (`RewriteBuildingPlayerScreen`, auto-pick `LaunchedEffect`)

Every Play path uses `autoPick = true`. When scoring finds no candidate or all candidates fail resolution, the user sees a loading/error card with only **Back**. The error text says “choose another source” but there is no manual picker, no “try next source,” and no handoff to the in-player Sources panel.

**User impact:** Playback cannot start and the user cannot recover without leaving and retrying from Details.

---

### BUG-007: Manual Stream Picker UI Is Never Reachable

**Severity:** P0  
**Where:** `RewriteAppShell.kt`, `RewriteStreamsScreen.kt`

`RewriteStreamsScreen` (manual source list) only renders when `autoPick = false`. Every construction of `RewriteRootDestination.Streams` in the codebase uses `autoPick = true`. The manual picker is dead UI.

**User impact:** Pre-play source selection does not exist in production paths.

---

## P1 — Major

### BUG-008: Autofile Ignores Season And Episode

**Severity:** P1  
**Where:** `RewriteDebridRuntime.kt` (Torbox, Premiumize, Real-Debrid resolvers)

All debrid file selectors are called with `selectFile(..., null, null)` for season and episode. Episode-name matching never runs. For season packs, fallback is typically the largest playable file.

**User impact:** Autoplay or manual play can start the wrong episode, a sample, or extras inside a multi-file torrent.

---

### BUG-009: In-Player Episode Switch Does Not Update Playback Identity

**Severity:** P1  
**Where:** `RewriteRetainedPlayerHost.kt`, `PlayerRuntimeController.switchToRewritePlaybackSource`

Choosing another episode in the in-player Episodes panel loads new streams and swaps the playback URL, but navigation args and controller state (`contentId`, season, episode, checkpoints, skip metadata) stay on the original episode.

**User impact:** User watches E2 while progress, title, next-episode, and scrobble logic still target E1.

---

### BUG-010: Cancel During Stream Resolve Treated As Failure

**Severity:** P1  
**Where:** `RewriteAppShell.kt` (`resolveStreamCandidate`)

After handling `TimeoutCancellationException`, a generic `catch (failure: Exception)` also catches job cancellation (e.g. user presses Back). Cancellation is not rethrown. Failed auto-pick adds the candidate ID to `autoPickRejectedIds`.

**User impact:** Back during autoplay permanently excludes a good source for that session and may show a false error.

---

### BUG-011: Sub-2% Progress Still Saved And Synced

**Severity:** P1  
**Where:** `RewriteRetainedPlayerHost.kt`, `RewritePlaybackProgressStore.kt`, `WatchStatePolicy.kt`

The product contract is 2% started / 90% complete. The host saves any `positionMs > 0`. The store persists and syncs below the 2% threshold even though the UI filters display with `isStartedButIncomplete`.

**User impact:** Brief opens pollute Continue Watching and cloud sync with junk resume entries.

---

### BUG-012: Auto-Picker Prefers Any Direct URL Over Better Cached Torrents

**Severity:** P1  
**Where:** `RewriteStreamScoringEngine.kt` (`choose`)

If any well-formed direct URL exists, the scorer uses only the direct tier. Cached 4K/HDR torrents are never compared. Low-quality or CAM-like directs win over better cached candidates.

**User impact:** Autoplay picks poor or unplayable directs when a better torrent was available.

---

### BUG-013: Skip Overlay Mislabels Mixed Opening As Outro

**Severity:** P1  
**Where:** `RewritePlayerSkipOverlay.kt` (`skipLabel`)

The outro branch matches `type.contains("ed")`. The AniSkip type `mixed-op` contains `"ed"`, so openings are labeled **“Skip outro.”**

**User impact:** Wrong skip button text during anime intros.

---

### BUG-014: AniSkip Permanently Skipped When Episode Is Null

**Severity:** P1  
**Where:** `PlayerRuntimeControllerObservers.kt` (`fetchSkipIntervals`)

The fetch cache key is set before the network work. Branches that return early when `episode` is null still mark the request as handled with empty intervals and never retry.

**User impact:** Skip intro/outro never appears when season/episode metadata is missing on first attempt.

---

### BUG-015: ARM Season Index Maps Wrong MAL IDs

**Severity:** P1  
**Where:** `SkipIntroRepository.kt` (`getSkipIntervals`)

ARM entries are indexed with `getOrNull(season - 1)` as if list order equals season number. Multi-season anime can resolve the wrong MAL ID or season 0.

**User impact:** Wrong or missing skip intervals on multi-season shows.

---

### BUG-016: In-Player Source Switch Resume Race

**Severity:** P1  
**Where:** `PlayerRuntimeControllerStreams.kt` (`switchToSourceStream`)

Progress is loaded after `initializePlayer`, but inline comments state resume must be loaded **before** init or `STATE_READY` drops the seek position.

**User impact:** Changing source mid-playback can restart at 0 instead of current position.

---

### BUG-017: Opening Auth From Settings Clears Guest Without Recovery

**Severity:** P1  
**Where:** `RewriteAppShell.kt` (`onOpenAuth`), `RewriteAuthGate.kt`

Opening sign-in from Settings calls `guestSession.setGuest(false)`. If the user backs out without signing in or choosing Guest again, the next launch is SignedOut and non-guest — stuck on the auth wall.

**User impact:** Guest users who peek at Account get trapped until they explicitly pick Guest again.

---

### BUG-018: Library And Watched State Not Synced

**Severity:** P1  
**Where:** `RewriteLibraryWatchStore.kt`, `SyncCoordinator.kt`, `SyncModule.kt`

Only watch progress has a rewrite sync bridge. Library, watchlist, and watched IDs are local DataStore only. Settings expose library/CW source modes that imply cloud behavior.

**User impact:** Signed-in users expect cross-device watchlist and watched state; rewrite path never merges it.

---

### BUG-019: Continue Watching Source Setting Ignored By Rewrite Home

**Severity:** P1  
**Where:** `RewriteIntegrationSettings.kt`, `RewriteAppShell.kt` (CW row)

Integration settings let users cycle Continue Watching source (Trakt vs SlugYard sync). Home Continue Watching is built only from `RewritePlaybackProgressStore`.

**User impact:** Toggling CW source has no effect on the rewrite Home row.

---

### BUG-020: Series Play Falls Back To Parent Show ID

**Severity:** P1  
**Where:** `RewritePlaybackTarget.kt` (`playbackTarget`)

When the episode list is empty, playback target becomes the parent series id instead of a video/episode id. Streams are queried with the show id.

**User impact:** Empty or malformed metadata leads to no sources or wrong fan-out.

---

### BUG-021: Player Startup Logs Stream URL Prefix

**Severity:** P1  
**Where:** `PlayerRuntimeControllerStartup.kt`

Debug log includes `streamUrl.take(80)`, which often contains host and tokenized query parameters.

**User impact:** Debrid link fragments in logcat on every rewrite playback start.

---

### BUG-022: Debrid Disabled Toggle Does Not Stop Rewrite Resolution

**Severity:** P1  
**Where:** `RewriteAppShell.kt`, `RewriteIntegrationSettings.kt`, `RewriteDebridRuntime.kt`

“Debrid playback” disabled only affects addon provisioning (`provisionCommunitySources`). Stream discovery and resolution still use `debridConnection.activeService` and rewrite credentials if present.

**User impact:** User disables debrid but torrents may still be cache-checked and resolved.

---

### BUG-023: Auth Refresh Failure Shows Blank Choice Screen

**Severity:** P1  
**Where:** `RewriteAuthGate.kt`

On corrupt session or failed refresh, `message` is set but the Choice screen never renders it (only Sign In / Create Account forms show errors).

**User impact:** Expired or corrupt sessions look like a normal gate with no explanation.

---

### BUG-024: Next-Episode Handoff Dropped At Rewrite Player Boundary

**Severity:** P1  
**Where:** `RewriteRetainedPlayerHost.kt`, `PlayerScreen.kt`

The host passes `onBackPress = { _, _, _, _, _ -> onBack() }`, discarding next-episode handoff parameters. End-of-episode “play next” cannot re-enter rewrite discovery with the next episode id.

**User impact:** Auto-advance to next episode is broken on the rewrite path.

---

### BUG-025: Continue Watching Card Routes To Details (Unresolved)

**Severity:** P1  
**Where:** `RewriteAppShell.kt` (`onPosterSelected`)

Static code routes checkpoints without `addonId` to Streams. On-device `uiautomator` dumps showed Continue Watching CENTER opening Details instead. Diagnostic log `SlugYardDebug` remains in tree; root cause not confirmed.

**User impact:** Resume may open wrong screen or wrong flow.

---

### BUG-026: In-Player Sources And Episodes Still Hybrid Legacy Path

**Severity:** P1  
**Where:** `RewriteRetainedPlayerHost.kt`, `PlayerViewModel.kt`, `PlayerRuntimeController*`

In-player Sources/Episodes panels do not fully use the rewrite addon registry, scoring, retry state, and candidate models used for initial autoplay. Documented in `HANDOFF_BUG_SWEEP.md` as remaining work.

**User impact:** In-player source pick behaves differently from pre-play autoplay; inconsistent failures and ranking.

---

### BUG-027: Sync Apply Races With Live Playback Checkpoints

**Severity:** P1  
**Where:** `SyncCoordinator.kt`, `RewritePlaybackProgressSyncBridge.apply`, `RewriteRetainedPlayerHost.kt`

Sync holds its own mutex but rewrite progress uses the same DataStore without coordination. `apply()` does destructive clear-and-rewrite while the player saves every ~15s and on dispose.

**User impact:** Resume position can jump backward, disappear, or show a stale remote snapshot mid-session.

---

### BUG-028: Profile Switch Broken When Remember Active Profile Is Off

**Severity:** P1  
**Where:** `RewriteSettingsEffects.kt` (`effectiveProfileId`), `RewriteAppShell.kt`, legacy `ProfileDataStore` default

When `rememberLastProfile` is false (legacy default), `effectiveProfileId` always returns the first profile id. A shell `LaunchedEffect` immediately resets `activeProfileId` to that value whenever they differ.

**User impact:** Selecting profile 2 snaps back to profile 1; watchlist, progress, and debrid appear “broken.”

---

### BUG-029: Guest Mode Does Not Clear Stale Supabase Session

**Severity:** P1  
**Where:** `RewriteAuthGate.kt`, `RewriteGuestSessionStore.kt`

“Continue as Guest” sets the guest flag and enters the app without clearing an existing Supabase session. Background `SupabaseSyncWorker` may still run.

**User impact:** User thinks they are offline-guest while sync/auth still targets an old session.

---

### BUG-030: Auth Gate Blocks App On Transient Network At Cold Start

**Severity:** P1  
**Where:** `RewriteAuthGate.kt`

Active session + failed `auth.refresh()` shows an error state but does not clear session or offer automatic guest fallback despite local library being intact.

**User impact:** Flaky network at launch traps users on auth instead of local browsing.

---

### BUG-031: Sync Push Aborts Entire Cycle On First Failed Mutation

**Severity:** P1  
**Where:** `SyncCoordinator.kt` (`synchronizeLocked`)

One exhausted mutation failure returns `SyncResult.Failure` immediately; remaining pending mutations are not attempted in that run.

**User impact:** One bad record blocks all other domains from pushing until fixed.

---

### BUG-032: MediaFusion Sends Plaintext Token To Third-Party Host

**Severity:** P1  
**Where:** `RewriteMediaFusionConfigTransport.kt`, `RewriteProviderAddonConfig.kt`

Debrid token is POSTed as JSON to `mediafusionfortheweebs.midnightignite.me/encrypt-user-data`. User credentials transit through a non-user-controlled endpoint.

**User impact:** Trust and compromise risk beyond Torrentio URL embedding.

---

### BUG-033: Session Errors Hidden On Auth Choice Screen

**Severity:** P1  
**Where:** `RewriteAuthGate.kt`

Same root cause as BUG-023: bootstrap failures set `message` but Choice UI does not display it.

**User impact:** Silent failure on the primary auth entry screen.

---

### BUG-034: Cloud Manager Focus Lands On Container Not Controls

**Severity:** P1  
**Where:** `RewriteCloudManagerScreen.kt`

Outer `Column` has `.focusRequester(contentFocusRequester).focusable()` wrapping chips, fields, and buttons.

**User impact:** D-pad may focus an inert panel instead of Connect, API key field, or service chips.

---

### BUG-035: Details Play Button Has Double FocusRequester

**Severity:** P1  
**Where:** `RewriteDetailsScreen.kt`

Play `Button` chains `.focusRequester(playFocusRequester)` and `.focusRequester(contentFocusRequester)`.

**User impact:** Unreliable initial focus and header↔content handoff on TV.

---

### BUG-036: Fast Horizontal Navigation Setting Does Nothing

**Severity:** P1  
**Where:** `RewriteAccountSettings.kt` (Layout), `DpadThrottleModifier.kt`, `RewriteAppShell.kt`

Toggle persists to `LayoutPreferenceDataStore`, but `LocalFastHorizontalNavigationEnabled` is never provided in the rewrite shell (defaults false). `DpadThrottleModifier` is unused in rewrite screens.

**User impact:** Setting appears functional but changes nothing.

---

### BUG-037: Settings Profiles Row Bypasses Split-Pane Profiles UI

**Severity:** P1  
**Where:** `RewriteSettingsScreen.kt`, `RewriteAccountSettings.kt`

Selecting **Profiles** in Settings calls `onOpenProfiles()` (full-screen route) instead of showing `ProfilesSettings` in the split pane. The in-settings summary composable is orphaned.

**User impact:** Inconsistent information architecture; Profiles settings summary never shown where expected.

---

### BUG-038: Player Error Back Label Says Back To Streams

**Severity:** P1  
**Where:** `RewritePlayerSystemOverlay.kt`

Button text is **“Back to streams”** but `onPlaybackErrorBack` calls `navController.popBackStack()`, which may return to Home, Details, or the building screen.

**User impact:** Misleading copy after playback failure.

---

### BUG-039: Home Aggressively Steals Focus On Return

**Severity:** P1  
**Where:** `RewriteHomeScreen.kt`

`LaunchedEffect(contentFocusRequester, state.hero != null)` requests content focus whenever a hero exists, including when returning from Details.

**User impact:** Focus jumps from top nav to Play without user intent.

---

### BUG-040: Secondary Player Overlay Has No Local BackHandler

**Severity:** P1  
**Where:** `RewritePlayerSecondaryOverlayV2.kt`, `PlayerScreen.kt` (`handleBackPress`)

Overlay relies on `isExternalSecondaryOpen` in the player back stack. No local `BackHandler` on the overlay itself.

**User impact:** If player back ordering regresses, overlay may trap focus with no hardware-Back escape.

---

### BUG-041: Progress Cloud Sync Skipped For Non-Integer Profile IDs

**Severity:** P1  
**Where:** `RewritePlaybackProgressStore.kt`

`profileId.toIntOrNull() ?: return` before `syncBridge?.record/remove`.

**User impact:** Progress saves locally but never uploads for UUID profiles; multi-device resume fails silently.

---

### BUG-042: Profile-Scoped Writes Vulnerable To Profile-Switch TOCTOU

**Severity:** P1  
**Where:** `RewriteLibraryWatchStore.kt`, `RewritePlaybackProgressStore.kt`, `RewriteHomeSettingsStore.kt`, `RewriteDebridCredentialStore.kt`

Pattern: read `activeProfile.id` from flow, then separate `dataStore.edit`. Fast profile switch during save can write to the wrong profile.

**User impact:** Library, progress, or debrid data attached to the wrong profile.

---

### BUG-043: Dual Debrid Credential Systems

**Severity:** P1  
**Where:** `RewriteDebridCredentialStore.kt` vs `DebridSettingsDataStore.kt` / `RewriteSettingsFacade`

Rewrite connect path writes encrypted rewrite store; legacy facade still exposes keys on old store. Rewrite playback uses only rewrite store.

**User impact:** Credentials updated through legacy paths may not affect playback; confusing migrations.

---

### BUG-044: Malformed DataStore JSON Silently Resets State

**Severity:** P1  
**Where:** All rewrite `*Codec` objects (`RewriteProfileCodec`, `RewriteLibraryWatchCodec`, etc.)

Decode failures use `getOrDefault(empty)` with no user-visible corruption signal (unlike sync quarantine).

**User impact:** Corrupt preference blob silently wipes profiles, watchlist, progress, or addon registry.

---

### BUG-045: Retained Player Still Owns Broad Legacy Dependency Graph

**Severity:** P1 (release / provenance)  
**Where:** `PlayerViewModel.kt`, `PlayerRuntimeController*.kt`, `docs/BEHAVIOR_SPEC.md`

Player still injects historical repositories, Trakt, TMDB, torrent services, and legacy DataStores beyond the retained engine and autofile selector boundary.

**User impact:** Not a single crash, but clean-room claim and behavior isolation are incomplete; higher risk of regressions and policy leaks.

---

## P2 — Moderate

### BUG-046: Home Vertical Scroll Stuck Near Bottom Row

**Severity:** P2  
**Where:** `RewriteHomeScreen.kt` (`RewriteHomeBringIntoViewSpec`)

Custom bring-into-view only scrolls when the focused item is fully outside the viewport. Partially clipped last rows fall through `else -> 0f` and never scroll into full view.

**User impact:** User reaches the last row, it is half cut off, and pressing Down does nothing useful.

---

### BUG-047: Skip Overlay And Transport Controls Fight For Focus

**Severity:** P2  
**Where:** `RewritePlayerSkipOverlay.kt`, `PlayerScreen.kt`

Both request focus when skip is visible and controls are shown.

**User impact:** D-pad landing is non-deterministic during intros.

---

### BUG-048: Episode Season Change Shows Stale Episode List

**Severity:** P2  
**Where:** `RewriteRetainedPlayerHost.kt` (`selectEpisodeSeason`)

Immediate state keeps previous season’s episodes while async reload runs; no loading flag on the list.

**User impact:** Brief wrong-season episodes; user can select the wrong episode.

---

### BUG-049: Continue Watching And Streams Omit Parent Metadata

**Severity:** P2  
**Where:** `RewriteAppShell.kt`, `RewritePlayerRoute`

CW tiles and some Streams navigations omit `addonId` / `parentId` / `parentType`.

**User impact:** In-player Episodes panel shows “unavailable”; weaker Details routing from CW.

---

### BUG-050: Poster URL Nav Arg Mismatch For Retained Player

**Severity:** P2  
**Where:** `RewritePlayerRoute`, `PlaybackLaunchRequest`

Route passes `posterUrl=`; retained player launch reads `"poster"`. Rewrite checkpoints use `posterUrl` correctly; retained player poster metadata stays empty.

**User impact:** Missing poster art in retained player UI for rewrite launches.

---

### BUG-051: Resolved Source ID Is Torrent Info Hash

**Severity:** P2  
**Where:** `RewriteDebridRuntime.kt` resolvers

`ResolvedPlaybackSource(url, infoHash)` uses hash as `sourceId` where the model expects a source identifier.

**User impact:** In-player source switch labels show torrent hashes.

---

### BUG-052: Timeout Error Copy Always Says TorBox

**Severity:** P2  
**Where:** `RewriteAppShell.kt` (`resolveStreamCandidate`)

`TimeoutCancellationException` message hardcodes TorBox regardless of `debridConnection.activeService`.

**User impact:** Wrong provider name for Real-Debrid and Premiumize users.

---

### BUG-053: LIVE Content Classifier Over-Matches Sport And News

**Severity:** P2  
**Where:** `RewriteStreamScoringEngine.kt` (`classify`)

Operator precedence: `"sport" in text || "news" in text || ("live" && …)` — any sport/news genre token forces LIVE weights.

**User impact:** Normal titles tagged Sports/News get wrong autoplay ranking.

---

### BUG-054: Dual-Engine Failover Is Single-Shot

**Severity:** P2  
**Where:** `PlayerRuntimeControllerEngineFailover.kt`, `PlayerRuntimeControllerInitialization.kt`

First engine error switches once with `allowEngineFailover = false` on re-init. No reverse hop; mid-playback failures may skip failover.

**User impact:** One failed Exo→MPV hop leaves dead playback until manual switch or exit.

---

### BUG-055: Keystore Credential Ciphertext Cannot Sync Across Devices

**Severity:** P2  
**Where:** `ProviderCredentialCiphertextCodec.kt`, `SyncDomainAdapter.kt`

Device-bound Android Keystore encryption for provider vault ciphertext. Remote copy is not decryptable on another TV.

**User impact:** “Synced” API credentials fail on second device; looks like corruption.

---

### BUG-056: CAM/TS Penalty False Positives In Rewrite Scorer

**Severity:** P2  
**Where:** `RewriteStreamScoringEngine.kt` (`qualityScore`)

Word-boundary `\bts\b` matches benign tokens; large quality penalty applied incorrectly.

**User impact:** Good releases demoted; worse streams win autoplay.

---

### BUG-057: Settings Surface Contradicts Locked-Down Product Policy

**Severity:** P2 (policy / product)  
**Where:** `RewriteContentDiscoverySettings.kt`, `RewriteIntegrationSettings.kt`, `docs/BEHAVIOR_SPEC.md`

Shipped UI includes Trakt QR, MDBList keys, OpenSubtitles keys, addon enable toggles, raw manifest URLs on installed addons, and CW/library source pickers. Spec says settings should be browsing prefs, profiles, debrid connect, and non-interactive community status only.

**User impact:** Power-user surface the rewrite was meant to remove; may be intentional product drift — confirm with owner.

---

### BUG-058: Blank Or Gray Poster Art On CW And Popular Rows

**Severity:** P2  
**Where:** `RewriteAppShell.kt`, `RewritePlaybackProgressStore.kt`, `RewriteHero`

Stale null `posterUrl` on persisted checkpoints; hero Play does not pass poster into Streams (only backdrop exists on hero).

**User impact:** Gray tiles on Continue Watching and catalog rows.

---

### BUG-059: Cloud Manager Never Highlighted In Top Nav

**Severity:** P2  
**Where:** `RewriteAppShell.kt` (`RewriteRootNavigation`)

Cloud icon uses `selected = false` always.

**User impact:** No nav highlight while on Cloud Manager.

---

### BUG-060: Movies And TV Browse Have No Nav Selection State

**Severity:** P2  
**Where:** `RewriteAppShell.kt`

Only Watchlist browse filter matches a nav `selected` state. Movies/TV from Search show no active tab.

**User impact:** User feels lost in hierarchy after browse from Search.

---

### BUG-061: Movies And TV Browse Only Reachable From Search

**Severity:** P2  
**Where:** `RewriteSearchScreen.kt`, `RewriteAppShell.kt`

`onBrowseMovies` / `onBrowseShows` only wired from Search; top nav has Home and Watchlist only.

**User impact:** No top-level Movies/TV entry despite browse filters existing.

---

### BUG-062: Watchlist Versus My List Terminology Split

**Severity:** P2  
**Where:** `RewriteAppShell.kt` (nav), `RewriteDetailsScreen.kt` (library button)

Nav: **Watchlist**. Details: **Add to My List** / **In My List**.

**User impact:** Same library concept, different names — confusing on TV.

---

### BUG-063: In My List Reads As Status But Removes On Click

**Severity:** P2  
**Where:** `RewriteDetailsScreen.kt`

When `inLibrary == true`, button label **“In My List”** still toggles library off on select.

**User impact:** Users may not realize the button removes from the list.

---

### BUG-064: Watchlist Empty State Grammar Error

**Severity:** P2  
**Where:** `RewriteAppShell.kt` (`forBrowse`)

Empty message: **“No watchlist are available yet.”**

**User impact:** Unpolished copy on empty watchlist.

---

### BUG-065: Browse Depends On Home Catalog Load

**Severity:** P2  
**Where:** `RewriteAppShell.kt`

Browse reuses `baseHomeState` from home catalog, not an independent fetch.

**User impact:** Movies/TV browse empty while home is still loading or failed, even if search would work.

---

### BUG-066: Settings Detail Destination Is Dead Code

**Severity:** P2  
**Where:** `RewriteSettingsScreen.kt`, `RewriteSettingsModels.kt`

`SettingsDestination.Detail` branch exists; nothing sets it. Split-pane is always used.

**User impact:** Untested navigation path; maintenance dead weight.

---

### BUG-067: Advanced Settings Composable Never Surfaced

**Severity:** P2  
**Where:** `RewritePlaybackSettings.kt`, `RewriteSettingsScreen.kt`

`AdvancedSettings()` defined but not referenced in `RenderSettingsDetail`.

**User impact:** Automatic playback policy information hidden from users.

---

### BUG-068: Integration Settings Show Raw Enum Names

**Severity:** P2  
**Where:** `RewriteIntegrationSettings.kt`

Cycle rows display `TRAKT`, `SLUGYARD_SYNC`, etc., instead of friendly labels.

**User impact:** Technical enum strings on a TV settings surface.

---

### BUG-069: Trakt Device Auth Poll Loop Without Lifecycle Guard

**Severity:** P2  
**Where:** `RewriteIntegrationSettings.kt`

`while (true) { delay; poll }` keyed only on auth `code`. Leaving settings mid-flow may leave polling until composition ends.

**User impact:** Background polling and races if user reopens Trakt quickly.

---

### BUG-070: Profile Selection Always Returns To Home

**Severity:** P2  
**Where:** `RewriteAppShell.kt` (`RewriteProfilesScreen` `onSelect`)

`openRoot(RewriteRootDestination.Home)` regardless of entry point (Settings vs avatar).

**User impact:** Picking a profile from Settings does not return to Settings.

---

### BUG-071: Profiles Screen Has No On-Screen Back

**Severity:** P2  
**Where:** `RewriteProfilesScreen.kt`

Only hardware `BackHandler` in shell; no visible Back affordance.

**User impact:** From Settings push, Back works but there is no TV-visible escape hint.

---

### BUG-072: Profile Rename And Remove Require Undocumented Long-Press

**Severity:** P2  
**Where:** `RewriteProfilesScreen.kt`

`combinedClickable(onLongClick = onEdit)` with no on-screen hint.

**User impact:** Rename/remove undiscoverable on remote.

---

### BUG-073: Duplicate Back Handlers On Search

**Severity:** P2  
**Where:** `RewriteSearchScreen.kt`, `RewriteAppShell.kt`

Search has local `BackHandler`; shell also handles Back when destination != Home. Both route to Home today.

**User impact:** Redundant; risk of divergent behavior if one path changes.

---

### BUG-074: Partial Home Catalog Failures Are Silent

**Severity:** P2  
**Where:** `RewriteAppShell.kt` (`toScreenState`)

`statusMessage` only when no rows exist and all sources failed. Per-row failures are invisible.

**User impact:** Missing shelves with no explanation.

---

### BUG-075: Hero Auto-Rotates While User Is Focused On Hero Actions

**Severity:** P2  
**Where:** `RewriteHomeScreen.kt`

`while (true) { delay(11_000); heroIndex++ }` while user may be focused on Play/More Info.

**User impact:** Focused actions may refer to a hero that rotates away.

---

### BUG-076: Hero Summary Truncated To One Line

**Severity:** P2  
**Where:** `RewriteHomeScreen.kt`

`maxLines = 1` on hero summary at TV viewing distance.

**User impact:** Plot text effectively unreadable.

---

### BUG-077: Details Description Truncated To Three Lines

**Severity:** P2  
**Where:** `RewriteDetailsScreen.kt`

`maxLines = 3` on synopsis with no read-more.

**User impact:** Long descriptions clipped on TV.

---

### BUG-078: Episode Watched Toggle Uses Undocumented Long-Press

**Severity:** P2  
**Where:** `RewriteDetailsScreen.kt`

`combinedClickable(onLongClick = { onWatchedChanged })` on episode rows.

**User impact:** Mark watched/unwatched undiscoverable on remote.

---

### BUG-079: Season Chips Lack Focus Chain To Episodes

**Severity:** P2  
**Where:** `RewriteDetailsScreen.kt`

Season `FilterChip` row has no `focusProperties` linking down to first episode.

**User impact:** D-pad Down from seasons may miss episode list depending on layout.

---

### BUG-080: Building Player Screen Back Not Auto-Focused On Error

**Severity:** P2  
**Where:** `RewriteAppShell.kt` (`RewriteBuildingPlayerScreen`)

`OutlinedButton(Back)` has no initial focus request on failure state.

**User impact:** On auto-pick failure, user may have no focused element.

---

### BUG-081: Tab Navigation Clears Back Stack

**Severity:** P2  
**Where:** `RewriteAppShell.kt` (`openRoot`)

Top nav tab switches replace `routeStack` with a single destination.

**User impact:** Home → Details → Settings loses Details (may be intended tab behavior but surprises users expecting stack).

---

### BUG-082: Sign Out Clears Guest And Forces Auth Gate

**Severity:** P2  
**Where:** `RewriteAppShell.kt`, `RewriteAccountSettings.kt`

Sign out sets guest false and `onSignedOut()` without offering continue-as-guest for local-only use.

**User impact:** Sign out always forces auth wall.

---

### BUG-083: Sources Panel Shows Perpetual Loading On Every Emit

**Severity:** P2  
**Where:** `RewriteRetainedPlayerHost.kt` (`loadPlayerSources`)

Each flow emit sets `sourceLoading = true` even after first batch arrived.

**User impact:** “Finding sources…” may persist while lists are already visible.

---

### BUG-084: Cache Check Storms And Duplicate Hash Lookups

**Severity:** P2  
**Where:** `RewriteStreamsDataSource.kt`, `RewriteDebridRuntime.kt`

Independent cache-check jobs per addon group; no cross-group deduplication or global concurrency cap. `joinAll()` blocks completion.

**User impact:** Slow stream screen on torrent-heavy titles; rate-limit risk; long “checking cache” state.

---

### BUG-085: Serial Hero Enrichment On Home Load

**Severity:** P2  
**Where:** `RewriteHeroEnrichmentDataSource.kt`, `RewriteAppShell.kt`

Up to eight sequential `fetchMeta` calls per home load; no cancel when hero key changes mid-loop.

**User impact:** Sluggish hero on slow networks; wasted work on fast catalog churn.

---

### BUG-086: Concurrent Community Provision Without Mutex

**Severity:** P2  
**Where:** `RewriteAppShell.kt`, `RewriteCommunityAddonProvisioner.kt`

Profile switch, debrid toggle, and reconnect each trigger full provision; no cancel-previous or mutex.

**User impact:** Redundant manifest fetches and registry write churn.

---

### BUG-087: No Retry Or Backoff On Debrid HTTP

**Severity:** P2  
**Where:** `RewriteTorboxTransport.kt`, `RewritePremiumizeTransport.kt`, `RewriteRealDebridTransport.kt`

Single attempt per call; contrast with sync coordinator’s retry loop.

**User impact:** Transient network blips show “unavailable” and reject auto-pick candidates.

---

### BUG-088: Separate OkHttp Clients Per Transport

**Severity:** P2  
**Where:** `RewriteNetworkClient.kt`, debrid transports, `StremioAddonTransport`

Each component calls `RewriteNetworkClient.create()` independently.

**User impact:** Extra memory and TCP overhead; slower warm-up on fan-out.

---

### BUG-089: Hardcoded MediaFusion Bootstrap Manifest In Allowlist

**Severity:** P2  
**Where:** `SlugYardCommunitySourcePolicy.kt`

Fixed encrypted-path URL baked into bootstrap list.

**User impact:** If upstream path expires, one of four bootstrap addons fails every cold start until debrid reconfigures MediaFusion.

---

### BUG-090: Community Provision Failures Silent At UI Layer

**Severity:** P2  
**Where:** `RewriteCommunityAddonProvisioner.kt`, `RewriteAppShell.kt`

`provisionCommunitySources` ignores `CommunityProvisioningReport` counts.

**User impact:** Empty catalog with no guided “configure debrid” or “retry” messaging.

---

### BUG-091: Sync Merge Uses lastWatched Not changedAt

**Severity:** P2  
**Where:** `SupabaseMergePolicy.kt` (`mergeProgress`)

Conflict resolution keys off `lastWatched`, not `changedAt`.

**User impact:** Older playback position can win if remote updates `changedAt` without bumping `lastWatched`.

---

### BUG-092: Library Watchlist Grows Without Cap

**Severity:** P2  
**Where:** `RewriteLibraryWatchStore.kt`

No cap on library entries (progress is capped at 100).

**User impact:** Long-term DataStore bloat and decode cost on low-memory TV devices.

---

### BUG-093: Playback Checkpoint Poll Every Second

**Severity:** P2  
**Where:** `RewriteRetainedPlayerHost.kt`

1s poll loop during playback; saves trigger DataStore edit and optional sync outbox every 15s of position change.

**User impact:** Higher IO and sync churn than necessary on always-on TVs.

---

### BUG-094: Settings Visual Cutoff Not Root-Caused

**Severity:** P2  
**Where:** Settings sub-screens (reported, unverified)

User reported something “cut off” in Settings after redesign. Scaffold and padding look correct statically; needs per-category screenshots.

**User impact:** Unknown until reproduced on device.

---

### BUG-095: Stream List Header Back Only When Empty

**Severity:** P2  
**Where:** `RewriteStreamsScreen.kt`

Header Back shown only when `firstStreamId == null` (manual mode path; currently unreachable — see BUG-007).

**User impact:** If manual mode is enabled later, inconsistent back affordance when streams exist.

---

### BUG-096: Nested SlugYardTvTheme Wrappers

**Severity:** P2  
**Where:** `MainActivity.kt`, `RewriteAppShell.kt`, `RewriteAuthGate.kt`

Theme applied at activity, shell content, and auth gate.

**User impact:** Usually harmless; possible subtle token duplication.

---

### BUG-097: Debug Logging Left In Production Paths

**Severity:** P2  
**Where:** `RewriteAppShell.kt`

`SlugYardDebug`, `SlugYardPlayback` logs in poster selection and stream resolve.

**User impact:** Log noise; potential metadata leakage in logcat.

---

### BUG-098: Catalog Ordering Row Is Read-Only Stub

**Severity:** P2  
**Where:** `RewriteContentDiscoverySettings.kt`

“Catalog ordering” shows static copy with no action.

**User impact:** Appears as a setting but does nothing.

---

## P3 — Minor

### BUG-099: DataStore File Still Named playflix_rewrite

**Severity:** P3  
**Where:** `SyncModule.kt` (`provideRewriteDataStore`)

Internal preferences file name uses historical `playflix_rewrite` string.

**User impact:** None user-visible; naming inconsistency for maintainers.

---

### BUG-100: BEHAVIOR_SPEC Design System Section Is Stale

**Severity:** P3  
**Where:** `docs/BEHAVIOR_SPEC.md` §3

Still references `PlayFlixDesignSystem` and “no SlugYard aliases”; code uses `SlugYardTvTheme` / `SlugYardPalette`.

**User impact:** Misleading handoff for future agents.

---

### BUG-101: IntroDB Requires Real Endpoint Before Verification

**Severity:** P3  
**Where:** `SkipIntroRepository.kt`, `HANDOFF_BUG_SWEEP.md`

Fake default IntroDB fallback removed; path needs explicit configuration.

**User impact:** Skip/IntroDB cannot be verified until configured — not a code defect, a test gap.

---

### BUG-102: Episode Pagination Contract Weak Across Addons

**Severity:** P3  
**Where:** `RewriteDetailsDataSource.kt`, addon protocol

Stremio addon responses do not provide a reliable universal pagination contract for long season lists.

**User impact:** Very long seasons may truncate or load inconsistently per source.

---

### BUG-103: Torbox Transport Logs Request Paths At Debug

**Severity:** P3  
**Where:** `RewriteTorboxTransport.kt`

`Log.d` includes encoded path (torrent/file identifiers, not bearer token).

**User impact:** Low privacy noise in logcat.

---

### BUG-104: Profile ID Migration Edge Cases default Versus 1

**Severity:** P3  
**Where:** `RewriteProfileStore.kt`, `RewriteAppShell.synchronizeProfiles`

Migration copies `default` → `1` only in specific pristine states. Partial init may leave inconsistent keys.

**User impact:** Rare invisible data on wrong profile key after upgrade.

---

### BUG-105: rememberLastProfile Default Mismatch Rewrite Versus Legacy

**Severity:** P3  
**Where:** `RewriteProfileState` (default true) vs `ProfileDataStore` (default false)

Imported only on first migration.

**User impact:** Fresh vs migrated installs behave differently for profile persistence semantics.

---

### BUG-106: Accessibility Gaps Across Rewrite UI

**Severity:** P3  
**Where:** Nav text items, settings rows, profiles, value picker, search icon, decorative images

Many controls lack `contentDescription` / semantics; player controls and poster cards are comparatively good.

**User impact:** TalkBack and accessibility services have poor coverage.

---

### BUG-107: Legacy RewritePlayerSecondaryOverlay Unused

**Severity:** P3  
**Where:** `RewritePlayerSecondaryOverlay.kt`

Production host uses V2; legacy overlay only in tests.

**User impact:** Maintenance drift risk, not user-facing.

---

### BUG-108: Skip Overlay Only Auto-Focuses Skip Not Not Now

**Severity:** P3  
**Where:** `RewritePlayerSkipOverlay.kt`

Focus requester on Skip button only.

**User impact:** Extra D-pad presses to reach “Not now.”

---

### BUG-109: Post-Play And Diagnostics Overlays Rely On Player Back Stack

**Severity:** P3  
**Where:** `RewritePlayerPostPlayOverlay.kt`, `RewritePlayerDiagnosticsOverlay.kt`

No local `BackHandler` except diagnostics when visible.

**User impact:** OK if player stack ordering stays correct.

---

### BUG-110: MDBList And OpenSubtitles Show Saved Securely Without Manage UX

**Severity:** P3  
**Where:** `RewriteIntegrationSettings.kt`

Security-appropriate but users cannot verify which key is saved beyond clear.

**User impact:** Minor confusion, not a leak.

---

### BUG-111: MainActivity Does Not Handle Deep Links Or External Play Intents

**Severity:** P3  
**Where:** `MainActivity.kt`

No intent URI routing to rewrite player.

**User impact:** External “play this” intents ignored if product expects them.

---

## Intentional behavior (not bugs)

| Behavior | Notes |
|----------|--------|
| Fixed PlayFlix Community allowlist (Cinemeta, OpenSubtitles, Torrentio, MediaFusion) | Product policy per `BEHAVIOR_SPEC.md` |
| No user-supplied generic addon manifest URLs | Locked-down rewrite |
| Guest session persisted across restarts | `RewriteGuestSessionStore` + `PRODUCT.md` |
| Ignore plaintext v1 debrid credential blobs | Clean-room boundary |
| Real-Debrid has no proactive cache checker | By design |
| Autoplay waits for full stream discovery before picking | Avoids locking onto first fast but bad DV stream |
| Tab nav `openRoot` resets stack | Debatable UX but intentional tab model |

---

## Open release gates

These are documented incomplete work, not individual UI defects:

- Full unit test suite (retained Media3/native JVM failures)
- Full lint gate (~2,373 errors reported in handoff)
- Retained-player seam not narrowed to engine + autofile only (see BUG-045)
- Keystore credential AndroidTest missing
- Fresh Onn 4K Pro end-to-end smoke
- Live playback verification on emulator (no working debrid/source configured in prior QA)
- Provenance / license audit for shipping variant

---

## Summary counts

| Severity | Count |
|----------|------:|
| P0 | 7 |
| P1 | 38 |
| P2 | 53 |
| P3 | 13 |
| **Total named bugs** | **111** |

---

## Suggested fix order (for a future pass)

1. **Progress & resume:** BUG-001, BUG-005, BUG-004, BUG-027, BUG-041  
2. **Play path dead end:** BUG-006, BUG-007, BUG-010  
3. **Security:** BUG-002, BUG-003, BUG-021  
4. **Debrid correctness:** BUG-008, BUG-022, BUG-012  
5. **TV focus & Back:** BUG-034, BUG-035, BUG-039, BUG-038, BUG-046  
6. **Profiles & sync:** BUG-028, BUG-018, BUG-042  

---

*This file supersedes informal sweep notes in chat. `HANDOFF_BUG_SWEEP.md` documents the broader
handoff history; this file retains the original finding text plus the current disposition ledger.*

## Resolution Ledger — 2026-07-28

The audit was converted from a read-only list into an evidence-backed disposition ledger. “Fixed”
means the current worktree contains a code or documentation change for the described behavior.
“Blocked” means the remaining behavior requires a backend/schema, trust-boundary, device, external
service, product, or provenance decision and is not being falsely reported as complete.

### Fixed

`BUG-001`, `BUG-002`, `BUG-003`, `BUG-005`, `BUG-006`, `BUG-007`, `BUG-008`, `BUG-009`,
`BUG-010`, `BUG-011`, `BUG-012`, `BUG-013`, `BUG-014`, `BUG-015`, `BUG-016`, `BUG-017`,
`BUG-019`, `BUG-020`, `BUG-021`, `BUG-022`, `BUG-023`, `BUG-024`, `BUG-026`, `BUG-027`,
`BUG-028`, `BUG-029`, `BUG-030`, `BUG-031`, `BUG-033`, `BUG-034`, `BUG-035`, `BUG-036`,
`BUG-037`, `BUG-038`, `BUG-039`, `BUG-040`, `BUG-042`, `BUG-043`, `BUG-044`, `BUG-046`,
`BUG-047`, `BUG-048`, `BUG-049`, `BUG-050`, `BUG-051`, `BUG-052`, `BUG-053`, `BUG-054`,
`BUG-056`, `BUG-058`, `BUG-059`, `BUG-060`, `BUG-061`, `BUG-062`, `BUG-063`, `BUG-064`,
`BUG-065`, `BUG-068`, `BUG-069`, `BUG-070`, `BUG-071`, `BUG-072`, `BUG-073`, `BUG-074`,
`BUG-075`, `BUG-076`, `BUG-077`, `BUG-078`, `BUG-079`, `BUG-080`, `BUG-082`, `BUG-083`,
`BUG-084`, `BUG-085`, `BUG-086`, `BUG-087`, `BUG-088`, `BUG-090`, `BUG-091`, `BUG-092`,
`BUG-093`, `BUG-095`, `BUG-097`, `BUG-098`, `BUG-100`, `BUG-103`, `BUG-104`, `BUG-106`,
`BUG-108`.

Evidence: the Rewrite graph now passes resume checkpoints into retained-player navigation;
library/watch mutations use `SyncMutationRecorder` and `RewriteLibraryWatchSyncBridge`; profile
writes capture one profile ID per operation; codecs expose `decodeOrNull` and
`RewriteDataIntegrityStore` surfaces corruption; cache probes deduplicate hashes and use a shared
semaphore; enrichment is bounded with cancellable `async` work; provisioning is mutex-serialized;
provider transports share `RewriteNetworkClient` and retry transient failures; player source
switching loads progress before initialization; and the UI changes are in the Rewrite Home,
Details, Streams, Profiles, Settings, and player overlay files. `:app:compileFullDebugKotlin`
passes after these changes.

### Fixed with a bounded contract

`BUG-004`, `BUG-018`, and `BUG-041` have their destructive/local portions fixed, but cloud sync
still accepts only the existing integer `profile_id` schema. UUID Rewrite profile IDs are not
hashed, truncated, or silently dropped into another profile. Numeric migrated profiles sync through
the existing Supabase tables; UUID cloud identity remains a backend-contract blocker.

### Intentional or reclassified

`BUG-055` is intentional device-local Keystore policy; credentials require reconnect on another
device. `BUG-066`, `BUG-067`, `BUG-081`, `BUG-089`, `BUG-096`, `BUG-099`, `BUG-107`, `BUG-109`,
and `BUG-110` are maintenance or locked-product policy dispositions, not reproduced user-facing
defects. `BUG-107` is explicitly deprecated because production uses
`RewritePlayerSecondaryOverlayV2`.

### Blocked pending product, backend, or external evidence

`BUG-025` remains an unreproduced device-routing report and needs UI-automation evidence.
`BUG-032` still requires an approved trust-boundary decision for MediaFusion's plaintext-token
configuration endpoint; removing a log line would not solve the credential transit problem.
`BUG-045` is a retained-player provenance/seam architecture gate, not a safe isolated deletion.
`BUG-057` remains a product-policy conflict because Trakt, MDBList, and OpenSubtitles surfaces
are still present while the locked-down spec says they should not be. The nonfunctional Rewrite
source pickers and addon enable toggles were removed; the remaining integrations require owner
approval before removing supported integrations. `BUG-094` needs per-category target-device
screenshots and overscan reproduction. `BUG-101` needs a real supported IntroDB endpoint.
`BUG-102` needs a provider-specific pagination contract. `BUG-105` needs a decision on fresh versus
migrated `rememberLastProfile` defaults. `BUG-111` needs a committed external-play/deep-link URI
contract before accepting intents into the player.

### Release gates still requiring external verification

The full JVM suite retains the two known Media3 `DefaultAllocatorTest` native/JNI failures.
Repository lint remains an existing debt gate and was not silently claimed green. The retained
player seam/provenance audit, Keystore AndroidTest execution, fresh Onn 4K Pro smoke, live
playback with a real configured source, and shipping-variant provenance/license audit require
device, environment, or release-owner evidence unavailable from static/JVM verification.
