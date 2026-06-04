# Incremental Backup Architecture Plan

This document describes the architecture plan for `kaca`, an incremental backup tool for directory snapshots. It defines architecture boundaries, data models, repository format, synchronization behavior, integrity guarantees, and implementation sequencing.

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
- Include remote repositories, synchronization, chunk-level deduplication, and scheduled jobs in the initial architecture scope.

## 2. Architecture Scope and Implementation Phasing

The architecture should account for the full product shape from the beginning, even when implementation is delivered in smaller slices.

Architecture scope includes:

- Local repositories.
- Remote repositories and synchronization.
- Immutable object storage.
- Mutable snapshot records.
- Compression.
- Optional encryption.
- Recovery records.
- Chunked large-object storage.
- Sparse restore and sparse checkout.
- Retention and pruning.
- Repository verification and repair.
- Internal binary repository metadata.
- User-editable repository configuration.
- Scheduling and background operation.
- Filesystem-level consistency integrations.
- GUI and service interfaces.
- Multi-client conflict handling.

Implementation can still be staged. The plan must distinguish architecture coverage from implementation order.

### 2.1 Repository Metadata and User Configuration

Repository metadata and user-editable configuration are separate files.

`repository.meta` is internal binary metadata. Users should not edit it directly. It stores stable repository identity and format data:

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

`config.toml` is user-editable configuration. It stores operational preferences:

- Remotes.
- Recovery record defaults.
- Retention defaults.
- Scheduling preferences.
- UI and service settings.

Changing `config.toml` must not change existing object identity or repository format. Changes that affect immutable repository structure require an explicit repository migration.

## 3. Core Principles

### 3.1 Snapshots Are Complete Views

Each snapshot should fully describe the source directory state at a point in time, including files, directories, symbolic links, and required metadata.

Restoring an arbitrary snapshot should not require replaying all previous snapshots.

### 3.2 Content-Addressed Storage

File content is stored in the object store by content hash. If multiple snapshots reference the same content, the repository stores that content only once.

The object identity is based on the logical uncompressed content. Physical object files may be compressed, but deduplication should still be based on the original content bytes.

Metadata objects, such as snapshot manifests and tree manifests, should also use the same object store. Object IDs are based on canonical logical bytes. The object store is untyped; semantic meaning is provided by typed references and self-describing payload formats.

The object format does not prepend object type, logical size, or repository-specific labels to the bytes used for object identity. Structured metadata payloads must contain their own magic and format version so that semantic verification can reject incorrectly interpreted bytes.

Conceptually:

```text
objectId = hash(canonicalLogicalBytes)
```

For unencrypted repositories, `objectId` and `contentHash` are the same value. For encrypted repositories, `contentHash` may exist only inside encrypted metadata, while the public `objectId` is keyed.

Recommended distinction:

```text
contentHash = hash(canonicalLogicalBytes)
objectId = contentHash
```

Typed references belong to the semantic layer, not to the object store identity:

```text
blobRef = objectId + logicalSize
snapshotRef = objectId + expectedFormat("kaca-snapshot-v1")
treeRef = objectId + expectedFormat("kaca-tree-v1")
```

Using a bare content hash as `objectId` is acceptable because the object store only stores immutable bytes. Code that interprets structured data must always use an expected payload format and verify the payload magic and version before trusting it.

`logicalSize` is validation metadata. It must be stored and checked, but it does not need to be part of the lookup key.

#### Object Pool Layout

The repository exposes one logical `ObjectStore` API and one untyped physical object pool.

Recommended physical layout:

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

The path builder should be centralized, but fanout is part of the repository format rather than a user-configurable setting.

The object store must not create separate physical pools for snapshots, trees, chunks, and file blobs. All immutable payloads use the same identity, envelope, verification, synchronization, recovery, packing, and pruning rules.

The semantic layer must distinguish how object bytes are interpreted:

```text
snapshot record -> snapshotRef -> snapshot payload
snapshot payload -> treeRef -> tree payload
tree payload -> blobRef or chunk list -> file bytes
```

Benefits:

- One object lookup path for all immutable bytes.
- Repository-wide byte-level deduplication across whole-file blobs, chunks, and structured metadata payloads.
- Simpler pack, sync, prune, verify, and recovery logic.
- Cleaner encrypted repositories because public object paths do not expose semantic object classes.
- Easier repository migration because physical storage is decoupled from metadata formats.

Costs:

- Type validation must be enforced by references and payload parsers.
- Semantic verification must traverse snapshot roots and parse expected formats.
- Type-specific maintenance requires indexes or semantic scans instead of directory partition scans.

Structured payload formats must be self-describing:

```text
kaca-snapshot-v1 := magic + version + canonical-binary-snapshot-body
kaca-tree-v1 := magic + version + canonical-binary-tree-body
```

Fully independent pools with unrelated identity rules are not recommended because they make `verify`, `sync`, `prune`, and recovery records harder to reason about.

#### Pack Files

Loose object files are simple, but large repositories can contain too many small files. Pack files group many objects into larger immutable physical files.

Recommended layout:

```text
packs/
  <pack-id>.pack
  <pack-id>.idx
```

A pack file stores object records. The pack index maps each object ID to its physical location:

```text
objectId -> packId + offset + storedSize
```

The simplest pack record stores the same object envelope bytes used by loose objects:

```text
pack record := object-envelope
```

This keeps object verification, compression, encryption, and metadata parsing identical for loose objects and packed objects.

Pack files should be immutable. New objects are written as loose objects or into new pack files. Existing pack files should not be modified in place.

Pack creation flow:

1. Select loose objects to pack.
2. Write a temporary pack file.
3. Write a temporary pack index.
4. Verify every indexed object can be read from the pack.
5. Atomically publish the pack and index.
6. Remove packed loose objects only after verification succeeds.

Pruning packed objects requires repacking. Individual objects inside a pack should not be deleted in place. A prune operation should compute live objects, write new packs containing live objects, then remove obsolete packs after verification.

Remote synchronization should treat packs as immutable transfer units. This avoids remote append semantics and makes interrupted uploads easier to recover.

Recovery records should protect pack files and pack indexes. This is more efficient than protecting millions of small loose objects.

#### Hash Collision Policy

Hash collisions are not expected with modern cryptographic hashes, but the repository must define how object identity is verified and what happens if a collision-like inconsistency is detected.

Rules:

- Use only cryptographic hashes for object identity.
- Record the hash algorithm in internal repository metadata.
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

Compression algorithm, encryption mode, payload size, and storage location are physical storage properties. They should not define logical object equality.

Typed references do not replace cryptographic collision resistance. They provide semantic validation after object lookup:

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
- Do not overwrite the existing object.
- Require repair, migration, or a stronger hash strategy before continuing.

The baseline object model supports file-level deduplication:

- Hash small and regular files as whole files.
- If the object already exists, reference it from the manifest.
- If the object does not exist, write it into the object store.

The chunk model extends the same object model for large files.

### 3.3 Compressed Object Envelope

The physical object format is an envelope instead of raw file bytes so compression, encryption, and verification metadata can be represented consistently.

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
- Logical content hash.
- Logical content size.
- Payload compression algorithm.
- Payload size.

In an encrypted repository, public headers must not expose logical content hashes or other plaintext-derived metadata unless the selected encryption mode explicitly allows that leakage. The object format should support a public header plus an encrypted private header.

The payload may be stored uncompressed when compression is disabled for that object or when compression does not reduce size enough. The header must explicitly record that decision.

The repository should not hard link mutable source files directly into the object store. A hard link shares the same underlying file data; if the original file is modified in place after import, the backup object would change as well. That violates snapshot immutability.

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

Compression must happen before encryption because encrypted payloads are not meaningfully compressible.

The recommended encrypted repository model is:

- Use a repository master key derived from a password or imported from a key file.
- Derive separate subkeys for object IDs, object encryption, manifest encryption, and metadata authentication.
- Use AEAD encryption for object payloads and encrypted private headers.
- Use random nonces for physical encryption.
- Use keyed object IDs, such as `HMAC(object-id-key, logical-content-hash)`, so deduplication still works inside the repository without exposing raw plaintext hashes in file names.

Object ID privacy requires a secret, not a public salt. A public salt does not prevent dictionary checks because an attacker can recompute salted hashes for guessed content.

The repository should store encrypted key material in `repository.meta`. After unlock, the key hierarchy derives an `object-id-key` used only for object identity:

```text
object-id-key = KDF(repository-master-key, "object-id")
objectId = HMAC(object-id-key, contentHash)
```

Do not implement object ID privacy as `hash(content || salt)` or `hash(salt || content)`. Use HMAC or a standard keyed hash mode, such as keyed BLAKE3, to avoid construction and length-extension pitfalls.

The public password KDF salt and the secret object ID key have different purposes:

- Public KDF salt makes password-derived keys unique.
- Secret object ID key prevents plaintext hash disclosure and known-content probing.

Compression still happens before encryption. Encrypted bytes are not compressible in a useful way.

To avoid multiple physical representations for the same logical object, the compression profile is part of the repository storage format:

- Store the canonical compression profile in `repository.meta`.
- Use deterministic compression settings for object payloads.
- Do not let `config.toml` change the compression profile of an existing repository.
- If the compression profile changes, treat it as a repository migration or repack operation.
- When an object already exists, reuse its existing physical representation instead of creating another compressed representation.

Encrypted object identity is computed before compression and encryption:

```text
canonicalLogicalBytes
  -> contentHash = hash(canonicalLogicalBytes)
  -> objectId = HMAC(object-id-key, contentHash)
  -> compress canonicalLogicalBytes
  -> encrypt compressed payload
  -> object envelope
```

`objectId` is a logical identity. It must not be computed from ciphertext, because ciphertext changes with encryption nonce and would break deduplication.

A separate physical checksum may be computed over the encrypted object envelope for transfer validation or recovery diagnostics, but that checksum is not the object identity.

Encrypted repositories should not expose raw plaintext `contentHash` values in public object paths or public headers. Raw content hashes may be stored inside encrypted private headers when needed for high-assurance verification.

Encryption should be repository-wide once enabled. Mixing encrypted and unencrypted snapshots in the same repository should be avoided unless there is a strong migration use case.

Manifest encryption needs a separate decision. Encrypting only object payloads still leaks paths, file sizes, timestamps, and directory structure through snapshot manifests. A privacy-focused encrypted repository should encrypt snapshot manifests as well.

### 3.5 Recovery Records

Recovery records should protect the physical repository bytes, not the logical file model.

The recovery layer should operate after compression and encryption:

```text
object-envelope files + snapshot record files + repository metadata files + user config files -> recovery record set
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

Recovery records must not be stored under `objects` or `snapshots`, and they must not be represented as snapshot entries. They are repository maintenance data, not user data.

A recovery set must not protect its own PAR2 files. Otherwise, recovery generation becomes recursive and pruning becomes ambiguous. It may protect older recovery metadata only if a future format explicitly defines that behavior.

The tool should also support an external recovery output directory:

```text
kaca recovery create --repo <repository> --redundancy 10 --output <recovery-directory>
```

External recovery records are useful when the repository itself is stored on a disk or network location that may fail as a whole. The recovery set manifest should include the repository ID and protected file paths relative to the repository root so that external records can be matched back to the correct repository.

For encrypted repositories, recovery records should protect encrypted physical files. The PAR2 payload does not need the encryption key to repair damaged bytes. However, recovery set manifests and file names may still leak repository layout and object sizes, so a future encrypted recovery manifest mode may be needed.

Recovery records can be generated explicitly before automatic recovery scheduling is implemented. Automatic recovery record generation depends on stable pruning and retention semantics.

### 3.6 Remote Repositories and Synchronization

Remote synchronization should be designed as transport for repository state, not as a separate backup format.

The remote model should handle:

- Immutable objects.
- Mutable snapshot records.
- Optional recovery record sets.
- Rebuildable indexes.
- Repository metadata, user configuration, and format negotiation.

Immutable objects are content-addressed and can be synchronized by object ID:

```text
local object IDs - remote object IDs = missing objects
upload missing objects
download missing objects
verify object hashes
```

Mutable snapshot records need conflict handling because users may edit notes, tags, pins, or retention hints from multiple clients.

Recommended conflict strategy:

- Keep immutable snapshot objects conflict-free.
- Version mutable snapshot records with `updatedAt` and a record revision.
- Detect concurrent edits instead of silently overwriting them.
- Resolve conflicts field-by-field when possible.
- Preserve conflicting records as explicit conflict copies when automatic merge is unsafe.

Remote repositories may be trusted or untrusted. For untrusted remotes:

- Object payloads should be encrypted before upload.
- Snapshot object metadata should be encrypted in full repository encryption mode.
- Mutable snapshot records may also need encryption.
- Remote providers should not need plaintext keys to store, list, or transfer repository files.

Remote synchronization should support resumable transfer:

- Upload and download temporary files.
- Commit files atomically on the remote when the provider supports it.
- Verify size and hash after transfer.
- Avoid relying on remote indexes as the source of truth.

The remote layout can mirror the local repository layout, or it can use a provider-specific API. The logical sync protocol should still be based on repository IDs, object IDs, snapshot record IDs, and recovery set IDs.

### 3.7 Large Object Chunking

Large files should be representable as ordered chunk references in the snapshot manifest. A chunk uses the same untyped object store as whole-file bytes.

Chunking must not change the logical identity of a file. A file's logical identity is based on its complete canonical file bytes, not on the chunk list used to store it.

The file manifest entry should store file-level identity:

```text
fileContentHash = hash(complete-file-bytes)
fileSize = complete-file-size
```

The chunk list is a storage representation for that file. Changing chunker parameters may change chunk boundaries and chunk object references, but it must not change `fileContentHash` for identical file bytes.

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

The preferred long-term chunking strategy is content-defined chunking, such as FastCDC, because it preserves deduplication when bytes are inserted or removed near the beginning of a large file. Fixed-size chunking is simpler but performs poorly after insertions or shifts.

Chunking implementation strategy:

1. Stream the source file sequentially.
2. Maintain a rolling or gear hash state for boundary detection.
3. Do not cut a chunk before `minSize`.
4. After `minSize`, cut a chunk when the boundary predicate matches the target average size.
5. Force a cut at `maxSize`.
6. Compute a cryptographic hash over the chunk bytes.
7. Convert the chunk hash to an object ID.
8. Write the chunk through the normal object pipeline.
9. Append the chunk reference to the file manifest entry.

The rolling or gear hash is only a boundary detector. It must never be used as object identity.

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

Chunker parameters must be stored in the manifest or internal repository metadata. Changing chunker parameters changes deduplication behavior, so a repository should treat them as part of the object format profile.

Each chunk should be compressed and encrypted independently. Do not compress a whole large file as one stream before chunking, because that would destroy chunk-level deduplication and make partial repair less useful.

Loose objects are a valid implementation slice. A pack format can group many small objects into pack files without changing snapshot manifests, as long as object IDs remain stable.

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

The `snapshots` directory should store mutable snapshot records, not full manifest bodies:

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

Mutable snapshot records remain necessary because content-addressed objects alone are not discoverable roots. `prune` should treat snapshot records as reachability roots, then walk snapshot objects and their referenced data objects.

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

Updating mutable metadata must not create a new snapshot object and must not change the snapshot object ID.

One implementation slice can store one snapshot manifest as one snapshot object. A tree-style metadata model can split very large manifests into reusable directory metadata payloads without changing the snapshot record model.

Snapshot object payloads should not use plain JSON as the long-term storage format. JSON examples in this document are schema illustrations only.

The immutable metadata stored in the object pool should use a canonical binary encoding:

- Deterministic field ordering.
- No duplicate keys.
- Normalized path strings.
- Explicit integer timestamp representation.
- Stable enum values for entry types.
- No floating point values.
- A magic and format version inside every structured metadata payload.

The object ID for a metadata payload should be computed from the canonical uncompressed metadata bytes before compression and encryption.

Mutable snapshot records outside the object pool may use JSON because they are user-editable catalog data, not content-addressed immutable metadata.

### 3.9 Sparse Restore and Checkout

The snapshot model should support sparse restore: restoring only selected paths or path patterns from a snapshot.

Simple sparse behavior can be implemented by loading the snapshot manifest and filtering entries:

```text
kaca restore <snapshot-id> <target> --path docs/readme.md
kaca restore <snapshot-id> <target> --include "src/**" --exclude "build/**"
```

This is enough for correctness but may be inefficient for very large snapshots because the whole manifest must be read.

Efficient long-term sparse checkout needs tree-style structured metadata payloads:

```text
snapshot object -> root tree reference -> directory tree references -> file content references
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

Sparse checkout state must not become a repository reachability root unless the user explicitly pins the snapshot. Otherwise, a local checkout could accidentally prevent expected pruning.

### 3.10 Atomic Writes

Snapshot creation must not leave behind a half-successful snapshot.

Recommended write order:

1. Write new content objects.
2. Write the snapshot manifest as a temporary snapshot object.
3. Verify that all objects referenced by the snapshot object exist.
4. Atomically move the snapshot object into the object store.
5. Atomically write the mutable snapshot record.
6. Update indexes.

If the process is interrupted, the repository should be able to detect and clean temporary files through `repair` or `verify`.

### 3.11 Verifiability

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
  repository.meta
  config.toml
  lock
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

- `repository.meta` stores binary internal metadata such as repository ID, repository format version, object format version, hash algorithm, metadata encoding, canonical compression profile, object layout, encryption mode, key derivation public parameters, and creation time.
- `config.toml` stores user-editable configuration such as remotes, recovery record defaults, retention defaults, scheduling preferences, and UI or service settings.
- `lock` prevents multiple processes from writing to the repository at the same time.
- `objects` stores untyped physical object envelopes keyed by object ID.
- `packs` stores immutable packed object files and pack indexes.
- `snapshots` stores mutable snapshot records that point to immutable snapshot metadata payloads.
- `indexes` stores rebuildable indexes and must not be treated as the only source of truth.
- `recovery` stores optional recovery record sets.
- `tmp` stores incomplete temporary writes.

Indexes can remain rebuildable from snapshot records and objects when needed.

The initial object format should be:

```text
magic
objectFormatVersion
headerLength
header
payload
```

The exact binary encoding is an implementation decision, but the header must be small, deterministic, and independent of snapshot records. This allows individual object files to be verified even when indexes are missing.

## 5. Snapshot Object Draft

Each mutable snapshot record points to a snapshot object. The snapshot object payload describes one immutable snapshot:

The example below is JSON for readability. The stored snapshot object should use a canonical binary metadata encoding.

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
        "id": "abcdef...",
        "contentSize": 1024,
        "storedSize": 512,
        "compression": "zstd",
        "encryption": "none"
      }
    }
  ]
}
```

Additional fields to define:

- Directory entries.
- Symbolic link entries.
- File permissions.
- Windows file attributes.
- Unix mode, uid, and gid.
- Extended attributes.
- ACLs.
- Case sensitivity policy.
- Whole-file content references.
- Chunked content references.

## 6. Main Modules

### 6.1 Repository

Manages repository initialization, internal metadata loading, user configuration loading, locking, path layout, and format compatibility.

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
- Store an object uncompressed when compression does not reduce size enough.
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
- Resolve object IDs to pack offsets.
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
- Restore timestamps and basic permissions.
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
- Coordinate with `prune` so recovery records do not reference deleted repository files indefinitely.

### 6.13 RemoteStore

Manages remote repository synchronization.

Suggested capabilities:

- Configure remote endpoints.
- List remote object IDs.
- Upload missing immutable objects.
- Download missing immutable objects.
- Upload and download mutable snapshot records with conflict detection.
- Synchronize recovery record sets.
- Verify transferred files by size and hash.
- Support resumable uploads and downloads.
- Keep remote indexes rebuildable and non-authoritative.

## 7. CLI Draft

The CLI surface should cover the architecture scope, while individual commands can be implemented incrementally:

```text
kaca init <repository>
kaca init <repository> --encrypt
kaca snapshot <source> --repo <repository>
kaca list --repo <repository>
kaca show <snapshot-id> --repo <repository>
kaca diff <from-snapshot-id> <to-snapshot-id> --repo <repository>
kaca restore <snapshot-id> <target> --repo <repository>
kaca restore <snapshot-id> <target> --repo <repository> --path <path>
kaca restore <snapshot-id> <target> --repo <repository> --include <pattern> --exclude <pattern>
kaca verify --repo <repository>
kaca prune --repo <repository> --keep-daily 7 --keep-weekly 4
kaca stats --repo <repository>
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
kaca checkout <snapshot-id> <target> --repo <repository> --sparse <spec>
kaca checkout add <path>
kaca checkout remove <path>
kaca checkout list
kaca mount <snapshot-id> --repo <repository>
kaca serve --repo <repository>
```

## 8. Architecture Baseline and Implementation Slices

The architecture baseline includes:

- Local and remote repositories.
- Untyped object envelopes.
- Untyped objects for whole files, chunks, and structured metadata payloads.
- Typed semantic references for snapshots, trees, files, and chunks.
- Mutable snapshot records.
- Compression.
- Optional encryption.
- Recovery records.
- Sparse restore and sparse checkout.
- Retention and pruning.
- Verification and repair.
- Remote synchronization.
- Scheduling and service integration.
- GUI integration points.

Implementation can be sliced without narrowing the architecture:

- Repository format, object envelope, and local object storage.
- Snapshot creation, listing, diff, restore, and sparse restore.
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

`prune` should not rely on old indexes to delete objects directly.

Safe strategy:

- Rebuild the reference set from all retained snapshots.
- Delete only objects that are not in the reference set.
- Default to dry run first.
- Write a prune transaction before actual deletion.

### 9.3 Repository Format Upgrades

Both `repository.meta` and structured metadata payload formats must record `formatVersion`.

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
- Record the repository default chunker profile in `repository.meta`.
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

Hard linking source files into the repository can corrupt backups because hard links share mutable file data.

Safe strategy:

- Do not hard link mutable source files into `objects`.
- Treat hard links as filesystem metadata to record and restore, not as a repository copy optimization.
- Prefer verified normal copies as the default import path.
- Consider reflink or platform copy-on-write clone support as an optimized copy path with verified semantics.
- Verify object content before making any optimized copy reachable.

### 9.10 Remote Sync Conflicts

Immutable objects are conflict-free because they are addressed by object ID. Mutable snapshot records are not conflict-free by default.

Safe strategy:

- Detect concurrent edits to mutable snapshot records.
- Keep record revisions and `updatedAt` timestamps.
- Merge independent fields when possible.
- Preserve explicit conflict copies when automatic merge is unsafe.
- Never let a remote index override local object reachability without verification.
- Treat remote storage as untrusted unless explicitly configured otherwise.

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
- Verify that object scanning covers the unified object pool.
- Verify that existing-object reuse checks logical size and repository hash compatibility.
- Verify that structured payload parsing fails when an expected format does not match.
- Verify that logical size mismatches fail validation.
- Verify that high-assurance mode compares logical bytes before reusing an existing object.
- Verify that compressed objects restore to the original content.
- Verify that object headers and payloads are checked by `verify`.
- Verify that objects remain self-describing without sidecar metadata files.
- Pack loose objects and verify that packed objects restore correctly.
- Repack live objects during pruning without losing references.
- Verify encrypted objects authenticate, decrypt, decompress, and restore to the original content.
- Verify that encrypted object IDs are stable for the same logical plaintext even when encryption nonces differ.
- Verify that encrypted object IDs do not expose raw plaintext content hashes.
- Verify that encrypted repositories use keyed object IDs rather than public salted hashes.
- Verify that wrong keys fail before decompression or restore.
- Verify that recovery records detect physical file corruption.
- Verify that recovery records can repair a damaged protected file when enough redundancy is available.
- Verify that external recovery records can be matched to the correct repository by repository ID.
- Create a chunked large-file snapshot and restore it byte-for-byte.
- Verify that file-level content hash is independent of chunk boundaries.
- Verify that chunk boundaries respect `minSize` and `maxSize`.
- Verify that rolling hash state is used only for boundaries, not object identity.
- Modify a large file in the middle and verify that unchanged chunks are reused.
- Insert bytes near the beginning of a large file and compare fixed-size chunking with content-defined chunking.
- Manually corrupt an object and confirm that `verify` detects it.
- Delete an old snapshot without deleting objects that are still referenced.
- Confirm that interrupted backups do not pollute the official repository state with temporary files.

## 11. Open Research Questions

- Should the default hash algorithm be `SHA-256` or `BLAKE3`?
- Should high-assurance mode store or verify a secondary hash?
- Should immutable metadata use canonical CBOR, deterministic Protocol Buffers, or a custom binary format?
- What canonical encoding should be used for metadata object IDs?
- Should very large snapshot manifests become tree-style structured metadata payloads?
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
- Should loose objects be packed after snapshot creation?
- What pattern syntax should sparse restore use?
- Should long-lived sparse checkout state be stored in the target directory or in the repository?
- Is a database index such as SQLite needed?
- How should Windows ACLs and Unix permissions be represented in the architecture baseline?
- Should exclude rules be compatible with `.gitignore` syntax?
- Should restore overwrite target files by default?
- Should `snapshot` fail, skip, or produce a partial snapshot when it sees unstable files?

## 12. Recommended Sequence

1. Define the repository format, untyped object envelope format, mutable snapshot record format, encryption extension points, recovery record layout, and manifest schema.
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
