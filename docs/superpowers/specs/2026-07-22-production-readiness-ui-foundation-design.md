# Production Readiness And UI Foundation Design

## Goal

Make the PlayFlix/SlugYard repository a safe production checkpoint for the next
round of Android TV frontend work. The cleanup must preserve the existing fork
migration, account/sync behavior, Settings behavior, and dual-player fallback
while removing stale product identity, disposable local artifacts, and avoidable
frontend coupling.

## Product Boundary

The product-facing identity is SlugYard/PlayFlix. App UI, user-visible copy,
active documentation, release scripts, and assets must not present Nuvio as the
current product. Legal attribution, upstream license notices, test fixtures that
intentionally exercise third-party data, and persisted/network compatibility
identifiers are retained where removing them would create legal, data-loss, or
protocol risk. Each retained exception must be isolated and documented.

## Repository Hygiene

- Remove generated screenshots, emulator XML dumps, temporary APKs, exported
  issue/PR research, and obsolete analysis files from the repository.
- Preserve product documents such as `PRODUCT.md`, `BEHAVIOR_SPEC.md`,
  `PUREFIRE_ADDON_CONFIG.md`, `SUPABASE_SETUP.md`, and the Supabase schema when
  they describe current PlayFlix behavior.
- Never stage or commit `sluggyard.jks`, local properties, API credentials,
  Supabase environment files, or other machine-local secrets.
- Expand `.gitignore` for keystores, APKs, emulator captures, temporary files,
  local reports, and exported research artifacts.
- Keep generated build output ignored and do not add a broad ignore rule that
  hides source, tests, design docs, or production configuration.

## Identity Cleanup

- Remove stale unused Nuvio logo assets and app-facing Nuvio product copy.
- Update README and release/support copy to describe PlayFlix/SlugYard directly.
- Keep GPL and upstream attribution in `NOTICE`, `LICENSE`, or a dedicated
  attribution section when required.
- Keep compatibility names such as legacy DataStore keys and server JSON fields
  only when they are required to read existing user data or satisfy a deployed
  protocol. Add concise compatibility comments instead of exposing those names
  in UI copy.
- Keep third-party fixture values in tests when they are needed to validate
  parsing behavior; do not rewrite test semantics solely to remove a fixture
  string.

## Frontend Foundation

- Preserve the existing SlugYard TV palette, typography, focus behavior, and
  remote-first interaction rules.
- Split the rewrite Settings implementation into focused category files behind
  `RewriteSettingsFacade` so future UI changes can be localized:
  - Account and Profiles
  - Layout
  - Content and Discovery
  - Integrations
  - Display, Subtitles, and Audio
  - Advanced and About
- Keep persistence in existing DataStore/repository boundaries. UI composables
  must not duplicate preference keys or access player implementation internals.
- Keep protected player paths unchanged:
  - `app/src/main/java/com/sluggyard/tv/ui/rewrite/player`
  - `app/src/main/java/com/sluggyard/tv/ui/screens/player`
  - Media3 and MPV sources
- Add focused tests for category policy, navigation, facade delegation, and
  secret masking.

## Verification Gate

Before the checkpoint is considered production-ready:

- `:app:compileFullDebugKotlin --no-daemon` passes.
- Focused Settings tests pass.
- Benchmark APK assembles successfully.
- A stale-branding scan reports only approved legal, compatibility, and fixture
  exceptions.
- No keystore, environment file, token, or API key is staged.
- `git diff --check` passes.
- Protected player paths have no changes from this readiness work.
- The designer anti-pattern gate passes for the rewrite Settings surface.

## Commit Boundary

Create reviewable checkpoint commits instead of one opaque cleanup commit:

1. Repository hygiene and generated-artifact cleanup.
2. Product identity and attribution cleanup.
3. Frontend Settings foundation and focused tests.

The private keystore, local environment files, unrelated machine artifacts, and
unclassified user work remain outside those commits.
