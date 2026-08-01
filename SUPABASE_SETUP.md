# Supabase Schema Setup Guide

## Quick Start

The Android client uses the Supabase public URL and publishable/anon key from
the local, gitignored configuration. It never ships a service-role key or any
other privileged database secret.

## Apply the Schema Safely

Since Supabase doesn't expose a SQL execution endpoint via REST API, apply the
schema through the dashboard SQL Editor. The file has two application paths:

- **Fresh project:** run the complete `supabase-schema.sql` file once. The
  bootstrap tables, indexes, policies, triggers, and avatar seed are retry-safe.
- **Existing baseline project:** do not replay the bootstrap section. Run only
  the section beginning at `VERSIONED SYNC CONTRACT MIGRATION (v1)` through the
  end of the file. This migration section is additive and rerunnable.

### Step-by-Step Instructions

1. **Open Supabase Dashboard**
   - Go to: `https://supabase.com/dashboard/project/<your-project-ref>/sql/new`

2. **Choose the application path**
   - For a new project, open `supabase-schema.sql` and copy the entire file.
   - For an existing baseline, copy only from the
     `VERSIONED SYNC CONTRACT MIGRATION (v1)` heading to the end.

3. **Paste and Execute**
   - Paste the selected SQL into the SQL Editor.
   - Click "Run" or press Ctrl+Enter.
   - Wait for completion (~10-30 seconds).

4. **Verify Success**
   - Go to Table Editor (left sidebar).
   - You should see the baseline tables plus `sync_tombstones`:
     - profiles
     - addons
     - plugins
     - library
     - watch_progress
     - watch_progress_events
     - watched_items
     - watched_items_events
     - collections
     - profile_settings
     - home_catalog_settings
     - provider_credentials
     - linked_devices
     - sync_codes
     - avatar_catalog
     - sync_cursors
     - sync_tombstones

5. **Test Integration**
   ```bash
   python test_supabase.py
   ```

## Replicated Contract

The version 1 contract contains exactly ten replicated domains:

1. `PROFILES`
2. `ADDONS`
3. `PLUGINS`
4. `LIBRARY`
5. `WATCH_PROGRESS`
6. `WATCHED_ITEMS`
7. `COLLECTIONS`
8. `PROFILE_SETTINGS`
9. `HOME_CATALOG_SETTINGS`
10. `PROVIDER_CREDENTIALS`

Each replicated row is account-scoped, uses its domain stable identity, and
has `client_changed_at` epoch-millisecond metadata. The generic mutation
envelope carries the owner, domain, optional profile, stable record key,
`UPSERT`/`DELETE` operation, client timestamp, schema version, and optional
JSON payload. Mutation IDs are deterministic SHA-256 identities derived from
the owner, domain, profile, key, timestamp, and operation. The canonical input
is the concatenation of six UTF-8-byte-length-prefixed values, with no
separator: owner, domain, optional profile (empty when absent), record key,
timestamp, and operation. Kotlin and SQL reject IDs that do not match this
encoding.

## Server-Managed Coordination

The following tables are coordination state rather than domain models:

- `watch_progress_events` and `watched_items_events` provide ordered delta events.
- `sync_cursors` stores per-account cursor type, domain/profile metadata, event position, and client timestamp.
- `sync_tombstones` stores accepted `UPSERT`/`DELETE` envelopes for stale-write checks; delete rows are tombstones. Authenticated clients may read their own ledger rows, but direct ledger INSERT/UPDATE/DELETE is denied and accepted operations must use `apply_sync_mutation()`.
- `linked_devices` and `sync_codes` manage authenticated device linking.

The migration adds account/timestamp/event indexes for pull and replay reads.
It preserves the existing tables and policy names. The versioned migration
section at the end of `supabase-schema.sql` is additive and rerunnable after
the baseline schema has been applied.

### RLS Policies

Row Level Security is enabled for every private table. Private policies use
`auth.uid()` for the row owner, including the mutation ledger and cursor
metadata. The sync-code insert policy is also authenticated and owner-scoped.
`avatar_catalog` is the only public table and is read-only to clients.

### RPC Functions

- `generate_sync_code()` creates device sync codes.
- `claim_sync_code()` links an authenticated device.
- `apply_sync_mutation()` is the authenticated, security-definer mutation gate that rejects unsupported schema versions, malformed/reused mutation identities, and stale writes.
- `sync_push_provider_credential_ciphertext(..., p_mutation_id)` is the protected provider-credential writer; it uses the same timestamp/mutation guard and identity lock as `apply_sync_mutation()`.
- Provider pull, delete, legacy JSON, and ciphertext RPC execution is not granted to direct clients. The protected server path is the only path with access to provider ciphertext or legacy credential data.
- The existing non-credential `sync_push_*`, `sync_pull_*`, delete, cursor, device, PIN, and cleanup functions remain available.

### Triggers
Automatic delta sync event creation for watch progress and watched items.

### Seed Data
6 default avatars in the avatar_catalog table.

### Provider Credentials

Provider credentials use the protected server-side path. The client contract
stores only versioned ciphertext metadata (`credential_ciphertext`,
`ciphertext_version`, `client_changed_at`, `mutation_id`, and
`schema_version`); plaintext credentials must never be written to
`credential_json`, sent through logs, or included in test fixtures. Direct
authenticated table access is revoked and the legacy owner policies are deny
policies. The legacy `credential_json` column remains only for compatibility
with the existing table and is outside the v1 client path. The server path
must preserve authenticated user context when calling the protected functions;
no privileged credential is shipped in Android or this repository.

## Migration Order

Apply changes in this order:

1. On a fresh project, run the complete file once. On an existing baseline, run only the `VERSIONED SYNC CONTRACT MIGRATION (v1)` section.
2. If the migration needs to be retried, rerun that migration section; do not replay the bootstrap section on the existing project.
3. Verify the ten replicated tables have `client_changed_at` and the stable identity indexes.
4. Verify `sync_cursors`, `sync_tombstones`, and event indexes are present.
5. Verify `apply_sync_mutation()` is executable by `authenticated` and not by `anon`, and that authenticated ledger DML is denied.
6. Verify direct authenticated privileges and policies on `provider_credentials` deny access to both `credential_json` and `credential_ciphertext`.
7. Run the pure JVM contract test before enabling a client writer.

The Android client performs only authenticated, RLS-checked requests with the
public key. Server-side credential encryption/decryption and any privileged
provider operation must remain behind the protected server path. No client,
log, or diagnostic report may contain a service-role secret or plaintext
provider credential.

### Deploy the Protected Provider Function

The Android client calls `functions/v1/provider-credentials`; this endpoint must
be deployed before enabling provider credential restore. Deploy the checked-in
function from the repository root with the Supabase CLI:

```bash
supabase functions deploy provider-credentials
supabase secrets set SUPABASE_SERVICE_ROLE_KEY=<service-role-key>
```

The function verifies the caller's access token, filters reads by the verified
user id, selects ciphertext columns only, and invokes the service-role-only SQL
wrappers added at the end of `supabase-schema.sql`. The service-role key stays
in Supabase Function secrets and must never be added to `local.properties`, an
APK, CI logs, or Android diagnostics. Re-run the SQL migration after adding the
server wrappers and verify that the wrapper functions are executable only by
`service_role`.

## Troubleshooting

### Schema Apply Failed
- Check for syntax errors in the SQL output
- Ensure you're connected to the correct project
- Try running statements in smaller batches

### Tables Not Showing
- Refresh the dashboard page
- Check if you're in the right project
- Verify the schema was fully applied

### Test Fails
- Ensure schema is applied
- Check credentials in local.properties
- Verify Supabase project is active

## Next Steps

After applying the schema:
1. Build and run the app
2. Create a test account
3. Add some addons
4. Verify sync works
5. Test on multiple devices
