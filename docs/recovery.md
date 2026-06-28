# Recovery Records

This document defines recovery record sets for physical repository repair.

## 1. Scope

Recovery records protect repository-owned physical files after compression, encryption, and envelope encoding.

Protected files may include:

- `repository`.
- `config.toml`.
- Source configuration files.
- Job configuration files.
- Profile configuration files.
- Snapshot record files.
- Loose object files.
- Pack files.
- Pack index files.
- Recovery set manifests.

Rebuildable indexes are excluded by default.

Recovery records do not define object reachability. Snapshot records and structured snapshot objects remain the source of truth for liveness.

## 2. Layout

Repository-local recovery sets are stored under:

```text
recovery/
  sets/
    <set-id>/
      manifest.json
      volumes/
        <set-id>.par2
        <set-id>.vol00+01.par2
```

External recovery output uses the same set layout under an external root:

```text
<external-root>/
  <repository-id>/
    sets/
      <set-id>/
        manifest.json
        volumes/
```

Set IDs are opaque lowercase hexadecimal strings with at least 128 bits of random entropy.

## 3. Manifest

`manifest.json` is program-managed JSON encoded as UTF-8.

Manifest fields:

| Field | Type | Description |
|---|---|---|
| `manifestVersion` | integer | Recovery manifest format version. |
| `repositoryId` | string | Repository ID. |
| `setId` | string | Recovery set ID. |
| `createdAt` | string | UTC timestamp. |
| `algorithm` | string | Recovery algorithm. |
| `redundancyPercent` | integer | Redundancy percentage. |
| `placement` | string | `repository` or `external`. |
| `protectedFiles` | array | Protected file records. |
| `volumes` | array | Recovery volume records. |

Protected file record:

| Field | Type | Description |
|---|---|---|
| `path` | string | Path relative to the repository root. |
| `kind` | string | Repository file kind. |
| `size` | integer | File size in bytes. |
| `physicalDigest` | string | Digest of the protected physical file bytes. |
| `modifiedAt` | string | File modified time observed during set creation. |

Recovery volume record:

| Field | Type | Description |
|---|---|---|
| `path` | string | Volume path relative to the recovery set directory. |
| `size` | integer | Volume size in bytes. |
| `physicalDigest` | string | Digest of the recovery volume bytes. |

## 4. Algorithms

Version 1 supports PAR2-compatible recovery sets:

```text
algorithm = "par2"
```

The PAR2 source file list is derived from `protectedFiles`. Generated recovery volumes are excluded from their own protected file set.

Future internal Reed-Solomon formats may use separate algorithm names. Recovery algorithm names are stable wire-format identifiers.

## 5. Protected File Selection

Recovery creation selects a fixed file set before generating recovery data.

Default protected file kinds:

| Kind | Included |
|---|---|
| `repository` | yes |
| `repository-config` | yes |
| `source-config` | yes |
| `job-config` | yes |
| `profile-config` | yes |
| `remote-config` | yes |
| `local-config` | no |
| `local-remote-config` | no |
| `snapshot-record` | yes |
| `loose-object` | yes |
| `pack` | yes |
| `pack-index` | yes |
| `recovery-manifest` | yes |
| `rebuildable-index` | no |
| `temporary-file` | no |
| `lock-file` | no |

The protected file set is immutable for a recovery set. A later repository state requires a new recovery set.

## 6. Creation

Recovery set creation sequence:

1. Acquire the repository writer lock or a recovery maintenance lock.
2. Build the protected file list.
3. Record size, modified time, and physical digest for each protected file.
4. Generate recovery volumes.
5. Verify generated recovery volumes.
6. Write `manifest.json`.
7. Publish the recovery set atomically through repository transaction rules.

Files that change during recovery generation are excluded from the set and reported, or the generation command restarts with a fresh protected file list.

## 7. Verification

Recovery verification checks:

- Manifest JSON parses.
- Repository ID matches.
- Protected file paths are relative repository paths.
- Protected file size and digest match the manifest when the file exists.
- Recovery volume size and digest match the manifest.
- PAR2 verification succeeds for the protected file set.

Missing protected files are reported as missing. Modified protected files are reported as stale relative to the recovery set.

## 8. Repair

Recovery repair uses the selected recovery set to repair protected physical files.

Repair rules:

- Repair writes into a transaction workspace.
- Repaired files are verified against manifest size and digest before publication.
- Publication uses repository transaction rules.
- Repair never changes snapshot reachability.
- Repair does not use recovery records to decide which objects are live.

After repair, `verify` validates repository semantics including object envelopes, snapshot records, pack indexes, and structured snapshot payloads.

## 9. Prune Coordination

Prune may make existing recovery sets stale.

Prune reports recovery sets that reference files planned for deletion. Recovery maintenance may create replacement recovery sets after prune completes.

Prune does not delete recovery sets automatically unless recovery retention policy selects them. Recovery set deletion is a recovery maintenance operation.

## 10. Encryption and Visibility

Recovery records protect encrypted physical files when repository encryption is enabled. Recovery repair does not require decrypting object payloads.

Repository-local recovery manifests expose protected relative paths, file sizes, and recovery set metadata. Encrypted recovery manifests are represented by a future manifest mode and use the same recovery volume layout.
