# Besu Stateless — Partitioned Binary Trie (EIP-8297)

[![CI](https://github.com/besu-eth/besu-stateless/actions/workflows/ci.yml/badge.svg)](https://github.com/besu-eth/besu-stateless/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

Java implementation of the [EIP-8297](https://eips.ethereum.org/EIPS/eip-8297) Partitioned Binary Trie for Hyperledger Besu. This repository is the home for Besu's stateless / binary-trie work: a production-oriented stored trie with embedding, proofs, parallel commit, and JMH benchmarks.

The trie model follows the [execution-specs binary trie proposal](https://github.com/ethereum/execution-specs/pull/3206) (and the reference [`bin-trie` branch](https://github.com/kevaundray/execution-specs/pull/9)).

## What it is

The Partitioned Binary Trie (PBT) replaces the Merkle Patricia Trie (PMT) as Ethereum's state commitment structure under EIP-8297. This library provides:

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
├── embedding/
│   ├── keys/      # Zone-based trie key derivation (account, storage, code)
│   ├── codec/     # Account header encoding, bytecode chunking
│   └── params/    # Embedding configuration
├── internal/
│   ├── hash/      # Allocation-free BLAKE3
│   └── bytes/     # Hot-path byte[] trie operations
├── stored/
│   ├── core/      # Trie engine (PartitionedBinaryTrie, Stored*, Parallel*)
│   ├── node/      # TrieNode graph (empty, memory, stored)
│   ├── visitor/   # Get/Put/Remove/Commit/Proof visitors
│   ├── factory/   # Node loading and trie construction
│   ├── codec/     # Binary node serialization
│   └── proof/     # Merkle proof generation and verification

src/test/java/.../trie/   # Spec-reference oracle — not in the JAR
├── reference/     # BinaryTrie, MutableBinaryTrie
├── node/          # Canonical branch/leaf node types
└── hash/          # Spec trie hashing utilities
```

### Package roles

| Package | Purpose | Entry points |
|---------|---------|--------------|
| `embedding.keys` | EIP-8297 zone-based key derivation | `TrieKeyDerivation` |
| `embedding.codec` | Account header encoding, bytecode chunking | `BasicDataEncoder`, `CodeChunkifier` |
| `stored.core` | Trie engine with `byte[]` hot path and Tuweni `Bytes` API | `PartitionedBinaryTrie`, `StoredPartitionedBinaryTrie` |
| `stored.factory` | Node loading and trie construction | `PartitionedBinaryTrieFactory` |
| `stored.visitor` | Trie operation visitors | `GetVisitor`, `PutVisitor`, `CommitVisitor` |
| `internal` | Allocation-free hashing, bit ops, trie constants (not public API) | `Blake3Hasher`, `ByteTrieOps`, `TrieConstants` |
| `trie.reference` (test only) | Spec-faithful conformance oracle | `BinaryTrie`, `MutableBinaryTrie` |

## Build and test

Requires **Java 21** and **Gradle 9** (wrapper included).

```bash
export JAVA_HOME=/path/to/java-21+
./gradlew clean test
./gradlew build
```

Format code:

```bash
./gradlew spotlessApply
```

## Benchmarks

JMH benchmarks live under `src/jmh/java` (`me.champeau.jmh` plugin).

```bash
# Run all benchmarks
./gradlew jmh

# Run a subset (regex on class name)
./gradlew jmh -Pincludes=PartitionedBinaryTrieGetBenchmark

# Quick smoke run
./gradlew jmh -Pincludes=StoredTrieBytesApiBenchmark -Pjmh.args="-f 1 -wi 1 -i 1"
```

| Benchmark class | What it measures |
|-----------------|------------------|
| `PartitionedBinaryTrieGetBenchmark` | `get` — warm/cold cache, single/random key, 34/66-byte embedding keys |
| `PartitionedBinaryTrieMutationBenchmark` | `put` (single + batch), `remove`, `getRootHash` after updates |
| `PartitionedBinaryTrieCommitBenchmark` | `commit` at 100/1K/10K keys; sequential vs `ParallelStoredPartitionedBinaryTrie` |
| `StoredTrieBytesApiBenchmark` | `StoredPartitionedBinaryTrie` get/put (`Bytes` API overhead) |

## Besu integration

The stored trie uses the same persistence contracts as `StoredMerklePatriciaTrie`:

| PMT (`ethereum/trie`) | This library |
|----------------------|--------------|
| `NodeLoader.getNode(location, hash)` | Same interface (`org.hyperledger.besu.internal:trie`) |
| `NodeUpdater.store(location, hash, value)` | Same interface |
| `get` / `put` / `remove` / `putDeferred` | `StoredPartitionedBinaryTrie` |
| `getRootHash()` | BLAKE3 root (32 bytes; empty = `0x00…00`) |
| `commit(NodeUpdater)` | Walks dirty nodes, stores at path `location` |

### Wiring in `BonsaiWorldState.createTrie()`

```java
// Before (PMT):
return new StoredMerklePatriciaTrie(nodeLoader, rootHash, Function.identity(), Function.identity());

// After (partitioned binary trie):
return new StoredPartitionedBinaryTrie(nodeLoader, rootHash);
```

`DefaultStateRootCommitter` already calls `putDeferred`, `commit`, and `getRootHash` — no changes needed there beyond the trie type.

### Dependency (future)

```gradle
implementation 'org.hyperledger.besu:besu-stateless:0.1.0-SNAPSHOT'
```

## Cross-client notes (Nethermind)

Direct state-root comparison with [Nethermind PR 12573](https://github.com/NethermindEth/nethermind/pull/12573) is **not feasible today**. Both implementations target EIP-8297, but they use incompatible trie models:

| Aspect | Besu PBT (this repo) | Nethermind `pbt-state` |
|--------|----------------------|------------------------|
| Spec oracle | execution-specs branch/leaf model | Internal `EipReferenceTree` stem model |
| Key format | Variable-length: `zone (1) \| digest (32 or 64) \| subIndex (1)` | Fixed 32-byte: `stem (31) \| subIndex (1)` |
| Merkleization | Tagged preimages: `0x00\|\|key\|\|value` (leaf), `0x01\|\|prefix\|\|left\|\|right` (branch) | Stem fold + `blake3(left\|\|right)` pairs |
| State root | Single monolithic trie root | Three partition roots folded via `PbtPartitionRoots` |

What does align: EIP embedding key derivation, `BasicDataEncoder` / `CodeChunkifier` layouts, and BLAKE3 as draft hash function. These vectors are pinned in `PartitionedBinaryTrieInteropTest` and `BinaryTrieReferenceVectorsTest`.

Use [execution-specs `ethereum.binary_trie`](https://github.com/ethereum/execution-specs/pull/3206) as the neutral cross-client oracle.

## Performance design

Hot paths (`stored` + `internal` packages) avoid Tuweni allocations:

| Technique | Where |
|-----------|-------|
| `ThreadLocal<Blake3Digest>` | `Blake3Hasher` — no digest alloc per hash |
| `ThreadLocal<byte[]>` bit/prefix buffers | `ByteTrieOps` — reused expand/encode buffers |
| `byte[]` keys/values in trie graph | `TrieNode`, `PartitionedBinaryTrie` hot path |
| Tuweni `Bytes` at public API | `StoredPartitionedBinaryTrie`, `PartitionedBinaryTrie` |
| Dirty/clean flags on commit | Skip unchanged subtrees (PMT pattern) |

## Remaining gaps for full Besu production

- Embedding layer wiring into `PathBasedWorldState` key encoding
- Chain activation / fork config (`TrieMode` selection in `WorldStateConfig`)
- Benchmark vs PMT on mainnet workloads

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). All contributions require [DCO sign-off](DCO.md).

## Security

See [SECURITY.md](SECURITY.md) for reporting vulnerabilities.

## License

Apache 2.0 — see [LICENSE](LICENSE).
