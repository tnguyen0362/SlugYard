# PureFire Addon Configuration System

## Overview
PureFire automatically configures content source addons when users link their debrid service, providing a seamless "Netflix-like" experience with zero manual configuration.

## How It Works

### User Flow
1. User installs PureFire
2. User goes to Settings → Integrations
3. User links their debrid service (Torbox, Real-Debrid, or Premiumize)
4. **Automatically**: Content source addons are configured and added
5. User can immediately start playing content - no manual addon setup needed

### Technical Implementation

#### 1. PureFireBundledAddons.kt
- **Infrastructure Addons** (always included):
  - Cinemeta (metadata)
  - OpenSubtitles (subtitles)

- **Content Sources** (added when debrid is configured):
  - Torrentio (auto-configured with debrid credentials)
  - Comet (manual setup - different config format)
  - MediaFusion (manual setup - different config format)

- **URL Generation**:
  ```
  https://torrentio.strem.fun/debrid={provider}&apiKey={key}/manifest.json
  ```

#### 2. AddonPreferences.kt
- Added `addContentSourceAddons()` method
- Called when debrid credentials are saved
- Generates configured addon URLs and adds them to the addon list

#### 3. DebridSettingsViewModel.kt
- Triggers `addContentSourceAddons()` in two places:
  - `saveProviderCredential()` - device auth flow
  - `validateAndSaveProviderApiKey()` - manual API key entry

#### 4. AddonRepositoryImpl.kt
- Updated to use `INFRASTRUCTURE_ADDONS` instead of `DEFAULT_ADDONS`
- Ensures infrastructure addons are always protected from removal

## Supported Debrid Services

| Service | Auto-Configure | Notes |
|---------|---------------|-------|
| Torbox | ✅ | Full support |
| Real-Debrid | ✅ | Full support |
| Premiumize | ✅ | Full support |

## Content Sources

| Addon | Auto-Configure | Status |
|-------|---------------|--------|
| Torrentio | ✅ | Generates URL with debrid credentials |
| Comet | ❌ | Different config format - manual setup |
| MediaFusion | ❌ | Different config format - manual setup |

## Benefits

1. **Zero Configuration**: Users don't need to understand addon URLs or configuration
2. **Seamless Experience**: Link debrid → start watching immediately
3. **Automatic Updates**: If user changes debrid service, addons are reconfigured
4. **Protected Infrastructure**: Core addons (Cinemeta, OpenSubtitles) can't be accidentally removed

## Future Enhancements

- Auto-configure Comet and MediaFusion (requires understanding their config formats)
- Support for multiple debrid services simultaneously
- Auto-detect best content source based on content type
- Fallback logic if one addon fails
