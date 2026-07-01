# Repository Synchronization

This document defines remote repository synchronization rules.

## 1. Scope

Synchronization transfers shared repository state between RepositoryStore endpoints. A file-tree local workspace exposes `share/` as the synchronized root. Archive and bundle repositories expose their internal root as the synchronized root. Workspace-local source overrides are client-local state and are not synchronized.

Authoritative synchronized state includes:

- `repository`.
- Repository `config.toml`.
- Repository source configuration files.
- Repository job configuration files.
- Repository profile configuration files.
- Repository remote configuration files.
- Immutable loose objects.
- Immutable pack files and pack indexes.
- Mutable snapshot records.
- Recovery record sets.

Rebuildable indexes are synchronized as optional cache data. Missing or stale indexes never define object reachability.

## 2. Remote Identity

Every synchronization endpoint stores or exposes the repository ID from `repository`. A client must verify the repository ID before uploading or downloading repository state.

Repository format compatibility is checked before mutation:

- A readable compatible endpoint may be pulled.
- A writable compatible endpoint may be pushed.
- An endpoint requiring migration blocks normal sync until migration is explicit.

## 3. Remote Layout

RepositoryStore backends that expose files use the shared repository layout:

```text
repository
config.toml
sources/
jobs/
profiles/
remotes/
objects/
  ab/
    <object-id>
  packs/
    <pack-id>.pack
    <pack-id>.idx
snapshots/
recovery/
indexes/
```

A file-tree workspace stores this layout under `share/`. Single-file archive and bundle repositories store this layout at the container internal root.

Provider-specific backends may map this layout to native objects, keys, archive entries, bundle segments, or API resources. The logical synchronization protocol still uses repository IDs, object IDs, pack IDs, snapshot record IDs, and recovery set IDs.

Temporary endpoint writes use backend-local staging outside the synchronized shared repository layout. Transfer IDs are opaque ASCII strings containing creation time and random entropy. Temporary upload paths are non-authoritative and are not synchronized as repository state.

## 4. Inventory

The synchronization inventory is the set of repository items visible to the client at one endpoint:

- Repository identity and format versions.
- Loose object IDs.
- Pack IDs and validated pack indexes.
- Snapshot record IDs, revisions, and deletion markers.
- Recovery set IDs.
- Repository configuration revision.
- Source configuration revisions.
- Job configuration revisions.
- Profile configuration revisions.
- Remote configuration revisions.

Inventory may be obtained by listing backend storage, reading pack indexes, reading snapshot record headers, reading archive indexes, or reading provider-native metadata.

Inventory caches are rebuildable. A stale inventory cache cannot delete source or destination data.

## 5. Immutable Data Transfer

Immutable repository files are synchronized by identity.

Immutable transfer rules:

- Upload missing loose objects by object ID.
- Download missing loose objects by object ID.
- Upload pack files by pack ID.
- Download pack files by pack ID.
- Verify every transferred object envelope before publication.
- Verify pack indexes against pack files before using them for lookup.
- Treat duplicate immutable uploads as successful when the existing destination item verifies against the expected identity.

Upload sequence for immutable files:

1. Write the destination temporary file.
2. Verify size and checksum when the backend exposes read-after-write validation.
3. Commit the temporary file to the final destination name.
4. Re-read or stat the final destination item when the backend supports it.

Backends without atomic commit use a temporary name plus final verification. Incomplete temporary uploads are non-authoritative and may be cleaned by sync maintenance.

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

- Pull or refresh destination inventory before pushing mutable records.
- Upload a record when the destination record is absent.
- Replace a destination record when the destination revision equals the source parent revision.
- Create a conflict record when source and destination revisions diverge.

Pull rules:

- Import source records absent at the destination.
- Replace destination records when the destination revision equals the source parent revision.
- Create a conflict record when source and destination revisions diverge.

Conflict records preserve both versions. Automatic merge may merge independent user metadata fields such as notes, tags, pins, and retention hints when each field has an unambiguous base revision.

## 7. Deletion and Tombstones

Absence is not a deletion signal.

Snapshot record deletion creates a tombstone record with:

- Snapshot record ID.
- Deleted revision.
- Parent revision.
- Deleted time.
- Deleting client ID.

Tombstones synchronize like mutable snapshot records. Pruning may remove tombstones only after the configured retention window and after every configured synchronization target has had an opportunity to observe them.

Object deletion is performed by prune after liveness is computed from retained snapshot records and active tombstones. Sync does not delete immutable objects solely because a destination inventory omits them.

## 8. Recovery Records

Recovery record sets are synchronized by recovery set ID.

Recovery sync rules:

- Transfer recovery manifests and recovery data files together.
- Verify that each recovery set references the expected repository ID.
- Report recovery sets that protect files no longer present after prune.
- Preserve stale recovery sets until recovery maintenance replaces or deletes them explicitly.

## 9. Trust and Encryption

Endpoint trust controls exposure policy, not repository identity.

For untrusted endpoints:

- Object payloads are encrypted when repository encryption is enabled.
- Snapshot manifests are encrypted in full repository encryption mode.
- Mutable snapshot records use the configured mutable record encryption mode.
- Backend providers are not trusted to enforce retention, deletion, or confidentiality.

Clients verify repository data after download regardless of endpoint trust level.

## 10. Failure Recovery

Synchronization is resumable.

Failure recovery rules:

- Incomplete endpoint temporary uploads are non-authoritative.
- Published immutable files are verified by identity before use.
- Published mutable records are verified by record ID and revision before use.
- Interrupted downloads remain in the local transaction workspace until local publication.
- Pull materialization uses local transaction rules from `docs/transactions.md`.

Sync repair may remove temporary endpoint uploads owned by the same client after confirming no active transfer uses them.
