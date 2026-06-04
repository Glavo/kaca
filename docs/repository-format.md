# Repository Binary Format

This document defines the binary repository formats used by `kaca`.

## 1. Encoding Rules

All fixed-width integers use unsigned big-endian encoding.

Binary strings are length-delimited by the surrounding structure. Text strings inside metadata payloads are UTF-8 and normalized before encoding.

Structured metadata bodies use deterministic CBOR with this profile:

- Definite-length arrays, maps, byte strings, and text strings.
- Shortest valid integer encoding.
- Canonically sorted map keys.
- Integer map keys for long-lived repository metadata.
- No floating point values.
- Explicit enum integer values.
- Explicit format version fields.
- Timestamps encoded as Unix milliseconds unless a payload defines a more precise field.

Object identity is computed from canonical logical bytes before compression, encryption, and envelope encoding.

## 2. Algorithm Identifiers

Hash algorithms:

| ID | Algorithm | Digest length |
|---:|---|---:|
| 1 | `sha256` | 32 |
| 2 | `blake3-256` | 32 |

Checksum algorithms:

| ID | Algorithm | Digest length |
|---:|---|---:|
| 1 | `sha256` | 32 |
| 2 | `blake3-256` | 32 |

Compression algorithms:

| ID | Algorithm |
|---:|---|
| 0 | `none` |
| 1 | `zstd` |

Encryption algorithms:

| ID | Algorithm |
|---:|---|
| 0 | `none` |
| 1 | `xchacha20-poly1305` |
| 2 | `aes-256-gcm` |

Metadata encodings:

| ID | Encoding |
|---:|---|
| 1 | `deterministic-cbor-v1` |

## 3. `repository.meta`

`repository.meta` is an internal binary file managed by the program.

```text
repository-meta :=
  fixed-header
  extension-header
  deterministic-cbor-body
  checksum
```

Fixed header:

| Offset | Size | Field |
|---:|---:|---|
| 0 | 8 | magic: `KACAMET1` |
| 8 | 2 | repository meta format version |
| 10 | 2 | flags |
| 12 | 4 | header length |
| 16 | 8 | body length |
| 24 | 2 | checksum algorithm |
| 26 | 2 | checksum length |
| 28 | 4 | reserved zero |

`header length` includes the fixed header and extension header. The fixed header length is 32 bytes. Version 1 uses an empty extension header.

The checksum is computed over `fixed-header + extension-header + deterministic-cbor-body`.

Repository meta body:

| Key | Type | Field |
|---:|---|---|
| 1 | bstr | repository ID |
| 2 | uint | repository format version |
| 3 | uint | object format version |
| 4 | uint | metadata encoding |
| 5 | uint | repository hash algorithm |
| 6 | uint | repository hash length |
| 7 | map | canonical compression profile |
| 8 | map | object layout |
| 9 | map | encryption mode |
| 10 | map | public KDF parameters |
| 11 | uint | creation time, Unix milliseconds |
| 12 | map | default chunker profile |
| 13 | array | enabled feature flags |
| 20 | array | encrypted key slots |

Canonical compression profile map:

| Key | Type | Field |
|---:|---|---|
| 1 | uint | compression algorithm |
| 2 | uint | compression level |
| 3 | uint | minimum input size |
| 4 | uint | minimum saved bytes |
| 5 | uint | minimum saved ratio, basis points |

Object layout map:

| Key | Type | Field |
|---:|---|---|
| 1 | uint | fanout hex characters |
| 2 | bool | full object ID file names |

Version 1 uses `fanout hex characters = 2` and `full object ID file names = true`.

Encryption mode map:

| Key | Type | Field |
|---:|---|---|
| 1 | uint | object ID mode |
| 2 | uint | payload encryption algorithm |
| 3 | uint | manifest encryption mode |

Object ID modes:

| ID | Mode |
|---:|---|
| 0 | plaintext content hash |
| 1 | HMAC over plaintext content hash |
| 2 | keyed BLAKE3 over plaintext content hash |

Default chunker profile map:

| Key | Type | Field |
|---:|---|---|
| 1 | uint | chunker algorithm |
| 2 | uint | minimum chunk size |
| 3 | uint | average chunk size |
| 4 | uint | maximum chunk size |

Chunker algorithms:

| ID | Algorithm |
|---:|---|
| 1 | `fastcdc` |

Encrypted key slot map:

| Key | Type | Field |
|---:|---|---|
| 1 | uint | slot ID |
| 2 | uint | KDF algorithm |
| 3 | map | KDF parameters |
| 4 | uint | key encryption algorithm |
| 5 | bstr | nonce |
| 6 | bstr | encrypted repository master key |
| 7 | bstr | authentication tag |

## 4. Object Envelope

Loose objects and pack records store the same object envelope.

```text
object-envelope :=
  fixed-header
  object-id
  public-header
  encrypted-private-header
  payload
  physical-checksum
```

Fixed header:

| Offset | Size | Field |
|---:|---:|---|
| 0 | 8 | magic: `KACAOBJ1` |
| 8 | 2 | object format version |
| 10 | 2 | flags |
| 12 | 4 | header length |
| 16 | 8 | payload length |
| 24 | 8 | logical size |
| 32 | 2 | object ID length |
| 34 | 2 | hash algorithm |
| 36 | 2 | compression algorithm |
| 38 | 2 | encryption algorithm |
| 40 | 4 | public header length |
| 44 | 4 | encrypted private header length |
| 48 | 2 | physical checksum algorithm |
| 50 | 2 | physical checksum length |
| 52 | 12 | reserved zero |

`header length` includes the fixed header, object ID, public header, and encrypted private header. The fixed header length is 64 bytes.

The physical checksum is computed over every byte before the checksum field.

Public header body uses deterministic CBOR:

| Key | Type | Field |
|---:|---|---|
| 1 | uint | object format version |
| 2 | uint | logical size |
| 3 | uint | stored payload size |
| 4 | map | compression parameters |
| 5 | map | public encryption parameters |
| 6 | uint | creation time, Unix milliseconds |

Encrypted private header body uses deterministic CBOR after decryption:

| Key | Type | Field |
|---:|---|---|
| 1 | bstr | plaintext content hash |
| 2 | uint | plaintext hash algorithm |
| 3 | bstr | secondary plaintext hash |
| 4 | uint | secondary hash algorithm |

Public encryption parameters:

| Key | Type | Field |
|---:|---|---|
| 1 | bstr | nonce |
| 2 | bstr | authentication tag |
| 3 | uint | key slot ID |

## 5. Structured Payloads

Snapshot and tree payloads are untyped object bytes at the object store layer. Their semantic format is defined by payload magic and version fields.

```text
structured-payload :=
  payload-magic
  payload-format-version
  body-length
  deterministic-cbor-body
```

Payload prefix:

| Offset | Size | Field |
|---:|---:|---|
| 0 | 8 | payload magic |
| 8 | 2 | payload format version |
| 10 | 8 | body length |

Payload magic values:

| Magic | Format |
|---|---|
| `KACASNP1` | snapshot payload |
| `KACATRE1` | tree payload |

### 5.1 Snapshot Body

Snapshot body map:

| Key | Type | Field |
|---:|---|---|
| 1 | bstr | snapshot ID |
| 2 | uint | created time, Unix milliseconds |
| 3 | map | source display information |
| 4 | bstr / null | parent snapshot object ID |
| 5 | uint | metadata capture profile |
| 6 | map | root tree reference |
| 7 | array | direct entries |

Metadata capture profiles:

| ID | Profile |
|---:|---|
| 1 | `portable` |
| 2 | `system` |
| 3 | `full` |

Tree reference map:

| Key | Type | Field |
|---:|---|---|
| 1 | bstr | object ID |
| 2 | uint | expected format |
| 3 | uint | logical size |

Expected formats:

| ID | Format |
|---:|---|
| 1 | `kaca-snapshot-v1` |
| 2 | `kaca-tree-v1` |
| 3 | `kaca-raw-bytes` |

### 5.2 Tree Body

Tree body map:

| Key | Type | Field |
|---:|---|---|
| 1 | array | entries |
| 2 | uint | case sensitivity policy |

Tree entry map:

| Key | Type | Field |
|---:|---|---|
| 1 | tstr | entry name |
| 2 | uint | entry type |
| 3 | uint | logical size |
| 4 | map / null | content reference |
| 5 | map | captured metadata |

Entry types:

| ID | Type |
|---:|---|
| 1 | regular file |
| 2 | directory |
| 3 | symbolic link |
| 4 | hard link |
| 5 | special file |

Content reference map:

| Key | Type | Field |
|---:|---|---|
| 1 | uint | content storage kind |
| 2 | bstr | object ID for single-object content |
| 3 | uint | logical size |
| 4 | bstr | file content hash |
| 5 | array | chunk references |

Content storage kinds:

| ID | Kind |
|---:|---|
| 1 | single object |
| 2 | chunk list |

Chunk reference map:

| Key | Type | Field |
|---:|---|---|
| 1 | bstr | object ID |
| 2 | uint | file offset |
| 3 | uint | logical size |
| 4 | uint | stored size |

### 5.3 Captured Metadata

Captured metadata map:

| Key | Type | Field |
|---:|---|---|
| 1 | uint | metadata capture profile |
| 2 | uint | modified time, Unix milliseconds |
| 3 | bool | executable |
| 4 | bool | read-only |
| 5 | tstr / null | symbolic link target |
| 20 | uint / null | POSIX mode |
| 21 | uint / null | uid |
| 22 | uint / null | gid |
| 23 | tstr / null | user name |
| 24 | tstr / null | group name |
| 30 | uint / null | Windows file attributes |
| 40 | array | extended attributes |
| 41 | array | ACL entries |
| 42 | uint / null | macOS flags |
| 43 | bstr / null | macOS resource fork metadata |
| 44 | bstr / null | Windows security descriptor |
| 45 | bstr / null | Windows reparse point metadata |

Extended attribute entry:

| Key | Type | Field |
|---:|---|---|
| 1 | tstr | namespace |
| 2 | tstr | name |
| 3 | bstr | value |

ACL entries use platform-specific canonical byte payloads in version 1. Future versions may define structured ACL maps.

## 6. Pack Files

```text
pack-file :=
  pack-header
  object-record*
  pack-footer
```

Pack header:

| Offset | Size | Field |
|---:|---:|---|
| 0 | 8 | magic: `KACAPAK1` |
| 8 | 2 | pack format version |
| 10 | 2 | flags |
| 12 | 4 | header length |
| 16 | 8 | object count |
| 24 | 8 | pack body length |
| 32 | 32 | pack ID |

Object record:

```text
record-header-length
object-id-length
envelope-length
object-id
object-envelope
```

Record fixed fields:

| Size | Field |
|---:|---|
| 4 | record header length |
| 2 | object ID length |
| 8 | envelope length |

Pack footer:

| Size | Field |
|---:|---|
| 2 | checksum algorithm |
| 2 | checksum length |
| n | checksum over pack header and object records |

## 7. Pack Index Files

```text
pack-index :=
  index-header
  deterministic-cbor-body
  checksum
```

Index header:

| Offset | Size | Field |
|---:|---:|---|
| 0 | 8 | magic: `KACAIDX1` |
| 8 | 2 | index format version |
| 10 | 2 | flags |
| 12 | 4 | header length |
| 16 | 8 | body length |
| 24 | 32 | pack ID |
| 56 | 2 | checksum algorithm |
| 58 | 2 | checksum length |
| 60 | 4 | reserved zero |

Index body map:

| Key | Type | Field |
|---:|---|---|
| 1 | array | entries |

Index entry map:

| Key | Type | Field |
|---:|---|---|
| 1 | bstr | object ID |
| 2 | uint | pack offset |
| 3 | uint | envelope length |
| 4 | uint | logical size |
| 5 | bstr | physical checksum |

The checksum is computed over `index-header + deterministic-cbor-body`.
