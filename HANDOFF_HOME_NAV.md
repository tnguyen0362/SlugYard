# SlugYard Handoff — Home / Top Nav / Shell Navigation

**Status as of 2026-07-22.** This doc covers everything `CLAUDE_UI_HANDOFF.md` doesn't:
top nav bar (search + profile), Home catalog D-pad scrolling, the Continue Watching
routing mystery, and a Settings visual report that hasn't been root-caused yet.
`CLAUDE_UI_HANDOFF.md` is still the locked authority for Settings information
architecture — this file doesn't override it.

**Constraint at time of writing**: no device testing was done for this pass (user hit
usage limits mid-session, "stop the shells"). Everything below is either (a) confirmed
by static code reading with exact line numbers, or (b) explicitly flagged as an
unverified hypothesis. Don't promote (b) items to "fixed" without an actual
adb install/launch/screencap verification per the project's standing rule.

## 1. Search icon does nothing — CONFIRMED root cause

`RewriteAppShell.kt` lines 605-610 (`RewriteRootNavigation`):

```kotlin
Icon(
    imageVector = Icons.Filled.Search,
    contentDescription = "Search",
    tint = SlugYardPalette.OnCanvas,
    modifier = Modifier.size(22.dp),
)
```

Plain `Icon`, no `clickable`, no `MutableInteractionSource`, no focus handling, no
destination. It can't take D-pad focus and does nothing on click. There is also no
search screen/destination anywhere in the codebase — `RewriteRootDestination` has no
`Search` case.

**To fix**: needs (1) a real search destination/screen (frontend query UI +
whatever backend catalog search endpoint already exists — check
`RewriteHomeDataSource.kt`/repository layer for an existing search query method before
building a new one), (2) wire the icon the same way `RewriteRootNavigationItem` wires
its rows (`MutableInteractionSource`, `collectIsFocusedAsState`, `clickable`, focus
scale/tint feedback), (3) add `RewriteRootDestination.Search` and a case in the
`when (destination)` block (~line 358 onward).

This is the largest single gap — full search is "not started," not a small bug fix.

## 2. Profile avatar does nothing, shows only an initial — CONFIRMED root cause

`RewriteAppShell.kt` lines 591-604, the `Box` rendering `profileInitial`:

```kotlin
Box(
    modifier = Modifier
        .size(34.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(SlugYardPalette.Accent),
    contentAlignment = Alignment.Center,
) {
    Text(
        text = profileInitial,
        color = Color(0xFF181818),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
}
```

Same problem as the search icon: no `clickable`, no interaction source, no `onClick`.
`profileInitial` (computed ~line 556-557) defaults to `"S"` whenever there's no active
profile name match, which is why the user sees a static "V" (or whatever letter) that
never responds to input — it's decorative only.

**To fix**: wrap in the same focusable/clickable pattern as `RewriteRootNavigationItem`,
and decide the destination — most consistent option is navigating to
`RewriteRootDestination.Profiles` (already exists, already wired as a nav tab), unless
the intent is a dedicated dropdown/menu (sign out, switch profile, etc.) — that would be
new UI, not just a click handler.

## 3. Catalog vertical scroll gets "stuck" near the bottom row — HYPOTHESIS, NOT CONFIRMED

User's words: "when you are navigating up and down the catalogs it gets cut off at
the end, like when you go down the camera doesnt go down with it you are still on the
last row."

Suspected cause: `RewriteHomeScreen.kt` lines ~105-115, the custom `BringIntoViewSpec`:

```kotlin
private val RewriteHomeBringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailingEdge = offset + size
        return when {
            trailingEdge <= 0f -> offset
            offset >= containerSize -> trailingEdge - containerSize
            else -> 0f
        }
    }
}
```

This was added deliberately (see the inline comment above it in the file) to stop the
hero's Play/More-info focus changes from re-triggering a scroll. But its logic only
scrolls when a focused item is **fully** outside the viewport (`trailingEdge <= 0f` or
`offset >= containerSize`). Anything only **partially** clipped — e.g. a row near the
bottom of the screen that's half cut off by the screen edge — falls into the `else -> 0f`
branch and never gets scrolled into full view. That matches the reported symptom: you
reach the last (or second-to-last) row, it's partially visible, and pressing DOWN
further does nothing because Compose thinks it's "already in view."

**Not yet verified.** Before changing this, confirm on-device (dump the actual
container/item bounds, or just watch it happen) that this is really the offending
code path, since the same spec is also responsible for suppressing an intentional
behavior (hero button re-scroll) — a fix needs to distinguish "genuinely off-screen
last row" from "hero-button-focus-change inside an already-fully-visible hero," not
just relax the threshold globally. One approach: only apply the custom spec to the
hero's internal focus targets (Play/More info buttons) via a scoped
`CompositionLocalProvider`, and let the rest of the `LazyColumn` (the poster rows) use
Compose's default `BringIntoViewSpec`, which always fully reveals the focused item.

## 4. Settings "looking better" but something's cut off — NOT YET INVESTIGATED

User confirmed the flattened hairline-divider redesign is working ("settings is
looking better") but reported something is "cut off for some reason" within Settings,
without saying exactly where.

Checked this pass and ruled out as the cause:
- `RewriteSettingsDetailScaffold.kt` — top-level `LazyColumn` with a header `item`
  (title + description + Back button) and a `content()` item; no nested scrollable,
  no fixed-height clipping box.
- `RewriteAppShell.kt` line 356 — Settings destination correctly gets
  `.padding(top = SlugYardTvMetrics.RootNavBarHeight)` (only Home's hero skips this, by
  design, since the hero bleeds under a scrim). So the nav bar shouldn't be overlapping
  Settings content.
- `RewriteSettingsRows.kt` (`RewriteSettingsGroup`, `RewriteSettingsRowShell`) — plain
  `Column`s, no fixed heights that would clip row content.

Still unknown — needs an actual screenshot walk of every Settings category (Account,
Profiles, Layout, Content & Discovery, Integrations, Display, Subtitles, Audio,
Advanced, About) to find which one is visually cut off and where (title? description
text with a `maxLines`? a row's trailing value text overflowing at the right edge with
no wrap/ellipsis?). Next step once builds resume: adb screenshot each category screen
and compare against `CLAUDE_UI_HANDOFF.md`'s locked information architecture.

## 5. Continue Watching card routes to Details instead of Streams — UNRESOLVED MYSTERY

Static code reading of `onPosterSelected` (`RewriteAppShell.kt` lines 387-406) and the
Continue Watching row construction in `toScreenState` (lines ~674-690) both say a
checkpoint-derived poster should always have `addonId == null`, which should route
clicks to the `Streams` branch. On-device testing (confirmed via `uiautomator dump`
ground truth, not just screenshots — the dump showed `focused="true"` on the exact
"House of the Dragon" Continue Watching card) showed pressing CENTER instead opened
Details.

A temporary diagnostic log is **already sitting in the source, uncommitted**:

```kotlin
onPosterSelected = { poster ->
    android.util.Log.d("SlugYardDebug", "onPosterSelected id=${poster.id} addonId=${poster.addonId} imageUrl=${poster.imageUrl}")
    ...
```

(`RewriteAppShell.kt` line 388.) The rebuild needed to exercise this log and read
logcat was cancelled mid-flight when the user hit usage limits — this log has never
actually printed. **Next step, first thing once builds resume**: rebuild
`assembleFullBenchmark`, install, reproduce (Home → focus Continue Watching card →
CENTER), `adb logcat -s SlugYardDebug`, read the actual `addonId`/`imageUrl` values,
then remove the log once the mystery is resolved.

Related, lower priority, same bug class: the Continue Watching card's poster art was
also observed blank/gray (`cw_fixed1.png`) even after a previous fix threaded
`poster.imageUrl` into `RewriteRootDestination.Streams.posterUrl` — believed to be a
stale/null `posterUrl` on the *already-persisted* checkpoint (the fix only prevents
future loss, doesn't repair existing saved state). Confirming this requires a fresh
checkpoint save, which is blocked on resolving item 5 above first (since a real
playback session needs to actually launch and run to save a new checkpoint).

## 6. Carried-forward, lower-priority items (not started)

- Home hero's "Play" button for non-series content passes no `posterUrl` into
  `Streams` (only `backdropUrl` exists on `RewriteHero`) — same bug class as item 5,
  needs a `RewriteHero` data-model addition if fixed.
- A second blank/gray poster card was seen in the "Popular" row in an earlier pass
  (`focustest2.png`) — not yet triaged, may be the same stale-checkpoint-URL class or
  something else entirely.
- Player transport controls row — not yet screenshotted/verified on this rebrand pass.
- Full Profile section redesign/build-out — not started (currently just three
  `RewriteValueRow`s, see `ProfilesSettings` in `RewriteAccountSettings.kt` lines
  29-36).
- "Featured, larger first card" row visual pattern (from the reference Netflix-style
  image) — not started, would live in `RewriteHomeScreen.kt`'s row rendering.
- Protected player-engine files (`core/player/`, `PlayerRuntimeController*`,
  `AssMatroskaExtractor`, `MpvPlayerSurfaceView`, etc.) — still off-limits per
  `CLAUDE_UI_HANDOFF.md`; the "no sources found for movie" player bug is deferred for
  this reason, not forgotten.

## Verification reminder

Nothing in sections 1-6 has been screenshot-verified on-device this pass (compute was
paused mid-session). Before marking any of these "fixed" in a future pass: rebuild
(`assembleFullBenchmark`), `adb install -r`, launch, and actually screencap/pull the
relevant screen — per the project's standing rule, static code review alone doesn't
count as done.
