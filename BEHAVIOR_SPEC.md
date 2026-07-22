# SlugYard rewrite handoff

The complete current-state handoff is maintained at:

`docs/BEHAVIOR_SPEC.md`

Read that file first. It is the authoritative 503-line continuation document covering the
rewrite scope, SlugYard versus PlayFlix naming, retained Media3/autofile boundaries, current
architecture, fixed community addon policy, Supabase/auth relationship, credential security,
file-level continuation map, known incomplete work, commands, verification evidence, and the
release gate.

Why this root file exists: `docs/` is intentionally ignored by this dirty worktree’s `.gitignore`,
so standard LLM repository discovery (`rg --files`, shallow root scans, or candidate-file lists)
does not show the canonical document. Do not create a second competing specification. Follow the
canonical file above and update both only if the pointer itself changes.

Product naming: **SlugYard** is the Android TV product. **PlayFlix** is only the curated addon /
community backend name. The current code still has a few mistaken internal `PlayFlix*` identifiers;
the canonical handoff records that rename as pending work.
