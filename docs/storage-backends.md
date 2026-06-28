# Storage Backends

This document defines storage backend roles, repository locators, source locators, execution sites, and single-file repository modes.

## 1. Roles

`kaca` separates source access from repository storage.

```text
SourceStore
  reads files, directories, and filesystem metadata

RepositoryStore
  stores repository state, the object database, snapshot records, recovery records, and configuration

ExecutionSite
  runs scanning, hashing, chunking, repository writes, restore, verify, prune, and sync orchestration
```

Repository storage does not need to be located on the same device or host as the protected files.

## 2. SourceStore

A `SourceStore` provides read access to files and metadata.

Source backend kinds:

| Kind | Description |
|---|---|
| `local-filesystem` | Local OS filesystem access. |
| `sftp` | Files read through SFTP. |
| `smb` | Files read through SMB-compatible shares. |
| `agent` | Files read through a `kaca` agent running near the source. |
| `rest` | Files read through a repository-specific REST source API. |

SourceStore capabilities:

- Enumerate source roots.
- Read regular file bytes.
- Read filesystem metadata.
- Detect symbolic links and special files when supported.
- Provide stable reads or report unstable files.
- Expose source-side consistency hooks when available.

Metadata fidelity depends on the source backend and active capture profile.

## 3. RepositoryStore

A `RepositoryStore` exposes repository operations.

Repository backend kinds:

| Kind | Mode | Description |
|---|---|---|
| `file-tree` | active | Repository layout stored as files and directories. |
| `sftp-tree` | active | Repository layout stored through SFTP. |
| `s3-object` | active | Repository layout mapped to object keys. |
| `webdav-tree` | active | Repository layout stored through WebDAV resources. |
| `rest` | active | Repository operations exposed by a `kaca` repository service. |
| `zip-archive` | archive | Single-file ZIP repository archive. |
| `sevenzip-archive` | archive | Single-file 7z repository archive. |
| `bundle-file` | active | Single-file append-oriented `kaca` repository bundle. |

RepositoryStore operations:

- Read `repository`.
- Read and write repository configuration.
- List, read, and publish immutable objects.
- List, read, and publish packs and pack indexes.
- List, read, publish, and replace mutable snapshot records.
- List, read, and publish recovery record sets.
- Acquire writer coordination when the backend supports it.
- Materialize transactions or emulate transaction semantics through staging.

## 4. Backend Capabilities

Backends declare capabilities before mutation.

| Capability | Meaning |
|---|---|
| `atomic-rename` | Can publish a staged file to its final name atomically. |
| `conditional-create` | Can create a final name only when it is absent. |
| `conditional-replace` | Can replace a final name only when the observed revision matches. |
| `range-read` | Can read byte ranges without downloading the whole file. |
| `list` | Can enumerate repository items. |
| `stable-read-after-write` | Can verify a just-written item through the same backend. |
| `append` | Can append records to an existing physical file. |
| `server-side-copy` | Can clone or copy repository data within the backend. |
| `lock` | Can provide repository writer coordination. |

Repository mutation adapts to backend capabilities. Missing capabilities require transaction emulation, full-file rewrite, conflict records, or read-only mode.

## 5. Execution Sites

Backup and restore commands can run at different execution sites.

| Execution site | Source access | Repository access |
|---|---|---|
| source-side | Local or nearby source access. | Writes to local or remote RepositoryStore. |
| repository-side | Reads source through SourceStore backend. | Local or nearby repository access. |
| controller-agent | Coordinates source agents and repository backend. | Writes through RepositoryStore API. |

Source-side execution gives the most direct filesystem metadata access. Repository-side execution requires a SourceStore backend with sufficient metadata and consistency capabilities.

## 6. Repository Locators

Repository locators identify RepositoryStore targets.

Examples:

```text
D:/backup/kaca
sftp://backup.example.com/kaca/repository
s3://bucket/kaca/repository
webdav://backup.example.com/kaca/repository
rest+https://backup.example.com/kaca/repository
zip:file:///D:/archives/world.kaca.zip
7z:file:///D:/archives/world.kaca.7z
bundle:file:///D:/archives/world.kaca.bundle
```

Local paths without a URI scheme are `file-tree` repository locators.

## 7. Source Locators

Source locators identify SourceStore roots.

Examples:

```text
D:/Minecraft/world
sftp://server.example.com/home/minecraft/world
smb://fileserver/worlds/main
agent://minecraft-server/worlds/main
rest+https://source.example.com/worlds/main
```

Local paths without a URI scheme are `local-filesystem` source locators.

## 8. Single-File Repository Modes

Single-file repository modes store repository state inside one file.

### 8.1 ZIP Archive

`zip-archive` stores the repository layout inside a ZIP file.

ZIP archive rules:

- Object and pack entries use store mode by default because repository objects are already compressed and may be encrypted.
- Archive reads support list, verify, restore, and import.
- Archive rewrite writes a complete replacement archive and publishes it atomically through a temporary sibling file.
- Archive mutation is serialized by a writer lock when the archive is writable.

### 8.2 7z Archive

`sevenzip-archive` stores the repository layout inside a 7z file.

7z archive rules:

- Solid compression is disabled for repository object and pack entries.
- Archive reads support list, verify, restore, and import.
- Archive rewrite writes a complete replacement archive and publishes it atomically through a temporary sibling file.
- 7z archives are optimized for migration and cold storage rather than frequent mutation.

### 8.3 Bundle File

`bundle-file` is an append-oriented single-file repository backend.

Bundle file rules:

- Repository records are appended as immutable segments.
- A footer index maps repository paths and object IDs to segment offsets.
- Checkpoint indexes make startup independent of scanning the whole file.
- Prune and repack create a new compacted bundle and atomically replace the old bundle.
- External recovery records protect the bundle file as one physical file.

## 9. Synchronization

Synchronization copies repository state between RepositoryStore backends.

Examples:

```text
file-tree -> sftp-tree
sftp-tree -> file-tree
file-tree -> zip-archive
zip-archive -> file-tree
s3-object -> file-tree
bundle-file -> s3-object
```

The logical repository model is independent from the physical RepositoryStore backend.
