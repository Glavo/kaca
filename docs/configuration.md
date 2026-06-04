# Configuration Model

This document defines the user-editable configuration files used by `kaca`.

Repository identity, object identity, digest profiles, object layout, canonical compression profile, encryption mode, and repository format versions are stored in `repository.meta`. Configuration files store policy, defaults, local client settings, remote endpoints, and job definitions.

## 1. Configuration Layers

Configuration is resolved from ordered layers:

```text
command invocation overrides
job configuration
repository-local client configuration
repository configuration
user configuration
system configuration
built-in defaults
```

Higher layers override lower layers for scalar fields. Map fields merge by key. Array fields replace the lower-layer value unless the field defines a keyed merge rule.

Configuration file locations:

| Layer | Path |
|---|---|
| System configuration | platform configuration directory, `kaca/config.toml` |
| User configuration | user configuration directory, `kaca/config.toml` |
| Repository configuration | `<repository>/config.toml` |
| Repository-local client configuration | `<repository>/local/config.toml` |
| Job configuration | `<repository>/jobs/<job-name>.toml` or user configuration job directory |

The parser accepts the keys defined for the file's layer. Extension data is stored under `[extensions.<name>]`.

Repository synchronization includes repository configuration and repository state. Repository-local client configuration is resolved by the local client that owns the `local` directory.

## 2. Repository Configuration

`<repository>/config.toml` stores repository policy and portable repository defaults.

```toml
config_version = 1

[policy.metadata]
default_capture = "portable"

[policy.retention]
keep_daily = 7
keep_weekly = 4
keep_monthly = 12
keep_yearly = 0

[policy.recovery]
enabled = false
redundancy_percent = 10
placement = "repository"

[extensions.example]
enabled = true
```

### 2.1 Top-Level Keys

| Key | Type | Default | Description |
|---|---|---|---|
| `config_version` | integer | required | Configuration format version. |

### 2.2 `[policy.metadata]`

| Key | Type | Default | Description |
|---|---|---|---|
| `default_capture` | string | `"portable"` | Repository default filesystem metadata capture profile for new snapshots. |

### 2.3 `[policy.retention]`

| Key | Type | Default | Description |
|---|---:|---:|---|
| `keep_daily` | integer | 7 | Number of daily snapshots retained by default. |
| `keep_weekly` | integer | 4 | Number of weekly snapshots retained by default. |
| `keep_monthly` | integer | 12 | Number of monthly snapshots retained by default. |
| `keep_yearly` | integer | 0 | Number of yearly snapshots retained by default. |

Retention values are non-negative integers.

### 2.4 `[policy.recovery]`

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

## 3. Client Configuration

System, user, and repository-local client configuration files use the same schema. Repository-local client configuration is stored at `<repository>/local/config.toml`.

```toml
config_version = 1
client_id = "desktop-main"

[metadata]
capture = "portable"
restore = "portable"
restore_errors = "warn"

[restore]
overwrite = "ask"
target_conflicts = "fail"

[service]
enabled = false
listen = "127.0.0.1:0"

[ui]
theme = "system"
locale = "en-US"

[[remotes]]
name = "origin"
kind = "sftp"
url = "sftp://backup.example.com/kaca/repository"
trust = "untrusted"
credentials = "keyring:kaca/origin"
connections = 4
```

### 3.1 Top-Level Keys

| Key | Type | Default | Description |
|---|---|---|---|
| `config_version` | integer | required | Configuration format version. |
| `client_id` | string | absent | Stable client identifier for diagnostics and multi-client conflict records. |

### 3.2 `[metadata]`

| Key | Type | Default | Description |
|---|---|---|---|
| `capture` | string | absent | Client default filesystem metadata capture profile for new snapshots. |
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

### 3.3 `[restore]`

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

### 3.4 `[service]`

| Key | Type | Default | Description |
|---|---|---|---|
| `enabled` | boolean | `false` | Enables background service integration. |
| `listen` | string | absent | Service listen endpoint. |

### 3.5 `[ui]`

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

### 3.6 `[[remotes]]`

Each remote entry defines one synchronization target for the active client.

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

Plain secret values are stored outside configuration files.

## 4. Job Configuration

Job configuration files define repeatable snapshot tasks.

```toml
job_version = 1
name = "home"
source = "D:/Users/example"
repository = ".."

[metadata]
capture = "portable"

[schedule]
enabled = true
calendar = "daily 02:00"

[filters]
include = ["**"]
exclude = ["**/build/**", "**/.gradle/**"]
```

### 4.1 Top-Level Keys

| Key | Type | Default | Description |
|---|---|---|---|
| `job_version` | integer | required | Job configuration format version. |
| `name` | string | required | Stable job name. |
| `source` | string | required | Source path for snapshots created by the job. |
| `repository` | string | absent | Repository path for user-level job files. |

### 4.2 `[metadata]`

| Key | Type | Default | Description |
|---|---|---|---|
| `capture` | string | resolved | Filesystem metadata capture profile for snapshots created by the job. |

### 4.3 `[schedule]`

| Key | Type | Default | Description |
|---|---|---|---|
| `enabled` | boolean | `false` | Enables scheduled snapshots for the job. |
| `calendar` | string | absent | Calendar expression for scheduled snapshots. |

The calendar expression format is a separate scheduler interface decision.

### 4.4 `[filters]`

| Key | Type | Default | Description |
|---|---|---|---|
| `include` | array of strings | absent | Include patterns for source scanning. |
| `exclude` | array of strings | absent | Exclude patterns for source scanning. |

## 5. Extensions

Extensions use namespaced tables:

```toml
[extensions.example]
enabled = true
value = "custom"
```

Extension names are lowercase ASCII identifiers containing letters, digits, `_`, and `-`.

## 6. Value Resolution

Effective values are resolved with origin tracking. Diagnostics and `config get` output include the layer and file path that supplied each value.

Example resolution for snapshot metadata capture:

```text
command invocation override
job [metadata].capture
repository-local client [metadata].capture
repository [policy.metadata].default_capture
user [metadata].capture
system [metadata].capture
built-in default
```

Example resolution for restore behavior:

```text
command invocation override
repository-local client [restore]
user [restore]
system [restore]
built-in default
```

Remote definitions are keyed by remote name. A higher-layer remote with the same name replaces the lower-layer remote.

## 7. Validation Rules

The parser validates:

- Configuration and job format versions.
- Allowed sections for each configuration layer.
- Field types.
- Enum values.
- Non-negative retention values.
- Recovery redundancy range.
- Unique remote names after layer resolution.
- Extension table names.
- Required job fields.

Invalid configuration files produce diagnostics with file path, layer, table name, key name, invalid value, and expected value set.
