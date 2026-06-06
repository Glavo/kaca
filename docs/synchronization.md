# Repository Synchronization

This document defines remote repository synchronization rules.

## 1. Scope

Synchronization transfers repository state between a local repository and a remote endpoint.

Authoritative synchronized state includes:

- `repository`.
- Repository `config.toml`.
- Immutable loose objects.
- Immutable pack files and pack indexes.
- Mutable snapshot records.
- Recovery record sets.

Rebuildable indexes are synchronized as optional cache data. Missing or stale indexes never define object reachability.

## 2. Remote Identity

Every remote endpoint stores or exposes the repository ID from `repository`. A client must verify the repository ID before uploading or downloading repository state.

Repository format compatibility is checked before mutation:

- A readable compatible remote may be pulled.
- A writable compatible remote may be pushed.
- A remote requiring migration blocks normal sync until migration is explicit.

## 3. Remote Layout

Backends that expose files use the repository layout:

```text
repository
config.toml
objects/
packs/
snapshots/
recovery/
tmp/
```

Provider-specific backends may map this layout to native objects, keys, or API resources. The logical synchronization protocol still uses repository IDs, object IDs, pack IDs, snapshot record IDs, and recovery set IDs.

Temporary remote writes use:

```text
tmp/uploads/<client-id>/<transfer-id>/
```

Transfer IDs are opaque ASCII strings containing creation time and random entropy.

## 4. Inventory

The synchronization inventory is the set of remote repository items visible to the client:

- Repository identity and format versions.
- Loose object IDs.
- Pack IDs and validated pack indexes.
- Snapshot record IDs, revisions, and deletion markers.
- Recovery set IDs.
- Repository configuration revision.

Inventory may be obtained by listing remote storage, reading remote pack indexes, reading remote snapshot record headers, or reading provider-native metadata.

Inventory caches are rebuildable. A stale inventory cache cannot delete local or remote data.

## 5. Immutable Data Transfer

Immutable repository files are synchronized by identity.

Immutable transfer rules:

- Upload missing loose objects by object ID.
- Download missing loose objects by object ID.
- Upload pack files by pack ID.
- Download pack files by pack ID.
- Verify every transferred object envelope before publication.
- Verify pack indexes against pack files before using them for lookup.
- Treat duplicate immutable uploads as successful when the existing remote item verifies against the expected identity.

Upload sequence for immutable files:

1. Write the remote temporary file.
2. Verify size and checksum when the backend exposes read-after-write validation.
3. Commit the temporary file to the final remote name.
4. Re-read or stat the final remote item when the backend supports it.

Backends without atomic remote commit use a temporary name plus final verification. Incomplete temporary uploads are non-authoritative and may be cleaned by sync maintenance.

## 6. Mutable Snapshot Records

Snapshot records are synchronized with revision checks.

Each snapshot record contains:

- Snapshot record ID.
- Snapshot object reference.
- Record revision.
- Parent record revision.
- Updated time.
- Updating client ID.
- Mutable user metadata.
- Deletion marker when deleted.

Push rules:

- Pull or refresh remote inventory before pushing mutable records.
- Upload a record when the remote record is absent.
- Replace a remote record when the remote revision equals the local parent revision.
- Create a conflict record when local and remote revisions diverge.

Pull rules:

- Import remote records absent locally.
- Replace local records when the local revision equals the remote parent revision.
- Create a conflict record when local and remote revisions diverge.

Conflict records preserve both versions. Automatic merge may merge independent user metadata fields such as notes, tags, pins, and retention hints when each field has an unambiguous base revision.

## 7. Deletion and Tombstones

Absence is not a deletion signal.

Snapshot record deletion creates a tombstone record with:

- Snapshot record ID.
- Deleted revision.
- Parent revision.
- Deleted time.
- Deleting client ID.

Tombstones synchronize like mutable snapshot records. Pruning may remove tombstones only after the configured retention window and after every configured remote has had an opportunity to observe them.

Object deletion is performed by prune after liveness is computed from retained snapshot records and active tombstones. Sync does not delete immutable objects solely because a remote inventory omits them.

## 8. Recovery Records

Recovery record sets are synchronized by recovery set ID.

Recovery sync rules:

- Transfer recovery manifests and recovery data files together.
- Verify that each recovery set references the expected repository ID.
- Report recovery sets that protect files no longer present after prune.
- Preserve stale recovery sets until recovery maintenance replaces or deletes them explicitly.

## 9. Trust and Encryption

Remote trust controls exposure policy, not repository identity.

For untrusted remotes:

- Object payloads are encrypted when repository encryption is enabled.
- Snapshot manifests are encrypted in full repository encryption mode.
- Mutable snapshot records use the configured mutable record encryption mode.
- Remote providers are not trusted to enforce retention, deletion, or confidentiality.

Clients verify repository data after download regardless of remote trust level.

## 10. Failure Recovery

Synchronization is resumable.

Failure recovery rules:

- Incomplete remote temporary uploads are non-authoritative.
- Published immutable files are verified by identity before use.
- Published mutable records are verified by record ID and revision before use.
- Interrupted downloads remain in the local transaction workspace until local publication.
- Remote pull materialization uses local transaction rules from `docs/transactions.md`.

Sync repair may remove temporary remote uploads owned by the same client after confirming no active transfer uses them.
