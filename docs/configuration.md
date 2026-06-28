# Configuration Model

This document defines the user-editable configuration files used by `kaca`.

Repository identity, object identity, digest profiles, object layout, canonical compression profile, encryption mode, and repository format versions are stored in `repository`. Configuration files store policy, defaults, local client settings, repository endpoints, source definitions, and job definitions.

## 1. Configuration Layers

Configuration is resolved from ordered layers:

```text
command invocation overrides
job source reference configuration
job configuration
source configuration
profile configuration
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
| Source configuration | `<repository>/sources/<source-name>.toml` or user configuration source directory |
| Profile configuration | `<repository>/profiles/<profile-name>.toml` or user configuration profile directory |
| Job configuration | `<repository>/jobs/<job-name>.toml` or user configuration job directory |

The parser accepts the keys defined for the file's layer. Extension data is stored under `[extensions.<name>]`.

Repository synchronization includes repository configuration and repository state. Repository-local client configuration is resolved by the local client that owns the `local` directory.

Repository and source locations may be local paths or backend locators as defined in `docs/storage-backends.md`.

Configuration files may declare includes:

```toml
include = ["profiles/common.toml"]
```

Include paths are resolved relative to the file that declares them. Includes are parsed before the including file, and the including file has higher precedence for fields in the same layer. Include cycles are invalid.

## 2. Repository Configuration

`<repository>/config.toml` stores repository policy and portable repository defaults.

```toml
config_version = 1

[policy.metadata]
default_capture = "portable"

[policy.retention]
enabled = true
protect_pinned = true

[[policy.retention.rules]]
id = "latest"
type = "latest"
count = 20

[[policy.retention.rules]]
id = "daily"
type = "interval"
interval = "P1D"
keep_for = "P30D"

[[policy.retention.rules]]
id = "monthly"
type = "interval"
interval = "P1M"
keep_for = "P2Y"

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
|---|---|---|---|
| `enabled` | boolean | `true` | Enables automatic retention-based snapshot pruning. |
| `protect_pinned` | boolean | `true` | Keeps pinned snapshot records outside retention pruning. |

Retention rules select snapshot records that remain protected from pruning. The final retention set is the union of all rule results and pinned snapshots when `protect_pinned = true`.

### 2.4 `[[policy.retention.rules]]`

| Key | Type | Default | Description |
|---|---|---|---|
| `id` | string | required | Stable rule ID used for keyed merge and diagnostics. |
| `type` | string | required | Retention rule type. |
| `count` | integer | required for `latest` | Number of newest matching snapshot records retained by the rule. |
| `interval` | temporal amount | required for `interval` | Time spacing used to select representative snapshot records. |
| `keep_for` | temporal amount | required for `interval` | Lookback window evaluated from the pruning reference time. |

Retention rule types:

| Value | Meaning |
|---|---|
| `"latest"` | Retain the newest `count` snapshot records. |
| `"interval"` | Retain representative snapshot records spaced by `interval` within `keep_for`. |

`count` is a positive integer.

### 2.5 `[policy.recovery]`

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

## 3. Profile Configuration

Profile configuration files define reusable policy bundles. Sources, jobs, and source references may apply one or more profiles before local overrides.

```toml
profile_version = 1
name = "minecraft"
extends = ["default"]

[metadata]
capture = "portable"

[filters]
default_action = "include"

[[filters.rules]]
id = "exclude-logs"
action = "exclude"
pattern = "logs/**"

[[filters.rules]]
id = "exclude-crash-reports"
action = "exclude"
pattern = "crash-reports/**"

[[chunking.rules]]
id = "minecraft-region"
glob = "**/region/*.mca"
strategy = "fixed-size"
block_size = "1MiB"
```

### 3.1 Top-Level Keys

| Key | Type | Default | Description |
|---|---|---|---|
| `profile_version` | integer | required | Profile configuration format version. |
| `name` | string | required | Stable profile name. |
| `extends` | array of strings | absent | Profiles applied before this profile. |

### 3.2 `[metadata]`

| Key | Type | Default | Description |
|---|---|---|---|
| `capture` | string | absent | Filesystem metadata capture profile supplied by this profile. |

### 3.3 `[filters]`

The `[filters]` table uses the shared filter schema defined in Section 7. Profile filter rules are applied when the profile is applied.

### 3.4 `[[chunking.rules]]`

Chunking rules are evaluated in order. The first matching rule supplies the chunking strategy for a file unless a higher layer overrides it.

| Key | Type | Default | Description |
|---|---|---|---|
| `id` | string | required | Stable rule ID used for keyed merge and diagnostics. |
| `glob` | glob pattern | required | Source-scoped display path pattern. |
| `strategy` | string | required | Chunking strategy. |
| `block_size` | size | required for `fixed-size` | Fixed chunk size. |
| `min_size` | size | required for `cdc` | Minimum CDC chunk size. |
| `avg_size` | size | required for `cdc` | Target average CDC chunk size. |
| `max_size` | size | required for `cdc` | Maximum CDC chunk size. |

Chunking strategy values:

| Value | Meaning |
|---|---|
| `"whole-file"` | Store matching files as a single content object. |
| `"fixed-size"` | Split matching files into fixed-size chunks. |
| `"cdc"` | Split matching files with content-defined chunking. |
| `"adaptive"` | Let the implementation choose from available strategies with recorded diagnostics. |

Profile names use the same syntax as source names: `[a-z][a-z0-9._-]{0,63}`.

## 4. Client Configuration

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

### 4.1 Top-Level Keys

| Key | Type | Default | Description |
|---|---|---|---|
| `config_version` | integer | required | Configuration format version. |
| `client_id` | string | absent | Stable client identifier for diagnostics and multi-client conflict records. |

### 4.2 `[metadata]`

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

### 4.3 `[restore]`

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

### 4.4 `[service]`

| Key | Type | Default | Description |
|---|---|---|---|
| `enabled` | boolean | `false` | Enables background service integration. |
| `listen` | string | absent | Service listen endpoint. |

### 4.5 `[ui]`

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

### 4.6 `[[remotes]]`

Each remote entry defines one RepositoryStore synchronization target for the active client.

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
| `"file-tree"` |
| `"sftp"` |
| `"sftp-tree"` |
| `"s3"` |
| `"s3-object"` |
| `"webdav"` |
| `"webdav-tree"` |
| `"rest"` |
| `"zip-archive"` |
| `"sevenzip-archive"` |
| `"bundle-file"` |

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

## 5. Source Configuration

Source configuration files define stable tracked roots. Repository source configuration is stored as `sources/<source-name>.toml`. The file name and `name` field use the mutable source name. The immutable source identity is `source_id`.

```toml
source_version = 1
source_id = "018f4c8b-2d4d-7a1c-9a4f-2f7d6d0e8a31"
name = "world-a"
kind = "directory"
path = "D:/Minecraft/.minecraft/versions/1.21.1/saves/world-a"
display_name = "World A"
profiles = ["minecraft-world"]

[metadata]
capture = "portable"

[filters]
default_action = "include"

[[filters.rules]]
id = "exclude-cache"
action = "exclude"
pattern = "cache/**"
```

### 5.1 Top-Level Keys

| Key | Type | Default | Description |
|---|---|---|---|
| `source_version` | integer | required | Source configuration format version. |
| `source_id` | UUID string | required | Immutable source identity. |
| `name` | string | required | Mutable source name used by users, file names, CLI, and job references. |
| `kind` | string | `"directory"` | Source root kind. |
| `path` | locator | required | Source locator scanned for this root. |
| `display_name` | string | `name` | User-facing source label. |
| `profiles` | array of strings | absent | Profiles applied before source-local settings. |

Source IDs are canonical lowercase UUID text:

```text
[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}
```

Source names match:

```text
[a-z][a-z0-9._-]{0,63}
```

Repository source file names match the source name: `sources/<source-name>.toml`. Source names are unique after layer resolution. Source IDs are unique after layer resolution and remain unchanged when the source name or source path changes.

Source `kind` values:

| Value | Meaning |
|---|---|
| `"directory"` | Scan a directory tree. |
| `"file"` | Scan one regular file as a source root. |

Snapshots use `source_id` as the logical identity of the tracked root. The `name`, `display_name`, and `path` values are captured for display, audit, and source resolution metadata.

Overlapping source paths are valid. Each source remains a separate logical root identified by its source ID.

### 5.2 `[metadata]`

| Key | Type | Default | Description |
|---|---|---|---|
| `capture` | string | resolved | Filesystem metadata capture profile for snapshots of this source. |

### 5.3 `[filters]`

The `[filters]` table uses the shared filter schema defined in Section 7. Source filter rules are source defaults inherited by jobs that reference the source.

## 6. Job Configuration

Job configuration files define repeatable snapshot tasks. A job references source definitions and supplies scheduling plus job-local policy.

```toml
job_version = 1
name = "minecraft-1-21-1"
profiles = ["minecraft"]
repository = "sftp://backup.example.com/kaca/repository"

[[source_refs]]
source = "instance"

[source_refs.filters]
default_action = "include"

[[source_refs.filters.rules]]
id = "exclude-saves-root"
action = "exclude"
pattern = "saves"

[[source_refs.filters.rules]]
id = "exclude-saves-children"
action = "exclude"
pattern = "saves/**"

[[source_refs]]
source = "world-a"
profiles = ["minecraft-world"]

[metadata]
capture = "portable"

[schedule]
enabled = true
calendar = "daily 02:00"

[filters]
default_action = "include"

[[filters.rules]]
id = "exclude-temp"
action = "exclude"
pattern = "tmp/**"
```

### 6.1 Top-Level Keys

| Key | Type | Default | Description |
|---|---|---|---|
| `job_version` | integer | required | Job configuration format version. |
| `name` | string | required | Stable job name. |
| `repository` | string | absent | Repository locator for user-level job files. |
| `profiles` | array of strings | absent | Profiles applied before job-local settings. |

### 6.2 `[[source_refs]]`

Each source reference selects one source definition for snapshots created by the job.

| Key | Type | Default | Description |
|---|---|---|---|
| `source` | string | absent | Current source name used for user-authored configuration. |
| `source_id` | UUID string | absent | Immutable source ID used for generated, migration, and repair configuration. |
| `profiles` | array of strings | absent | Profiles applied for this source binding. |
| `filters` | table | resolved | Source binding filter rules using the shared filter schema. |

Each source reference contains exactly one of `source` or `source_id`. The `source` field resolves through the current source name table. The `source_id` field resolves directly to the matching source definition. A source reference may contain a nested `[source_refs.filters]` table and `[[source_refs.filters.rules]]` entries.

Each resolved source reference expands to one snapshot root. The snapshot root identity is the resolved source ID. Display paths use the captured source name and relative path.

A job references a source at most once after source resolution. Overlapping source paths are valid and remain separate logical roots.

### 6.3 `[source_refs.metadata]`

| Key | Type | Default | Description |
|---|---|---|---|
| `capture` | string | resolved | Filesystem metadata capture profile for this source binding. |

### 6.4 `[metadata]`

| Key | Type | Default | Description |
|---|---|---|---|
| `capture` | string | resolved | Filesystem metadata capture profile for snapshots created by the job. |

### 6.5 `[schedule]`

| Key | Type | Default | Description |
|---|---|---|---|
| `enabled` | boolean | `false` | Enables scheduled snapshots for the job. |
| `calendar` | string | absent | Calendar expression for scheduled snapshots. |

The calendar expression format is a separate scheduler interface decision.

### 6.6 `[filters]`

The `[filters]` table uses the shared filter schema defined in Section 7. Job filter rules are inherited by every source reference in the job unless a source reference overrides the effective rule list.

## 7. Filter Configuration

Filter tables appear as `[filters]` under profiles, sources, and jobs. Source references use `[source_refs.filters]`. All filter tables use the same schema.

```toml
[filters]
default_action = "include"
reset = false

[[filters.rules]]
id = "exclude-logs"
action = "exclude"
pattern = "logs/**"

[[filters.rules]]
id = "include-latest-log"
action = "include"
pattern = "logs/latest.log"
```

### 7.1 `[filters]`

| Key | Type | Default | Description |
|---|---|---|---|
| `default_action` | string | `"include"` | Action used when no rule matches an entry. |
| `reset` | boolean | `false` | Clears accumulated lower-layer rules before applying local rules. |

`default_action` values:

| Value | Meaning |
|---|---|
| `"include"` | Include unmatched entries. |
| `"exclude"` | Exclude unmatched entries. |

### 7.2 `[[filters.rules]]`

| Key | Type | Default | Description |
|---|---|---|---|
| `id` | string | required | Stable rule ID used for replacement and diagnostics. |
| `action` | string | required | Rule action. |
| `pattern` | glob pattern | required | Source-relative path pattern. |

Filter rule IDs use the same syntax as source names: `[a-z][a-z0-9._-]{0,63}`.

Filter rule actions:

| Value | Meaning |
|---|---|
| `"include"` | Include matching entries. |
| `"exclude"` | Exclude matching entries. |

Filter patterns match source-relative paths using `/` separators. They do not include the source name prefix.

Effective filter construction processes filter tables in source binding application order. The effective `default_action` is resolved as a scalar in the same order, starting from `"include"`. If a filter table has `reset = true`, the accumulated rule list is cleared before local rules are applied. When a local rule has the same `id` as an accumulated rule, the accumulated rule is removed and the local rule is appended at the current position. Rules without matching IDs are appended.

Entry matching starts with `default_action` and then evaluates rules in order. Each matching rule sets the current action. The final action after the last matching rule determines whether the entry is included. Directory traversal may prune an excluded directory only when no later include rule can match a descendant path.

Snapshot filter summaries record the resolved default action and ordered effective rule list.

## 8. Extensions

Extensions use namespaced tables:

```toml
[extensions.example]
enabled = true
value = "custom"
```

Extension names are lowercase ASCII identifiers containing letters, digits, `_`, and `-`.

## 9. Value Types

Configuration values are parsed according to the schema field type.

| Type | Syntax | Examples |
|---|---|---|
| size | string with binary unit | `"256KiB"`, `"64MiB"`, `"1GiB"` |
| duration | ISO-8601 duration string | `"PT30S"`, `"PT5M"`, `"PT24H"` |
| period | ISO-8601 period string | `"P30D"`, `"P6M"`, `"P2Y"` |
| temporal amount | ISO-8601 duration or period string | `"PT1H"`, `"P1D"`, `"P1M"` |
| UUID string | canonical lowercase UUID text | `"018f4c8b-2d4d-7a1c-9a4f-2f7d6d0e8a31"` |
| percentage | integer | `10` |
| locator | string | `"D:/backup"`, `"s3://bucket/repo"` |
| glob pattern | string | `"**/region/*.mca"` |
| credential reference | string | `"keyring:kaca/origin"` |
| calendar expression | string | `"daily 02:00"` |

Size units use binary powers. Supported suffixes are `B`, `KiB`, `MiB`, `GiB`, and `TiB`.

Duration values use `java.time.Duration` semantics and represent fixed elapsed time. Duration fields accept only time-based ISO-8601 `PT...` values such as `PT30S`, `PT5M`, and `PT24H`.

Period values use `java.time.Period` semantics and represent calendar-based date periods. Period fields accept only date-based ISO-8601 values such as `P30D`, `P6M`, and `P2Y`.

Temporal amount fields accept either a duration or a period. The literal syntax determines the temporal kind: `PT...` values are fixed durations, and date-based `P...` values are calendar periods. Mixed date-time values such as `P1DT12H` are invalid.

Short unit forms such as `30s`, `10m`, `24h`, and `30d` are not valid configuration values.

Calendar expressions are parsed only by scheduling components.

UUID string values use canonical lowercase text with hyphen separators. Locator values follow `docs/storage-backends.md`. Glob pattern fields define their own match root. Filter rule patterns are source-relative; chunking rule patterns are source-scoped display paths such as `<source-name>/<relative-path>`.

## 10. Merge Rules

Effective configuration is built with schema-aware merge rules.

General merge rules:

| Field kind | Rule |
|---|---|
| scalar | Higher layer replaces lower layer. |
| map | Keys merge recursively. |
| unknown map key | Accepted only under `extensions.<name>`. |
| unkeyed array | Higher layer replaces lower layer. |
| keyed array | Entries merge by the schema key. |

Keyed array merge rules:

| Field | Key | Rule |
|---|---|---|
| `[[remotes]]` | `name` | Higher-layer entry replaces the lower-layer entry with the same name. |
| `[[source_refs]]` | resolved `source_id` | Job-local entries define the job source reference set. |
| `[[filters.rules]]` | `id` | A same-ID local rule removes the accumulated rule and appends the local rule. |
| `[[chunking.rules]]` | `id` | Higher-layer rule replaces the lower-layer rule with the same ID. |
| `[[policy.retention.rules]]` | `id` | Higher-layer rule replaces the lower-layer rule with the same ID. |

Source binding application order:

1. Resolve profile `extends` recursively for every referenced profile.
2. Apply source definition profiles in listed order.
3. Apply source definition settings.
4. Apply job profiles in listed order.
5. Apply job settings.
6. Apply source reference profiles in listed order.
7. Apply source reference settings.
8. Apply command invocation overrides.

Profile cycles are invalid. Duplicate profile names at the same layer are invalid.

## 11. Secret Policy

Configuration files store credential references only.

Allowed credential references:

```toml
credentials = "env:KACA_REMOTE_ORIGIN_PASSWORD"
credentials = "keyring:kaca/origin"
credentials = "file:secrets/origin.credentials"
```

Plaintext password, token, access key, secret key, and private key values are invalid in configuration files. The validator reports keys whose names or values match secret-like patterns unless the field type is credential reference.

## 12. Synchronization and Protection Policy

Repository state protection by file class:

| File class | Synchronize | Recovery records | Notes |
|---|---|---|---|
| `repository` | yes | yes | Internal binary repository metadata. |
| `config.toml` | yes | yes | Portable repository policy. |
| `sources/*.toml` | yes | yes | Stable source definitions. |
| `jobs/*.toml` | yes | yes | Repeatable job definitions. |
| `profiles/*.toml` | yes | yes | Reusable policy bundles. |
| `local/config.toml` | no | optional | Machine-local settings and credential references. |
| system/user config | no | no | Protected only when explicitly configured as a source. |

Synchronization treats repository configuration, source configuration, job configuration, and profile configuration as repository state. Repository-local client configuration is evaluated by the client that owns the `local` directory.

## 13. Compatibility and Migration

Configuration files carry explicit format versions.

Compatibility rules:

- Unknown top-level sections are invalid outside `extensions.<name>`.
- Unknown keys are invalid outside extension tables.
- Deprecated keys remain readable until the configured migration boundary.
- A newer required configuration version blocks mutation until migration succeeds.
- Migration writes a full replacement configuration file through repository transaction rules.
- Diagnostics include old key, new key, source file, and migration action.

`kaca config validate` validates every configuration layer. `kaca config migrate` rewrites compatible old configuration files to the current schema.

## 14. Value Resolution

Effective values are resolved with origin tracking. Diagnostics and `config get` output include the layer and file path that supplied each value.

Example resolution for snapshot metadata capture:

```text
command invocation override
job source reference [metadata].capture
job [metadata].capture
source [metadata].capture
profile [metadata].capture
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

Repository endpoint definitions are keyed by remote name. A higher-layer endpoint with the same name replaces the lower-layer endpoint.

## 15. Validation Rules

The parser validates:

- Configuration, source, profile, and job format versions.
- Allowed sections for each configuration layer.
- Field types.
- Enum values.
- Valid retention rule structure.
- Recovery redundancy range.
- Unique remote names after layer resolution.
- Extension table names.
- Required source fields.
- Required job fields.
- At least one source reference for each job.
- Unique source IDs across resolved source definitions.
- Unique source names across resolved source definitions.
- Unique resolved source references within each job.
- Valid source ID UUID syntax.
- Valid source name syntax.
- Matching repository source file names and source names.
- Valid source root kind values.
- Valid source references by source name or source ID.
- Valid include-file paths and include cycle absence.
- Valid profile references and profile cycle absence.
- Valid typed values for size, duration, period, temporal amount, UUID string, locator, glob pattern, credential reference, and calendar expression.
- Valid filter rule IDs, actions, patterns, reset flags, and default actions.
- Valid keyed merge identifiers.
- Secret policy compliance.
- Migration compatibility for deprecated keys and old format versions.

Invalid configuration files produce diagnostics with file path, layer, table name, key name, invalid value, and expected value set.
