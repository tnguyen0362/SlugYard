# MatroskaExtractor Merge Guide: DV Vendored Additions

Rebase target: Media3 1.10.1 stock `MatroskaExtractor.java`
Source: `app/src/main/java/com/sluggyard/tv/core/player/dvmkv/MatroskaExtractor.java` (3,523 lines)

---

## 1. Import Additions (DV-Specific)

The following imports are NOT in stock MatroskaExtractor and must be added:

```java
import androidx.media3.container.DolbyVisionConfig;           // line 43
import com.sluggyard.tv.core.player.dvmkv.DolbyVisionCompatibility; // used at lines 2901, 2929, 2936
import java.nio.ByteBuffer;                                    // may already be stock
import java.nio.ByteOrder;                                    // may already be stock
```

**Note:** `DolbyVisionConfig` is from `media3-container` and is used in `initializeFormat()` to parse DV config bytes. `DolbyVisionCompatibility` is a PlayFlix-specific helper class.

---

## 2. Interface Definition: `DolbyVisionSampleTransformer`

**Location:** Lines 120-206 (top-level inner interface of `MatroskaExtractor`)

This is an entirely new public interface. It must be added as an inner interface of `MatroskaExtractor`. Full definition:

```java
public interface DolbyVisionSampleTransformer {

  default boolean shouldTransform(
      @Nullable String codecs, @Nullable byte[] dolbyVisionConfigBytes) {
    return true;
  }

  @Nullable
  default byte[] onDolbyVisionBlockAdditionalData(
      byte[] blockAdditionalData, int blockAddIdType, @Nullable byte[] dolbyVisionConfigBytes) {
    return null;
  }

  default void onHevcSample(
      int sampleSizeBytes,
      @Nullable byte[] blockAdditionalData,
      @Nullable byte[] dolbyVisionConfigBytes) {}

  @Nullable
  default byte[] transformHevcSample(
      byte[] sampleLengthDelimitedData,
      int sampleLength,
      int nalUnitLengthFieldLength,
      @Nullable byte[] blockAdditionalData,
      @Nullable byte[] dolbyVisionConfigBytes) {
    return null;
  }

  default int lastTransformedSampleLength() {
    return 0;
  }

  @Nullable
  default String onDolbyVisionCodecString(
      @Nullable String codecs, @Nullable byte[] dolbyVisionConfigBytes) {
    return null;
  }
}
```

---

## 3. Static Factory Methods Added

### 3a. `newFactory` overload with DV transformer (lines 98-106)

```java
public static ExtractorsFactory newFactory(
    SubtitleParser.Factory subtitleParserFactory,
    @Nullable DolbyVisionSampleTransformer dolbyVisionSampleTransformer) {
  return () ->
      new Extractor[] {
        new MatroskaExtractor(subtitleParserFactory, /* flags= */ 0, dolbyVisionSampleTransformer)
      };
}
```

---

## 4. Instance Field Additions

### 4a. `dolbyVisionSampleTransformer` field (line 536)
```java
@Nullable private final DolbyVisionSampleTransformer dolbyVisionSampleTransformer;
```

### 4b. `getDolbyVisionSampleTransformer()` accessor (lines 538-542)
```java
@Nullable
public DolbyVisionSampleTransformer getDolbyVisionSampleTransformer() {
  return dolbyVisionSampleTransformer;
}
```

### 4c. `dolbyVisionSampleBuffer` field (line 558)
```java
private byte[] dolbyVisionSampleBuffer = new byte[0];
```

---

## 5. Constructor Modifications

### 5a. Modified constructors: all pass `null` for the new parameter

Lines 626-697 show that every existing constructor now delegates to the 4-arg package-private constructor with `/* dolbyVisionSampleTransformer= */ null`. This is a non-breaking change — the default is null (no DV processing).

### 5b. New public constructor (lines 683-688)
```java
public MatroskaExtractor(
    SubtitleParser.Factory subtitleParserFactory,
    @Flags int flags,
    @Nullable DolbyVisionSampleTransformer dolbyVisionSampleTransformer) {
  this(new DefaultEbmlReader(), flags, subtitleParserFactory, dolbyVisionSampleTransformer);
}
```

### 5c. New package-private constructor (lines 699-725)
```java
/* package */ MatroskaExtractor(
    EbmlReader reader,
    @Flags int flags,
    SubtitleParser.Factory subtitleParserFactory,
    @Nullable DolbyVisionSampleTransformer dolbyVisionSampleTransformer) {
  this.reader = reader;
  this.reader.init(new InnerEbmlProcessor());
  this.subtitleParserFactory = subtitleParserFactory;
  this.dolbyVisionSampleTransformer = dolbyVisionSampleTransformer;  // <-- DV addition
  // ... rest is stock initialization
}
```

The only change in the 4-arg constructor body is `this.dolbyVisionSampleTransformer = dolbyVisionSampleTransformer;` (line 707). Everything else in the constructor body is stock.

---

## 6. Track Inner Class Modifications

### 6a. New fields in `Track` (lines 2587-3032)

```java
public byte @MonotonicNonNull [] dolbyVisionConfigBytes;                    // line 2642
public byte @MonotonicNonNull [] pendingDolbyVisionBlockAdditionalData;     // line 2643
public boolean requiresDolbyVisionTransform;                               // line 2665
public boolean isHevc;                                                      // line 2666
```

### 6b. `initializeFormat()` signature change (lines 2670-2671)

Stock signature is `initializeFormat(int trackId)`.
Vendored signature adds the transformer parameter:

```java
public void initializeFormat(
    int trackId, @Nullable DolbyVisionSampleTransformer dolbyVisionSampleTransformer)
    throws ParserException {
```

### 6c. DV logic in `initializeFormat()` (lines 2878-2937)

After the main codec switch and before building the Format, the following DV logic is inserted:

**Block A: Parse DV config when `dolbyVisionConfigBytes != null` (lines 2879-2907)**
```java
@Nullable String hevcCodecsString = codecs;
if (dolbyVisionConfigBytes != null) {
  @Nullable
  DolbyVisionConfig dolbyVisionConfig =
      DolbyVisionConfig.parse(new ParsableByteArray(this.dolbyVisionConfigBytes));
  if (dolbyVisionConfig != null) {
    codecs = dolbyVisionConfig.codecs;
    mimeType = MimeTypes.VIDEO_DOLBY_VISION;
    if (dolbyVisionSampleTransformer != null) {
      @Nullable String transformedCodecs =
          dolbyVisionSampleTransformer.onDolbyVisionCodecString(codecs, this.dolbyVisionConfigBytes);
      if (transformedCodecs != null && !transformedCodecs.isEmpty()) {
        codecs = transformedCodecs;
      }
      if (codecs != null) {
        String lower = codecs.toLowerCase(Locale.ROOT);
        if (lower.startsWith("hvc1.") || lower.startsWith("hev1.")) {
          mimeType = MimeTypes.VIDEO_H265;
        }
      }
      if (MimeTypes.VIDEO_DOLBY_VISION.equals(mimeType) && hevcCodecsString != null) {
        if (DolbyVisionCompatibility.isHdr10BaseLayerModeActive()) {
          mimeType = MimeTypes.VIDEO_H265;
          codecs = hevcCodecsString;
        }
      }
    }
  }
}
```

**Block B: Codec-string rewrite without DV config bytes (lines 2908-2927)**
```java
else if (dolbyVisionSampleTransformer != null && codecs != null) {
  String lower = codecs.toLowerCase(Locale.ROOT);
  if (lower.startsWith("dvhe.") || lower.startsWith("dvh1.")
      || lower.startsWith("dvav.") || lower.startsWith("dva1.")) {
    @Nullable String transformedCodecs =
        dolbyVisionSampleTransformer.onDolbyVisionCodecString(codecs, null);
    if (transformedCodecs != null && !transformedCodecs.isEmpty()) {
      codecs = transformedCodecs;
      String tLower = codecs.toLowerCase(Locale.ROOT);
      if (tLower.startsWith("hvc1.") || tLower.startsWith("hev1.")) {
        mimeType = MimeTypes.VIDEO_H265;
      } else {
        mimeType = MimeTypes.VIDEO_DOLBY_VISION;
      }
    }
  }
}
```

**Block C: DV7 to HEVC profile mapping (lines 2929-2937)**
```java
if (DolbyVisionCompatibility.shouldMapDolbyVisionProfile7(mimeType, codecs)) {
  mimeType = MimeTypes.VIDEO_H265;
  codecs = DolbyVisionCompatibility.chooseHevcCodecsString(codecs, null);
}
```

### 6d. Post-build field assignments (lines 3028-3031)

After `format = formatBuilder...build();`:
```java
requiresDolbyVisionTransform =
    dolbyVisionSampleTransformer != null
        && dolbyVisionSampleTransformer.shouldTransform(codecs, dolbyVisionConfigBytes);
isHevc = CODEC_ID_H265.equals(codecId);
```

### 6e. Reset method modification (line 3047)

```java
public void reset() {
  if (trueHdSampleRechunker != null) {
    trueHdSampleRechunker.reset();
  }
  pendingDolbyVisionBlockAdditionalData = null;  // <-- DV addition
}
```

---

## 7. Call Site Modifications (where `initializeFormat` is called)

**Line 1137:** The call site passes the transformer:
```java
currentTrack.initializeFormat(currentTrack.number, dolbyVisionSampleTransformer);
```

Stock would be: `currentTrack.initializeFormat(currentTrack.number);`

---

## 8. `handleBlockAddIDExtraData()` Method (lines 1753-1763)

This is a **new protected method** that reads DV config bytes from `BlockAddIDExtraData`:

```java
protected void handleBlockAddIDExtraData(Track track, ExtractorInput input, int contentSize)
    throws IOException {
  if (track.blockAddIdType == BLOCK_ADD_ID_TYPE_DVVC
      || track.blockAddIdType == BLOCK_ADD_ID_TYPE_DVCC) {
    track.dolbyVisionConfigBytes = new byte[contentSize];
    input.readFully(track.dolbyVisionConfigBytes, 0, contentSize);
  } else {
    input.skipFully(contentSize);
  }
}
```

**Note:** This method must be called from `binaryElement()` when `id == ID_BLOCK_ADDITION_MAPPING` (or equivalent). Verify the call site in stock 1.10.1 — in 1.8.0 stock, `ID_BLOCK_ADD_ID_EXTRA_DATA` may be handled differently.

---

## 9. `handleBlockAdditionalData()` Method (lines 1800-1833)

This is a **new protected method** handling VP9 ITU-T.35 and DV BlockAdditional data:

```java
protected void handleBlockAdditionalData(
    Track track, int blockAdditionalId, ExtractorInput input, int contentSize)
    throws IOException {
  if (blockAdditionalId == BLOCK_ADDITIONAL_ID_VP9_ITU_T_35
      && CODEC_ID_VP9.equals(track.codecId)) {
    supplementalData.reset(contentSize);
    input.readFully(supplementalData.getData(), 0, contentSize);
  } else if (track.isHevc
      && (track.blockAddIdType == BLOCK_ADD_ID_TYPE_DVVC
          || track.blockAddIdType == BLOCK_ADD_ID_TYPE_DVCC)) {
    byte[] blockAdditionalData = new byte[contentSize];
    input.readFully(blockAdditionalData, 0, contentSize);
    track.pendingDolbyVisionBlockAdditionalData = blockAdditionalData;
    if (dolbyVisionSampleTransformer != null) {
      try {
        byte[] transformed =
            dolbyVisionSampleTransformer.onDolbyVisionBlockAdditionalData(
                blockAdditionalData, track.blockAddIdType, track.dolbyVisionConfigBytes);
        if (transformed != null) {
          track.pendingDolbyVisionBlockAdditionalData = transformed;
        }
      } catch (RuntimeException e) {
        Log.w(TAG, "DolbyVisionSampleTransformer.onDolbyVisionBlockAdditionalData failed: "
            + e.getMessage());
      }
    }
  } else {
    input.skipFully(contentSize);
  }
}
```

This method is called from `binaryElement()` at line 1744 for `ID_BLOCK_ADDITIONAL`.

---

## 10. `commitSampleToOutput()` Modification (lines 1908-1910)

After `track.output.sampleMetadata(...)` and before `haveOutputSample = true;`, add:

```java
if (track.isHevc) {
  track.pendingDolbyVisionBlockAdditionalData = null;
}
```

---

## 11. `writeSampleData()` DV Transform Logic (lines 2062-2132)

This is the largest DV modification. Three sections:

### 11a. Defer supplemental data size prefix (lines 2067-2068)
```java
deferSupplementalMainSampleSizePrefix =
    track.isHevc && track.requiresDolbyVisionTransform && dolbyVisionSampleTransformer != null;
```
When true, the 4-byte supplemental data size prefix is deferred until after the transform, because the Annex-B size differs from the length-delimited size.

### 11b. `onHevcSample` event (lines 2079-2087)
```java
if (track.isHevc && track.requiresDolbyVisionTransform && dolbyVisionSampleTransformer != null) {
  try {
    dolbyVisionSampleTransformer.onHevcSample(
        size, track.pendingDolbyVisionBlockAdditionalData, track.dolbyVisionConfigBytes);
  } catch (RuntimeException e) {
    Log.w(TAG, "DolbyVisionSampleTransformer.onHevcSample failed: " + e.getMessage());
  }
}
```

### 11c. Full transform path (lines 2090-2132)
Reads the full sample into `dolbyVisionSampleBuffer`, calls `transformHevcSample()`, and writes the result as Annex-B via `writeLengthDelimitedSampleAsAnnexB()`.

---

## 12. Helper Methods Added for DV

### `writeLengthDelimitedSampleAsAnnexB()` (lines 2196-2235)
Converts length-delimited NAL sample data to start-code delimited (Annex-B) format. Used only in the DV transform path.

### `getAnnexBSize()` (lines 2237-2256)
Calculates the output size of a length-delimited sample after Annex-B conversion. Used to size the deferred supplemental data prefix.

---

## 13. Constants Added for DV

```java
private static final int BLOCK_ADD_ID_TYPE_DVCC = 0x64766343;  // line 390
private static final int BLOCK_ADD_ID_TYPE_DVVC = 0x64767643;  // line 396
```

These are used alongside the stock `BLOCK_ADDITIONAL_ID_VP9_ITU_T_35 = 4` (line 384, which may already exist in stock).

---

## 14. Vendored Non-DV Additions (also in this file)

These are NOT Dolby Vision additions but are also vendored. They must be preserved separately:

### 14a. DTS-HD analysis (lines 1765-1797)
`isSampleDtsHd()` — vendored copy of a post-1.8.0 API. Uses `DtsUtil.getFrameType()` and `DtsUtil.getDtsFrameSize()` from the vendored `DtsUtil.java`.

### 14b. DTS format detection in `writeSampleData()` (lines 1954-1965)
```java
if (track.waitingForDtsAnalysis) {
  // ... peek sample data, call DtsUtil.getDtsAudioMimeType(), update format
}
```

These are separate from the DV additions and have their own merge requirements.

---

## 15. Areas Likely Affected by Media3 1.9.0 Cue Point Seeking Fix

The 1.9.0 changelog mentions: "Fix cue point seeking for multi-track Matroska files."

### 15a. `perTrackCues` and `MatroskaSeekMap` (high risk)

The entire cue-building and seeking infrastructure uses `perTrackCues` (a `SparseArray<List<CuePointData>>`) keyed by track number. The 1.9.0 fix likely changes:

1. **How cue points are associated with tracks** — the `currentCueTrackNumber` field and the `ID_CUE_TRACK_POSITIONS` handler (lines 1056-1073)
2. **How `MatroskaSeekMap.getSeekPoints(timeUs, trackId)` falls back** between tracks (lines 3386-3420)
3. **The `buildChunkIndex()` method** (lines 3426-3475) which builds per-track cue indexes
4. **The `primarySeekTrackNumber` selection logic** (lines 1153-1200)

**DV impact:** The DV additions do NOT modify any cue point logic. The DV changes are entirely in block data handling, sample writing, and format building. Therefore, the 1.9.0 cue fix should apply cleanly to stock code, and the DV additions can be layered on top without conflicts in this area.

### 15b. `SeekMap` implementation changes (medium risk)

If 1.9.0 changed the `TrackAwareSeekMap` interface or `MatroskaSeekMap` class structure, the vendored copy's `MatroskaSeekMap` (lines 3336-3522) will need to be fully replaced with the 1.10.1 version. The DV additions do NOT touch `MatroskaSeekMap` at all.

### 15c. `seek()` method (low risk)

The `seek()` method (lines 741-762) resets per-track cue state. DV additions do not modify this method. Any 1.9.0 changes to seek behavior will apply cleanly.

---

## 16. Summary of DV Modifications by Method

| Method | Modification Type | Lines |
|--------|------------------|-------|
| `DolbyVisionSampleTransformer` interface | NEW (entire interface) | 120-206 |
| `newFactory(Factory, Transformer)` | NEW factory overload | 98-106 |
| `dolbyVisionSampleTransformer` field | NEW field | 536 |
| `getDolbyVisionSampleTransformer()` | NEW accessor | 538-542 |
| `dolbyVisionSampleBuffer` field | NEW field | 558 |
| Constructors (5 overloads) | Modified: pass null transformer | 626-697 |
| Constructors (2 new) | NEW: accept transformer | 683-725 |
| `Track.dolbyVisionConfigBytes` | NEW field | 2642 |
| `Track.pendingDolbyVisionBlockAdditionalData` | NEW field | 2643 |
| `Track.requiresDolbyVisionTransform` | NEW field | 2665 |
| `Track.isHevc` | NEW field | 2666 |
| `Track.initializeFormat()` | Modified: new param + DV logic | 2670-3031 |
| `Track.reset()` | Modified: clear DV state | 3042-3048 |
| `endMasterElement(ID_TRACK_ENTRY)` | Modified: pass transformer | 1137 |
| `binaryElement(ID_BLOCK_ADDITIONAL)` | Modified: call handleBlockAdditionalData | 1744-1745 |
| `handleBlockAddIDExtraData()` | NEW protected method | 1753-1763 |
| `handleBlockAdditionalData()` | NEW protected method | 1800-1833 |
| `commitSampleToOutput()` | Modified: clear DV state | 1908-1910 |
| `writeSampleData()` | Modified: DV transform path | 2062-2132 |
| `writeLengthDelimitedSampleAsAnnexB()` | NEW private method | 2196-2235 |
| `getAnnexBSize()` | NEW private method | 2237-2256 |

---

## 17. Rebase Procedure (Recommended)

1. Start with stock Media3 1.10.1 `MatroskaExtractor.java`
2. Add the `DolbyVisionSampleTransformer` interface (section 2)
3. Add the constants `BLOCK_ADD_ID_TYPE_DVCC` and `BLOCK_ADD_ID_TYPE_DVVC` (section 13)
4. Add the instance fields and accessor (section 4)
5. Modify constructors to accept and store the transformer (section 5)
6. Add DV fields to the `Track` inner class (section 6a)
7. Modify `Track.initializeFormat()` signature and add DV logic blocks (sections 6b-6d)
8. Modify `Track.reset()` (section 6e)
9. Update `initializeFormat()` call site (section 7)
10. Add `handleBlockAddIDExtraData()` (section 8)
11. Add `handleBlockAdditionalData()` (section 9)
12. Modify `commitSampleToOutput()` (section 10)
13. Add DV transform logic to `writeSampleData()` (section 11)
14. Add helper methods (section 12)
15. Verify the `binaryElement()` dispatch for `ID_BLOCK_ADD_ID_EXTRA_DATA` and `ID_BLOCK_ADDITIONAL` matches the vendored call sites
16. Add the `newFactory` overload (section 3)
