<div align="center">

  <img src="app/src/main/res/drawable-nodpi/slugyard_logo.png" alt="SlugYard" width="280" />

  [![License][license-shield]][license-url]

  **Android TV media client** for the Stremio addon ecosystem.  
  Dual-engine playback · Debrid · remote-first Compose UI

</div>

**0.1.0-beta** — public pre-release. See [SECURITY](SECURITY.md) and [CONTRIBUTING](CONTRIBUTING.md).

**Legal:** [Terms](TERMS.md) · [Privacy](PRIVACY.md) · [License (GPLv3)](LICENSE)

## Install

- **APK:** [GitHub Releases](https://github.com/tnguyen0362/SlugYard/releases) when published  
- **From source:**

```bash
cp local.example.properties local.properties
# set sdk.dir + any optional keys
./gradlew :app:assembleFullDebug
```

Debrid credentials (Torbox, Premiumize, Real-Debrid) are entered in **Settings** — never committed or required at build time.

## Config

`local.properties` is gitignored. Copy [local.example.properties](local.example.properties).

| Keys | Purpose |
|------|---------|
| `TMDB_*` / `TRAKT_*` / `OPENSUBTITLES_*` | Optional metadata / scrobble |
| `SLUGYARD_SUPABASE_*` | Optional account sync ([SUPABASE_SETUP.md](SUPABASE_SETUP.md)) |
| `AIOSTREAMS_*` / `MEDIAFUSION_*` | Optional stream hosts |
| `SLUGYARD_RELEASE_*` | Release signing (CI / local release APKs) |

Blank keys disable the feature. Official release builds inject keys via CI secrets; public clones stay usable without them.

## Stack

Kotlin · Jetpack Compose (TV) · Media3 / ExoPlayer · libmpv · Hilt  

UI lives under `app/.../ui/app`.

## Credits

Playback builds on open-source work we gratefully acknowledge:

- **[ExoPlayer / AndroidX Media3](https://github.com/androidx/media)** (Apache-2.0) — Exo engine and FFmpeg decoder base
- **[mpv](https://mpv.io/) / [libmpv-android](https://github.com/jarnedemeulemeester/libmpv-android)** — MPV engine path
- **[Kodi](https://github.com/xbmc/xbmc)** — FFmpeg audio downmix behavior adapted in `ffmpeg-decoder-downmix/`

[license-shield]: https://img.shields.io/github/license/tnguyen0362/SlugYard.svg?style=for-the-badge
[license-url]: http://www.gnu.org/licenses/gpl-3.0.en.html
