# Repository Configuration

This document defines the user-editable `config.toml` format.

`config.toml` stores operational preferences. Repository identity, object identity, hash algorithm, object layout, canonical compression profile, encryption mode, and repository format versions are stored in `repository.meta`.

## 1. File Shape

```toml
config_version = 1

[metadata]
capture = "portable"
restore = "portable"
restore_errors = "warn"

[restore]
overwrite = "ask"
target_conflicts = "fail"

[retention]
keep_daily = 7
keep_weekly = 4
keep_monthly = 12
keep_yearly = 0

[recovery]
enabled = false
redundancy_percent = 10
placement = "repository"

[schedule]
enabled = false

[service]
enabled = false

[ui]
theme = "system"

[[remotes]]
name = "origin"
kind = "sftp"
url = "sftp://backup.example.com/kaca/repository"
trust = "untrusted"
```

The parser accepts the keys defined in this document. Extension data is stored under `[extensions.<name>]`.

## 2. Top-Level Keys

| Key | Type | Default | Description |
|---|---|---|---|
| `config_version` | integer | required | User configuration format version. |

## 3. `[metadata]`

| Key | Type | Default | Description |
|---|---|---|---|
| `capture` | string | `"portable"` | Default filesystem metadata capture profile for new snapshots. |
| `restore` | string | `"portable"` | Default filesystem metadata restore profile for restore operations. |
| `restore_errors` | string | `"warn"` | Handling mode for metadata restore failures. |

Metadata profile values:

| Value | Meaning |
|---|---|
| `"portable"` | Paths, entry types, content references, logical sizes, modified times, executable bits, read-only bits, and symbolic link targets. |
| `"system"` | `portable` metadata plus POSIX mode, uid, gid, user name, group name, and Windows file attributes. |
| `"full"` | `system` metadata plus ACLs, extended attributes, macOS metadata, Windows security descriptors, and Windows reparse point metadata. |

`restore_errors` values:

| Value | Meaning |
|---|---|
| `"warn"` | Restore file content and report metadata failures in the restore report. |
| `"fail"` | Treat metadata restore failures as restore failures. |
| `"ignore"` | Suppress metadata restore failure reporting. |

## 4. `[restore]`

| Key | Type | Default | Description |
|---|---|---|---|
| `overwrite` | string | `"ask"` | Default overwrite behavior for existing target paths. |
| `target_conflicts` | string | `"fail"` | Handling mode for target path conflicts. |

`overwrite` values:

| Value | Meaning |
|---|---|
| `"ask"` | Require an explicit command decision before overwriting. |
| `"never"` | Preserve existing target files. |
| `"always"` | Replace existing target files. |

`target_conflicts` values:

| Value | Meaning |
|---|---|
| `"fail"` | Stop the restore operation when a path conflict is detected. |
| `"rename"` | Restore the conflicting path with a generated conflict suffix. |
| `"overwrite"` | Apply the configured overwrite behavior. |

## 5. `[retention]`

| Key | Type | Default | Description |
|---|---:|---:|---|
| `keep_daily` | integer | 7 | Number of daily snapshots retained by default. |
| `keep_weekly` | integer | 4 | Number of weekly snapshots retained by default. |
| `keep_monthly` | integer | 12 | Number of monthly snapshots retained by default. |
| `keep_yearly` | integer | 0 | Number of yearly snapshots retained by default. |

Retention values are non-negative integers.

## 6. `[recovery]`

| Key | Type | Default | Description |
|---|---|---|---|
| `enabled` | boolean | `false` | Default recovery record generation switch. |
| `redundancy_percent` | integer | 10 | Default recovery redundancy percentage. |
| `placement` | string | `"repository"` | Default recovery output placement. |
| `external_path` | string | absent | External recovery output path when `placement = "external"`. |

`placement` values:

| Value | Meaning |
|---|---|
| `"repository"` | Store recovery records under the repository `recovery` directory. |
| `"external"` | Store recovery records under `external_path`. |

`redundancy_percent` is an integer from 1 through 100.

## 7. `[schedule]`

| Key | Type | Default | Description |
|---|---|---|---|
| `enabled` | boolean | `false` | Enables scheduled snapshots. |
| `calendar` | string | absent | Calendar expression for scheduled snapshots. |
| `source` | string | absent | Default source path for scheduled snapshots. |

The calendar expression format is a separate scheduler interface decision.

## 8. `[service]`

| Key | Type | Default | Description |
|---|---|---|---|
| `enabled` | boolean | `false` | Enables background service integration. |
| `listen` | string | absent | Service listen endpoint. |

## 9. `[ui]`

| Key | Type | Default | Description |
|---|---|---|---|
| `theme` | string | `"system"` | UI theme preference. |
| `locale` | string | absent | UI locale override. |

`theme` values:

| Value |
|---|
| `"system"` |
| `"light"` |
| `"dark"` |

## 10. `[[remotes]]`

Each remote entry defines one synchronization target.

| Key | Type | Required | Description |
|---|---|---|---|
| `name` | string | yes | Unique remote name. |
| `kind` | string | yes | Remote backend kind. |
| `url` | string | yes | Remote location. |
| `trust` | string | no | Remote trust level. Default: `"untrusted"`. |
| `credentials` | string | no | Credential reference. |
| `connections` | integer | no | Maximum concurrent transfer count. |

Remote `kind` values:

| Value |
|---|
| `"local"` |
| `"sftp"` |
| `"s3"` |
| `"webdav"` |
| `"rest"` |

Remote `trust` values:

| Value | Meaning |
|---|---|
| `"trusted"` | Remote storage is treated as controlled infrastructure. |
| `"untrusted"` | Remote storage receives encrypted repository data when encryption is enabled. |

Credential references identify an external credential source:

```toml
credentials = "env:KACA_REMOTE_ORIGIN_PASSWORD"
credentials = "keyring:kaca/origin"
credentials = "file:secrets/origin.credentials"
```

Plain secret values are stored outside `config.toml`.

## 11. Extensions

Extensions use namespaced tables:

```toml
[extensions.example]
enabled = true
value = "custom"
```

Extension names are lowercase ASCII identifiers containing letters, digits, `_`, and `-`.

## 12. Validation Rules

The parser validates:

- `config_version`.
- Known top-level sections.
- Field types.
- Enum values.
- Non-negative retention values.
- Recovery redundancy range.
- Unique remote names.
- Extension table names.

Invalid configuration files produce diagnostics with table name, key name, invalid value, and expected value set.
