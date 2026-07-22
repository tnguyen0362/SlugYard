# SlugYard Local AAR Libraries

This directory contains pre-built AAR files that replace stock Media3 modules with SlugYard-modified versions.

## Media3 Fork Build (v1.10.1)

SlugYard maintains a fork of `androidx.media3` at `C:\Users\tnguy\Documents\Media3Fork` (branch `playflix-1.10.1`) with custom performance optimizations. The following 7 modules are built from the forked source:

| Module | AAR | Purpose |
|--------|-----|---------|
| `media3-common` | `lib-common-release.aar` | SlugYardEngineConfig, ByteBufferDataReader |
| `media3-exoplayer` | `lib-exoplayer-release.aar` | Native allocation (DefaultAllocatorNative JNI), scrubbing mode |
| `media3-datasource` | `lib-datasource-release.aar` | ByteBufferDataReader zero-copy |
| `media3-datasource-okhttp` | `lib-datasource-okhttp-release.aar` | OkHttp datasource |
| `media3-exoplayer-hls` | `lib-exoplayer-hls-release.aar` | HLS support |
| `media3-extractor` | `lib-extractor-release.aar` | Container extraction |
| `decoder-ffmpeg` | `lib-decoder-ffmpeg-release.aar` | FFmpeg audio decoders |

### Build Requirements

- **Media3 source:** Fork at `C:\Users\tnguy\Documents\Media3Fork`, branch `playflix-1.10.1`
- **Android SDK:** API 34 (compileSdk)
- **Build tools:** AGP 8.13.2, Kotlin 2.3.0
- **JDK:** 21 (from Android Studio: `C:\Program Files\Android\Android Studio\jbr`)

### Building the Forked AARs

1. Navigate to the forked media3 repository:
   ```bash
   cd C:\Users\tnguy\Documents\Media3Fork
   git checkout playflix-1.10.1
   ```

2. Set environment:
   ```powershell
   $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
   $env:ANDROID_HOME = "C:\Users\tnguy\AppData\Local\Android\Sdk"
   ```

3. Build each module's AAR:
   ```bash
   ./gradlew :libraries:common:bundleReleaseAar
   ./gradlew :libraries:exoplayer:bundleReleaseAar
   ./gradlew :libraries:datasource:bundleReleaseAar
   ./gradlew :libraries:datasource_okhttp:bundleReleaseAar
   ./gradlew :libraries:exoplayer_hls:bundleReleaseAar
   ./gradlew :libraries:extractor:bundleReleaseAar
   ./gradlew :extension-ffmpeg:bundleReleaseAar
   ```

4. Copy the built AARs to `app/libs/` (note: output path is `buildout/`, not `build/`):
   ```bash
   cp libraries/common/buildout/outputs/aar/common-release.aar app/libs/lib-common-release.aar
   cp libraries/exoplayer/buildout/outputs/aar/exoplayer-release.aar app/libs/lib-exoplayer-release.aar
   cp libraries/datasource/buildout/outputs/aar/datasource-release.aar app/libs/lib-datasource-release.aar
   cp libraries/datasource_okhttp/buildout/outputs/aar/datasource-okhttp-release.aar app/libs/lib-datasource-okhttp-release.aar
   cp libraries/exoplayer_hls/buildout/outputs/aar/exoplayer-hls-release.aar app/libs/lib-exoplayer-hls-release.aar
   cp libraries/extractor/buildout/outputs/aar/extractor-release.aar app/libs/lib-extractor-release.aar
   cp extension/ffmpeg/buildout/outputs/aar/extension-ffmpeg-release.aar app/libs/lib-decoder-ffmpeg-release.aar
   ```

### Fork Modifications

The fork includes these custom modifications on top of stock Media3 1.10.1:

- **SlugYardEngineConfig** (Java) — Global performance toggle in `common` module
- **ByteBufferDataReader** — Zero-copy interface in `common` module
- **DefaultAllocatorNative** — JNI-backed native allocator in `exoplayer` module
- **Allocation.buffer** — Added `ByteBuffer buffer` field for parallel range data source
- **ScrubbingModeParameters** — Custom scrubbing mode (stock in 1.10.1, removed to avoid conflict)

### FFmpeg Decoder Details

The binary FFmpeg extension was built with the following decoders:

```
ENABLED_DECODERS=(vorbis opus flac alac pcm_mulaw pcm_alaw mp3 amrnb amrwb aac ac3 eac3 dca mlp truehd)
```

Complete [build instructions](https://github.com/androidx/media/blob/release/libraries/decoder_ffmpeg/README.md).

### Notes

- Stock Media3 modules are excluded globally in `app/build.gradle.kts` to prevent version conflicts
- The fork modifications are in the Media3Fork repo at `C:\Users\tnguy\Documents\Media3Fork`
- Module paths use underscores: `datasource_okhttp`, `exoplayer_hls`
- Build output goes to `buildout/` (not `build/`) due to custom AGP configuration
- These modifications are SlugYard-specific and not intended for upstream contribution
