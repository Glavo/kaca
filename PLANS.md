# Incremental Backup Architecture Plan

This document records the initial architecture plan for `kaca`, an incremental backup tool for directory snapshots. The current phase focuses on architecture boundaries, data models, repository format, and the minimum useful feature set. The plan can be refined step by step as the design becomes clearer.

## 1. Design Goals

- Create restorable snapshots for a directory.
- Reuse identical content across snapshots to avoid duplicate storage.
- Treat every snapshot as a complete directory view, not as a patch chain that must be replayed from the beginning.
- Keep the backup repository verifiable, restorable, and safely cleanable.
- Prioritize data reliability first, then optimize speed, storage efficiency, and user experience.
- Use compressed object storage as part of the initial repository format.
- Treat optional encryption and recovery records as first-class architecture concerns.
- Leave room for future remote repositories, chunk-level deduplication, and scheduled jobs.

## 2. Non-Goals

The first phase should not target these capabilities:

- GUI.
- Cloud sync or remote repositories.
- Filesystem-level consistency snapshots such as Windows VSS, btrfs, zfs, or lvm.
- Chunk-level deduplication.
- Multiple clients writing to the same repository concurrently.
- Complex schedulers or background services.

These capabilities can be added after the core repository format and restore flow are stable. Encryption and recovery records do not need to block the first usable snapshot flow, but the initial repository and object formats must reserve explicit extension points for them.

## 3. Core Principles

### 3.1 Snapshots Are Complete Views

Each snapshot should fully describe the source directory state at a point in time, including files, directories, symbolic links, and required metadata.

Restoring an arbitrary snapshot should not require replaying all previous snapshots.

### 3.2 Content-Addressed Storage

File content is stored in the object store by content hash. If multiple snapshots reference the same content, the repository stores that content only once.

The object identity is based on the logical uncompressed content. Physical object files may be compressed, but deduplication should still be based on the original content bytes.

The initial version should use file-level deduplication:

- Hash small and regular files as whole files.
- If the object already exists, reference it from the manifest.
- If the object does not exist, write it into the object store.

A chunk model can be introduced later for large files.

### 3.3 Compressed Object Envelope

Because object compression is a core feature, the initial physical object format should be an envelope instead of raw file bytes.

The object file should contain:

```text
object-file := object-header + payload
payload := raw-content | compressed-content
```

The object header should be minimal and versioned. In an unencrypted repository, it should identify:

- Object format version.
- Content hash algorithm.
- Logical content hash.
- Logical content size.
- Payload compression algorithm.
- Payload size.

In an encrypted repository, public headers must not expose logical content hashes or other plaintext-derived metadata unless the selected encryption mode explicitly allows that leakage. The object format should support a public header plus an encrypted private header.

The payload may be stored uncompressed when compression is disabled for that object or when compression does not reduce size enough. The header must explicitly record that decision.

The object path should still be derived from the logical content hash:

```text
objects/
  sha256/
    ab/
      cd/
        abcdef...
```

This keeps deduplication stable even if the physical storage format changes later.

### 3.4 Optional Encryption

Encryption should be modeled as an optional object pipeline stage:

```text
logical-content -> hash -> compress -> encrypt -> object-envelope -> object-file
```

Compression must happen before encryption because encrypted payloads are not meaningfully compressible.

The recommended encrypted repository model is:

- Use a repository master key derived from a password or imported from a key file.
- Derive separate subkeys for object IDs, object encryption, manifest encryption, and metadata authentication.
- Use AEAD encryption for object payloads and encrypted private headers.
- Use random nonces for physical encryption.
- Use keyed object IDs, such as `HMAC(content-id-key, logical-content-hash)`, so deduplication still works inside the repository without exposing raw plaintext hashes in file names.

Encryption should be repository-wide once enabled. Mixing encrypted and unencrypted snapshots in the same repository should be avoided unless there is a strong migration use case.

Manifest encryption needs a separate decision. Encrypting only object payloads still leaks paths, file sizes, timestamps, and directory structure through snapshot manifests. A privacy-focused encrypted repository should encrypt snapshot manifests as well.

### 3.5 Recovery Records

Recovery records should protect the physical repository bytes, not the logical file model.

The recovery layer should operate after compression and encryption:

```text
object-envelope files + snapshot files + config files -> recovery record set
```

This allows recovery tools to repair corrupted encrypted objects without needing the encryption key.

The first design should support PAR2-style recovery sets:

```text
recovery/
  sets/
    <set-id>/
      manifest.json
      volumes/
        <set-id>.par2
        <set-id>.vol00+01.par2
```

Each recovery set should record:

- Protected repository files.
- File sizes.
- File hashes.
- Recovery algorithm.
- Redundancy percentage.
- Creation time.

Recovery records should be optional and generated explicitly at first. Automatic recovery record generation can be added later after pruning and retention semantics are stable.

### 3.6 Atomic Writes

Snapshot creation must not leave behind a half-successful snapshot.

Recommended write order:

1. Write new content objects.
2. Write a temporary snapshot manifest.
3. Verify that all objects referenced by the temporary manifest exist.
4. Atomically move the temporary manifest into the final snapshot location.
5. Update indexes.

If the process is interrupted, the repository should be able to detect and clean temporary files through `repair` or `verify`.

### 3.7 Verifiability

The repository must support integrity checks:

- Snapshot manifests are parseable.
- Objects referenced by manifests exist.
- Object headers are parseable and compatible with the repository format.
- Decompressed object content matches the logical content hash.
- Encrypted object payloads authenticate successfully before decompression.
- Recovery records match the physical repository files they protect.
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
  recovery/
    sets/
  tmp/
```

Notes:

- `config.json` stores the repository version, object format version, hash algorithm, default compression profile, encryption mode, recovery record settings, and creation time.
- `lock` prevents multiple processes from writing to the repository at the same time.
- `objects` stores physical object envelopes keyed by logical content hash.
- `snapshots` stores snapshot manifests.
- `indexes` stores rebuildable indexes and must not be treated as the only source of truth.
- `recovery` stores optional recovery record sets.
- `tmp` stores incomplete temporary writes.

Indexes can be simple in the first phase. They should be rebuildable from all snapshots when needed.

The initial object format should be:

```text
magic
objectFormatVersion
headerLength
header
payload
```

The exact binary encoding can be decided later, but the header must be small, deterministic, and independent of snapshot manifests. This allows individual object files to be verified even when indexes are missing.

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
        "id": "sha256:...",
        "contentSize": 1024,
        "storedSize": 512,
        "compression": "zstd",
        "encryption": "none"
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
- Compress object payloads.
- Store an object uncompressed when compression does not reduce size enough.
- Write and parse object headers.
- Encrypt and decrypt object private headers and payloads when repository encryption is enabled.
- Write objects through temporary files.
- Verify the logical content hash after writing.
- Authenticate encrypted payloads before decompression.
- Atomically move objects into the `objects` directory.
- Read and decompress objects for restore.

### 6.5 Crypto

Manages optional repository encryption.

Suggested capabilities:

- Derive repository keys.
- Derive independent subkeys for object IDs, object payloads, manifests, and metadata authentication.
- Encrypt and decrypt object private headers.
- Encrypt and decrypt object payloads.
- Encrypt and decrypt snapshot manifests when full repository encryption is enabled.
- Rotate or migrate encryption settings in a controlled future format upgrade.

### 6.6 SnapshotStore

Writes, reads, lists, and verifies snapshot manifests.

Suggested capabilities:

- Write a new snapshot.
- List snapshots.
- Read a specific snapshot.
- Compare two snapshots.
- Rebuild the object reference index from snapshots.

### 6.7 Restore

Restores files from a snapshot.

Suggested capabilities:

- Restore a complete snapshot.
- Restore a single path.
- Detect target path conflicts.
- Support dry run.
- Restore timestamps and basic permissions.

### 6.8 Verify

Checks repository integrity.

Suggested capabilities:

- Verify all snapshots.
- Verify all objects.
- Verify manifest references.
- Detect orphan objects.
- Detect corrupted objects.
- Verify recovery record sets.

### 6.9 Prune

Deletes old snapshots according to retention policy and performs safe garbage collection.

Suggested capabilities:

- Delete a specific snapshot.
- Select snapshots by retention policy.
- Compute still-referenced objects.
- Remove unreferenced objects.
- Support dry run.

### 6.10 RecoveryStore

Manages optional recovery records for repository files.

Suggested capabilities:

- Create recovery sets for selected repository files.
- Verify recovery sets.
- Repair damaged physical files when enough recovery data is available.
- Track which snapshots, objects, and metadata files are protected by each recovery set.
- Coordinate with `prune` so recovery records do not reference deleted repository files indefinitely.

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
kaca init <repository> --encrypt
kaca stats --repo <repository>
kaca repair --repo <repository>
kaca recovery create --repo <repository> --redundancy 10
kaca recovery verify --repo <repository>
kaca recovery repair --repo <repository>
kaca mount <snapshot-id> --repo <repository>
kaca serve --repo <repository>
```

## 8. MVP Scope

The first useful version should include:

- Local repository.
- File-level deduplication.
- Object compression.
- Versioned object envelope format.
- Repository format extension points for optional encryption.
- Repository format extension points for optional recovery records.
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

- Mandatory encryption.
- Automated recovery record scheduling.
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

### 9.5 Encryption Metadata Leakage

Encrypted object payloads alone do not hide all sensitive information.

Risk areas:

- Object file names can leak content identity if raw content hashes are used.
- Snapshot manifests can leak paths, sizes, timestamps, and directory structure.
- Recovery records can leak physical file names and sizes.

Safer strategy:

- Use keyed object IDs for encrypted repositories.
- Encrypt snapshot manifest payloads in full repository encryption mode.
- Keep recovery records over physical encrypted files, but document what metadata remains visible.

### 9.6 Recovery Records and Pruning

Recovery sets can become stale when snapshots and objects are pruned.

Safe strategy:

- Treat recovery records as protection for a known physical file set.
- Make `prune` report which recovery sets become stale.
- Prefer rebuilding affected recovery sets instead of mutating them in place.
- Never let recovery records act as the source of truth for object liveness.

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
- Verify that compressed objects restore to the original content.
- Verify that object headers and payloads are checked by `verify`.
- Verify encrypted objects authenticate, decrypt, decompress, and restore to the original content.
- Verify that wrong keys fail before decompression or restore.
- Verify that recovery records detect physical file corruption.
- Verify that recovery records can repair a damaged protected file when enough redundancy is available.
- Manually corrupt an object and confirm that `verify` detects it.
- Delete an old snapshot without deleting objects that are still referenced.
- Confirm that interrupted backups do not pollute the official repository state with temporary files.

## 11. Open Research Questions

- Should the default hash algorithm be `SHA-256` or `BLAKE3`?
- Should manifests use JSON, CBOR, MessagePack, or a custom binary format?
- Which compression library and default compression level should be used?
- What minimum size or compression ratio should decide whether an object is stored compressed or raw?
- Which AEAD algorithm should be used for object encryption?
- Should encrypted repositories always encrypt snapshot manifests?
- How should password-based key derivation be configured?
- Should recovery records use an external PAR2 implementation or an internal Reed-Solomon implementation?
- What is the default redundancy percentage for recovery records?
- Should recovery records protect every snapshot immediately or be generated in batches?
- Should large file chunking use fixed-size chunks or rolling hash?
- Is a database index such as SQLite needed?
- Should Windows ACLs and Unix permissions be included in the first version?
- Should exclude rules be compatible with `.gitignore` syntax?
- Should restore overwrite target files by default?
- Should `snapshot` fail, skip, or produce a partial snapshot when it sees unstable files?

## 12. Recommended Sequence

1. Define the repository format, object envelope format, encryption extension points, recovery record layout, and manifest schema.
2. Implement `Repository` and compressed `ObjectStore`.
3. Implement `Scanner` and file-level `Hasher`.
4. Implement the `snapshot` write flow.
5. Implement `list` and `show`.
6. Implement `restore`.
7. Implement `diff`.
8. Implement `verify`.
9. Add tests for crash recovery and integrity scenarios.
10. Implement optional encryption.
11. Implement optional recovery record creation, verification, and repair.
12. Decide whether to add chunk-level deduplication.
