<div align="center">

  <img src="assets/brand/app_logo_wordmark.png" alt="SlugYard" width="300" />
  <br />
  <br />

  [![License][license-shield]][license-url]

  <p>
    A modern Android TV media player powered by the Stremio addon ecosystem.
    <br />
    Dual-engine playback (ExoPlayer + libmpv) · Debrid integration · Anime-optimized subtitles
  </p>

</div>

## About

SlugYard is a substantially overhauled Android TV fork of [NuvioTV](https://github.com/NuvioMedia/NuvioTV), designed as a polished, plug-and-play media experience: connect a supported debrid account and press play.

This project is independently branded and maintained as SlugYard. It retains the NuvioTV attribution and is released under the same GPLv3 license; see [NOTICE](NOTICE) and [LICENSE](LICENSE).

It acts as a client-side playback interface that integrates with the Stremio addon ecosystem for content discovery and source resolution through bundled extensions.

Built with Kotlin and Jetpack Compose, optimized for a TV-first viewing experience with dual playback engines:

- **MPV (libmpv)** — Default engine. Best-in-class HDR tone-mapping (libplacebo), full ASS/SSA subtitle rendering (libass), broad codec support (AV1, HEVC Main10, VP9, DTS-HD, TrueHD).
- **ExoPlayer (Media3 1.10.1)** — Used for Dolby Vision 7→8.1 conversion via the forked DoviBridge pipeline.

## Key Features

### PureFire Fork Features

**Smart Autoplay Engine**
- Content-type-aware scoring (mainstream, anime, K-drama, J-drama, live events)
- Multi-dimensional scoring: quality, audio, subtitles, reliability, size
- Anime/K-drama/J-drama prioritizes subtitles and seeders
- Mainstream content prioritizes quality and audio

**Simplified Settings**
- Reduced from 12 categories to 6
- Only essential settings visible: Account, Profiles, Playback, Trakt, About
- Advanced settings hidden for consumer audience

**Bundled Addons**
- 4 pre-configured addons (Torrentio, Comet, MediaFusion, Torbox Stream)
- Addon manager hidden from users
- Bundled addons cannot be removed or disabled

**OpenSubtitles Integration**
- Automatic fallback when embedded subtitles unavailable
- Hash-based lookup first, IMDB ID search fallback
- Configurable API key and auto-download toggle

**Netflix-Style UI**
- Email/password authentication (no QR codes)
- Simplified startup flow (skips all onboarding)
- Torbox-only debrid settings
- Streamlined Trakt configuration

### Player Engine Architecture

**AUTO Mode (Hardcoded)**
- Dolby Vision content → ExoPlayer (for DV7→DV8.1 conversion via DoviBridge)
- Everything else → mpv (for HDR10/HDR10+/HLG tone-mapping via libplacebo)
- Both engines embedded in-process, share same PlayerScreen composable

**mpv Performance Optimizations**
- Adaptive buffer sizing based on available memory:
  - High-end (>512MB free): 256MB forward, 128MB back, 30s read-ahead
  - Mid-range (256-512MB): 128MB forward, 64MB back, 30s read-ahead
  - Low-end (<256MB): 64MB forward, 32MB back, 15s read-ahead
- Video latency hacks enabled
- Hardware GPU rendering forced
- NV12 image format for better compatibility
- Subtitle font size optimized for TV (42)

**ExoPlayer Optimizations**
- Adaptive memory budgeting via MemoryBudget
- Low-RAM tier: 65% of heap minus 210MB reserve
- High-RAM tier: 85% of heap
- Automatic DV7 conversion headroom management

### Subtitle System

**mpv Engine**
- Native libass for pixel-perfect ASS/SSA rendering
- Full customization: size, bold, outline, colors, offset

**ExoPlayer Engine**
- libass via ass-media:0.4.0 (5 render modes)
- Custom MKV extractor for ASS subtitle extraction
- Dynamic libass pipeline switching

**OpenSubtitles Fallback**
- Automatic download when embedded subs unavailable
- Language preference matching
- Cached to avoid rate limits

## Installation

### Android TV

Download the latest APK from [GitHub Releases](https://github.com/tnguyen0362/SlugYard/releases/latest) and install on your Android TV device.

### From Source

```bash
git clone https://github.com/tnguyen0362/SlugYard.git
cd SlugYard
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for build prerequisites and project structure.

## Development

### Prerequisites

- Android Studio (latest version, with JDK 21+)
- Android SDK (API 36)
- NDK 29.0.14206865

### Build

```bash
# Full debug build
./gradlew :app:assembleFullDebug

# Play Store debug build
./gradlew :app:assemblePlaystoreDebug
```

### Key Configuration

Create `local.properties` (git-ignored) with:

```properties
sdk.dir=/path/to/android/sdk
ndk.dir=/path/to/android/ndk

# Optional: Dolby Vision native bridge
DOVI_NATIVE_ENABLED=false
DOVI_EXTRACTOR_HOOK_READY=false
DOVI_LIBDOVI_PREBUILT_ROOT=DV7/libdovi

# Optional: Trakt integration
TRAKT_CLIENT_ID=your_client_id
TRAKT_CLIENT_SECRET=your_client_secret

# Optional: TMDB integration
TMDB_API_KEY=your_api_key

# Optional: OpenSubtitles integration
OPENSUBTITLES_API_KEY=your_api_key
```

## Architecture

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for:
- Project structure and module layout
- Player engine architecture (MPV vs ExoPlayer)
- Dual-engine switching and AUTO heuristic
- Dolby Vision conversion pipeline
- Build flavors and configuration

## Key Changes from NuvioTV

| Area | NuvioTV | SlugYard |
|------|---------|----------|
| Default player | ExoPlayer | MPV (via AUTO mode) |
| AUTO heuristic | HDR→ExoPlayer, anime→MPV | DV→ExoPlayer, everything else→MPV |
| MPV config | `profile=fast`, basic settings | Optimized: adaptive buffers, tone-mapping, AAudio, ASS compat, latency hacks |
| UI strings | "Libmpv (Beta)", "Experimental" | "MPV Player", production labels |
| Auto-failover | Disabled | Enabled (ExoPlayer fallback on MPV error) |
| Deep link scheme | `nuvio://` | `slugyard://` |
| Settings categories | 12 | 6 (simplified for consumers) |
| Autoplay modes | MANUAL, FIRST_STREAM, REGEX_MATCH | + SCORED (content-type-aware scoring) |
| Addon manager | Visible, user-configurable | Hidden, 4 bundled addons locked |
| OpenSubtitles | Not integrated | Automatic fallback when embedded subs unavailable |
| Auth screens | QR code + email/password | Email/password only (Netflix-style) |
| Startup flow | Experience mode + layout selection | Skips all onboarding, goes straight to home |
| Debrid settings | Multi-provider UI | Torbox-only simplified UI |
| Media3 version | 1.8.0 | 1.10.1 (forked with custom modifications) |
| Branding | NuvioTV | SlugYard |

## Media3 Fork Details

SlugYard maintains a fork of Media3 1.10.1 with custom performance optimizations:

| Module | Purpose |
|--------|---------|
| `media3-common` | NuvioEngineConfig global toggle, ByteBufferDataReader |
| `media3-exoplayer` | Native allocation (DefaultAllocatorNative with JNI), scrubbing mode |
| `media3-datasource` | ByteBufferDataReader zero-copy interface |
| `media3-datasource-okhttp` | OkHttp datasource |
| `media3-exoplayer-hls` | HLS support |
| `media3-extractor` | Container extraction (MatroskaExtractor with 17 DV modifications) |
| `decoder-ffmpeg` | FFmpeg audio decoders |

See [app/libs/README.md](app/libs/README.md) for build instructions.

## Commercial Readiness

All critical and high severity issues have been fixed:

**Critical (7) — All Fixed:**
- LoudnessEnhancer resource leak
- 6 missing job cancellations in releasePlayer()

**High (7) — All Fixed:**
- isReleasingPlayer missing @Volatile
- disposeExoPlayerBeforeRebuild() doesn't release loudnessEnhancer
- initializeMpvPlayer() incomplete ExoPlayer teardown
- Engine failover race conditions
- Missing job cancellations in onCleared()

**Medium (8) — 6 Fixed:**
- pauseForLifecycle() ExoPlayer progress updates
- keepMpvPlayingIfNeeded false playing state
- NuvioMpvSurfaceView initialized @Volatile
- resolveAutoInternalPlayerEngine regex
- onPlayerError background crash recovery UI state
- attemptAutoRetry corrupt state handling

See [HANDOFF.md](HANDOFF.md) for complete details.

## Legal & DMCA

SlugYard functions solely as a client-side interface for browsing metadata and playing media provided by user-installed extensions and/or user-provided sources. It is intended for content the user owns or is otherwise authorized to access.

SlugYard is not affiliated with any third-party extensions or content providers. It does not host, store, or distribute any media content.

## Built With

* Kotlin + Jetpack Compose (TV Material3)
* [libmpv-android](https://github.com/jarnedemeulemeester/libmpv-android) (MPV playback engine)
* ExoPlayer / Media3 1.10.1 (forked, with DV7 conversion pipeline)
* [libdovi](https://github.com/quietvoid/dovi_tool) (Dolby Vision RPU conversion)
* [ass-media](https://github.com/peerless2012/ass-media) (libass for ExoPlayer)
* Hilt (Dependency Injection)
* Retrofit (Networking)
* Supabase (Auth)
* Gradle

## License

This project is licensed under the GNU General Public License v3.0 — see [LICENSE](LICENSE) for details.

## Credits

- [NuvioTV](https://github.com/tapframe/NuvioTV) — upstream project
- [NuvioTV](https://github.com/NuvioMedia/NuvioTV) — original project
- [libmpv-android](https://github.com/jarnedemeulemeester/libmpv-android) — MPV Android integration
- [Stremio](https://www.stremio.com/) — addon ecosystem

<!-- MARKDOWN LINKS & IMAGES -->
[contributors-shield]: https://img.shields.io/github/contributors/tnguyen0362/SlugYard.svg?style=for-the-badge
[contributors-url]: https://github.com/tnguyen0362/SlugYard/graphs/contributors
[forks-shield]: https://img.shields.io/github/forks/tnguyen0362/SlugYard.svg?style=for-the-badge
[forks-url]: https://github.com/tnguyen0362/SlugYard/network/members
[stars-shield]: https://img.shields.io/github/stars/tnguyen0362/SlugYard.svg?style=for-the-badge
[stars-url]: https://github.com/tnguyen0362/SlugYard/stargazers
[issues-shield]: https://img.shields.io/github/issues/tnguyen0362/SlugYard.svg?style=for-the-badge
[issues-url]: https://github.com/tnguyen0362/SlugYard/issues
[license-shield]: https://img.shields.io/github/license/tnguyen0362/SlugYard.svg?style=for-the-badge
[license-url]: http://www.gnu.org/licenses/gpl-3.0.en.html
