<div align="center">

  <img src="app/src/main/res/drawable-nodpi/slugyard_logo.png" alt="SlugYard" width="300" />
  <br />
  <br />

  [![License][license-shield]][license-url]

  <p>
    A modern Android TV media player powered by the Stremio addon ecosystem.
    <br />
    Dual-engine playback (ExoPlayer + libmpv) · Debrid integration · Anime-optimized subtitles
  </p>

</div>

## Status

**0.1.0-beta** — public pre-release. Expect rough edges. Please file bugs with
reproduction steps; see [CONTRIBUTING.md](CONTRIBUTING.md) and [SECURITY.md](SECURITY.md).

## About

SlugYard is an independently maintained Android TV media experience: connect a
supported debrid account and press play. It is designed for remote-first browsing,
local playback, optional profiles, and opt-in account sync.

The project is released under GPLv3. Required upstream attribution and third-party
notices are maintained in [NOTICE](NOTICE) and [LICENSE](LICENSE). Privacy behavior
is summarized in [PRIVACY.md](PRIVACY.md).

## Release notes

### 0.1.0-beta

- SlugYard rewrite UI for Android TV (home catalogs, details, search, settings)
- Catalog-first home with focused rows, profile switching, and Continue Watching
- Automatic stream scoring and next-episode handoff
- Torbox, Premiumize, and Real-Debrid support with device-local credentials
- Dual-engine playback: ExoPlayer (Dolby Vision conversion) + MPV (anime / ASS)
- Optional TMDB enrichment, OpenSubtitles, and Trakt when configured at build time

## Features

**Playback**
- Content-aware autoplay scoring (mainstream, anime, K-/J-drama, live events)
- AUTO engine selection with startup failover between ExoPlayer and MPV
- Intro/credits skip integrations when available

**Sources**
- Bundled community sources: Cinemeta, OpenSubtitles, Torrentio, MediaFusion
- Connecting a debrid account provisions Torrentio for that provider
- WatchHub “where to watch” path when debrid is not configured

**TV-first UX**
- Guest or email/password account gate (sync is optional)
- One-screen debrid connection for Torbox, Premiumize, and Real-Debrid
- Profiles, library / My List, and cloud manager for Torbox files

## Installation

### Android TV

Install a release APK from the project’s
[GitHub Releases](https://github.com/tnguyen0362/SlugYard/releases) when published,
or build from source below.

### From source

```bash
git clone https://github.com/tnguyen0362/SlugYard.git
cd SlugYard
cp local.example.properties local.properties
# Edit local.properties: sdk.dir and any optional API keys
./gradlew :app:assembleFullDebug
```

Flavor notes:
- `fullDebug` — sideload / development build
- `playstoreDebug` / release variants — Play Store application id path

## Development

### Prerequisites

- Android Studio (JDK 21+)
- Android SDK (API 36)
- NDK 29.0.14206865 (for native player / DV pieces when enabled)

### Configuration

`local.properties` is gitignored. Start from [local.example.properties](local.example.properties).

Optional integrations (blank = disabled at build time):

| Key | Purpose |
|-----|---------|
| `SLUGYARD_SUPABASE_URL` / `SLUGYARD_SUPABASE_ANON_KEY` | Account sync |
| `TMDB_API_KEY` | TMDB metadata / art |
| `TRAKT_CLIENT_ID` / `TRAKT_CLIENT_SECRET` | Trakt scrobble |
| `OPENSUBTITLES_API_KEY` | OpenSubtitles API |
| `MDBLIST_API_KEY` | MDBList ratings |

Self-hosted sync schema: [SUPABASE_SETUP.md](SUPABASE_SETUP.md) and
[supabase-schema.sql](supabase-schema.sql).

**Never** put a Supabase service-role key in the app or this repository.

## Architecture (short)

- **Kotlin + Jetpack Compose** TV UI under `app/src/main/java/com/sluggyard/tv/ui/rewrite`
- **MPV (libmpv)** — HDR tone-mapping, ASS/SSA via libass, broad codecs
- **ExoPlayer (Media3 fork)** — Dolby Vision 7→8.1 via DoviBridge when enabled
- **Hilt** DI, **Retrofit/OkHttp** networking, optional **Supabase** auth/sync

Media3 fork notes: [app/libs/README.md](app/libs/README.md) and
[playflix-media3-mods/README.md](playflix-media3-mods/README.md).

## Legal & DMCA

SlugYard is a client-side interface for browsing metadata and playing media from
user-configured extensions and sources. It is intended for content the user owns
or is otherwise authorized to access.

SlugYard is not affiliated with third-party extensions or content providers. It
does not host, store, or distribute media content.

## Built with

* Kotlin + Jetpack Compose (TV)
* [libmpv-android](https://github.com/jarnedemeulemeester/libmpv-android)
* ExoPlayer / Media3 (forked)
* [libdovi](https://github.com/quietvoid/dovi_tool)
* [ass-media](https://github.com/peerless2012/ass-media)
* Hilt · Retrofit · Supabase Auth

## License

GNU General Public License v3.0 — see [LICENSE](LICENSE).

## Credits

Upstream attribution: [NOTICE](NOTICE).

- [libmpv-android](https://github.com/jarnedemeulemeester/libmpv-android)
- [Stremio](https://www.stremio.com/) addon ecosystem
- [NuvioTV](https://github.com/NuvioMedia/NuvioTV) (see NOTICE)

<!-- MARKDOWN LINKS & IMAGES -->
[license-shield]: https://img.shields.io/github/license/tnguyen0362/SlugYard.svg?style=for-the-badge
[license-url]: http://www.gnu.org/licenses/gpl-3.0.en.html
