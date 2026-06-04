# Incremental Backup Architecture Plan

This document records the initial architecture plan for `kaca`, an incremental backup tool for directory snapshots. The current phase focuses on architecture boundaries, data models, repository format, and the minimum useful feature set. The plan can be refined step by step as the design becomes clearer.

## 1. Design Goals

- Create restorable snapshots for a directory.
- Reuse identical content across snapshots to avoid duplicate storage.
- Treat every snapshot as a complete directory view, not as a patch chain that must be replayed from the beginning.
- Keep the backup repository verifiable, restorable, and safely cleanable.
- Prioritize data reliability first, then optimize speed, storage efficiency, and user experience.
- Leave room for future remote repositories, compression, encryption, chunk-level deduplication, and scheduled jobs.

## 2. Non-Goals

The first phase should not target these capabilities:

- GUI.
- Cloud sync or remote repositories.
- Filesystem-level consistency snapshots such as Windows VSS, btrfs, zfs, or lvm.
- Chunk-level deduplication.
- Multiple clients writing to the same repository concurrently.
- Complex schedulers or background services.

These capabilities can be added after the core repository format and restore flow are stable.

## 3. Core Principles

### 3.1 Snapshots Are Complete Views

Each snapshot should fully describe the source directory state at a point in time, including files, directories, symbolic links, and required metadata.

Restoring an arbitrary snapshot should not require replaying all previous snapshots.

### 3.2 Content-Addressed Storage

File content is stored in the object store by content hash. If multiple snapshots reference the same content, the repository stores that content only once.

The initial version should use file-level deduplication:

- Hash small and regular files as whole files.
- If the object already exists, reference it from the manifest.
- If the object does not exist, write it into the object store.

A chunk model can be introduced later for large files.

### 3.3 Atomic Writes

Snapshot creation must not leave behind a half-successful snapshot.

Recommended write order:

1. Write new content objects.
2. Write a temporary snapshot manifest.
3. Verify that all objects referenced by the temporary manifest exist.
4. Atomically move the temporary manifest into the final snapshot location.
5. Update indexes.

If the process is interrupted, the repository should be able to detect and clean temporary files through `repair` or `verify`.

### 3.4 Verifiability

The repository must support integrity checks:

- Snapshot manifests are parseable.
- Objects referenced by manifests exist.
- Object hashes match object content.
- Indexes can be rebuilt from snapshots and objects.
- Deleting old snapshots does not delete objects that are still referenced.

## 4. Repository Format Draft

A local repository can use this layout:

```text
repository/
  config.json
  lock
  objects/
    ab/
      cd/
        abcdef...
  snapshots/
    2026-05-27T01-30-00Z-<id>.json
  indexes/
    file-cache.json
    object-refcount.json
  tmp/
```

Notes:

- `config.json` stores the repository version, hash algorithm, compression algorithm, encryption settings, and creation time.
- `lock` prevents multiple processes from writing to the repository at the same time.
- `objects` stores content objects.
- `snapshots` stores snapshot manifests.
- `indexes` stores rebuildable indexes and must not be treated as the only source of truth.
- `tmp` stores incomplete temporary writes.

Indexes can be simple in the first phase. They should be rebuildable from all snapshots when needed.

## 5. Snapshot Manifest Draft

Each snapshot manifest describes one snapshot:

```json
{
  "formatVersion": 1,
  "id": "snapshot-id",
  "createdAt": "2026-05-27T01:30:00Z",
  "source": {
    "path": "D:\\Projects\\example"
  },
  "parent": "previous-snapshot-id",
  "entries": [
    {
      "path": "docs/readme.md",
      "type": "file",
      "size": 1024,
      "modifiedAt": "2026-05-27T01:00:00Z",
      "object": {
        "hash": "sha256:...",
        "size": 1024
      }
    }
  ]
}
```

Fields to define later:

- Directory entries.
- Symbolic link entries.
- File permissions.
- Windows file attributes.
- Unix mode, uid, and gid.
- Extended attributes.
- ACLs.
- Case sensitivity policy.

## 6. Main Modules

### 6.1 Repository

Manages repository initialization, configuration loading, locking, path layout, and format compatibility.

Suggested capabilities:

- `init`
- `open`
- `lock`
- `unlock`
- `checkFormatVersion`

### 6.2 Scanner

Scans the source directory and produces candidate entries.

Suggested capabilities:

- Walk directories.
- Apply exclude rules.
- Handle symbolic link policy.
- Capture basic file metadata.
- Detect files that change during scanning.

### 6.3 Hasher

Computes file hashes.

Suggested capabilities:

- Stream-based hashing.
- Future hash algorithm switching.
- Progress reporting for large files.
- Reserved interfaces for chunk hashing.

### 6.4 ObjectStore

Writes, reads, and verifies content objects.

Suggested capabilities:

- Check whether an object already exists by hash.
- Write objects through temporary files.
- Verify the hash after writing.
- Atomically move objects into the `objects` directory.
- Read objects for restore.

### 6.5 SnapshotStore

Writes, reads, lists, and verifies snapshot manifests.

Suggested capabilities:

- Write a new snapshot.
- List snapshots.
- Read a specific snapshot.
- Compare two snapshots.
- Rebuild the object reference index from snapshots.

### 6.6 Restore

Restores files from a snapshot.

Suggested capabilities:

- Restore a complete snapshot.
- Restore a single path.
- Detect target path conflicts.
- Support dry run.
- Restore timestamps and basic permissions.

### 6.7 Verify

Checks repository integrity.

Suggested capabilities:

- Verify all snapshots.
- Verify all objects.
- Verify manifest references.
- Detect orphan objects.
- Detect corrupted objects.

### 6.8 Prune

Deletes old snapshots according to retention policy and performs safe garbage collection.

Suggested capabilities:

- Delete a specific snapshot.
- Select snapshots by retention policy.
- Compute still-referenced objects.
- Remove unreferenced objects.
- Support dry run.

## 7. CLI Draft

The first phase should provide a CLI:

```text
kaca init <repository>
kaca snapshot <source> --repo <repository>
kaca list --repo <repository>
kaca show <snapshot-id> --repo <repository>
kaca diff <from-snapshot-id> <to-snapshot-id> --repo <repository>
kaca restore <snapshot-id> <target> --repo <repository>
kaca verify --repo <repository>
kaca prune --repo <repository> --keep-daily 7 --keep-weekly 4
```

Future commands:

```text
kaca stats --repo <repository>
kaca repair --repo <repository>
kaca mount <snapshot-id> --repo <repository>
kaca serve --repo <repository>
```

## 8. MVP Scope

The first useful version should include:

- Local repository.
- File-level deduplication.
- `init`.
- `snapshot`.
- `list`.
- `diff`.
- `restore`.
- `verify`.
- Basic exclude rules.
- Atomic writes.
- Repository lock.
- Basic test coverage.

The MVP should not include:

- Compression.
- Encryption.
- Chunk-level deduplication.
- Remote repositories.
- Background scheduling.
- GUI.

## 9. Key Risks

### 9.1 Source Files Change During Backup

Initial strategy:

- Record size and modified time before reading a file.
- Read the file.
- Record size and modified time again after reading.
- If the file changed, retry once.
- If the file remains unstable, record it as a failed entry and make the `snapshot` command return a non-zero status or explicit warning.

Filesystem-level consistency snapshots can be supported later.

### 9.2 Pruning Deletes Referenced Objects

`prune` should not rely on old indexes to delete objects directly.

Safe strategy:

- Rebuild the reference set from all retained snapshots.
- Delete only objects that are not in the reference set.
- Default to dry run first.
- Write a prune transaction before actual deletion.

### 9.3 Repository Format Upgrades

Both `config.json` and manifest files must record `formatVersion`.

When a newer program opens an older repository, it must clearly decide:

- Whether the repository is readable.
- Whether migration is required.
- Whether writing should be blocked.

### 9.4 Cross-Platform Path Differences

The internal path format should be defined early:

- Use `/` as the path separator inside manifests.
- Do not store absolute paths in entry paths.
- Keep the source directory path only for display and audit.
- Define the policy for Windows case-insensitive paths and Linux case-sensitive paths.

## 10. Test Plan

Basic test scenarios:

- Initialize an empty repository.
- Create a snapshot for an empty directory.
- Create a snapshot for a directory with multiple files.
- Modify a file and create a second snapshot.
- Delete a file and create a third snapshot.
- Compare two snapshots.
- Restore a complete snapshot.
- Restore a single file.
- Verify that duplicate files store only one object.
- Manually corrupt an object and confirm that `verify` detects it.
- Delete an old snapshot without deleting objects that are still referenced.
- Confirm that interrupted backups do not pollute the official repository state with temporary files.

## 11. Open Research Questions

- Should the default hash algorithm be `SHA-256` or `BLAKE3`?
- Should manifests use JSON, CBOR, MessagePack, or a custom binary format?
- Should compression happen at the object layer or the chunk layer?
- How should encryption interact with content deduplication?
- Should large file chunking use fixed-size chunks or rolling hash?
- Is a database index such as SQLite needed?
- Should Windows ACLs and Unix permissions be included in the first version?
- Should exclude rules be compatible with `.gitignore` syntax?
- Should restore overwrite target files by default?
- Should `snapshot` fail, skip, or produce a partial snapshot when it sees unstable files?

## 12. Recommended Sequence

1. Define the repository format and manifest schema.
2. Implement `Repository` and `ObjectStore`.
3. Implement `Scanner` and file-level `Hasher`.
4. Implement the `snapshot` write flow.
5. Implement `list` and `show`.
6. Implement `restore`.
7. Implement `diff`.
8. Implement `verify`.
9. Add tests for crash recovery and integrity scenarios.
10. Decide whether to add compression, encryption, and chunk-level deduplication.
