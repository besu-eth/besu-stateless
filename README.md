# Besu Stateless — Partitioned Binary Trie (EIP-8297)

[![CI](https://github.com/besu-eth/besu-stateless/actions/workflows/ci.yml/badge.svg)](https://github.com/besu-eth/besu-stateless/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

Java implementation of the [EIP-8297](https://eips.ethereum.org/EIPS/eip-8297) Partitioned Binary Trie for Hyperledger Besu — a production-oriented stored trie with embedding, proofs, parallel commit, and JMH benchmarks.

The trie model follows the [execution-specs binary trie proposal](https://github.com/ethereum/execution-specs/pull/3206) and the reference [`bin-trie` branch](https://github.com/kevaundray/execution-specs/pull/9).

## Features

- **Compressed binary radix trie** — `BranchNode` and `LeafNode` only (no stem nodes, no RLP)
- **BLAKE3 merkleization** with domain-separating node tags
- **EIP-8297 embedding** — zone-based key derivation (account, code, storage)
- **Stored trie** with `NodeLoader` / `NodeUpdater` and Tuweni `Bytes` API (`putDeferred`, `commit`, `getRootHash`)
- **Parallel commit** via `ParallelStoredPartitionedBinaryTrie`
- **Merkle proofs** — generation and verification
- **Spec-faithful test oracle** — `BinaryTrie` / `MutableBinaryTrie` under `src/test` (not published in the JAR)

## Architecture

```
org.hyperledger.besu.ethereum.partitionedbinarytrie
├── embedding/    # Key derivation, account/code encoding
├── internal/     # BLAKE3 hashing, byte[] hot paths (not public API)
└── stored/       # Trie engine, visitors, proofs, serialization
```

| Package | Purpose |
|---------|---------|
| `embedding.keys` | EIP-8297 zone-based key derivation |
| `embedding.codec` | Account header encoding, bytecode chunking |
| `stored.core` | `PartitionedBinaryTrie`, `StoredPartitionedBinaryTrie` |
| `stored.visitor` | Get/Put/Remove/Commit visitors |
| `stored.proof` | Merkle proof generation and verification |
| `trie.reference` (test) | Spec conformance oracle |

Hot paths use `byte[]` internally and Tuweni `Bytes` at the public API; `Blake3Hasher` and `ByteTrieOps` reuse thread-local buffers to avoid allocations.

## Build and test

Requires **Java 21** and **Gradle 9** (wrapper included).

```bash
export JAVA_HOME=/path/to/java-21+
./gradlew clean test build
./gradlew spotlessApply   # formatting
```

## Benchmarks

JMH benchmarks live under `src/jmh/java`.

```bash
./gradlew jmh
./gradlew jmh -Pincludes=PartitionedBinaryTrieGetBenchmark
./gradlew jmh -Pincludes=StoredTrieBytesApiBenchmark -Pjmh.args="-f 1 -wi 1 -i 1"
```

| Benchmark | Measures |
|-----------|----------|
| `PartitionedBinaryTrieGetBenchmark` | `get` — warm/cold cache, single/random keys |
| `PartitionedBinaryTrieMutationBenchmark` | `put`, `remove`, `getRootHash` |
| `PartitionedBinaryTrieCommitBenchmark` | `commit` at scale; sequential vs parallel |
| `StoredTrieBytesApiBenchmark` | `Bytes` API overhead |

## Besu integration

Uses the same persistence contracts as `StoredMerklePatriciaTrie` (`NodeLoader`, `NodeUpdater`, `putDeferred`, `commit`, `getRootHash`). Swap the trie type in `BonsaiWorldState.createTrie()`:

```java
return new StoredPartitionedBinaryTrie(nodeLoader, rootHash);
```

`DefaultStateRootCommitter` needs no changes beyond the trie type. Remaining work: embedding wiring in `PathBasedWorldState`, fork config for `TrieMode`, and mainnet benchmarks vs PMT.

## Links

- [EIP-8297](https://eips.ethereum.org/EIPS/eip-8297)
- [execution-specs binary trie proposal](https://github.com/ethereum/execution-specs/pull/3206)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). All contributions require [DCO sign-off](DCO.md).

## Security

See [SECURITY.md](SECURITY.md) for reporting vulnerabilities.

## License

Apache 2.0 — see [LICENSE](LICENSE).
