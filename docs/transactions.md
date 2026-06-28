# Repository Transactions

This document defines repository mutation, atomic publication, and crash recovery rules.

## 1. Scope

Repository transactions cover operations that create, replace, or delete repository-owned files:

- Loose object publication.
- Snapshot record creation and replacement.
- Pack and pack index publication.
- Repack and prune deletion.
- Recovery record publication.
- Remote pull materialization.
- Repository format migration.

Indexes are rebuildable and do not define object reachability.

## 2. Locking

Mutating commands acquire the repository writer lock before publishing repository state. Multiple readers may run while no writer is publishing a partially visible state.

The writer lock is stored at:

```text
lock
```

The lock payload records:

- Lock format version.
- Process ID.
- Host name.
- Client ID.
- Creation time.
- Command name.
- Random lock token.

Lock acquisition uses atomic file creation. Lock release deletes the lock only when the stored lock token matches the owning process token.

Stale lock recovery is an explicit repair operation. Normal commands report the stale lock candidate and stop.

## 3. Transaction Workspace

Each mutating command writes temporary files under:

```text
tmp/transactions/<transaction-id>/
```

Transaction IDs are opaque ASCII strings containing creation time and random entropy:

```text
<unix-ms>-<random-128-bit-hex>
```

Each transaction directory contains a transaction manifest:

```text
transaction
```

The transaction manifest records:

- Transaction format version.
- Transaction ID.
- Operation kind.
- Repository ID.
- Creation time.
- Client ID.
- Planned publish targets.
- Planned deletion targets.

The transaction manifest is internal program metadata and is encoded with deterministic CBOR.

## 4. Atomic Publish Rules

Temporary files are written on the same filesystem as their final repository target. Publication uses atomic rename within that filesystem.

Before publish, the writer flushes:

- Temporary file contents.
- Temporary file metadata needed by the platform.
- Parent directories that gain new directory entries.

After publish, the writer flushes the final parent directory before the transaction is considered durable.

If a target already exists for an immutable file, the writer verifies that the existing target matches the expected identity and discards the temporary file.

## 5. Loose Object Publish

Loose object publication writes one complete object envelope to a transaction temporary file.

Publish sequence:

1. Write the object envelope temporary file.
2. Flush the temporary file.
3. Verify the object ID, logical size, object header, payload authentication, and physical checksum.
4. Create the fanout directory when needed.
5. Atomically rename the temporary file to `objects/<fanout>/<object-id>`.
6. Flush the fanout directory.

An object is reachable only through snapshot records, pack indexes, or other authoritative references. A loose object published before a snapshot record is an orphan until a reachable snapshot references it.

## 6. Snapshot Record Publish

Snapshot records are mutable reachability roots. Snapshot record creation writes a full replacement file to a transaction temporary file and atomically renames it into `snapshots/`.

Snapshot creation publish order:

1. Publish all referenced content objects.
2. Publish all referenced tree and snapshot objects.
3. Verify that the snapshot object can be parsed and all referenced objects exist.
4. Publish the snapshot record.
5. Update rebuildable indexes.

If the process stops before the snapshot record is published, the published objects remain valid orphans.

## 7. Pack Publish

Pack publication writes pack and index files in one transaction.

Pack files are discoverable only when both files exist and the index validates the pack checksum:

```text
objects/packs/<pack-id>.pack
objects/packs/<pack-id>.idx
```

Publish sequence:

1. Write the temporary pack file.
2. Flush and verify the pack file.
3. Write the temporary pack index.
4. Flush and verify the pack index.
5. Atomically rename the pack file into `objects/packs/`.
6. Atomically rename the pack index into `objects/packs/`.
7. Flush the `objects/packs/` directory.

Loose objects remain authoritative until pruning removes them after the new pack is visible and verified.

## 8. Prune and Repack Deletion

Deletion operations compute liveness from snapshot records and structured snapshot objects before deleting files.

Deletion transactions record planned deletion targets in the transaction manifest before deleting repository files.

Deletion rules:

- Delete only files listed in the transaction manifest.
- Recheck liveness immediately before deletion.
- Preserve objects referenced by retained snapshot records.
- Preserve packs whose indexes are invalid until repair decides their disposition.
- Delete recovery records only through recovery set maintenance.

Interrupted deletion leaves either the old file or no file. `verify` and `repair` recompute liveness from authoritative roots.

## 9. Crash Recovery

Temporary transaction directories are non-authoritative. After confirming that no writer lock is active, `repair` may remove incomplete transaction directories.

Crash recovery rules:

- Published loose objects with no references are reported as orphans.
- Published snapshot objects without snapshot records are reported as orphan snapshot objects.
- Snapshot records pointing to missing objects are reported as broken reachability roots.
- Pack files without valid indexes are ignored by normal object lookup and reported by `verify`.
- Pack indexes without matching pack files are ignored and reported by `verify`.
- Rebuildable indexes may be deleted and regenerated.

`repair` must not delete reachable objects. Object deletion requires a fresh liveness computation.

## 10. Remote Pull Materialization

Remote downloads materialize into transaction temporary files before local publication.

Remote pull publication uses the same local atomic publish rules as local writers. Downloaded files are verified before publication using repository identity, object IDs, pack checksums, snapshot record parsing, and recovery set metadata.
