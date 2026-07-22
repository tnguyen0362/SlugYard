# Production UI Readiness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert the mixed PlayFlix/SlugYard fork worktree into a reviewable production checkpoint with no stale product-facing Nuvio identity and a maintainable frontend Settings boundary.

**Architecture:** Preserve the current SlugYard package tree, DataStore boundaries, auth flow, and protected player implementation. Clean repository identity and automation separately from the Settings UI split. Add one deterministic repository-readiness checker that understands legal, fixture, and compatibility exceptions instead of using an unsafe global text replacement.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Hilt, Android Gradle Plugin, Python 3 standard library, GitHub Actions, existing SlugYard TV design system.

## Global Constraints

- Product-facing identity is PlayFlix/SlugYard.
- Legal attribution, upstream license notices, test fixtures, persisted keys, and deployed JSON compatibility fields remain only where required.
- Never stage or commit `sluggyard.jks`, local properties, API credentials, Supabase environment files, or machine-local artifacts.
- Never modify `app/src/main/java/com/sluggyard/tv/ui/rewrite/player`.
- Never modify `app/src/main/java/com/sluggyard/tv/ui/screens/player`.
- Never modify Media3 or MPV implementation sources as part of this readiness pass.
- Preserve remote-first focus, visible TV-scale targets, existing palette, typography, and Back behavior.
- Use the existing DataStore and repository boundaries; do not duplicate preference keys.
- Verify with `:app:compileFullDebugKotlin --no-daemon` and focused Settings tests.

---

### Task 1: Establish Repository Hygiene And Secret Boundaries

**Files:**
- Modify: `.gitignore`
- Create: `scripts/check_repository_readiness.py`
- Delete: `nuviotv-pr-analysis.md`
- Delete: `open_issues.csv`
- Delete: `open_issues_raw.txt`
- Delete: `open_prs.csv`
- Delete: `open_prs_page2.csv`
- Delete: `open_prs_raw.txt`
- Preserve untracked: `sluggyard.jks`, `local.example.properties`, Supabase credentials/config files until separately reviewed

**Interfaces:**
- Produces `python scripts/check_repository_readiness.py` as a dependency-free readiness gate.
- The checker accepts `--staged` and exits `0` when the staged tree has no forbidden product references or secret paths; it exits `1` with one finding per violation.

- [ ] **Step 1: Add failing checker tests as executable fixtures**

Create `scripts/test_check_repository_readiness.py` using `unittest` and temporary directories. The tests must cover:

```python
class ReadinessCheckerTest(unittest.TestCase):
    def test_product_copy_rejects_nuvio(self):
        self.assertEqual(check_text("Welcome to NuvioTV"), ["product-facing Nuvio reference"])

    def test_notice_is_an_allowed_attribution_file(self):
        self.assertEqual(check_file(Path("NOTICE"), "Original project: NuvioTV"), [])

    def test_legacy_storage_key_is_an_allowed_compatibility_file(self):
        self.assertEqual(check_file(Path("PlayerSettingsDataStore.kt"), '"nuvio_performance_mode_enabled"'), [])

    def test_keystore_path_is_rejected(self):
        self.assertIn("secret path", check_path(Path("sluggyard.jks")))
```

Use the real checker functions `check_text`, `check_file`, and `check_path`; the initial run must fail because the checker does not exist.

- [ ] **Step 2: Implement the checker with explicit exception lists**

Implement `scripts/check_repository_readiness.py` with these constants and rules:

```python
PRODUCT_TEXT_SUFFIXES = {".kt", ".kts", ".java", ".xml", ".yml", ".yaml", ".md", ".py", ".json"}
ALLOWED_TEXT_FILES = {"NOTICE", "LICENSE"}
ALLOWED_COMPATIBILITY_FILES = {
    "app/src/main/java/com/sluggyard/tv/data/local/PlayerSettingsDataStore.kt",
    "app/src/main/java/com/sluggyard/tv/data/remote/dto/PlaybackIssueReportDto.kt",
}
SECRET_SUFFIXES = {".jks", ".keystore", ".p12", ".pfx", ".pem"}
SECRET_NAMES = {"local.properties", "local.dev.properties", ".env", ".supabase_db.env"}
IGNORED_PARTS = {".git", "build", ".gradle", ".kotlin", ".tmp"}
```

The checker must scan only files in the repository root after excluding `IGNORED_PARTS`, permit Nuvio text only in `ALLOWED_TEXT_FILES`, `ALLOWED_COMPATIBILITY_FILES`, and existing Trakt parser fixture files, and report forbidden paths without reading secret contents. For `--staged`, use `git diff --cached --name-only --diff-filter=ACMR` and fail if any staged path matches a secret suffix/name.

- [ ] **Step 3: Expand `.gitignore` without hiding source**

Append these rules to `.gitignore`:

```gitignore
# Local production credentials and signing material
*.jks
*.keystore
*.apk
*.aab

# Emulator captures and local readiness output
.tmp/
.tmp-*
captures/
*.uiautomator.xml
*.xml.dump

# Local exports and generated research output
open_issues*.csv
open_prs*.csv
*_raw.txt
```

Do not ignore `PRODUCT.md`, `BEHAVIOR_SPEC.md`, `SUPABASE_SETUP.md`, source files, tests, or `docs/superpowers`.

- [ ] **Step 4: Remove only confirmed disposable research exports**

Delete the five issue/PR export files and `nuviotv-pr-analysis.md`. Leave product docs, Supabase schema, `test_supabase.py`, `skills-lock.json`, `tools/`, and all source migration files for the later identity review.

- [ ] **Step 5: Run the hygiene tests and checker**

Run:

```powershell
python -m unittest scripts/test_check_repository_readiness.py -v
python scripts/check_repository_readiness.py
```

Expected: all tests pass; the checker reports only the intentionally retained legal, compatibility, and fixture exceptions.

- [ ] **Step 6: Commit the hygiene checkpoint**

Stage only `.gitignore`, `scripts/check_repository_readiness.py`, its test, and the confirmed disposable-file deletions. Force-add ignored design files only when they are explicitly part of the commit. Commit with:

```powershell
git add .gitignore scripts/check_repository_readiness.py scripts/test_check_repository_readiness.py
git add -u -- nuviotv-pr-analysis.md open_issues.csv open_issues_raw.txt open_prs.csv open_prs_page2.csv open_prs_raw.txt
git commit -m "chore: harden repository hygiene"
```

### Task 2: Remove Stale Product Identity From Automation And Docs

**Files:**
- Modify: `README.md`
- Modify: `.github/PULL_REQUEST_TEMPLATE.md`
- Modify: `.github/workflows/pr-full-debug-build.yml`
- Modify: `.github/workflows/pr-full-debug-build-comment.yml`
- Modify: `.github/workflows/beta-release.yml`
- Modify: `.github/workflows/close-unlabeled-issues.yml`
- Modify: `.github/workflows/close-stale-issues.yml`
- Modify: `.github/workflows/stale-needs-info.yml`
- Modify: `.github/workflows/triage-needs-info.yml`
- Modify: `scripts/release_beta.py`
- Modify: `test_supabase.py`
- Delete: `assets/nuviotv.png`
- Delete: `app/src/main/res/drawable/nuvio_text.png`
- Modify: `NOTICE` only if needed to clarify the legally required upstream attribution

**Interfaces:**
- CI release secrets use `SLUGYARD_RELEASE_*` names.
- CI artifacts and bot markers use `slugyard-*` names.
- `test_supabase.py` reads `SLUGYARD_SUPABASE_URL` and `SLUGYARD_SUPABASE_ANON_KEY` only.
- `scripts/release_beta.py` no longer inserts a Nuvio product name into generated release text.

- [ ] **Step 1: Add focused release-script assertions**

Extend or create `scripts/test_release_beta.py` with standard-library `unittest` coverage for `WORD_REPLACEMENTS` and generated text:

```python
def test_release_copy_does_not_insert_legacy_product_name(self):
    rendered = apply_word_replacements("SlugYard UI update")
    self.assertNotIn("Nuvio", rendered)
```

Use the release script’s existing replacement helper; do not duplicate release logic in the test.

- [ ] **Step 2: Rename CI identity variables and markers**

Replace production-facing names in the workflows:

```yaml
SLUGYARD_RELEASE_STORE_FILE=$RUNNER_TEMP/ci-debug.jks
SLUGYARD_RELEASE_KEY_ALIAS=androiddebugkey
SLUGYARD_RELEASE_KEY_PASSWORD=android
SLUGYARD_RELEASE_STORE_PASSWORD=android
```

Use `SLUGYARD_RELEASE_KEYSTORE_BASE64`, `SLUGYARD_RELEASE_KEY_ALIAS`, `SLUGYARD_RELEASE_KEY_PASSWORD`, and `SLUGYARD_RELEASE_STORE_PASSWORD` for beta secrets. Rename artifact names and HTML comment markers to `slugyard-full-debug-build` and `slugyard-full-debug-pr-*` consistently between the build and comment workflows. Rename bot markers to `slugyard-bot:*` consistently within each workflow.

- [ ] **Step 3: Rewrite active README copy**

Make the README describe PlayFlix/SlugYard as the current product, its Android TV audience, offline-first behavior, optional sync, and current build commands. Remove comparative “Key Changes from NuvioTV” tables and upstream project links from the product narrative. Keep one concise pointer to `NOTICE` for required attribution rather than presenting the upstream brand as the product.

- [ ] **Step 4: Remove unused legacy assets and update Supabase helper names**

Delete only the two unused image assets confirmed by a zero-reference search. Update `test_supabase.py` to use the current `SLUGYARD_*` property names and fail with a clear message when required values are missing. Do not print credential values.

- [ ] **Step 5: Verify identity cleanup**

Run:

```powershell
python -m unittest scripts/test_release_beta.py scripts/test_check_repository_readiness.py -v
python scripts/check_repository_readiness.py
```

Expected: no product-facing Nuvio references outside the checker’s approved legal, compatibility, and fixture exceptions; CI secret names match `app/build.gradle.kts`.

- [ ] **Step 6: Commit the automation checkpoint**

```powershell
git add README.md NOTICE .github scripts/release_beta.py test_supabase.py assets app/src/main/res/drawable
git commit -m "chore: align release automation with SlugYard"
```

### Task 3: Split Settings Into Frontend-Focused Compose Units

**Files:**
- Modify: `app/src/main/java/com/sluggyard/tv/ui/rewrite/settings/RewriteSettingsScreen.kt`
- Create: `app/src/main/java/com/sluggyard/tv/ui/rewrite/settings/RewriteSettingsRows.kt`
- Create: `app/src/main/java/com/sluggyard/tv/ui/rewrite/settings/RewriteAccountSettings.kt`
- Create: `app/src/main/java/com/sluggyard/tv/ui/rewrite/settings/RewriteLayoutSettings.kt`
- Create: `app/src/main/java/com/sluggyard/tv/ui/rewrite/settings/RewriteContentDiscoverySettings.kt`
- Create: `app/src/main/java/com/sluggyard/tv/ui/rewrite/settings/RewriteIntegrationSettings.kt`
- Create: `app/src/main/java/com/sluggyard/tv/ui/rewrite/settings/RewritePlaybackSettings.kt`
- Preserve: `app/src/main/java/com/sluggyard/tv/ui/rewrite/settings/RewriteSettingsFacade.kt`
- Preserve: `app/src/main/java/com/sluggyard/tv/ui/rewrite/settings/RewriteSettingsModels.kt`
- Modify: `app/src/test/java/com/sluggyard/tv/ui/rewrite/settings/RewriteSettingsPolicyTest.kt`

**Interfaces:**
- `RewriteSettingsScreen` owns only destination state, facade flow collection, and category dispatch.
- `RewriteSettingsRows.kt` owns reusable focusable rows: `RewriteToggleRow`, `RewriteValueRow`, `RewriteActionRow`, `RewriteCycleRow`, and `RewriteSettingsGroup`.
- Category files consume `RewriteSettingsFacade`, `RewriteHomeSettings`, `RewriteProfileState`, and callback parameters already supplied by `RewriteAppShell`.
- `RewritePlaybackSettings.kt` exposes only the approved effective display, subtitle, and audio controls.

- [ ] **Step 1: Add pure composition-boundary tests**

Add tests asserting that the approved category policy remains unchanged and that the navigation reducer maps `Root -> Detail(Audio) -> Root` without touching player routes. Add a source-boundary test that fails if the Settings package imports `ui.rewrite.player`, `ui.screens.player`, Media3 player implementation packages, or MPV implementation packages.

- [ ] **Step 2: Extract shared row primitives without behavior changes**

Move the existing row/group composables verbatim into `RewriteSettingsRows.kt`. Keep all rows focusable, remote-sized, palette-backed, and clickable from the full row. Replace any direct `GlobalScope` use with a caller-provided `CoroutineScope` or `rememberCoroutineScope`.

- [ ] **Step 3: Extract account, layout, content, and integration categories**

Move each category body into its named file. Keep all mutations delegated to the facade or existing graph callbacks. Preserve Trakt device-code OAuth, encrypted Debrid connection flow, masked MDBList/OpenSubtitles credentials, and addon enablement behavior.

- [ ] **Step 4: Extract effective playback categories**

Move Display, Subtitles, Audio, Advanced, and About into `RewritePlaybackSettings.kt`. Keep the locked policy: no engine selector, manual Dolby Vision policy, libass controls, raw buffer controls, autoplay, or intro-skip controls. Do not import or modify player implementation classes.

- [ ] **Step 5: Reduce the screen orchestrator**

Leave `RewriteSettingsScreen.kt` with the public composable signature, destination state, collected flows, and a `when` dispatch to category composables. Keep `RewriteSettingsCategoryList.kt`, `RewriteSettingsDetailScaffold.kt`, `RewriteAppShell.kt`, Home, Profiles, and player route behavior stable.

- [ ] **Step 6: Run focused UI compilation and tests**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testFullDebugUnitTest --tests 'com.sluggyard.tv.ui.rewrite.settings.*' --no-daemon
.\gradlew.bat :app:compileFullDebugKotlin --no-daemon
```

Expected: focused tests and Kotlin compilation pass with no changed files under the protected player paths.

- [ ] **Step 7: Commit the frontend foundation checkpoint**

```powershell
git add app/src/main/java/com/sluggyard/tv/ui/rewrite/settings app/src/test/java/com/sluggyard/tv/ui/rewrite/settings
git commit -m "refactor: prepare settings surface for frontend work"
```

### Task 4: Add Production Readiness Verification And Final Checkpoint

**Files:**
- Modify: `.github/workflows/pr-full-debug-build.yml`
- Modify: `.github/workflows/pr-full-debug-build-comment.yml`
- Modify: `.github/workflows/beta-release.yml`
- Modify: `scripts/check_repository_readiness.py`
- Modify: `AGENTS.md` only if the verification commands or protected paths change

**Interfaces:**
- Pull requests run the deterministic repository checker before the Android build.
- Local verification uses the same checker and Gradle commands as CI.
- The final checkpoint excludes private secrets and unclassified artifacts.

- [ ] **Step 1: Add the readiness checker to PR validation**

Add this step before the Gradle build in `pr-full-debug-build.yml`:

```yaml
- name: Check repository readiness
  run: python scripts/check_repository_readiness.py
```

Do not print environment values or decode release secrets before the checker runs.

- [ ] **Step 2: Verify formatting, scope, and secrets**

Run:

```powershell
git diff --check
python scripts/check_repository_readiness.py
git diff --name-only -- app/src/main/java/com/sluggyard/tv/ui/rewrite/player app/src/main/java/com/sluggyard/tv/ui/screens/player
git diff --cached --name-only | Select-String -Pattern '\.(jks|keystore|pem|p12|pfx)$|(^|/)(local\.properties|local\.dev\.properties|\.env)$'
```

Expected: `git diff --check` is silent, the readiness checker passes, the protected-player diff is empty for this work, and the staged secret-path query returns no matches.

- [ ] **Step 3: Build and test the production-facing variants**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testFullDebugUnitTest --tests 'com.sluggyard.tv.ui.rewrite.settings.*' --no-daemon
.\gradlew.bat :app:compileFullDebugKotlin --no-daemon
.\gradlew.bat :app:assembleFullBenchmark --no-daemon
```

Expected: all commands pass. Existing unrelated full-lint or native allocator failures must be reported rather than hidden or fixed inside player code.

- [ ] **Step 4: Run the designer gate on the final Settings surface**

Run the deterministic designer review against `app/src/main/java/com/sluggyard/tv/ui/rewrite/settings`. Expected result: pass with no blocking findings.

- [ ] **Step 5: Review the staged file list and commit the readiness checkpoint**

Review:

```powershell
git status --short
git add .github/workflows/pr-full-debug-build.yml .github/workflows/pr-full-debug-build-comment.yml .github/workflows/beta-release.yml scripts/check_repository_readiness.py
git diff --cached --stat
git diff --cached --name-only
git commit -m "chore: prepare repository for production UI work"
```

Confirm that `sluggyard.jks`, local properties, Supabase environment files, and unclassified user files are absent. Commit only the reviewed hygiene, identity, frontend, and verification changes with:

## Plan Self-Review

- Spec coverage: repository hygiene is Task 1; identity and legal exceptions are
  Task 2; frontend boundaries and protected player paths are Task 3; the final
  verification gate and CI integration are Task 4.
- No task requires a global text replacement or a destructive reset.
- The checker’s exception lists are explicit, so compatibility identifiers and
  legally required attribution cannot silently spread into product copy.
- The Settings facade and category file responsibilities are consistent across
  Tasks 3 and 4.
- No secrets are read or printed by the planned readiness checks.
