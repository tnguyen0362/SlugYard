# SlugYard UI Handoff

## Read This First

You are designing and refining an Android TV application for viewers sitting at
living-room distance and navigating with a directional remote. This is a Kotlin
and Jetpack Compose product surface, not a web dashboard. Preserve the existing
SlugYard TV visual language and make every decision work with D-pad focus,
predictable Back behavior, and no touch or mouse dependency.

The repository is at a clean local checkpoint on `dev`. The current product
identity is PlayFlix/SlugYard. Do not reintroduce upstream branding, old product
copy, old logo assets, or web-oriented settings patterns.

## Product Context

SlugYard is an Android TV media application with:

- Offline-first local browsing and playback.
- Optional profiles and local library state.
- Addon-backed catalogs and source resolution.
- Optional Supabase account sync.
- Trakt, TMDB, MDBList, Debrid, and OpenSubtitles integrations.
- Dual-player fallback behavior already implemented and protected.

The app must remain useful without an account or network connection. Authentication
is optional and exists to support sync; it is not a conversion funnel.

## Current Visual Direction

### Register

Product UI. Familiarity is valuable, but the surface should feel deliberate and
TV-native rather than like a desktop settings dashboard.

### Physical Scene

A viewer sits roughly ten feet away in a dim living room using a directional
remote. Use a deep neutral canvas, restrained warm-white text, quiet surface
elevation, and a calm amber focus state that remains obvious at distance.

### Composition

Use two established layout families:

1. A focusable category index with full-width rows and concise descriptions.
2. A detail page with grouped settings rows, inline current values, and an
   explicit Back action.

The hierarchy should come from spacing, surface contrast, text scale, and focus
state. Avoid a desktop sidebar, dense form grid, modal-first navigation, or
decorative hero treatment.

### Type And Spacing

- Preserve the existing SlugYard TV typography and palette tokens.
- Keep a clear display/body contrast with at least a 1.25 scale relationship.
- Use readable TV-scale body text and concise labels.
- Keep spacing on the existing 4/8/12/16/24/32 rhythm.
- Use sentence case for visible copy.
- Avoid adding a web font dependency for a settings polish pass.

### Explicit Anti-Slop Bans

- No purple/blue AI gradients, neon glows, or gradient text.
- No generic SaaS dashboard cards or desktop sidebar.
- No icon tile stacked above every heading.
- No decorative fake metrics or fake screenshots.
- No hidden focus state or hover-only interaction.
- No oversized rounded cards, nested cards, or repeated card grids.
- No em dashes in visible copy.

## Current Settings Architecture

The locked Settings surface is already split into frontend-focused Compose files:

- `app/src/main/java/com/sluggyard/tv/ui/rewrite/settings/RewriteSettingsScreen.kt`
  - Owns destination state, facade flow collection, and category dispatch only.
- `RewriteSettingsRows.kt`
  - Shared TV rows: toggle, value, action, cycle, and grouped section.
- `RewriteAccountSettings.kt`
  - Account, Profiles summary, and Layout categories.
- `RewriteContentDiscoverySettings.kt`
  - TMDB enrichment and installed addon controls.
- `RewriteIntegrationSettings.kt`
  - Trakt device auth, TMDB/MDBList, Debrid, and OpenSubtitles.
- `RewritePlaybackSettings.kt`
  - Display, Subtitles, Audio, Advanced, and About.
- `RewriteSettingsCategoryList.kt`
  - Root category index.
- `RewriteSettingsDetailScaffold.kt`
  - Detail header and Back behavior.
- `RewriteSettingsFacade.kt`
  - Boundary to existing stores and services.
- `RewriteSettingsModels.kt`
  - Category policy and destination reducer.

The Settings facade is the persistence boundary. Do not add preference keys in
composables and do not reach into player implementation classes from Settings.

## Locked Settings Information Architecture

The root category order is fixed:

1. Account
2. Profiles
3. Layout
4. Content & Discovery
5. Integrations
6. Display
7. Subtitles
8. Audio
9. Advanced
10. About

There is no Essential/Advanced experience-mode switch. `Advanced` is a category,
not a mode selector. There is no Network Speed category and no player More menu.

### Account

- Signed-out, signed-in, and sync status.
- Sign out without deleting local data.
- Never show access tokens, refresh tokens, API keys, or secret values.
- Keep guest mode clear and non-judgmental.

### Profiles

- Active profile identity.
- Existing profile creation, rename, remove, and selection flows remain on the
  existing Profiles route.
- Remember Last Profile uses the existing profile store.
- Preserve the minimum-one-profile rule.

### Layout

- Effective modern layout only.
- Poster labels and metadata labels.
- Focus movement and horizontal navigation behavior.
- Hide unreleased content.
- Home/catalog presentation controls only where the rewrite surface honors them.
- Do not expose ignored Classic/Grid or legacy layout modes.

### Content & Discovery

- TMDB enrichment and Continue Watching enrichment.
- Installed addon enabled state.
- Managed PlayFlix source status.
- Catalog ordering and source explanations.
- Preserve primary-addon sharing rules for secondary profiles.

### Integrations

Trakt uses device-code OAuth. The user enters a verification code in a browser;
there is no user-entered Trakt client secret or API key.

- Trakt connection state, username, verification URL, code, polling, disconnect.
- Continue Watching source: Trakt or SlugYard Sync.
- Library source: Trakt or Local.
- More Like This source: Trakt or TMDB.
- TMDB enrichment and metadata controls.
- MDBList enablement, masked API key, and rating-source controls.
- Debrid provider connection and active-provider state.
- OpenSubtitles enablement, masked API key, and automatic download.

Credential behavior:

- Save immediately through existing encrypted/local boundaries.
- Show `Saved securely`, `Connected`, or `Not configured`, never the secret.
- Provide readable loading, expired, retry, and failure states.
- Never log or echo credentials.

### Display

Only expose effective controls:

- Frame-rate matching.
- Resolution matching.
- Resize/aspect mode.

Do not expose engine selection or manual display policy overrides.

### Subtitles

- Preferred and secondary subtitle language.
- Forced subtitle preference.
- Preferred-language filtering.
- Size, position, bold, and supported outline presentation.

Do not expose raw renderer internals or add a new in-player subtitle download
action.

### Audio

- Preferred and secondary audio language.
- Downmix.
- Output channels.
- Preserve original mix.
- Tunneling.
- Optical passthrough.

Do not expose audio amplification, per-device delay, engine selection, or raw
renderer tuning.

### Advanced

Read-only or policy-safe status:

- Engine: Automatic.
- Buffering: Managed automatically or Standard.
- Dolby Vision: Automatic.
- Cache status.
- Diagnostics status.
- Performance status.

This category explains behavior; it must not become a hidden raw player control
panel.

### About

- PlayFlix TV identity.
- Locked-down SlugYard TV settings model.
- Automatic dual-player fallback explanation.
- Local credential/privacy explanation.

## Interaction Contract

- Every interactive row is focusable with the remote.
- Focus must be visually obvious through scale and/or surface contrast.
- The entire row should be the hit target where appropriate.
- Initial Settings focus lands on Account.
- Select opens a category detail page.
- Back from detail returns to the category index.
- Back from the category index returns to the existing rewrite root navigation.
- Toggle and selection changes persist immediately.
- Dialogs support confirm and cancel with remote navigation.
- Long values must remain readable without overflowing the viewport.
- Empty, loading, error, connected, disconnected, and saved states need explicit
  copy, not color-only communication.

## Protected Boundaries

Do not modify these for a Settings or frontend design pass:

- `app/src/main/java/com/sluggyard/tv/ui/rewrite/player`
- `app/src/main/java/com/sluggyard/tv/ui/screens/player`
- Media3 fork implementation modules.
- MPV/libmpv implementation and JNI.
- Existing dual-player fallback behavior.
- Existing DataStore key names used for persisted compatibility.

Do not add a second design system. Reuse:

- `com.sluggyard.tv.ui.design.SlugYardPalette`
- `com.sluggyard.tv.ui.design.SlugYardTvMetrics`
- Existing Material 3 typography and focus patterns.

## Recommended Next Design Pass

Work in this order:

1. Audit the Settings root/index at 720p and 1080p TV densities.
2. Improve category descriptions and current-value summaries without increasing
   row density.
3. Add explicit loading/error/expired states for Trakt authorization.
4. Make Account sign-out state update immediately after the action.
5. Expand Profiles detail behavior while reusing the existing Profiles route
   operations.
6. Complete Layout controls only for values honored by the current rewrite Home.
7. Improve credential dialogs with clear save/cancel/failure states.
8. Validate long addon URLs, usernames, provider names, and failure messages.
9. Capture emulator screenshots only as ignored local artifacts.

## Verification Commands

From the repository root:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
python scripts/check_repository_readiness.py
python -m unittest scripts/test_check_repository_readiness.py scripts/test_release_beta.py -v
.\gradlew.bat :app:testFullDebugUnitTest --tests 'com.sluggyard.tv.ui.rewrite.settings.*' --no-daemon
.\gradlew.bat :app:compileFullDebugKotlin --no-daemon
.\gradlew.bat :app:assembleFullBenchmark --no-daemon
```

The deterministic designer gate should be run against:

```text
app/src/main/java/com/sluggyard/tv/ui/rewrite/settings
```

Current baseline: focused Settings tests pass, Kotlin compilation passes, the
benchmark APK assembles, repository readiness passes, and the Settings designer
gate passes with no blocking findings.

## Definition Of Done For UI Changes

- The design works at TV viewing distance with D-pad only.
- Focus, Back, loading, empty, error, and credential states are explicit.
- No protected player paths changed.
- No new preference keys were created in UI code.
- No stale upstream product identity appears in visible copy.
- No secrets are shown or logged.
- Existing focused tests and compile command pass.
- The designer gate reports at least 85/100 with zero blocking findings.
