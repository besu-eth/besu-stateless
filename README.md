# Besu Stateless — Partitioned Binary Trie (EIP-8297)

[![CI](https://github.com/besu-eth/besu-stateless/actions/workflows/ci.yml/badge.svg)](https://github.com/besu-eth/besu-stateless/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

Java implementation of the [EIP-8297](https://eips.ethereum.org/EIPS/eip-8297) Partitioned Binary Trie for Hyperledger Besu — a production-oriented stored trie with embedding, proofs, and parallel commit.

The trie model follows the [execution-specs binary trie proposal](https://github.com/ethereum/execution-specs/pull/3206) and the reference [`bin-trie` branch](https://github.com/kevaundray/execution-specs/pull/9).

## Features

- **Compressed binary radix trie** — `BranchNode` and `LeafNode` only (no stem nodes, no RLP)
- **BLAKE3 merkleization** with domain-separating node tags
- **EIP-8297 embedding** — zone-based key derivation (account, code, storage, EIP-7702 delegation)
- **Stored trie** with `NodeLoader` / `NodeUpdater` and Tuweni `Bytes` API (`put` / `remove`, `putDeferred`, `commit`, `getRootHash`)
- **Parallel commit** via `ParallelStoredPartitionedBinaryTrie`
- **Merkle proofs** — generation and verification
- **Spec-faithful test oracle** — `BinaryTrie` / `MutableBinaryTrie` under `src/test/java/.../trie/reference` (not published in the JAR)

## Architecture

```
org.hyperledger.besu.ethereum.partitionedbinarytrie
├── codec/        # BasicDataEncoder, DelegationEncoder, CodeChunkifier, TrieNodeCodec
├── keys/         # Trie constants and EIP-8297 key derivation
├── params/       # Embedding parameters
├── internal/     # BLAKE3 hashing, byte[] hot paths (not public API)
└── trie/         # Trie engine, visitors, factories
    ├── factory/
    ├── node/
    └── visitor/
```

| Package | Purpose |
|---------|---------|
| `codec` | `BasicDataEncoder`, `DelegationEncoder`, `CodeChunkifier`, `TrieNodeCodec` |
| `keys` | EIP-8297 zone-based key derivation (`TrieKeyDerivation`) |
| `params` | Embedding constants (`EmbeddingParameters`) |
| `trie` | `PartitionedBinaryTrie`, `StoredPartitionedBinaryTrie`, parallel commit |
| `trie.factory` | `PartitionedBinaryTrieFactory`, `StoredTrieNodeFactory` |
| `trie.node` | `BranchNode`, `LeafNode`, `StoredTrieNode` |
| `trie.visitor` | Get/Put/Remove/Commit visitors |
| `embedding` (test) | EIP-8297 embedding section tests |
| `trie.reference` (test) | Spec conformance oracle (`BinaryTrie`, `MutableBinaryTrie`) |

Hot paths use `byte[]` internally and Tuweni `Bytes` at the public API; `Blake3Hasher` and `ByteTrieOps` reuse thread-local buffers to avoid allocations.

### EIP-8297 key layout

Source of truth: `EmbeddingParameters`, `TrieKeyDerivation`, `DelegationEncoder`.

Every leaf key is:

```text
key = zone (1 byte) || tree_position || sub_index (1 byte)
```

A **stem** is the shared key prefix `zone || tree_position` (layout concept only — the trie has no stem node type). Up to `STEM_SUBTREE_WIDTH` (256) leaves share one stem, distinguished by `sub_index` ∈ `[0, 255]`.

| Zone | Value | Key length | `tree_position` |
|------|-------|------------|-----------------|
| Account | `0x00` (`ACCOUNT_ZONE`) | 34 bytes | `BLAKE3(address32)` (32 bytes) |
| Code | `0x01` (`CODE_ZONE`) | 34 bytes | `BLAKE3(code_hash \|\| tree_index32)` (32 bytes) |
| Storage (overflow) | `0xFF` (`STORAGE_ZONE`) | 66 bytes | `BLAKE3(address32) \|\| BLAKE3(address32 \|\| tree_index32)` (64 bytes) |

Addresses are left-padded from 20 to 32 bytes before hashing (`address20ToAddress32`).

#### Account header stem (`ACCOUNT_ZONE`)

One stem per account (`tree_position = BLAKE3(address32)`). Header leaves:

| `sub_index` | Leaf |
|-------------|------|
| `0` (`BASIC_DATA_LEAF_KEY`) | Basic data (version, nonce, balance, code size, …) |
| `1` (`CODE_HASH_LEAF_KEY`) | Code hash |
| `2` (`DELEGATION_LEAF_KEY`) | EIP-7702 delegation (`DelegationEncoder`: `0xef0100 \|\| target20 \|\| 9 zero bytes`) |
| `64`–`127` | Header storage slots `0`–`63` (`HEADER_STORAGE_OFFSET=64`, `HEADER_STORAGE_SLOTS=64`) |

Code-hash and delegation are mutually exclusive for an existing account. Delegation accounts use `DELEGATION_CODE_SIZE = 23` in basic data. There are no header code chunks; all bytecode lives in `CODE_ZONE`.

#### Code chunks (`CODE_ZONE`)

All chunks live in the code zone and are **content-addressed by `code_hash`** (no account address in the key):

```text
tree_index = chunk_id / 256
sub_index  = chunk_id % 256
tree_position = BLAKE3(code_hash || left_pad32(tree_index))
```

#### Storage

- Slots `0`–`63` → account header stem at `sub_index = HEADER_STORAGE_OFFSET + slot`
- Slots `≥ HEADER_STORAGE_SLOTS` → storage zone:

```text
tree_index = slot / 256
sub_index  = slot % 256
tree_position = BLAKE3(address32) || BLAKE3(address32 || left_pad32(tree_index))
```

### Trie API

`PartitionedBinaryTrie` mutates with `put` / `remove` only (no `writeState`).

| Method | Behavior |
|--------|----------|
| `put` | Stores the value as a leaf, **including zero** |
| `remove` | Deletes the leaf (and collapses single-child branches) |
| `get` | Empty when absent |
| `readState` | Stored value, or 32 zero bytes when absent |

EIP-8297 state-layer zeroization is delete: Besu callers must `remove` rather than `put` zeros.

## Build and test

Requires **Java 21** and **Gradle 9** (wrapper included).

```bash
export JAVA_HOME=/path/to/java-21+
./gradlew clean test build
./gradlew spotlessApply   # formatting
```

## Besu integration

Uses the same persistence contracts as `StoredMerklePatriciaTrie` (`NodeLoader`, `NodeUpdater`, `put` / `remove`, `putDeferred`, `commit`, `getRootHash`). Swap the trie type in `BonsaiWorldState.createTrie()`:

```java
return new StoredPartitionedBinaryTrie(nodeLoader, rootHash);
```

State writes go through Besu: `put` for non-zero values, `remove` when zeroing a leaf. `DefaultStateRootCommitter` needs no changes beyond the trie type. Remaining work: embedding wiring in `PathBasedWorldState`, fork config for `TrieMode`, and mainnet benchmarks vs PMT.

## Links

- [EIP-8297](https://eips.ethereum.org/EIPS/eip-8297)
- [execution-specs binary trie proposal](https://github.com/ethereum/execution-specs/pull/3206)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). All contributions require [DCO sign-off](DCO.md).

## Security

See [SECURITY.md](SECURITY.md) for reporting vulnerabilities.

## License

Apache 2.0 — see [LICENSE](LICENSE).
