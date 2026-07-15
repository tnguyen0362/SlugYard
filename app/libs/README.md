# PlayFlix Local AAR Libraries

This directory contains pre-built AAR files that replace stock Media3 modules with PlayFlix-modified versions.

## Media3 Fork Build (v1.10.1)

PlayFlix maintains a fork of `androidx.media3` with custom performance optimizations. The following 7 modules are built from the forked source:

| Module | AAR | Purpose |
|--------|-----|---------|
| `media3-common` | `lib-common-release.aar` | NuvioEngineConfig global toggle |
| `media3-exoplayer` | `lib-exoplayer-release.aar` | Native allocation, scrubbing mode |
| `media3-datasource` | `lib-datasource-release.aar` | ByteBufferDataReader zero-copy |
| `media3-datasource-okhttp` | `lib-datasource-okhttp-release.aar` | OkHttp datasource |
| `media3-exoplayer-hls` | `lib-exoplayer-hls-release.aar` | HLS support |
| `media3-extractor` | `lib-extractor-release.aar` | Container extraction |
| `decoder-ffmpeg` | `lib-decoder-ffmpeg-release.aar` | FFmpeg audio decoders |

### Build Requirements

- **Media3 source:** Fork `https://github.com/androidx/media` at tag `1.10.1`
- **Android SDK:** API 34 (compileSdk)
- **Build tools:** AGP 8.13.2, Kotlin 2.3.0
- **JDK:** 17 or higher

### Building the Forked AARs

1. Clone the forked media3 repository:
   ```bash
   git clone https://github.com/<your-org>/media.git
   cd media
   git checkout 1.10.1
   ```

2. Apply PlayFlix modifications from `playflix-media3-mods/`:
   - Copy `common/` files into the media3 `common/` module
   - Copy `exoplayer/` files into the media3 `exoplayer/` module
   - Copy `datasource/` files into the media3 `datasource/` module
   - Apply any `.patch` files using `git apply`

3. Build each module's AAR:
   ```bash
   ./gradlew :libraries:common:bundleReleaseAar
   ./gradlew :libraries:exoplayer:bundleReleaseAar
   ./gradlew :libraries:datasource:bundleReleaseAar
   ./gradlew :libraries:datasource-okhttp:bundleReleaseAar
   ./gradlew :libraries:exoplayer-hls:bundleReleaseAar
   ./gradlew :libraries:extractor:bundleReleaseAar
   ./gradlew :extension-ffmpeg:bundleReleaseAar
   ```

4. Copy the built AARs to `app/libs/`:
   ```bash
   cp libraries/common/build/outputs/aar/common-release.aar app/libs/lib-common-release.aar
   cp libraries/exoplayer/build/outputs/aar/exoplayer-release.aar app/libs/lib-exoplayer-release.aar
   cp libraries/datasource/build/outputs/aar/datasource-release.aar app/libs/lib-datasource-release.aar
   cp libraries/datasource_okhttp/build/outputs/aar/datasource-okhttp-release.aar app/libs/lib-datasource-okhttp-release.aar
   cp libraries/exoplayer_hls/build/outputs/aar/exoplayer-hls-release.aar app/libs/lib-exoplayer-hls-release.aar
   cp libraries/extractor/build/outputs/aar/extractor-release.aar app/libs/lib-extractor-release.aar
   cp extension/ffmpeg/build/outputs/aar/extension-ffmpeg-release.aar app/libs/lib-decoder-ffmpeg-release.aar
   ```

### FFmpeg Decoder Details

The binary FFmpeg extension was built with the following decoders:

```
ENABLED_DECODERS=(vorbis opus flac alac pcm_mulaw pcm_alaw mp3 amrnb amrwb aac ac3 eac3 dca mlp truehd)
```

Complete [build instructions](https://github.com/androidx/media/blob/release/libraries/decoder_ffmpeg/README.md).

### Notes

- Stock Media3 modules are excluded globally in `app/build.gradle.kts` to prevent version conflicts
- The fork modifications are in `playflix-media3-mods/` directory
- These modifications are PlayFlix-specific and not intended for upstream contribution
