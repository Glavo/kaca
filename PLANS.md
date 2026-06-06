# Incremental Backup Architecture Plan

This document describes the architecture plan for `kaca`, an incremental backup tool for directory snapshots. It defines architecture boundaries, data models, repository format, synchronization behavior, integrity guarantees, and implementation sequencing.

## 1. Design Goals

- Create restorable snapshots for one or more directories.
- Reuse identical content across snapshots to reduce duplicate storage.
- Treat every snapshot as a complete multi-root directory view with direct restore semantics.
- Keep the backup repository verifiable, restorable, and safely cleanable.
- Prioritize data reliability first, then optimize speed, storage efficiency, and user experience.
- Use compressed object storage as part of the initial repository format.
- Treat optional encryption and recovery records as first-class architecture concerns.
- Reserve a manifest model for chunked large objects.
- Support sparse restore and leave room for long-lived sparse checkout worktrees.
- Support repository storage backends that are independent from source file locations.
- Support single-file repository archive and bundle formats for migration and portable storage.
- Include repository storage backends, synchronization, chunk-level deduplication, and scheduled jobs in the initial architecture scope.

## 2. Architecture Scope and Implementation Phasing

The architecture should account for the full product shape from the beginning, even when implementation is delivered in smaller slices.

Architecture scope includes:

- Repository storage backends.
- Source storage backends.
- Remote synchronization between repository backends.
- Single-file repository archive and bundle formats.
- Immutable object storage.
- Mutable snapshot records.
- Compression.
- Optional encryption.
- Recovery records.
- Chunked large-object storage.
- Sparse restore and sparse checkout.
- Filesystem metadata capture and restore profiles.
- Retention and pruning.
- Repository verification and repair.
- Internal binary `repository` file.
- Layered user-editable configuration.
- Scheduling and background operation.
- Filesystem-level consistency integrations.
- GUI and service interfaces.
- Multi-client conflict handling.

Implementation can still be staged. The plan must distinguish architecture coverage from implementation order.

### 2.1 Repository Metadata and Layered Configuration

Repository metadata and user-editable configuration are separate concerns.

`repository` is internal binary metadata managed by the program. It stores stable repository identity and format data:

- Repository ID.
- Repository format version.
- Object format version.
- Metadata encoding.
- Hash algorithm.
- Canonical compression profile.
- Object layout.
- Encryption mode.
- Public encryption and key derivation parameters.
- Creation time.

Configuration files use a layered model:

```text
command invocation overrides
job configuration
repository-local client configuration
repository configuration
user configuration
system configuration
built-in defaults
```

Repository configuration is stored in `config.toml`. It contains portable repository policy:

- Retention defaults.
- Recovery record defaults.
- Filesystem metadata capture default.
- Extension policy.

Repository-local client configuration is stored in `local/config.toml`. It contains machine-specific and client-specific defaults:

- Remotes.
- Credential references.
- Restore defaults.
- UI preferences.
- Service settings.

Job configuration is stored in `jobs/*.toml` or a user-level job directory. It contains repeatable snapshot task definitions:

- Source roots.
- Schedule expressions.
- Include and exclude patterns.
- Per-job metadata capture override.

Changing `config.toml` preserves existing object identity and repository format. Changes that affect immutable repository structure require an explicit repository migration.

Concrete repository binary formats are defined in `docs/repository-format.md`. Snapshot and tree semantics are defined in `docs/snapshot-model.md`. Storage backend rules are defined in `docs/storage-backends.md`. The layered configuration schema is defined in `docs/configuration.md`. Repository transaction and crash recovery rules are defined in `docs/transactions.md`. Remote synchronization rules are defined in `docs/synchronization.md`. Recovery record rules are defined in `docs/recovery.md`.

### 2.2 Storage Backend Model

The architecture separates source access from repository storage:

```text
SourceStore -> scanner/hash/chunker -> RepositoryStore
```

Source files and repository storage may be located on different devices, hosts, or services. Snapshot creation may execute near the source, near the repository, or through a controller coordinating source agents and repository backends.

RepositoryStore backends include file-tree repositories, remote tree/object repositories, single-file archives, and append-oriented bundle files. The logical repository model is independent from the physical RepositoryStore backend.

## 3. Core Principles

### 3.1 Snapshots Are Complete Multi-Root Views

Each snapshot should fully describe the source root set at a point in time. A source root represents one tracked directory or regular file with a stable root ID, a display name, a captured source path, a source root kind, source-specific filters, and required metadata.

Restoring an arbitrary snapshot uses the snapshot's own complete root set. Restore commands can restore every root, a selected root, or selected paths inside selected roots.

Snapshot-relative paths are scoped by root ID:

```text
<root-id>/<relative-path>
```

The captured source path is display and audit metadata. Object identity and snapshot path identity use root IDs and relative paths.

Root IDs match `[a-z][a-z0-9._-]{0,63}` and are unique within a snapshot. Overlapping source paths are valid and remain separate logical roots.

### 3.2 Content-Addressed Storage

File content is stored in the object store by content hash. If multiple snapshots reference the same content, the repository stores that content only once.

The object identity is based on the logical uncompressed content. Physical object files may be compressed, but deduplication should still be based on the original content bytes.

Metadata objects, such as snapshot manifests and tree manifests, should also use the same object store. Object IDs are based on canonical logical bytes. The object store is untyped; semantic meaning is provided by typed references and self-describing payload formats.

The object identity hash input is exactly `canonicalLogicalBytes`. Structured metadata payloads contain their own magic and format version so that semantic verification can reject incorrectly interpreted bytes.

Conceptually:

```text
objectId = hash(canonicalLogicalBytes)
```

For unencrypted repositories, `objectId` and `contentHash` are the same value. For encrypted repositories, `contentHash` may exist only inside encrypted metadata, while the public `objectId` is keyed.

Identity fields:

```text
contentHash = hash(canonicalLogicalBytes)
objectId = contentHash
```

Typed references belong to the semantic layer:

```text
blobRef = objectId + logicalSize
snapshotRef = objectId + expectedFormat("kaca-snapshot-v1")
treeRef = objectId + expectedFormat("kaca-tree-v1")
```

The object store stores immutable bytes by `objectId`. Structured data is interpreted through an expected payload format, verified payload magic, and verified payload version.

`logicalSize` is validation metadata. It is stored and checked outside the lookup key.

#### Object Pool Layout

The repository exposes one logical `ObjectStore` API and one untyped physical object pool.

Physical layout:

```text
objects/
  ab/
    <full-object-id>
```

The object file name is the complete object ID. The fanout directory repeats the first two hex characters for directory distribution.

Object paths use fixed one-level fanout:

```text
objects/<first-two-hex>/<full-object-id>
```

The path builder is centralized. Fanout is fixed by the repository format.

The object pool stores snapshot payloads, tree payloads, chunk bytes, and whole-file bytes under the same physical layout. All immutable payloads use the same identity, envelope, verification, synchronization, recovery, packing, and pruning rules.

The semantic layer must distinguish how object bytes are interpreted:

```text
snapshot record -> snapshotRef -> snapshot payload
snapshot payload -> root entries -> treeRef -> tree payload
tree payload -> blobRef or chunk list -> file bytes
```

The unified object pool has the following required properties:

- All immutable bytes use the same lookup path.
- Whole-file blobs, chunks, and structured metadata payloads share repository-wide byte-level deduplication.
- Pack, sync, prune, verify, recovery, and repository migration operate on object IDs across the unified pool.
- Public object paths omit semantic object classes.

Semantic validation must happen above the object store:

- References must carry the expected semantic format when the target object is structured metadata.
- Payload parsers must reject mismatched magic values, unsupported versions, and malformed canonical encodings.
- Semantic verification must traverse every snapshot root and parse expected formats.
- Type-specific maintenance must use indexes or semantic scans.

Structured payload formats must be self-describing:

```text
kaca-snapshot-v1 := magic + version + canonical-binary-snapshot-body
kaca-tree-v1 := magic + version + canonical-binary-tree-body
```

#### Pack Files

Loose object files are repository-owned staging files for newly imported objects, repair output, and simple local operation. Pack files are the optimized physical layout for large repositories and remote synchronization. They group many immutable object envelopes into larger immutable physical files.

Pack layout:

```text
packs/
  <pack-id>.pack
  <pack-id>.idx
```

A pack file stores object records. The pack index maps each object ID to its physical location:

```text
objectId -> packId + recordOffset + envelopeOffset + envelopeLength
```

Each pack record stores the same object envelope bytes used by loose objects:

```text
pack record := record-header + object-id + object-envelope
```

Object verification, compression, encryption, and metadata parsing are identical for loose objects and packed objects.

The pack ID is a digest value for the physical pack records. The pack file name and pack index file name use lowercase hexadecimal encoding of the complete pack ID digest value. Pack IDs identify transfer units, while object IDs remain the authoritative identity for repository contents.

Pack files are immutable. New objects are written as loose objects or into new pack files. Pack replacement is performed by writing replacement packs and publishing them atomically. Repository correctness does not depend on mutable pack state.

Pack indexes are sidecar lookup structures. A pack index is required for normal random access and remote inventory exchange, but it is rebuildable from the pack file. Verification must detect missing, stale, corrupt, or mismatched pack indexes.

Pack creation flow:

1. Select loose objects to pack.
2. Write a temporary pack file.
3. Compute the pack ID from the completed pack records.
4. Write a temporary pack index.
5. Verify every indexed object can be read from the pack.
6. Atomically publish the pack and index.
7. Remove packed loose objects only after verification succeeds.

Pruning packed objects requires repacking. A prune operation computes live objects, writes new packs containing live objects, then removes obsolete packs after verification.

Remote synchronization treats packs as immutable transfer units with resumable upload and verification. A remote inventory can advertise pack IDs, pack sizes, and pack index summaries. Missing logical objects are resolved through pack indexes before transferring whole packs or selected loose objects.

Recovery records protect pack files and pack indexes as physical repository files. A repaired pack index may also be rebuilt from a verified pack file.

#### Hash Collision Policy

The repository defines how object identity is verified and how collision-like inconsistencies are handled.

Rules:

- Use only cryptographic hashes for object identity.
- Record the hash algorithm in the `repository` file.
- Use canonical logical bytes as the object hash input.
- Store logical content size in the object header.
- Treat object equality as byte identity by object ID.
- Recompute hashes during `verify`.
- Treat rolling hashes used by chunkers as boundary detectors only, never as object identity.
- Never silently accept two different logical payloads with the same object ID.

The logical object identity should be:

```text
objectId = hash(canonicalLogicalBytes)
```

Two objects are the same logical object when they have the same object ID and the same canonical logical bytes. If the same object ID resolves to different bytes, the repository must treat it as a collision or corruption event.

Compression algorithm, encryption mode, payload size, and storage location are physical storage properties outside logical object equality.

Typed references provide semantic validation after object lookup:

- Fast mismatch detection.
- Stronger type-safety.
- Better parser diagnostics.
- Better corruption diagnostics.
- Protection against accidentally interpreting bytes with the wrong structured format.

`logicalSize` remains required validation metadata and must be checked before object reuse and during `verify`.

For same-ID, same-size collision concerns, use a stronger hash, a secondary hash, or high-assurance byte comparison.

When adding an object whose ID already exists:

- Check that logical size matches.
- Check that the repository hash algorithm is compatible with the object ID.
- In normal mode, trust the cryptographic object ID after header validation.
- In high-assurance mode, read the existing object and compare logical bytes before reusing it.

If a collision or corruption is detected:

- Stop the operation.
- Mark the object as suspicious.
- Preserve the existing object.
- Require repair, migration, or a stronger hash strategy before continuing.

The baseline object model supports file-level deduplication:

- Hash small and regular files as whole files.
- If the object already exists, reference it from the manifest.
- Write missing objects into the object store.

The chunk model extends the same object model for large files.

### 3.3 Compressed Object Envelope

The physical object format is an envelope containing payload bytes plus compression, encryption, and verification metadata.

The object file should contain:

```text
object-file := fixed-header + object-id + public-header + private-header + payload + physical-checksum
payload := raw-content | compressed-content | encrypted-raw-content | encrypted-compressed-content
```

Object metadata is embedded in the object header.

Object headers must satisfy these requirements:

- Each object remains self-describing.
- Moving or copying an object must preserve the metadata needed to verify it.
- `verify` must be able to validate one physical file at a time.
- Recovery records must protect complete object files without pairing data and metadata files.
- Encryption must authenticate the object private header and payload together with one AEAD operation.
- The physical checksum must cover every envelope byte before the checksum field.
- Pruning must operate on complete object files.

The repository object format has no sidecar metadata files.

The object header should be minimal and versioned. In an unencrypted repository, it should identify:

- Object format version.
- Logical content hash.
- Logical content size.
- Payload compression algorithm.
- Payload size.

In an encrypted repository, public headers contain only the metadata allowed by the selected encryption mode. The object format supports a public header plus a private header that is encrypted when object encryption is enabled.

The payload may be stored uncompressed when compression is disabled for that object or when compression produces insufficient size reduction. The header explicitly records that decision.

The object envelope stores a private header. In unencrypted objects, the private header is deterministic metadata bytes. In encrypted objects, the private header and payload are ciphertext produced by one AEAD operation.

The import pipeline writes immutable repository objects from verified bytes. Mutable source files are copied or cloned into repository-owned storage before becoming reachable.

Future copy optimizations should prefer safe copy-on-write clone mechanisms, such as reflink or platform block cloning, when available. Any optimized copy path must still verify the resulting object before making it reachable.

The object path should be derived from the object ID:

```text
objects/
  ab/
    abcdef...
```

For all immutable objects, the object ID is derived from canonical logical bytes. File bytes, chunk bytes, snapshot metadata bytes, and tree metadata bytes all use the same object identity rule. This keeps deduplication and metadata integrity stable even as the physical storage format evolves.

### 3.4 Optional Encryption

Encryption should be modeled as an optional object pipeline stage:

```text
logical-content -> hash -> compress -> encrypt -> object-envelope -> object-file
```

The object pipeline performs compression before encryption.

Encrypted repository model:

- Use a repository master key derived from a password or imported from a key file.
- Derive separate subkeys for object IDs, object encryption, manifest encryption, and metadata authentication.
- Use AEAD encryption for object private headers and payloads.
- Use random nonces for physical encryption.
- Use keyed object IDs, such as `HMAC(object-id-key, logical-content-hash)`, so deduplication still works inside the repository without exposing raw plaintext hashes in file names.

Object ID privacy uses a secret repository-derived key. Public KDF salts are used only for password-derived key uniqueness.

The repository should store encrypted key material in `repository`. After unlock, the key hierarchy derives an `object-id-key` used only for object identity:

```text
object-id-key = KDF(repository-master-key, "object-id")
objectId = HMAC(object-id-key, contentHash)
```

Object ID privacy is implemented with HMAC or a standard keyed hash mode, such as keyed BLAKE3.

The public password KDF salt and the secret object ID key have different purposes:

- Public KDF salt makes password-derived keys unique.
- Secret object ID key prevents plaintext hash disclosure and known-content probing.

Compression precedes encryption in every encrypted object pipeline.

The compression profile is part of the repository storage format:

- Store the canonical compression profile in `repository`.
- Use deterministic compression settings for object payloads.
- Keep the compression profile independent from user-editable `config.toml` changes.
- If the compression profile changes, treat it as a repository migration or repack operation.
- When an object already exists, reuse its existing physical representation.

Encrypted object identity is computed before compression and encryption:

```text
canonicalLogicalBytes
  -> contentHash = hash(canonicalLogicalBytes)
  -> objectId = HMAC(object-id-key, contentHash)
  -> compress canonicalLogicalBytes
  -> encrypt compressed payload
  -> object envelope
```

`objectId` is a logical identity computed before compression and encryption.

A separate physical checksum may be computed over the encrypted object envelope for transfer validation or recovery diagnostics. The checksum is physical validation metadata.

Encrypted repositories keep raw plaintext `contentHash` values out of public object paths and public headers. Raw content hashes may be stored inside private headers that are encrypted when object encryption is enabled.

Encryption is repository-wide once enabled. Migration formats define any transition between encrypted and unencrypted storage.

Manifest encryption needs a separate decision. Encrypting only object payloads still leaks paths, file sizes, timestamps, and directory structure through snapshot manifests. A privacy-focused encrypted repository should encrypt snapshot manifests as well.

### 3.5 Recovery Records

Recovery records protect the physical repository bytes.

The recovery layer should operate after compression and encryption:

```text
object-envelope files + pack files + snapshot record files + `repository` files + repository config files -> recovery record set
```

This allows recovery tools to repair corrupted encrypted objects without needing the encryption key.

The recovery record layout supports PAR2-style recovery sets:

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

Recovery records are stored under `recovery` and are repository maintenance data.

A recovery set records a fixed protected file set and excludes its own generated recovery volumes. Future formats may define protection rules for older recovery metadata.

The tool should also support an external recovery output directory:

```text
kaca recovery create --repo <repository> --redundancy 10 --output <recovery-directory>
```

External recovery records are useful when the repository itself is stored on a disk or network location that may fail as a whole. The recovery set manifest should include the repository ID and protected file paths relative to the repository root so that external records can be matched back to the correct repository.

For encrypted repositories, recovery records protect encrypted physical files. The PAR2 payload repairs damaged bytes without requiring the encryption key. Recovery set manifests and file names may expose repository layout and object sizes, so the format reserves an encrypted recovery manifest mode.

Recovery records can be generated explicitly before automatic recovery scheduling is implemented. Automatic recovery record generation depends on stable pruning and retention semantics.

The recovery record specification is defined in `docs/recovery.md`.

### 3.6 Repository Synchronization

Remote synchronization is repository state transfer between RepositoryStore backends.

The synchronization model handles:

- Immutable objects.
- Mutable snapshot records.
- Optional recovery record sets.
- Rebuildable indexes.
- Repository metadata, user configuration, and format negotiation.

Immutable objects are content-addressed and can be synchronized by object ID:

```text
source RepositoryStore object IDs - destination RepositoryStore object IDs = missing objects
upload missing objects
download missing objects
verify object hashes
```

Mutable snapshot records have conflict handling for notes, tags, pins, and retention hints edited from multiple clients.

Conflict strategy:

- Keep immutable snapshot objects conflict-free.
- Version mutable snapshot records with `updatedAt` and a record revision.
- Detect concurrent edits before replacing destination snapshot records.
- Resolve conflicts field-by-field when possible.
- Preserve conflicting records as explicit conflict copies when automatic merge is unsafe.

Synchronization targets may be trusted or untrusted. For untrusted targets:

- Object payloads should be encrypted before upload.
- Snapshot object metadata should be encrypted in full repository encryption mode.
- Mutable snapshot records may also need encryption.
- Backend providers store, list, and transfer repository files without plaintext keys.

Remote synchronization should support resumable transfer:

- Upload and download temporary files.
- Commit files atomically on the destination backend when the provider supports it.
- Verify size and hash after transfer.
- Avoid relying on synchronized indexes as the source of truth.

The synchronized layout can mirror the file-tree repository layout, map repository paths to provider-specific objects, or use a single-file repository backend. The logical sync protocol is based on repository IDs, object IDs, pack IDs, snapshot record IDs, and recovery set IDs.

The remote synchronization specification is defined in `docs/synchronization.md`.

### 3.7 Large Object Chunking

Large files should be representable as ordered chunk references in the snapshot manifest. A chunk uses the same untyped object store as whole-file bytes.

Chunking preserves the logical identity of a file. A file's logical identity is based on its complete canonical file bytes.

The file manifest entry should store file-level identity:

```text
fileContentHash = hash(complete-file-bytes)
fileSize = complete-file-size
```

The chunk list is a storage representation for that file. Changing chunker parameters may change chunk boundaries and chunk object references while preserving `fileContentHash` for identical file bytes.

Logical pipeline for large files:

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
    "contentHash": "abcdef...",
    "contentSize": 107374182400,
    "chunker": {
      "algorithm": "fastcdc",
      "minSize": 1048576,
      "avgSize": 4194304,
      "maxSize": 16777216
    },
    "chunks": [
      {
        "id": "abcdef...",
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

The chunking strategy uses content-defined chunking, such as FastCDC, to preserve deduplication when bytes are inserted or removed near the beginning of a large file.

Chunking implementation strategy:

1. Stream the source file sequentially.
2. Maintain a rolling or gear hash state for boundary detection.
3. Keep accumulating bytes until at least `minSize`.
4. After `minSize`, cut a chunk when the boundary predicate matches the target average size.
5. Force a cut at `maxSize`.
6. Compute a cryptographic hash over the chunk bytes.
7. Convert the chunk hash to an object ID.
8. Write the chunk through the normal object pipeline.
9. Append the chunk reference to the file manifest entry.

The rolling or gear hash is only a boundary detector. Object identity uses the repository cryptographic hash.

Chunk buffering should be bounded by `maxSize`. A chunk can be buffered in memory when `maxSize` is modest, or spooled to a temporary file when memory limits require it.

Chunk object write pipeline:

```text
chunk bytes
  -> contentHash
  -> objectId
  -> compression
  -> optional encryption
  -> object envelope
  -> loose object or pack
```

Encrypted repositories use the same chunk boundaries, but derive public chunk object IDs with the repository `object-id-key`.

Chunker parameters must be stored in the manifest or the `repository` file. Changing chunker parameters changes deduplication behavior, so a repository should treat them as part of the object format profile.

Each chunk is compressed and encrypted independently after chunk boundaries are selected.

Loose objects and packed objects share the same logical identity and manifest references. Packing changes only the physical location of an object envelope.

Chunk-level deduplication is important for large mutable files and belongs in the architecture baseline. Its implementation can follow once whole-file references, snapshot objects, restore, `verify`, and `prune` are reliable.

It is most valuable for:

- VM images.
- Disk images.
- Large database dumps.
- Large project files with localized edits.
- Large binary files that are rewritten with small internal changes.

It is less valuable for:

- Small source files.
- Already compressed media.
- Files that are replaced entirely with unrelated content.
- Workloads where file-level deduplication already captures most reuse.

The repository format should reserve chunked object support from the beginning. Chunk-level deduplication can then be implemented without changing the snapshot record model.

### 3.8 Snapshot Metadata as Objects

Snapshot contents should be committed as structured metadata payloads in the same repository object store used by file and chunk bytes.

The `snapshots` directory stores mutable snapshot records:

```text
snapshots/
  2026-05-27T01-30-00Z-<snapshot-id>.json
```

A snapshot record points to the actual immutable snapshot object through a typed semantic reference and may also contain user-editable metadata:

```json
{
  "formatVersion": 1,
  "snapshotId": "abcdef...",
  "createdAt": "2026-05-27T01:30:00Z",
  "object": {
    "id": "abcdef...",
    "format": "kaca-snapshot-v1"
  },
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

The snapshot object payload contains the snapshot manifest. This gives snapshot metadata the same storage guarantees as file and chunk objects:

- Compression.
- Optional encryption.
- Integrity checks.
- Recovery record protection.
- Atomic object writes.
- Uniform object reachability for `prune`.

Mutable snapshot records are reachability roots. `prune` treats snapshot records as roots, then walks snapshot objects and their referenced data objects.

Only non-critical user metadata should be mutable. Restore-critical information must stay in the immutable snapshot object:

- Source identity.
- Parent snapshot object ID.
- File tree entries.
- Typed semantic references.
- Chunk lists.
- Required restore metadata.

Mutable records may include:

- User title.
- Notes.
- Tags.
- Pin or favorite state.
- Retention hints.
- Last viewed or UI state.

Updating mutable metadata preserves the snapshot object and its object ID.

One implementation slice can store one snapshot manifest as one snapshot object. A tree-style metadata model can split very large manifests into reusable directory metadata payloads without changing the snapshot record model.

Snapshot object payloads use canonical binary metadata as the long-term storage format. JSON examples in this document are schema illustrations only.

The immutable metadata stored in the object pool should use a canonical binary encoding:

- Deterministic field ordering.
- No duplicate keys.
- Normalized path strings.
- Explicit integer timestamp representation.
- Stable enum values for entry types.
- No floating point values.
- A magic and format version inside every structured metadata payload.

The object ID for a metadata payload should be computed from the canonical uncompressed metadata bytes before compression and encryption.

Snapshot and tree semantic rules are defined in `docs/snapshot-model.md`.

Mutable snapshot records outside the object pool may use JSON as user-editable catalog data.

### 3.9 Filesystem Metadata Profiles

Filesystem metadata belongs to snapshot and tree metadata. File content objects contain file bytes only.

Content identity and metadata identity are separate:

```text
contentObjectId = hash(file-bytes)
treeEntry = path + entryType + contentRef + capturedMetadata
```

Identical file bytes reuse the same content object across snapshots. Captured metadata is part of the structured snapshot or tree payload and affects the snapshot or tree object ID.

The repository supports separate capture and restore profiles:

```toml
[policy.metadata]
default_capture = "portable"
```

```toml
[metadata]
restore = "portable"
restore_errors = "warn"
```

Capture profile values:

- `portable` captures root-scoped snapshot-relative path, entry type, target reference, logical size, modified time, executable bit, read-only bit, and symbolic link target.
- `system` captures `portable` fields plus POSIX mode, uid, gid, user name, group name, and Windows file attributes.
- `full` captures `system` fields plus ACLs, extended attributes, macOS flags, macOS resource fork metadata, Windows security descriptor, and Windows reparse point metadata.

Restore profile values:

- `portable` restores file bytes, directories, symbolic links, modified time, executable bit, and read-only bit.
- `system` restores `portable` fields plus supported POSIX mode, ownership fields, and Windows file attributes according to platform capabilities and process privileges.
- `full` restores `system` fields plus supported ACLs, extended attributes, macOS metadata, Windows security descriptors, and Windows reparse point metadata.

The restore profile may have lower fidelity than the captured profile. Metadata restore failures are recorded in the restore report with the affected path, metadata field, platform error, and applied fallback.

Capture profile resolution uses command invocation overrides, job configuration, repository-local client configuration, repository policy, user configuration, system configuration, and built-in defaults. Restore profile resolution uses command invocation overrides, repository-local client configuration, user configuration, system configuration, and built-in defaults. Existing snapshot objects keep the metadata captured at snapshot creation time.

### 3.10 Sparse Restore and Checkout

The snapshot model should support sparse restore: restoring only selected paths or path patterns from a snapshot.

Simple sparse behavior can be implemented by loading the snapshot manifest and filtering entries. Path filters may include a root ID prefix:

```text
kaca restore <snapshot-id> <target> --path docs/readme.md
kaca restore <snapshot-id> <target> --root work --path docs/readme.md
kaca restore <snapshot-id> <target> --include "src/**" --exclude "build/**"
```

This simple sparse path is correct for one-shot restore. Very large snapshots use the tree-style metadata model for efficient partial reads.

Efficient long-term sparse checkout needs tree-style structured metadata payloads:

```text
snapshot object -> root entries -> root tree references -> directory tree references -> file content references
```

With tree objects, a restore operation for `src/main/**` only needs to load the relevant metadata branches and referenced data objects.

Long-lived sparse checkout should be treated as a separate feature from one-shot sparse restore. A checkout worktree may need mutable local state:

```text
.kaca-checkout.json
```

Checkout commands:

```text
kaca checkout <snapshot-id> <target> --sparse <spec>
kaca checkout add <path>
kaca checkout remove <path>
kaca checkout list
```

Sparse checkout state is local working-tree state. Snapshot pinning is the explicit mechanism for preserving repository reachability.

### 3.11 Atomic Writes

Snapshot creation is transactional.

Write order:

1. Write new content objects.
2. Write the snapshot manifest as a temporary snapshot object.
3. Verify that all objects referenced by the snapshot object exist.
4. Atomically move the snapshot object into the object store.
5. Atomically write the mutable snapshot record.
6. Update indexes.

Repository mutations use a writer lock, transaction workspace, atomic same-filesystem rename, directory flushing, and explicit crash recovery rules.

The transaction and atomic publication specification is defined in `docs/transactions.md`.

If the process is interrupted, `repair` and `verify` detect incomplete transaction workspaces, orphan objects, broken snapshot records, invalid pack pairs, and stale rebuildable indexes.

### 3.12 Verifiability

The repository must support integrity checks:

- Snapshot records are parseable and point to valid snapshot objects.
- Snapshot objects are parseable.
- Snapshot metadata profiles and captured metadata fields are parseable.
- Objects referenced by snapshot objects exist.
- Object headers are parseable and compatible with the repository format.
- Decompressed object content matches the logical content hash.
- Encrypted object payloads authenticate successfully before decompression.
- Chunked files restore to the expected file-level content hash.
- Sparse restore outputs only the selected paths and preserves their metadata.
- Recovery records match the physical repository files they protect.
- Indexes can be rebuilt from snapshots and objects.
- Deleting old snapshots preserves objects that are still referenced.

## 4. Repository Format Draft

A local repository can use this layout:

```text
repository/
  repository
  config.toml
  lock
  local/
    config.toml
  jobs/
    <job-name>.toml
  objects/
    ab/
      abcdef...
  packs/
    <pack-id>.pack
    <pack-id>.idx
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

- `repository` stores binary internal metadata such as repository ID, repository format version, object format version, hash algorithm, metadata encoding, canonical compression profile, object layout, encryption mode, key derivation public parameters, and creation time.
- `config.toml` stores repository policy such as retention defaults, recovery record defaults, filesystem metadata capture defaults, and extension policy.
- `local/config.toml` stores client-local configuration such as remotes, credential references, restore defaults, service settings, and UI preferences.
- `jobs` stores repeatable snapshot job definitions such as source roots, schedules, filters, and per-job capture overrides.
- `lock` prevents multiple processes from writing to the repository at the same time.
- `objects` stores untyped physical object envelopes keyed by object ID.
- `packs` stores immutable packed object files and pack indexes.
- `snapshots` stores mutable snapshot records that point to immutable snapshot metadata payloads.
- `indexes` stores rebuildable indexes; snapshot records and objects remain the source of truth.
- `recovery` stores optional recovery record sets.
- `tmp` stores incomplete temporary writes.

Indexes can remain rebuildable from snapshot records and objects when needed.

The concrete binary formats for `repository`, object envelopes, structured payloads, pack files, and pack indexes are defined in `docs/repository-format.md`.

The concrete layered configuration schema is defined in `docs/configuration.md`.

## 5. Snapshot Object Draft

Each mutable snapshot record points to a snapshot object. The snapshot object payload describes one immutable snapshot:

The example below is JSON for readability. The stored snapshot object should use a canonical binary metadata encoding.

```json
{
  "formatVersion": 1,
  "id": "snapshot-id",
  "createdAt": "2026-05-27T01:30:00Z",
  "metadataProfile": "portable",
  "parent": "previous-snapshot-id",
  "roots": [
    {
      "rootId": "work",
      "displayName": "Work",
      "source": {
        "path": "D:\\Projects\\example"
      },
      "tree": {
        "id": "tree-object-id",
        "format": "kaca-tree-v1"
      },
      "entries": [
        {
          "path": "docs/readme.md",
          "type": "file",
          "size": 1024,
          "metadata": {
            "modifiedAt": "2026-05-27T01:00:00Z",
            "executable": false,
            "readonly": false
          },
          "object": {
            "id": "abcdef...",
            "contentSize": 1024,
            "storedSize": 512,
            "compression": "zstd",
            "encryption": "none"
          }
        }
      ]
    }
  ]
}
```

Snapshot payload requirements:

- The root list contains one or more source roots.
- Each source root records root ID, display name, source root kind, captured source path display information, filter summary, case sensitivity policy, and root content entries.
- Directory, regular file, symbolic link, hard link, and special file entries use structured tree entry records.
- Regular file entries support whole-file content references and ordered chunked content references.
- Captured metadata follows the active metadata capture profile.
- Captured metadata supports portable fields, POSIX fields, Windows attributes and security descriptors, extended attributes, ACLs, macOS flags, macOS resource fork metadata, and Windows reparse point metadata.

## 6. Main Modules

### 6.1 Repository

Manages repository initialization, internal metadata loading, layered configuration loading, locking, path layout, and format compatibility.

Suggested capabilities:

- `init`
- `open`
- `lock`
- `unlock`
- `checkFormatVersion`
- `loadConfigLayers`
- `resolveConfigValue`
- `showConfigOrigins`

### 6.2 Scanner

Scans source roots and produces candidate entries.

Suggested capabilities:

- Resolve configured source roots.
- Enforce unique root IDs within a snapshot.
- Walk directories.
- Apply include and exclude rules.
- Handle symbolic link policy.
- Capture filesystem metadata according to the active capture profile.
- Detect files that change during scanning.

### 6.3 Hasher

Computes file hashes.

Suggested capabilities:

- Stream-based hashing.
- Repository-level hash algorithm implementation.
- Progress reporting for large files.
- Reserved interfaces for chunk hashing.

### 6.4 Chunker

Splits large files into stable logical chunks.

Suggested capabilities:

- Choose whether a file should be stored as a single blob reference or a chunked blob reference list.
- Produce deterministic chunk boundaries from file content.
- Record chunker parameters in manifests.
- Stream chunks without loading the whole file into memory.
- Support content-defined chunking in the long term.

### 6.5 ObjectStore

Writes, reads, and verifies untyped repository objects.

Suggested capabilities:

- Check whether an object already exists by hash.
- Compress object payloads.
- Store an object uncompressed when compression produces insufficient size reduction.
- Write and parse object headers.
- Encrypt and decrypt object private headers and payloads when repository encryption is enabled.
- Store file bytes, chunk bytes, and structured metadata payloads through the same envelope format.
- Write objects through temporary files.
- Verify the logical content hash after writing.
- Authenticate encrypted payloads before decompression.
- Atomically move objects into the `objects` directory.
- Read and decompress objects for restore.

### 6.6 PackStore

Manages immutable pack files and pack indexes.

Suggested capabilities:

- Select loose objects for packing.
- Write temporary pack files and indexes.
- Publish packs atomically.
- Resolve object IDs to pack record and envelope offsets.
- Read object envelopes from packs.
- Verify pack indexes against pack contents.
- Repack live objects during pruning.
- Coordinate pack transfer with remote synchronization.

### 6.7 Crypto

Manages optional repository encryption.

Suggested capabilities:

- Derive repository keys.
- Derive independent subkeys for object IDs, object payloads, manifests, and metadata authentication.
- Encrypt and decrypt object private headers.
- Encrypt and decrypt object payloads.
- Encrypt and decrypt snapshot manifests when full repository encryption is enabled.
- Rotate or migrate encryption settings in a controlled future format upgrade.

### 6.8 SnapshotStore

Builds snapshot manifests, stores them as structured snapshot payloads, and manages mutable snapshot records.

Suggested capabilities:

- Write a new snapshot object.
- Atomically write a snapshot record.
- Update mutable snapshot metadata without rewriting the snapshot object.
- List snapshot records.
- Read a specific snapshot through its record.
- Compare two snapshots.
- Rebuild the object reachability index from snapshot records and snapshot objects.

### 6.9 Restore

Restores files from a snapshot.

Suggested capabilities:

- Restore a complete snapshot.
- Restore a single path.
- Restore selected paths by include and exclude patterns.
- Detect target path conflicts.
- Support dry run.
- Restore filesystem metadata according to the active restore profile.
- Report metadata restore failures separately from content restore failures.
- Restore chunked files by streaming ordered blob objects.

### 6.10 Verify

Checks repository integrity.

Suggested capabilities:

- Verify all snapshots.
- Verify all objects.
- Verify manifest references.
- Detect orphan objects.
- Detect corrupted objects.
- Verify chunk lists and content object references.
- Verify recovery record sets.

### 6.11 Prune

Deletes old snapshots according to retention policy and performs safe garbage collection.

Suggested capabilities:

- Delete a specific snapshot.
- Select snapshots by retention policy.
- Compute still-referenced objects.
- Remove unreferenced objects.
- Support dry run.

### 6.12 RecoveryStore

Manages optional recovery records for repository files.

Suggested capabilities:

- Create recovery sets for selected repository files.
- Verify recovery sets.
- Repair damaged physical files when enough recovery data is available.
- Track which snapshots, objects, and metadata files are protected by each recovery set.
- Coordinate with `prune` so recovery records are refreshed after protected repository files are deleted.

### 6.13 RepositorySync

Manages synchronization between RepositoryStore backends.

Suggested capabilities:

- Configure repository backend endpoints.
- Build remote inventory.
- Upload missing immutable objects.
- Download missing immutable objects.
- Upload and download mutable snapshot records with conflict detection.
- Synchronize snapshot record tombstones.
- Synchronize recovery record sets.
- Verify transferred files by size and hash.
- Support resumable uploads and downloads.
- Keep synchronized indexes rebuildable and non-authoritative.

## 7. CLI Draft

The CLI surface should cover the architecture scope, while individual commands can be implemented incrementally:

```text
kaca init <repository>
kaca init <repository> --encrypt
kaca init zip:file:///<archive-path>
kaca init bundle:file:///<bundle-path>
kaca snapshot <source>... --repo <repository>
kaca snapshot --source <root-id>=<source-path> --source <root-id>=<source-path> --repo <repository>
kaca snapshot <source>... --repo <repository> --metadata-capture portable|system|full
kaca list --repo <repository>
kaca show <snapshot-id> --repo <repository>
kaca diff <from-snapshot-id> <to-snapshot-id> --repo <repository>
kaca restore <snapshot-id> <target> --repo <repository>
kaca restore <snapshot-id> <target> --repo <repository> --metadata-restore portable|system|full
kaca restore <snapshot-id> <target> --repo <repository> --path <path>
kaca restore <snapshot-id> <target> --repo <repository> --root <root-id>
kaca restore <snapshot-id> <target> --repo <repository> --include <pattern> --exclude <pattern>
kaca verify --repo <repository>
kaca prune --repo <repository> --keep-daily 7 --keep-weekly 4
kaca stats --repo <repository>
kaca config get <key> --repo <repository> --show-origin
kaca config list --repo <repository> --show-origin
kaca repair --repo <repository>
kaca recovery create --repo <repository> --redundancy 10
kaca recovery create --repo <repository> --redundancy 10 --output <recovery-directory>
kaca recovery verify --repo <repository>
kaca recovery repair --repo <repository>
kaca remote add <name> <url>
kaca remote list --repo <repository>
kaca sync push <remote> --repo <repository>
kaca sync pull <remote> --repo <repository>
kaca sync verify <remote> --repo <repository>
kaca archive export <archive-repository> --repo <repository>
kaca archive import <archive-repository> --repo <repository>
kaca checkout <snapshot-id> <target> --repo <repository> --sparse <spec>
kaca checkout add <path>
kaca checkout remove <path>
kaca checkout list
kaca mount <snapshot-id> --repo <repository>
kaca serve --repo <repository>
```

## 8. Architecture Baseline and Implementation Slices

The architecture baseline includes:

- RepositoryStore and SourceStore backends.
- Single-file repository archive and bundle backends.
- Untyped object envelopes.
- Untyped objects for whole files, chunks, and structured metadata payloads.
- Typed semantic references for snapshots, trees, files, and chunks.
- Mutable snapshot records.
- Compression.
- Optional encryption.
- Recovery records.
- Filesystem metadata capture and restore profiles.
- Sparse restore and sparse checkout.
- Retention and pruning.
- Verification and repair.
- Remote synchronization.
- Scheduling and service integration.
- GUI integration points.

Implementation can be sliced without narrowing the architecture:

- Repository format, object envelope, and local object storage.
- Snapshot creation, listing, diff, restore, and sparse restore.
- Filesystem metadata capture and restore profiles.
- Verification, repair, pruning, and recovery record generation.
- Optional encryption and encrypted metadata.
- Remote repository synchronization.
- Chunk-level deduplication and pack files.
- Long-lived sparse checkout state.
- Scheduling, service mode, and GUI.

## 9. Key Risks

### 9.1 Source Files Change During Backup

Detection strategy:

- Record size and modified time before reading a file.
- Read the file.
- Record size and modified time again after reading.
- If the file changed, retry once.
- If the file remains unstable, record it as a failed entry and make the `snapshot` command return a non-zero status or explicit warning.

Filesystem-level consistency snapshots are a dedicated integration area in the architecture baseline.

### 9.2 Pruning Deletes Referenced Objects

`prune` computes liveness from retained snapshot records before deleting objects.

Safe strategy:

- Rebuild the reference set from all retained snapshots.
- Delete objects outside the reference set.
- Default to dry run first.
- Write a prune transaction before actual deletion.

### 9.3 Repository Format Upgrades

Both `repository` and structured metadata payload formats must record `formatVersion`.

When a newer program opens an older repository, it must clearly decide:

- Whether the repository is readable.
- Whether migration is required.
- Whether writing should be blocked.

### 9.4 Cross-Platform Path Differences

The internal path format is:

- Use `/` as the path separator inside manifests.
- Store only root-scoped snapshot-relative entry paths.
- Store tree entry names as single path segments.
- Reject empty path segments, `.`, `..`, path separators inside entry names, NUL, and ASCII control characters.
- Keep captured source root paths only for display and audit.
- Record root-level case sensitivity as `case-sensitive` or `case-insensitive-preserving`.

### 9.5 Encryption Metadata Leakage

Encrypted object payloads alone leave some metadata visible.

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
- Rebuild affected recovery sets as new recovery sets.
- Never let recovery records act as the source of truth for object liveness.

### 9.7 Chunker Parameter Drift

Changing chunker parameters can reduce deduplication efficiency or create confusing mixed behavior.

Safe strategy:

- Store chunker algorithm and parameters with every chunked file entry.
- Record the repository default chunker profile in `repository`.
- Allow reading old chunker profiles indefinitely.
- Treat changes to default chunker parameters as an explicit repository configuration change.

### 9.8 Mutable Snapshot Record Loss

Mutable snapshot records are reachability roots. If a record is deleted or corrupted, the immutable snapshot object may still exist in the object store but become undiscoverable during normal listing.

Safe strategy:

- Protect snapshot record files with recovery records.
- Update snapshot records atomically.
- Keep mutable metadata separate from restore-critical snapshot object content.
- Make `verify` report orphan snapshot objects.
- Let `repair` rebuild a minimal snapshot record by scanning structured snapshot payloads when possible.

### 9.9 Unsafe Hard Link Imports

Hard links share mutable file data and are represented as filesystem metadata in snapshots.

Safe strategy:

- Import mutable source files through verified object writes.
- Treat hard links as filesystem metadata to record and restore.
- Prefer verified normal copies as the default import path.
- Evaluate reflink or platform copy-on-write clone support as an optimized copy path with verified semantics.
- Verify object content before making any optimized copy reachable.

### 9.10 Remote Sync Conflicts

Immutable objects are conflict-free through object ID addressing. Mutable snapshot records use record revisions for conflict detection.

Safe strategy:

- Detect concurrent edits to mutable snapshot records.
- Keep record revisions and `updatedAt` timestamps.
- Merge independent fields when possible.
- Preserve explicit conflict copies when automatic merge is unsafe.
- Never let a remote index override local object reachability without verification.
- Treat remote storage as untrusted by default.

## 10. Test Plan

Basic test scenarios:

- Initialize an empty repository.
- Create a snapshot for an empty directory.
- Create a snapshot for a directory with multiple files.
- Create a snapshot with multiple source roots.
- Restore a selected root from a multi-root snapshot.
- Verify that a snapshot record points to a valid snapshot object.
- Verify that a snapshot object can be read, decompressed, decrypted when needed, and parsed.
- Update snapshot notes or tags and verify that the snapshot object ID remains stable.
- Delete a snapshot record and verify that the snapshot object is reported as an orphan.
- Modify a file and create a second snapshot.
- Delete a file and create a third snapshot.
- Compare two snapshots.
- Restore a complete snapshot.
- Restore a single file.
- Restore selected paths and verify that unrelated paths remain absent.
- Restore with include and exclude patterns.
- Create a snapshot with the default `portable` metadata capture profile.
- Restore with the default `portable` metadata restore profile.
- Create a snapshot with the `system` metadata capture profile on a supported platform.
- Create a snapshot with the `full` metadata capture profile on a supported platform.
- Restore a snapshot with a lower-fidelity restore profile than the captured profile.
- Verify that metadata restore failures are reported with path, field, platform error, and fallback.
- Verify that identical file bytes with different metadata reuse the same content object.
- Verify that different captured metadata changes the containing snapshot or tree object ID.
- Verify that duplicate files store only one object.
- Verify that object scanning covers the unified object pool.
- Verify that existing-object reuse checks logical size and repository hash compatibility.
- Verify that structured payload parsing fails for mismatched expected formats.
- Verify that logical size mismatches fail validation.
- Verify that high-assurance mode compares logical bytes before reusing an existing object.
- Verify that compressed objects restore to the original content.
- Verify that object headers and payloads are checked by `verify`.
- Verify that objects remain self-describing without sidecar metadata files.
- Pack loose objects and verify that packed objects restore correctly.
- Repack live objects during pruning without losing references.
- Verify encrypted objects authenticate, decrypt, decompress, and restore to the original content.
- Verify that encrypted object IDs are stable for the same logical plaintext even when encryption nonces differ.
- Verify that encrypted object IDs omit raw plaintext content hashes.
- Verify that encrypted repositories use keyed object IDs.
- Verify that wrong keys fail before decompression or restore.
- Verify that recovery records detect physical file corruption.
- Verify that recovery records can repair a damaged protected file when enough redundancy is available.
- Verify that external recovery records can be matched to the correct repository by repository ID.
- Verify layered configuration precedence from system, user, repository, repository-local, job, and command invocation layers.
- Verify that effective configuration diagnostics report the source file and layer for resolved values.
- Verify that repository-local remote definitions override lower-layer remotes with the same name.
- Create a chunked large-file snapshot and restore it byte-for-byte.
- Verify that file-level content hash is independent of chunk boundaries.
- Verify that chunk boundaries respect `minSize` and `maxSize`.
- Verify that rolling hash state is used only for boundaries and cryptographic hashes provide object identity.
- Modify a large file in the middle and verify that unchanged chunks are reused.
- Insert bytes near the beginning of a large file and compare fixed-size chunking with content-defined chunking.
- Manually corrupt an object and confirm that `verify` detects it.
- Delete an old snapshot without deleting objects that are still referenced.
- Confirm that interrupted backups leave temporary files outside the official repository state.

## 11. Open Research Questions

- Should the default hash algorithm be `SHA-256` or `BLAKE3`?
- Should high-assurance mode store or verify a secondary hash?
- Should very large snapshot manifests become tree-style structured metadata payloads?
- Which compression library and default compression level should be used?
- What minimum size or compression ratio should decide whether an object is stored compressed or raw?
- Should future platforms support reflink or copy-on-write clone optimization for raw object payloads?
- Which AEAD algorithm should be used for object encryption?
- Should encrypted repositories always encrypt snapshot manifests?
- Should mutable snapshot records be encrypted in encrypted repositories?
- How should password-based key derivation be configured?
- Which PAR2-compatible implementation is used for version 1 recovery generation?
- What is the default redundancy percentage for recovery records?
- Should recovery records protect every snapshot immediately or be generated in batches?
- Should encrypted repositories encrypt recovery set manifests when recovery records are stored externally?
- Should large file chunking use fixed-size chunks or rolling hash?
- What large-file threshold should enable chunking by default?
- What default pack size, pack object count, and automatic pack scheduling should be used?
- What pattern syntax should sparse restore use?
- Should long-lived sparse checkout state be stored in the target directory or in the repository?
- Is a database index such as SQLite needed?
- How should unsupported platform metadata fields be represented in restore reports?
- Should exclude rules be compatible with `.gitignore` syntax?
- Should restore overwrite target files by default?
- Should `snapshot` fail, skip, or produce a partial snapshot when it sees unstable files?

## 12. Implementation Sequence

1. Implement the repository binary format, layered configuration schema, mutable snapshot record format, encryption extension points, recovery record layout, and manifest schema.
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
13. Implement pack file creation, pack indexes, remote pack transfer, and repacking.
