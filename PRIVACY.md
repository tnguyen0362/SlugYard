# SlugYard Privacy Policy

**Effective date:** 0.1.0-beta  
**Contact:** [github.com/tnguyen0362/SlugYard](https://github.com/tnguyen0362/SlugYard)

This policy describes how the SlugYard Android TV app handles information. It reflects current app behavior and is not a substitute for legal counsel.

SlugYard is a client-side media interface. It does **not** host media or operate a content-delivery service.

## Who we are

SlugYard is an open-source project. Source, license ([LICENSE](LICENSE)), and attribution ([NOTICE](NOTICE)) are in this repository. For privacy questions, use the [GitHub project](https://github.com/tnguyen0362/SlugYard).

## Data stored on your device

Depending on how you use the app, SlugYard may store locally (on the TV / device):

- Profiles and preferences
- Watch progress and library / My List state
- Guest or session-related state
- **Debrid API credentials** (per profile), kept in device-local storage and intentionally **excluded** from SlugYard cloud sync

Protect physical access to the device. Remove keys before selling, returning, or sharing it. Uninstalling the app removes local app data subject to Android’s normal uninstall behavior.

## Optional account and sync

If your build includes account sync and you sign in:

- Authentication is handled by Supabase Authentication
- SlugYard may sync profiles, watch progress, library state, and selected non-secret preferences between your devices
- Synced rows are intended to be scoped to your authenticated user (row-level security on the sync backend)

**Credential exception:** if you link Trakt, OAuth access and refresh tokens may be synced so Trakt stays connected across your devices. **Debrid credentials are not uploaded** as part of SlugYard sync.

Guest mode does not require an account for local use.

## Third-party services

When you enable a feature or connect a provider, the app may send requests directly to that service. Examples include:

- Debrid providers you configure (for example Torbox, Premiumize, Real-Debrid)
- Installed or provisioned addon / stream hosts
- Metadata providers (for example TMDB), when configured at build time
- Trakt, OpenSubtitles, and similar integrations when enabled
- Optional AIOStreams / MediaFusion hosts when configured

Those parties process data under their own privacy policies. SlugYard does not control their practices.

## Crash and diagnostic reporting

Some builds may enable Sentry (or similar) crash / ANR reporting when a reporting DSN is configured at build time. Events are intended to support reliability; the app is configured to avoid sending unnecessary personal information. If reporting is not configured, that channel is inactive.

## Children’s privacy

SlugYard is a general Android TV application and is not directed at children under 13. We do not knowingly collect personal information from children under 13. If you believe a child has provided such information through an account you control, contact us via GitHub so we can help you delete account-linked sync data where applicable.

## Retention and deletion

- **On device:** clear app data or uninstall to remove local storage
- **Guest / local-only:** data stays on the device until you clear it
- **Synced account data:** managed through your sync provider account; delete or request deletion via that provider’s tools and/or contact the project on GitHub for guidance on project-hosted sync
- **Third-party accounts** (debrid, Trakt, etc.): managed directly with those providers

## Your choices

- Use guest / local mode without signing in
- Leave optional integrations (Trakt, debrid, etc.) disconnected
- Build from source with optional API keys left blank so those integrations stay disabled

## Changes

We may update this policy when product behavior changes. The current version lives in this repository. Material updates will bump the effective date or version note above.

## Open source

SlugYard is released under GPLv3. Publishing source code does not mean user credentials or private sync data are public; secrets belong in gitignored local config and on-device storage, not in the repository.
