# SlugYard — UX annoyance sweep (polish tier)

**Status:** Read-only audit. Not dealbreakers — the stuff that works but grates over time.  
**Companion doc:** `BUG_SWEEP_FINDINGS.md` (P0/P1 and structural issues).  
**Last updated:** 2026-07-27

Use this for a **polish pass**: copy, focus, sizing, labels, discoverability, nav consistency.

---

## How to read this

| Tier | Meaning |
|------|---------|
| **Annoy** | Noticeable on a normal session; fix in &lt;1 day each |
| **Nit** | Small inconsistency; batch-friendly |

Each item: **name → what bugs you → where → quick fix idea**

---

## Search

### ANN-001: Search can’t tell movie from TV show
**What:** Results look identical — no Movie/TV badge, year, or row grouping. Searching “jjk” may show the film but the series is easy to miss or looks the same.  
**Where:** `RewriteSearchDataSource.kt` (stores `contentType`); `RewritePosterCard` (never renders it); `RewriteSearchScreen.kt`  
**Fix:** Type pill on card; optional Movies / TV sections; dedupe by `type:id` not `id` alone.

### ANN-002: Search posters feel thin / small
**What:** 132×198 dp in a 5-column grid vs Home 176×264 in horizontal rows — same aspect ratio but cramped on TV.  
**Where:** `RewriteSearchScreen.kt` (`GridCells.Fixed(5)`, `cardWidth = 132.dp`)  
**Fix:** Match Home card size; use 4 columns or horizontal rows per type.

### ANN-003: Browse rail (Movies / TV Shows) isn’t D-pad friendly
**What:** Left rail uses `clickable` text only — no focus ring, hard to reach from search field with remote.  
**Where:** `RewriteSearchScreen.kt` (`SearchCategoryRail`)  
**Fix:** Focusable TV rows with same pattern as `RewriteRootNavigationItem`.

### ANN-004: Search screen hides the top nav bar
**What:** On Search (and Profiles), the global nav band is omitted — feels like a different app; only Back returns you.  
**Where:** `RewriteAppShell.kt` (`destination !is Search && !is Profiles`)  
**Fix:** Keep slim nav on Search or add a visible “Home” escape in header.

### ANN-005: Search field not wired up to header focus
**What:** `headerFocusRequester = null` on Search — Up from field doesn’t land on a consistent global target.  
**Where:** `RewriteAppShell.kt` (Search destination)  
**Fix:** Wire Up to a search header or restored nav row.

### ANN-006: Idle search is bare
**What:** Only “Start typing to search…” — no recent searches, examples, or trending (Nuvio has history + discover).  
**Where:** `RewriteSearchScreen.kt` (`SearchHint`)  
**Fix:** Recent queries DataStore + 2–3 example chips.

### ANN-007: Flat search results (no per-addon rows)
**What:** Everything dumped into one grid; Nuvio shows progressive catalog rows so you see *where* a hit came from.  
**Where:** `RewriteSearchDataSource.kt`, `RewriteSearchScreen.kt`  
**Fix:** Group by addon or type like Nuvio `CatalogRowSection`.

---

## Home

### ANN-008: Hero title and synopsis truncated to one line
**What:** Long titles and plot clipped at 10-foot distance.  
**Where:** `RewriteHomeScreen.kt` (`maxLines = 1` on title and summary)  
**Fix:** 2-line title, 2–3 line synopsis with scrim.

### ANN-009: Hero carousel rotates while you’re on it
**What:** Every 11s the featured title changes even if you’re focused on Play/Details.  
**Where:** `RewriteHomeScreen.kt` (`LaunchedEffect(heroKey)` loop)  
**Fix:** Pause rotation when hero actions have focus.

### ANN-010: Hero summary empty until enrichment finishes
**What:** Brief flash of hero with no synopsis after catalog load.  
**Where:** `RewriteAppShell.kt` hero mapping; `RewriteHeroEnrichmentDataSource.kt`  
**Fix:** Placeholder skeleton or hide summary line until loaded.

### ANN-011: Shelf posters have no movie/TV badge
**What:** Hero has `HeroBadge`; row cards don’t — same discoverability gap as search.  
**Where:** `RewritePosterCard` in `RewriteHomeScreen.kt`  
**Fix:** Small type chip consistent with search fix.

### ANN-012: Continue Watching tiles lack episode context
**What:** Series resume doesn’t show S01E05 (or similar) on the poster — only in checkpoint data.  
**Where:** `RewriteAppShell.kt` CW mapping; `RewritePosterCard`  
**Fix:** Overlay label for series checkpoints.

### ANN-013: Hero Play on a series goes to Details, not play
**What:** Extra tap for series — Play should feel like Play (next ep / streams).  
**Where:** `RewriteAppShell.kt` (`onPlay` for series → Details)  
**Fix:** Route series Play to streams/next episode like Nuvio.

### ANN-014: Partial catalog failures are silent
**What:** Missing rows with no “this source failed” message — only total failure shows status.  
**Where:** `RewriteAppShell.kt` (`toScreenState`)  
**Fix:** Per-row error or subtle “unavailable” on empty row.

### ANN-015: Home scroll sticks near last row
**What:** Custom `BringIntoViewSpec` doesn’t scroll partially clipped bottom rows.  
**Where:** `RewriteHomeScreen.kt` (`RewriteHomeBringIntoViewSpec`)  
**Fix:** Scope custom spec to hero only; default scroll for catalog rows.

### ANN-016: Returning Home steals focus to hero
**What:** `LaunchedEffect` re-requests content focus when hero exists — yanks from top nav.  
**Where:** `RewriteHomeScreen.kt`  
**Fix:** Only request initial focus on cold entry, not every remount.

---

## Navigation & shell

### ANN-017: Watchlist vs My List
**What:** Nav says **Watchlist**; Details says **Add to My List** / **In My List**.  
**Where:** `RewriteAppShell.kt`, `RewriteDetailsScreen.kt`  
**Fix:** One vocabulary everywhere.

### ANN-018: “No watchlist are available yet.”
**What:** Grammar break from `filter.title().lowercase()` + “are”.  
**Where:** `RewriteAppShell.kt` (`forBrowse` statusMessage)  
**Fix:** Per-filter copy: “Your watchlist is empty.”

### ANN-019: Cloud Manager never highlighted in nav
**What:** Cloud icon always `selected = false` on that screen.  
**Where:** `RewriteAppShell.kt` (`RewriteRootNavigation`)  
**Fix:** `selected = destination == CloudManager`.

### ANN-020: Movies/TV browse has no nav highlight
**What:** Browse from Search doesn’t light up Home, Watchlist, or Search.  
**Where:** `RewriteAppShell.kt` (`navDestination` for `Browse`)  
**Fix:** Highlight Browse or add Movies/TV nav entries.

### ANN-021: Details/Streams highlight Home in nav
**What:** You’re on Details but **Home** looks selected — misleading wayfinding.  
**Where:** `RewriteAppShell.kt` (maps Details/Streams → Home for nav highlight)  
**Fix:** Neutral nav state or breadcrumb, not fake Home.

### ANN-022: Tab switch wipes stack
**What:** Home → Details → tap Settings = Details gone (by design, but surprising).  
**Where:** `RewriteAppShell.kt` (`openRoot`)  
**Fix:** Document or use stack for drill-down, tabs for roots only.

### ANN-023: TorBox named on every timeout
**What:** Error says TorBox even for Real-Debrid / Premiumize.  
**Where:** `RewriteAppShell.kt` (`resolveStreamCandidate` timeout message)  
**Fix:** Active provider label or generic “cloud service”.

### ANN-024: Torbox vs TorBox spelling
**What:** Settings **Torbox**; errors **TorBox**.  
**Where:** `RewriteSettingsRows.kt`, `RewriteAppShell.kt`  
**Fix:** One brand spelling.

### ANN-025: Debug logs in normal use
**What:** `SlugYardDebug`, `SlugYardPlayback`, Torbox HTTP paths in logcat.  
**Where:** `RewriteAppShell.kt`, `RewriteTorboxTransport.kt`, `MainActivity.kt`  
**Fix:** `BuildConfig.DEBUG` gate.

---

## Details

### ANN-026: Synopsis capped at three lines
**What:** No “read more” on TV.  
**Where:** `RewriteDetailsScreen.kt` (`maxLines = 3`)  
**Fix:** Expand on Select or 5 lines.

### ANN-027: Cast capped at four names, one line each
**What:** Rest of cast invisible with no affordance.  
**Where:** `RewriteDetailsScreen.kt` (`take(4)`)  
**Fix:** Cast row scroll or “Full cast”.

### ANN-028: Mark episode watched = long-press only
**What:** No on-screen hint; remote users won’t find it.  
**Where:** `RewriteDetailsScreen.kt` (`combinedClickable` on episode rows)  
**Fix:** Visible watched toggle or Select-menu hint.

### ANN-029: Watched icon looks clickable but isn’t
**What:** Checkmark shows state; only long-press changes it.  
**Where:** `RewriteDetailsScreen.kt` (episode row)  
**Fix:** Focusable icon or label “Hold to mark watched”.

### ANN-030: “In My List” looks like status, removes on click
**What:** Button reads like a label, acts like remove.  
**Where:** `RewriteDetailsScreen.kt`  
**Fix:** **Remove from watchlist** when in list; align with ANN-017.

### ANN-031: Disabled “Watched” button on full season
**What:** Grey button that does nothing — confusing affordance.  
**Where:** `RewriteDetailsScreen.kt` (season header)  
**Fix:** Static badge instead of disabled button.

### ANN-032: Play button double focus requester
**What:** Can confuse initial D-pad landing on Details.  
**Where:** `RewriteDetailsScreen.kt` (two `focusRequester`s on Play)  
**Fix:** Single focus owner.

---

## Streams & auto-pick UI

### ANN-033: “Choose a stream” vs player “Sources”
**What:** Same concept, different words.  
**Where:** `RewriteStreamsScreen.kt`, `RewritePlayerControls.kt`  
**Fix:** Pick one term.

### ANN-034: Building screen Back not auto-focused on error
**What:** After auto-pick failure, may have no focused control.  
**Where:** `RewriteAppShell.kt` (`RewriteBuildingPlayerScreen`)  
**Fix:** Focus Back button when error shows.

### ANN-035: Error says “choose another source” but no picker
**What:** Copy promises action the UI doesn’t offer (polish + functional annoyance).  
**Where:** `RewriteAppShell.kt` auto-pick path  
**Fix:** Button to manual source list.

---

## Player overlays

### ANN-036: “Back to streams” after error
**What:** Often goes to Details/Home — user never saw a streams screen.  
**Where:** `RewritePlayerSystemOverlay.kt`  
**Fix:** **Back** or **Try another source**.

### ANN-037: Speed button doesn’t show current rate
**What:** Always labeled “Speed” even at 1.25×.  
**Where:** `RewritePlayerControls.kt`  
**Fix:** Show active multiplier on label.

### ANN-038: Skip “mixed-op” labeled “Skip outro”
**What:** Wrong CTA on anime openings (`contains("ed")` matches “mixed”).  
**Where:** `RewritePlayerSkipOverlay.kt` (`skipLabel`)  
**Fix:** Match controller’s intro/outro mapping.

### ANN-039: Skip vs transport focus fight
**What:** Skip overlay and controls both grab focus when visible.  
**Where:** `RewritePlayerSkipOverlay.kt`, `PlayerScreen.kt`  
**Fix:** One focus owner when skip visible.

### ANN-040: Post-play autoplay has no “Not now”
**What:** Countdown card only **Play next** — no dismiss.  
**Where:** `RewritePlayerPostPlayOverlay.kt`  
**Fix:** Cancel / Back dismisses countdown.

### ANN-041: Player error text hard-truncated
**What:** `cleanPlayerStatus()` caps at ~180 chars.  
**Where:** `RewritePlayerSystemOverlay.kt`  
**Fix:** Scrollable or “Show more”.

### ANN-042: Subtitle cue list one line
**What:** Long dialogue ellipsized in sync picker.  
**Where:** `RewritePlayerSubtitleTimingOverlay.kt`  
**Fix:** 2 lines or preview pane.

### ANN-043: Episodes unavailable — vague message
**What:** Doesn’t say *why* (missing parent id, addon, etc.).  
**Where:** `RewriteRetainedPlayerHost.kt`  
**Fix:** Actionable copy: “Open from show details to pick episodes.”

---

## Settings & integrations

### ANN-044: Raw enum names in cycle pickers
**What:** User sees `TRAKT`, `SLUGYARD_SYNC`, `FRAME_RATE_MATCHING_MODE` names, etc.  
**Where:** `RewriteIntegrationSettings.kt`, `RewritePlaybackSettings.kt`  
**Fix:** Human labels in picker; store enum internally.

### ANN-045: Trakt source toggles visible when logged out
**What:** CW/Library source cycles do nothing useful without Trakt.  
**Where:** `RewriteIntegrationSettings.kt`  
**Fix:** Hide or disable until connected.

### ANN-046: “Catalog ordering” row does nothing
**What:** Read-only stub looks like a setting.  
**Where:** `RewriteContentDiscoverySettings.kt`  
**Fix:** Implement or remove row.

### ANN-047: TMDB duplicated across categories
**What:** Toggle in Content & Discovery; read-only row in Integrations.  
**Where:** `RewriteContentDiscoverySettings.kt`, `RewriteIntegrationSettings.kt`  
**Fix:** Single surface or cross-link.

### ANN-048: “Ratings” row is vague
**What:** “TMDB setting controls player metadata” — unclear.  
**Where:** `RewriteIntegrationSettings.kt`  
**Fix:** Describe MDBList/ratings behavior.

### ANN-049: “Connect encrypted credentials.”
**What:** Reads like an error, not a CTA.  
**Where:** `RewriteIntegrationSettings.kt` (Debrid rows)  
**Fix:** **Connect with API key**.

### ANN-050: Installed addon shows raw manifest URL
**What:** Long URL as subtitle on TV.  
**Where:** `RewriteContentDiscoverySettings.kt`  
**Fix:** Host name or “Configured”.

### ANN-051: Fast horizontal navigation toggle inert
**What:** Layout setting saves but rewrite shell never provides `LocalFastHorizontalNavigationEnabled`.  
**Where:** `RewriteAccountSettings.kt`, `DpadThrottleModifier.kt`  
**Fix:** Wire composition local or remove toggle.

### ANN-052: Profiles row in Settings jumps away
**What:** Tapping Profiles leaves split-pane Settings for full-screen chooser; in-pane `ProfilesSettings` unused.  
**Where:** `RewriteSettingsScreen.kt`  
**Fix:** Inline summary or label “Opens full screen”.

### ANN-053: About mentions “Locked-down” internally
**What:** Reads like dev copy on a user About screen.  
**Where:** `RewritePlaybackSettings.kt` (`AboutSettings`)  
**Fix:** User-facing product description.

### ANN-054: Read-only value rows skip D-pad focus
**What:** Gaps when navigating settings with remote.  
**Where:** `RewriteSettingsRows.kt` (`RewriteValueRow`)  
**Fix:** Focusable read-only rows.

### ANN-055: Advanced settings composable orphaned
**What:** `AdvancedSettings()` exists but never shown.  
**Where:** `RewritePlaybackSettings.kt`, `RewriteSettingsScreen.kt`  
**Fix:** Link from About/Advanced or delete.

---

## Profiles & auth

### ANN-056: Edit profile = long-press only
**What:** Rename/remove hidden; no hint.  
**Where:** `RewriteProfilesScreen.kt`  
**Fix:** **Edit** on focus or “Hold to edit” hint.

### ANN-057: Profiles screen no on-screen Back
**What:** Hardware Back works; no visible affordance from Settings entry.  
**Where:** `RewriteProfilesScreen.kt`  
**Fix:** Back row in header.

### ANN-058: Profile pick always returns Home
**What:** From Settings → Profiles, selecting profile should maybe return to Settings.  
**Where:** `RewriteAppShell.kt` (`onSelect` → `openRoot(Home)`)  
**Fix:** Remember entry route.

### ANN-059: Password field hidden on TV keyboard
**What:** Hard to verify typos on auth forms.  
**Where:** `RewriteAuthGate.kt`  
**Fix:** Optional show-password for TV.

---

## Cloud Manager

### ANN-060: Focus lands on container, not fields
**What:** Whole card `focusable()` — D-pad hits dead panel instead of Connect/key field.  
**Where:** `RewriteCloudManagerScreen.kt`  
**Fix:** Per-control focus chain.

### ANN-061: Service label from enum formatting
**What:** `service.name` formatting instead of shared `DebridService.label`.  
**Where:** `RewriteCloudManagerScreen.kt`  
**Fix:** Reuse settings label map.

---

## Cross-cutting (Nuvio polish gap)

Things Nuvio does nicer at **interaction** level — reference `origin/main` `com/nuvio/tv/ui/screens/*` as spec, reimplement in rewrite:

| Area | Nuvio | SlugYard rewrite |
|------|-------|------------------|
| Search | History, suggestions, voice, discover, per-catalog rows, type suffixes | Text field + flat grid |
| Home | Layout modes, poster size prefs, trailer on focus, see-all | Single layout, fixed poster size |
| Focus | `dpadRepeatThrottle`, saved focus restore after Details | Partial; several gaps above |
| Settings | Enum labels, scroll indicators, dedicated screens | Split-pane; some raw enums |
| Details | Rich meta, cast, related, library tabs | Compact details |

**Reverse-engineering:** Use fork as **UX reference**, not copy-paste (`com.nuvio.tv` conflicts with clean-room + locked addon policy). GPLv3 allows study; SlugYard should reimplement behavior under `ui/rewrite`.

---

## Suggested polish batches (low risk)

1. **Copy & labels** — ANN-017–018, 023–024, 030, 044, 049–050, 053  
2. **Search feel** — ANN-001–003, 006–007 (your JJK / thin poster report)  
3. **TV focus** — ANN-003–005, 016, 028–029, 039, 054, 060  
4. **Truncation** — ANN-008–010, 026–027, 041–042  
5. **Nav honesty** — ANN-019–022, 036  

---

## Count

**61 annoyance-tier items** (ANN-001 … ANN-061)

For crashes, data loss, and broken journeys, see `BUG_SWEEP_FINDINGS.md`.
