# Repository Binary Format

This document defines the binary repository formats used by `kaca`.

## 1. Encoding Rules

All fixed-width integers use unsigned big-endian encoding.

Binary strings are length-delimited by the surrounding structure. Text strings inside metadata payloads are UTF-8 and normalized before encoding.

Structured metadata bodies use deterministic CBOR with this profile:

- Definite-length arrays, maps, byte strings, and text strings.
- Shortest valid integer encoding.
- Canonically sorted map keys.
- Integer map keys for long-lived `repository` fields.
- No floating point values.
- Explicit enum integer values.
- Explicit format version fields.
- Timestamps encoded as Unix milliseconds unless a payload defines a more precise field.

Object identity is computed from canonical logical bytes before compression, encryption, and envelope encoding.

### 1.1 Identifiers and Paths

Root IDs are UTF-8 text strings that match:

```text
[a-z][a-z0-9._-]{0,63}
```

Root IDs are case-sensitive and are compared by exact Unicode scalar value after UTF-8 decoding. The values `.`, `..`, and strings containing `/`, `\`, `:`, ASCII control characters, or NUL are invalid.

Tree entry names are UTF-8 text strings normalized to NFC. Entry names are single path segments and cannot contain `/`, `\`, NUL, or ASCII control characters. The values `.`, `..`, and the empty string are invalid entry names.

Snapshot-relative paths are formed for display, filtering, sparse restore, and diagnostics by joining the root ID and tree entry names with `/`:

```text
<root-id>/<entry-name>/<entry-name>
```

Stored tree entries contain only one path segment at a time. Full paths are reconstructed by walking tree references.

## 2. Algorithm Identifiers

Digest algorithms use an 8-byte profile identifier:

```text
digest-profile-id :=
  type: u16
  algorithm: u16
  variant: u16
  output-bits: u16
```

`type` selects the algorithm namespace for `algorithm`. `algorithm` selects the namespace for `variant`. `output-bits` is the actual digest output length in bits.

Digest values store the profile followed by the digest bytes:

```text
digest-value :=
  digest-profile-id
  digest-bytes
```

The digest byte length is `output-bits / 8`.

CBOR `bstr` fields named `digest profile` contain exactly the 8-byte `digest-profile-id`. CBOR `bstr` fields named `digest value` contain the complete `digest-value`.

Object IDs are digest values whose type is `cryptographic-hash` or `keyed-cryptographic-hash`.

Physical checksums are digest values whose type is `cryptographic-hash` or `fast-checksum`.

Chunk boundary fingerprints are digest values whose type is `rolling-fingerprint`.

Digest profile registry:

| ID | Type |
|---:|---|
| 1 | `cryptographic-hash` |
| 2 | `keyed-cryptographic-hash` |
| 3 | `fast-checksum` |
| 4 | `rolling-fingerprint` |

### 2.1 Type 1: `cryptographic-hash`

Algorithms:

| Algorithm ID | Algorithm |
|---:|---|
| 1 | `sha-2` |
| 2 | `sha-3` |
| 3 | `blake3` |
| 4 | `sm` |

#### 2.1.1 Algorithm 1: `sha-2`

| Variant ID | Variant | Output bits |
|---:|---|---:|
| 1 | `sha-224` | 224 |
| 2 | `sha-256` | 256 |
| 3 | `sha-384` | 384 |
| 4 | `sha-512` | 512 |
| 5 | `sha-512/224` | 224 |
| 6 | `sha-512/256` | 256 |

#### 2.1.2 Algorithm 2: `sha-3`

| Variant ID | Variant | Output bits |
|---:|---|---:|
| 1 | `sha3-224` | 224 |
| 2 | `sha3-256` | 256 |
| 3 | `sha3-384` | 384 |
| 4 | `sha3-512` | 512 |

#### 2.1.3 Algorithm 3: `blake3`

| Variant ID | Variant | Output bits |
|---:|---|---:|
| 1 | `blake3` | 256, 384, or 512 |

#### 2.1.4 Algorithm 4: `sm`

| Variant ID | Variant | Output bits |
|---:|---|---:|
| 1 | `sm3` | 256 |

### 2.2 Type 2: `keyed-cryptographic-hash`

Algorithms:

| Algorithm ID | Algorithm |
|---:|---|
| 1 | `hmac-sha-2` |
| 2 | `hmac-sha-3` |
| 3 | `keyed-blake3` |
| 4 | `hmac-sm` |

#### 2.2.1 Algorithm 1: `hmac-sha-2`

| Variant ID | Variant | Output bits |
|---:|---|---:|
| 1 | `hmac-sha224` | 224 |
| 2 | `hmac-sha256` | 256 |
| 3 | `hmac-sha384` | 384 |
| 4 | `hmac-sha512` | 512 |
| 5 | `hmac-sha512/224` | 224 |
| 6 | `hmac-sha512/256` | 256 |

#### 2.2.2 Algorithm 2: `hmac-sha-3`

| Variant ID | Variant | Output bits |
|---:|---|---:|
| 1 | `hmac-sha3-224` | 224 |
| 2 | `hmac-sha3-256` | 256 |
| 3 | `hmac-sha3-384` | 384 |
| 4 | `hmac-sha3-512` | 512 |

#### 2.2.3 Algorithm 3: `keyed-blake3`

| Variant ID | Variant | Output bits |
|---:|---|---:|
| 1 | `blake3-keyed` | 256, 384, or 512 |

#### 2.2.4 Algorithm 4: `hmac-sm`

| Variant ID | Variant | Output bits |
|---:|---|---:|
| 1 | `hmac-sm3` | 256 |

### 2.3 Type 3: `fast-checksum`

Algorithms:

| Algorithm ID | Algorithm |
|---:|---|
| 1 | `xxh3` |
| 2 | `crc` |

#### 2.3.1 Algorithm 1: `xxh3`

| Variant ID | Variant | Output bits |
|---:|---|---:|
| 1 | `xxh3-128` | 128 |

#### 2.3.2 Algorithm 2: `crc`

| Variant ID | Variant | Output bits |
|---:|---|---:|
| 1 | `crc32c` | 32 |

### 2.4 Type 4: `rolling-fingerprint`

Algorithms:

| Algorithm ID | Algorithm |
|---:|---|
| 1 | `gear` |
| 2 | `rabin` |

#### 2.4.1 Algorithm 1: `gear`

| Variant ID | Variant | Output bits |
|---:|---|---:|
| 1 | `gear64` | 64 |

#### 2.4.2 Algorithm 2: `rabin`

| Variant ID | Variant | Output bits |
|---:|---|---:|
| 1 | `rabin64` | 64 |

For fixed-output variants, `output-bits` must equal the output bit length listed in the registry. For `blake3` and `blake3-keyed`, version 1 accepts `output-bits` values of 256, 384, and 512.

### 2.5 Compression Algorithms

| ID | Algorithm |
|---:|---|
| 0 | `none` |
| 1 | `zstd` |

### 2.6 Encryption Algorithms

| ID | Algorithm |
|---:|---|
| 0 | `none` |
| 1 | `xchacha20-poly1305` |
| 2 | `aes-256-gcm` |

### 2.7 Metadata Encodings

| ID | Encoding |
|---:|---|
| 1 | `deterministic-cbor-v1` |

## 3. `repository`

`repository` is an internal binary file managed by the program.

```text
repository-file :=
  fixed-header
  extension-header
  deterministic-cbor-body
  checksum
```

Fixed header:

| Offset | Size | Field |
|---:|---:|---|
| 0 | 8 | magic: `KACAREP1` |
| 8 | 2 | repository file format version |
| 10 | 2 | flags |
| 12 | 4 | header length |
| 16 | 8 | body length |
| 24 | 8 | checksum digest profile |
| 32 | 8 | reserved zero |

`header length` includes the fixed header and extension header. The fixed header length is 40 bytes. Version 1 uses an empty extension header.

The checksum is computed over `fixed-header + extension-header + deterministic-cbor-body`.

Repository file body:

| Key | Type | Field |
|---:|---|---|
| 1 | bstr | repository ID |
| 2 | uint | repository format version |
| 3 | uint | object format version |
| 4 | uint | metadata encoding |
| 5 | bstr | content digest profile |
| 6 | bstr | object ID digest profile |
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
| 1 | uint | payload encryption algorithm |
| 2 | uint | manifest encryption mode |
| 3 | uint | mutable record encryption mode |

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
  private-header
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
| 34 | 2 | compression algorithm |
| 36 | 2 | encryption algorithm |
| 38 | 2 | reserved zero |
| 40 | 4 | public header length |
| 44 | 4 | private header length |
| 48 | 8 | physical checksum digest profile |
| 56 | 8 | reserved zero |

`header length` includes the fixed header, object ID, public header, and private header. The fixed header length is 64 bytes.

`payload length` is the stored payload length after compression and after encryption when encryption is enabled. `logical size` is the canonical logical byte length before compression, encryption, and envelope encoding.

The object ID field stores a complete digest value. The object ID length field is the byte length of that digest value.

The physical checksum is computed over `fixed-header + object-id + public-header + private-header + payload`. The `physical-checksum` field stores only digest bytes; the digest profile is stored in the fixed header.

Object payload construction:

```text
canonical logical bytes
  -> content digest
  -> object ID
  -> compression decision
  -> stored plaintext payload
  -> optional encryption
  -> envelope payload
```

Compression is applied before encryption. When compression is not used, the compression algorithm is `none` and the stored plaintext payload is the canonical logical bytes.

Public header body uses deterministic CBOR:

| Key | Type | Field |
|---:|---|---|
| 1 | uint | object format version |
| 2 | uint | logical size |
| 3 | uint | stored payload size |
| 4 | map | compression parameters |
| 5 | map | public encryption parameters |
| 6 | uint | creation time, Unix milliseconds |

Private header body uses deterministic CBOR. In encrypted objects, this body is available after decryption:

| Key | Type | Field |
|---:|---|---|
| 1 | bstr | plaintext content digest value |
| 2 | bstr | secondary plaintext digest value |

When object encryption is disabled, `private-header` contains the deterministic CBOR private header body and `payload` contains the stored plaintext payload.

When object encryption is enabled, one AEAD operation authenticates and encrypts `private-header body + stored plaintext payload`. The resulting ciphertext is split back into `private-header` and `payload` using the private header length and payload length fields. The AEAD associated data is:

```text
fixed-header + object-id + public-header-with-authentication-tag-empty
```

The authentication tag is stored in the public encryption parameters.

Public encryption parameters:

| Key | Type | Field |
|---:|---|---|
| 1 | bstr | nonce |
| 2 | bstr | authentication tag |
| 3 | uint | key slot ID |

When encryption is disabled, the public encryption parameters map is empty. When encryption is enabled, `public-header-with-authentication-tag-empty` is encoded by setting key `2` to an empty byte string before computing AEAD associated data.

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
| 3 | array | snapshot roots |
| 4 | bstr / null | parent snapshot object ID digest value |
| 5 | uint | metadata capture profile |

Metadata capture profiles:

| ID | Profile |
|---:|---|
| 1 | `portable` |
| 2 | `system` |
| 3 | `full` |

Snapshot root map:

| Key | Type | Field |
|---:|---|---|
| 1 | tstr | root ID |
| 2 | tstr | display name |
| 3 | map | source display information |
| 4 | map / null | root tree reference |
| 5 | array | direct entries |
| 6 | map / null | source filter summary |
| 7 | uint | case sensitivity policy |
| 8 | uint | source root kind |

Root IDs are unique within a snapshot. Snapshot-relative paths are scoped as `<root-id>/<relative-path>`.

Source root kinds:

| ID | Kind |
|---:|---|
| 1 | directory |
| 2 | regular file |

Case sensitivity policies:

| ID | Policy |
|---:|---|
| 1 | case-sensitive |
| 2 | case-insensitive-preserving |

Source display information map:

| Key | Type | Field |
|---:|---|---|
| 1 | tstr | captured source path |
| 2 | tstr | source platform |
| 3 | bstr / null | source path fingerprint |

Source filter summary map:

| Key | Type | Field |
|---:|---|---|
| 1 | array | include patterns |
| 2 | array | exclude patterns |

For a directory source root, `root tree reference` points to the root directory tree when tree payloads are used. `direct entries` stores root directory entries when the snapshot embeds entries directly.

For a regular file source root, `direct entries` contains one regular file entry using the captured file name as the entry name.

Tree reference map:

| Key | Type | Field |
|---:|---|---|
| 1 | bstr | object ID digest value |
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
| 2 | bstr | object ID digest value for single-object content |
| 3 | uint | logical size |
| 4 | bstr | file content digest value |
| 5 | array | chunk references |

Content storage kinds:

| ID | Kind |
|---:|---|
| 1 | single object |
| 2 | chunk list |

Chunk reference map:

| Key | Type | Field |
|---:|---|---|
| 1 | bstr | object ID digest value |
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

The pack ID is a digest value computed over `pack-header + object-record*`. The footer stores the digest bytes for that computed pack ID. The pack file name uses lowercase hexadecimal encoding of the complete pack ID digest value.

Pack header:

| Offset | Size | Field |
|---:|---:|---|
| 0 | 8 | magic: `KACAPAK1` |
| 8 | 2 | pack format version |
| 10 | 2 | flags |
| 12 | 4 | header length |
| 16 | 8 | object count |
| 24 | 8 | object records length |
| 32 | 8 | pack ID digest profile |
| 40 | 8 | reserved zero |

The fixed pack header length is 48 bytes. The pack ID digest profile must have type `cryptographic-hash`.

Object record:

```text
record-header-length
flags
object-id-length
envelope-length
object-id
object-envelope
```

Record fixed fields:

| Size | Field |
|---:|---|
| 4 | record header length |
| 2 | flags |
| 2 | object ID length |
| 8 | envelope length |

Version 1 uses a 16-byte record header. The `object-id` field stores the complete object ID digest value and must match the object ID embedded in the object envelope.

Pack footer:

| Size | Field |
|---:|---|
| 8 | pack ID digest profile |
| n | pack ID digest bytes |

The footer digest profile must match the pack header digest profile. The complete pack ID is `pack ID digest profile + pack ID digest bytes`.

## 7. Pack Index Files

```text
pack-index :=
  index-header
  pack-id
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
| 24 | 2 | pack ID length |
| 26 | 6 | reserved zero |
| 32 | 8 | checksum digest profile |
| 40 | 8 | reserved zero |

The fixed index header length is 48 bytes. `header length` includes the fixed index header and the variable-length `pack-id` field. The `pack-id` field stores the complete pack ID digest value.

Index body map:

| Key | Type | Field |
|---:|---|---|
| 1 | array | entries |

Index entry map:

| Key | Type | Field |
|---:|---|---|
| 1 | bstr | object ID digest value |
| 2 | uint | record offset |
| 3 | uint | envelope offset |
| 4 | uint | envelope length |
| 5 | uint | logical size |
| 6 | bstr | physical checksum digest value |

Index entries are sorted by object ID digest value byte order. The checksum is computed over `index-header + pack-id + deterministic-cbor-body`.
