# Security policy

## Supported versions

| Version | Supported |
|---------|-----------|
| 0.1.0-beta | Yes (pre-release) |

## Reporting a vulnerability

Please **do not** open a public GitHub issue for security reports that include:

- Credential or session leaks
- Privilege escalation or sync/auth bypasses
- Ways to exfiltrate debrid keys, Trakt tokens, or account data

Instead, email the maintainer privately through the contact listed on the
[GitHub profile / release notes](https://github.com/tnguyen0362/SlugYard), and include:

1. A short description of the issue and impact
2. Steps to reproduce (or a proof-of-concept against a local debug build)
3. Affected app version / build flavor if known

We will acknowledge receipt when possible and coordinate a fix before any public
disclosure.

## Scope notes for contributors

- Never commit `local.properties`, `.env`, keystores, APKs, or real API keys.
- Debrid credentials must stay device-local (Keystore-backed storage).
- Prefer `playbackKey` / staged sources over embedding signed stream URLs in
  navigation arguments or logs.
- Supabase **service-role** keys belong only on the server / dashboard — never
  in the Android client or this repository.
