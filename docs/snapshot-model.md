# Snapshot Model

This document defines semantic rules for snapshot objects, tree objects, and snapshot records.

Binary field layouts are defined in `docs/repository-format.md`.

## 1. Snapshot Records and Snapshot Objects

Snapshot records are mutable reachability roots stored outside the object pool. Snapshot objects are immutable structured payloads stored in the object pool.

Restore-critical data is stored in snapshot objects:

- Source roots.
- Source IDs.
- Captured source names.
- Root display names.
- Source root kinds.
- Tree entries.
- Target references.
- Chunk references.
- Captured filesystem metadata.
- Parent snapshot object reference.

Mutable user metadata is stored in snapshot records:

- Title.
- Notes.
- Tags.
- Pin state.
- Retention hints.
- UI state.

Updating a snapshot record does not change the snapshot object ID.

## 2. Source Roots

A snapshot contains one or more source roots.

Source root rules:

- Source IDs are unique within the snapshot.
- Source IDs follow the UUID rules in `docs/repository-format.md`.
- Captured source names follow the source name rules in `docs/repository-format.md`.
- Source root kinds are `directory` or `regular file`.
- Captured source names, display names, and source paths are display and audit metadata.
- Source name changes and source path changes do not change the meaning of an existing snapshot root.

Directory roots contain a root directory tree. File roots contain one regular file entry using the captured file name as the entry name.

Overlapping source roots remain separate logical roots.

## 3. Tree Objects

A tree object represents one directory.

Tree entry rules:

- Entry names are single normalized path segments.
- Entries are sorted by entry name UTF-8 byte order in deterministic encodings.
- Duplicate entry names are invalid.
- Case-insensitive-preserving roots reject entries whose names collide under the root case comparison policy.
- A directory entry references a child tree object through a tree reference.
- A regular file entry references file content through a content reference.
- A symbolic link entry stores its target in captured metadata and has no target reference.
- A hard link entry references file content and carries a hard link group ID in captured metadata.
- A special file entry stores platform metadata and has no content reference.

## 4. Target References

Tree entry `target reference` is interpreted by entry type.

| Entry type | Target reference |
|---|---|
| regular file | content reference |
| directory | tree reference |
| symbolic link | null |
| hard link | content reference |
| special file | null |

Target references are typed by the surrounding entry type and validated by expected structured payload formats where applicable.

## 5. Regular File Content

Regular files support single-object and chunk-list storage.

Single-object content rules:

- The content reference points to one raw bytes object.
- Logical size equals the file size.
- File content digest is the digest of the complete logical file bytes.

Chunk-list content rules:

- Chunk references are ordered by file offset.
- The first chunk offset is zero.
- Each chunk starts at the previous chunk offset plus previous chunk logical size.
- The final chunk ends at the file logical size.
- File content digest is the digest of the complete logical file bytes.
- Chunk object identity is independent from file-level identity.

Changing chunker parameters changes chunk reuse behavior but does not change file-level identity for identical file bytes.

## 6. Symbolic Links

Symbolic link entries store the link target string in captured metadata.

The stored target is the link target observed from the filesystem. It is not resolved to an absolute path during capture.

Restoration recreates the symbolic link when the platform and restore profile allow it. Metadata restore failure reporting records unsupported symbolic link creation separately from content restore failures.

## 7. Hard Links

Hard links are represented as filesystem metadata.

Entries belonging to the same hard link group share the same hard link group ID. A hard link group ID is scoped to one snapshot object.

Restore behavior:

- Platforms supporting hard link creation may restore entries in the same group as hard links.
- Platforms without hard link support restore duplicate file contents and report metadata fallback.
- Content object deduplication still reuses identical file bytes regardless of hard link metadata.

## 8. Special Files

Special file entries represent filesystem nodes that are neither regular files, directories, symbolic links, nor hard links.

Special file metadata may include POSIX device major and minor values, Windows reparse point metadata, and platform-specific attributes according to the active metadata capture profile.

Restore creates special files only when the active restore profile, platform, and process privileges allow it. Unsupported special file restoration is reported as metadata restore failure.

## 9. Parent Snapshot Reference

The parent snapshot object reference records lineage for diff, display, and retention policy.

Parent references do not affect object reachability unless a retained snapshot record references the child snapshot. `prune` computes reachability from retained snapshot records and then walks referenced snapshot objects.

## 10. Validation

Snapshot validation checks:

- Snapshot object payload magic and version.
- Unique source IDs.
- Valid source ID UUID syntax.
- Valid captured source name syntax.
- Valid source root kind.
- Valid tree entry names.
- Valid entry target reference for each entry type.
- Valid chunk ordering and file size coverage.
- Captured metadata fields allowed by the captured metadata profile.
- Existence and integrity of every referenced object.
