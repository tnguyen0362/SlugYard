# PlayFlix Media3 Modifications

This directory contains modified/new source files for the PlayFlix fork of Media3 (androidx.media3).

## Source Repository

These modifications are designed to be applied to a fork of:
- **Repository:** `https://github.com/androidx/media`
- **Tag:** `1.10.1`

## Directory Structure

Each subdirectory corresponds to a Media3 module:

- `common/` - Shared configuration (SlugYardEngineConfig)
- `exoplayer/` - ExoPlayer modifications (native allocation, scrubbing mode, sample data queue)
- `datasource/` - DataSource modifications (ByteBufferDataReader)

## File Types

- **`.patch` files** - Modifications to existing stock Media3 files. Apply using `git apply` or `patch -p1`.
- **`.kt` / `.java` / `.c` files** - New files to add to the corresponding module directories.

## Key Features

- **SlugYardEngineConfig** - Global toggle for SlugYard performance mode (native off-heap allocation, zero-copy ByteBuffer pipeline)
- **DefaultAllocatorNative** - JNI bridge for native memory allocation (64-byte aligned)
- **SampleDataQueueNative** - JNI bridge for zero-copy buffer operations
- **ScrubbingModeParameters** - Configuration for seek optimization
- **ByteBufferDataReader** - Interface for zero-copy data reading

## Applying Modifications

1. Fork `androidx/media` at tag `1.10.1`
2. Apply `.patch` files to existing stock files
3. Copy new source files into corresponding module directories
4. Build the modified AARs

## Native Dependencies

The native code (`.c` files) reuses the existing `libdovi_bridge` native library for JNI calls. Ensure this library is available in the build environment.

## Notes

- These modifications are PlayFlix-specific and are not intended for upstream contribution
- The native allocation path is optional and controlled by `SlugYardEngineConfig`
- All modifications preserve backward compatibility with stock Media3 behavior
