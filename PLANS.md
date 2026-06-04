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
- Reserve a manifest model for chunked large objects.
- Support sparse restore and leave room for long-lived sparse checkout worktrees.
- Leave room for future remote repositories, chunk-level deduplication, and scheduled jobs.

## 2. Non-Goals

The first phase should not target these capabilities:

- GUI.
- Cloud sync or remote repositories.
- Filesystem-level consistency snapshots such as Windows VSS, btrfs, zfs, or lvm.
- Chunk-level deduplication.
- Long-lived sparse checkout state.
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

Metadata objects, such as snapshot manifests, should also use the object store, but their IDs must be domain-separated from file content objects. A snapshot object ID should be computed from its canonical typed metadata body, not from file content bytes.

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

Object metadata should be embedded in the object header by default, not stored as a separate sidecar file.

Reasons:

- Each object remains self-describing.
- Moving or copying an object cannot accidentally lose its metadata.
- `verify` can validate one physical file at a time.
- Recovery records can protect complete object files without pairing data and metadata files.
- Encryption can authenticate the object private header and payload together.
- Pruning does not need to reason about partially missing sidecar files.

Sidecar metadata would make raw payload files easier to represent, but it would double the number of repository files, complicate atomic writes, and make object repair and garbage collection more fragile.

The object header should be minimal and versioned. In an unencrypted repository, it should identify:

- Object format version.
- Object type.
- Content hash algorithm.
- Logical content hash.
- Logical content size.
- Payload compression algorithm.
- Payload size.

In an encrypted repository, public headers must not expose logical content hashes or other plaintext-derived metadata unless the selected encryption mode explicitly allows that leakage. The object format should support a public header plus an encrypted private header.

The payload may be stored uncompressed when compression is disabled for that object or when compression does not reduce size enough. The header must explicitly record that decision.

The repository should not hard link mutable source files directly into the object store. A hard link shares the same underlying file data; if the original file is modified in place later, the backup object would change as well. That violates snapshot immutability.

Future copy optimizations should prefer safe copy-on-write clone mechanisms, such as reflink or platform block cloning, when available. Any optimized copy path must still verify the resulting object before making it reachable.

The object path should be derived from the object ID:

```text
objects/
  sha256/
    ab/
      cd/
        abcdef...
```

For data objects, the object ID should be derived from the logical content hash. For metadata objects, the object ID should be derived from a canonical typed metadata encoding. This keeps deduplication and metadata integrity stable even if the physical storage format changes later.

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
object-envelope files + snapshot record files + config files -> recovery record set
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

Default placement should be inside the repository:

```text
repository/
  recovery/
    sets/
      <set-id>/
        manifest.json
        volumes/
          <set-id>.par2
          <set-id>.vol00+01.par2
```

This default placement helps with local bit rot, partial file corruption, accidental truncation, and damaged object files while keeping recovery records easy to manage with the repository.

Recovery records must not be stored under `objects` or `snapshots`, and they must not be represented as snapshot entries. They are repository maintenance data, not user data.

A recovery set must not protect its own PAR2 files. Otherwise, recovery generation becomes recursive and pruning becomes ambiguous. It may protect older recovery metadata only if a future format explicitly defines that behavior.

The tool should also support an external recovery output directory:

```text
kaca recovery create --repo <repository> --redundancy 10 --output <recovery-directory>
```

External recovery records are useful when the repository itself is stored on a disk or network location that may fail as a whole. The recovery set manifest should include the repository ID and protected file paths relative to the repository root so that external records can be matched back to the correct repository.

For encrypted repositories, recovery records should protect encrypted physical files. The PAR2 payload does not need the encryption key to repair damaged bytes. However, recovery set manifests and file names may still leak repository layout and object sizes, so a future encrypted recovery manifest mode may be needed.

Recovery records should be optional and generated explicitly at first. Automatic recovery record generation can be added later after pruning and retention semantics are stable.

### 3.6 Large Object Chunking

Large files should be representable as ordered chunk references in the snapshot manifest. A chunk should use the same object envelope format as a whole-file object.

Recommended logical pipeline for large files:

```text
large-file -> chunk -> chunk-hash -> compress chunk -> encrypt chunk -> chunk-object
```

The file-level manifest should preserve the complete logical file identity and the ordered chunk list:

```json
{
  "path": "images/disk.raw",
  "type": "file",
  "size": 107374182400,
  "modifiedAt": "2026-05-27T01:00:00Z",
  "object": {
    "kind": "chunked",
    "contentSize": 107374182400,
    "chunker": {
      "algorithm": "fastcdc",
      "minSize": 1048576,
      "avgSize": 4194304,
      "maxSize": 16777216
    },
    "chunks": [
      {
        "id": "sha256:...",
        "offset": 0,
        "contentSize": 4194304,
        "storedSize": 2097152,
        "compression": "zstd",
        "encryption": "none"
      }
    ]
  }
}
```

For unencrypted repositories, chunk IDs can be content hashes of logical chunk bytes. For encrypted repositories, chunk IDs should be keyed IDs derived from logical chunk hashes.

The preferred long-term chunking strategy is content-defined chunking, such as FastCDC, because it preserves deduplication when bytes are inserted or removed near the beginning of a large file. Fixed-size chunking is simpler but performs poorly after insertions or shifts.

Chunker parameters must be stored in the manifest or repository config. Changing chunker parameters changes deduplication behavior, so a repository should treat them as part of the object format profile.

Each chunk should be compressed and encrypted independently. Do not compress a whole large file as one stream before chunking, because that would destroy chunk-level deduplication and make partial repair less useful.

The first implementation may keep chunk objects as loose object files. A later pack format can group many small chunks into pack files without changing snapshot manifests, as long as chunk IDs remain stable.

### 3.7 Snapshot Metadata as Objects

Snapshot contents should be committed as typed metadata objects in the same repository object store used by file and chunk objects.

The `snapshots` directory should store mutable snapshot records, not full manifest bodies:

```text
snapshots/
  2026-05-27T01-30-00Z-<snapshot-id>.json
```

A snapshot record points to the actual immutable snapshot object and may also contain user-editable metadata:

```json
{
  "formatVersion": 1,
  "snapshotId": "sha256:...",
  "createdAt": "2026-05-27T01:30:00Z",
  "object": "sha256:...",
  "updatedAt": "2026-05-27T02:00:00Z",
  "title": "Before dependency upgrade",
  "notes": "User-editable notes.",
  "tags": ["manual", "important"],
  "pinned": true,
  "retention": {
    "keep": true
  }
}
```

The snapshot object payload contains the snapshot manifest. This gives snapshot metadata the same benefits as data objects:

- Compression.
- Optional encryption.
- Integrity checks.
- Recovery record protection.
- Atomic object writes.
- Uniform object reachability for `prune`.

Mutable snapshot records remain necessary because content-addressed objects alone are not discoverable roots. `prune` should treat snapshot records as reachability roots, then walk snapshot objects and their referenced data objects.

Only non-critical user metadata should be mutable. Restore-critical information must stay in the immutable snapshot object:

- Source identity.
- Parent snapshot object ID.
- File tree entries.
- Object references.
- Chunk lists.
- Required restore metadata.

Mutable records may include:

- User title.
- Notes.
- Tags.
- Pin or favorite state.
- Retention hints.
- Last viewed or UI state.

Updating mutable metadata must not create a new snapshot object and must not change the snapshot object ID.

The initial implementation can store one snapshot manifest as one snapshot object. A later tree-style metadata model can split very large manifests into reusable directory metadata objects without changing the snapshot record model.

### 3.8 Sparse Restore and Checkout

The snapshot model should support sparse restore: restoring only selected paths or path patterns from a snapshot.

MVP sparse behavior can be implemented by loading the snapshot manifest and filtering entries:

```text
kaca restore <snapshot-id> <target> --path docs/readme.md
kaca restore <snapshot-id> <target> --include "src/**" --exclude "build/**"
```

This is enough for correctness but may be inefficient for very large snapshots because the whole manifest must be read.

Efficient long-term sparse checkout needs tree-style metadata objects:

```text
snapshot object -> root tree object -> directory tree objects -> file object references
```

With tree objects, a restore operation for `src/main/**` only needs to load the relevant metadata branches and referenced data objects.

Long-lived sparse checkout should be treated as a separate feature from one-shot sparse restore. A checkout worktree may need mutable local state:

```text
.kaca-checkout.json
```

Possible future commands:

```text
kaca checkout <snapshot-id> <target> --sparse <spec>
kaca checkout add <path>
kaca checkout remove <path>
kaca checkout list
```

Sparse checkout state must not become a repository reachability root unless the user explicitly pins the snapshot. Otherwise, a local checkout could accidentally prevent expected pruning.

### 3.9 Atomic Writes

Snapshot creation must not leave behind a half-successful snapshot.

Recommended write order:

1. Write new content objects.
2. Write the snapshot manifest as a temporary snapshot object.
3. Verify that all objects referenced by the snapshot object exist.
4. Atomically move the snapshot object into the object store.
5. Atomically write the mutable snapshot record.
6. Update indexes.

If the process is interrupted, the repository should be able to detect and clean temporary files through `repair` or `verify`.

### 3.10 Verifiability

The repository must support integrity checks:

- Snapshot records are parseable and point to valid snapshot objects.
- Snapshot objects are parseable.
- Objects referenced by snapshot objects exist.
- Object headers are parseable and compatible with the repository format.
- Decompressed object content matches the logical content hash.
- Encrypted object payloads authenticate successfully before decompression.
- Chunked files restore to the expected file-level content hash.
- Sparse restore outputs only the selected paths and preserves their metadata.
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
    sha256/
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
- `objects` stores typed physical object envelopes keyed by object ID.
- `snapshots` stores mutable snapshot records that point to immutable snapshot metadata objects.
- `indexes` stores rebuildable indexes and must not be treated as the only source of truth.
- `recovery` stores optional recovery record sets.
- `tmp` stores incomplete temporary writes.

Indexes can be simple in the first phase. They should be rebuildable from snapshot records and objects when needed.

The initial object format should be:

```text
magic
objectFormatVersion
objectType
headerLength
header
payload
```

The exact binary encoding can be decided later, but the header must be small, deterministic, and independent of snapshot records. This allows individual object files to be verified even when indexes are missing.

## 5. Snapshot Object Draft

Each mutable snapshot record points to a snapshot object. The snapshot object payload describes one immutable snapshot:

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
- Whole-file object references.
- Chunked object references.

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

### 6.4 Chunker

Splits large files into stable logical chunks.

Suggested capabilities:

- Choose whether a file should be stored as a whole-file object or a chunked object.
- Produce deterministic chunk boundaries from file content.
- Record chunker parameters in manifests.
- Stream chunks without loading the whole file into memory.
- Support content-defined chunking in the long term.

### 6.5 ObjectStore

Writes, reads, and verifies typed repository objects.

Suggested capabilities:

- Check whether an object already exists by hash.
- Compress object payloads.
- Store an object uncompressed when compression does not reduce size enough.
- Write and parse object headers.
- Encrypt and decrypt object private headers and payloads when repository encryption is enabled.
- Store whole-file objects, chunk objects, and snapshot metadata objects through the same envelope format.
- Write objects through temporary files.
- Verify the logical content hash after writing.
- Authenticate encrypted payloads before decompression.
- Atomically move objects into the `objects` directory.
- Read and decompress objects for restore.

### 6.6 Crypto

Manages optional repository encryption.

Suggested capabilities:

- Derive repository keys.
- Derive independent subkeys for object IDs, object payloads, manifests, and metadata authentication.
- Encrypt and decrypt object private headers.
- Encrypt and decrypt object payloads.
- Encrypt and decrypt snapshot manifests when full repository encryption is enabled.
- Rotate or migrate encryption settings in a controlled future format upgrade.

### 6.7 SnapshotStore

Builds snapshot manifests, stores them as typed snapshot objects, and manages mutable snapshot records.

Suggested capabilities:

- Write a new snapshot object.
- Atomically write a snapshot record.
- Update mutable snapshot metadata without rewriting the snapshot object.
- List snapshot records.
- Read a specific snapshot through its record.
- Compare two snapshots.
- Rebuild the object reference index from snapshot records and snapshot objects.

### 6.8 Restore

Restores files from a snapshot.

Suggested capabilities:

- Restore a complete snapshot.
- Restore a single path.
- Restore selected paths by include and exclude patterns.
- Detect target path conflicts.
- Support dry run.
- Restore timestamps and basic permissions.
- Restore chunked files by streaming ordered chunk objects.

### 6.9 Verify

Checks repository integrity.

Suggested capabilities:

- Verify all snapshots.
- Verify all objects.
- Verify manifest references.
- Detect orphan objects.
- Detect corrupted objects.
- Verify chunk lists and chunk object references.
- Verify recovery record sets.

### 6.10 Prune

Deletes old snapshots according to retention policy and performs safe garbage collection.

Suggested capabilities:

- Delete a specific snapshot.
- Select snapshots by retention policy.
- Compute still-referenced objects.
- Remove unreferenced objects.
- Support dry run.

### 6.11 RecoveryStore

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
kaca restore <snapshot-id> <target> --repo <repository> --path <path>
kaca restore <snapshot-id> <target> --repo <repository> --include <pattern> --exclude <pattern>
kaca verify --repo <repository>
kaca prune --repo <repository> --keep-daily 7 --keep-weekly 4
```

Future commands:

```text
kaca init <repository> --encrypt
kaca stats --repo <repository>
kaca repair --repo <repository>
kaca recovery create --repo <repository> --redundancy 10
kaca recovery create --repo <repository> --redundancy 10 --output <recovery-directory>
kaca recovery verify --repo <repository>
kaca recovery repair --repo <repository>
kaca checkout <snapshot-id> <target> --repo <repository> --sparse <spec>
kaca checkout add <path>
kaca checkout remove <path>
kaca checkout list
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
- Manifest format extension points for chunked large objects.
- `init`.
- `snapshot`.
- `list`.
- `diff`.
- `restore`.
- Sparse restore for selected paths.
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

Chunk-level deduplication can be implemented after the whole-file object flow is correct, but the manifest schema should not need a breaking redesign to support it.

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

### 9.7 Chunker Parameter Drift

Changing chunker parameters can reduce deduplication efficiency or create confusing mixed behavior.

Safe strategy:

- Store chunker algorithm and parameters with every chunked file entry.
- Record the repository default chunker profile in `config.json`.
- Allow reading old chunker profiles indefinitely.
- Treat changes to default chunker parameters as an explicit repository configuration change.

### 9.8 Mutable Snapshot Record Loss

Mutable snapshot records are reachability roots. If a record is deleted or corrupted, the immutable snapshot object may still exist in the object store but become undiscoverable during normal listing.

Safe strategy:

- Protect snapshot record files with recovery records.
- Update snapshot records atomically.
- Keep mutable metadata separate from restore-critical snapshot object content.
- Make `verify` report orphan snapshot objects.
- Let `repair` rebuild a minimal snapshot record by scanning typed snapshot objects when possible.

### 9.9 Unsafe Hard Link Imports

Hard linking source files into the repository can corrupt backups because hard links share mutable file data.

Safe strategy:

- Do not hard link mutable source files into `objects`.
- Treat hard links as filesystem metadata to record and restore, not as a repository copy optimization.
- Prefer verified normal copies for the initial implementation.
- Consider reflink or platform copy-on-write clone support later.
- Verify object content before making any optimized copy reachable.

## 10. Test Plan

Basic test scenarios:

- Initialize an empty repository.
- Create a snapshot for an empty directory.
- Create a snapshot for a directory with multiple files.
- Verify that a snapshot record points to a valid snapshot object.
- Verify that a snapshot object can be read, decompressed, decrypted when needed, and parsed.
- Update snapshot notes or tags and verify that the snapshot object ID does not change.
- Delete a snapshot record and verify that the snapshot object is reported as an orphan.
- Modify a file and create a second snapshot.
- Delete a file and create a third snapshot.
- Compare two snapshots.
- Restore a complete snapshot.
- Restore a single file.
- Restore only selected paths and verify that unrelated paths are not materialized.
- Restore with include and exclude patterns.
- Verify that duplicate files store only one object.
- Verify that compressed objects restore to the original content.
- Verify that object headers and payloads are checked by `verify`.
- Verify that objects remain self-describing without sidecar metadata files.
- Verify encrypted objects authenticate, decrypt, decompress, and restore to the original content.
- Verify that wrong keys fail before decompression or restore.
- Verify that recovery records detect physical file corruption.
- Verify that recovery records can repair a damaged protected file when enough redundancy is available.
- Verify that external recovery records can be matched to the correct repository by repository ID.
- Create a chunked large-file snapshot and restore it byte-for-byte.
- Modify a large file in the middle and verify that unchanged chunks are reused.
- Insert bytes near the beginning of a large file and compare fixed-size chunking with content-defined chunking.
- Manually corrupt an object and confirm that `verify` detects it.
- Delete an old snapshot without deleting objects that are still referenced.
- Confirm that interrupted backups do not pollute the official repository state with temporary files.

## 11. Open Research Questions

- Should the default hash algorithm be `SHA-256` or `BLAKE3`?
- Should manifests use JSON, CBOR, MessagePack, or a custom binary format?
- What canonical encoding should be used for metadata object IDs?
- Should very large snapshot manifests become tree-style metadata objects?
- Which compression library and default compression level should be used?
- What minimum size or compression ratio should decide whether an object is stored compressed or raw?
- Should future platforms support reflink or copy-on-write clone optimization for raw object payloads?
- Which AEAD algorithm should be used for object encryption?
- Should encrypted repositories always encrypt snapshot manifests?
- Should mutable snapshot records be encrypted in encrypted repositories?
- How should password-based key derivation be configured?
- Should recovery records use an external PAR2 implementation or an internal Reed-Solomon implementation?
- What is the default redundancy percentage for recovery records?
- Should recovery records protect every snapshot immediately or be generated in batches?
- Should encrypted repositories encrypt recovery set manifests when recovery records are stored externally?
- Should large file chunking use fixed-size chunks or rolling hash?
- What large-file threshold should enable chunking by default?
- Should loose chunk objects be packed after snapshot creation?
- What pattern syntax should sparse restore use?
- Should long-lived sparse checkout state be stored in the target directory or in the repository?
- Is a database index such as SQLite needed?
- Should Windows ACLs and Unix permissions be included in the first version?
- Should exclude rules be compatible with `.gitignore` syntax?
- Should restore overwrite target files by default?
- Should `snapshot` fail, skip, or produce a partial snapshot when it sees unstable files?

## 12. Recommended Sequence

1. Define the repository format, typed object envelope format, mutable snapshot record format, encryption extension points, recovery record layout, and manifest schema.
2. Implement `Repository` and compressed `ObjectStore`.
3. Implement `Scanner` and file-level `Hasher`.
4. Implement snapshot object writing and mutable snapshot records.
5. Implement `list` and `show`.
6. Implement `restore`, including sparse restore.
7. Implement `diff`.
8. Implement `verify`.
9. Add tests for crash recovery and integrity scenarios.
10. Implement optional encryption.
11. Implement optional recovery record creation, verification, and repair.
12. Implement chunked large-object storage.
13. Decide whether to add object pack files for chunk-heavy repositories.
