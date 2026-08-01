# Product

## Register

product

## Users

TV viewers using a remote from a living room. They need to browse and play media
locally first, with optional account-backed sync when they choose to sign in.

## Product Purpose

SlugYard is an Android TV media application with an offline-first local library,
profiles, addon-backed browsing, and optional Supabase sync across devices. The
authentication gate exists to make sync testable without making an account a
requirement for local playback.

## Brand Personality

Direct, calm, resilient. The product should feel dependable at television viewing
distance and should explain account state without marketing language.

## Anti-references

Do not turn the auth entry point into a SaaS conversion page, a decorative
gradient login card, or a mouse-first web form. Do not hide guest access or make
network availability a prerequisite for local use.

## Design Principles

- Local use remains useful without authentication or network access.
- Remote navigation and visible focus are first-class interaction requirements.
- Account state is explicit, recoverable, and never destructive to local data.
- Sync is opt-in and operationally quiet after successful authentication.

## Accessibility & Inclusion

Use readable TV-scale text, strong contrast, visible focus rings, remote-sized
targets, concise errors, and predictable Back behavior. Avoid color-only status
communication and do not introduce motion that is required to understand state.
