# SlugYard privacy summary

SlugYard is an Android TV client. It does not host media or operate a content-delivery service.

## Account data

If a user signs in, Supabase Authentication manages the account session. SlugYard uses the authenticated account to sync profiles, watch progress, library state, and selected non-secret preferences between that user's devices.

Supabase rows are scoped to the authenticated user through row-level security. A linked Trakt account is the one credential exception: its OAuth access and refresh tokens are synced so Trakt can remain connected across a user's devices. Debrid credentials are not part of that mechanism.

## Credentials

Debrid API keys are stored per profile in the app's device-local storage. They are intentionally excluded from the Supabase profile-settings payload and are never uploaded as part of SlugYard sync. Users should still protect the physical device and remove keys before selling, returning, or sharing it.

## Third-party services

When enabled by a user, SlugYard communicates directly with the selected debrid provider, metadata provider, Trakt, subtitle provider, and installed addon endpoints. Those services have their own privacy practices and terms.

## Open source and contact

SlugYard is GPLv3 software. Its source, license, and attribution notices are available in this repository. This document describes the current app behavior and should be reviewed by qualified counsel before a commercial launch.
