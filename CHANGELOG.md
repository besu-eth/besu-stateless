# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0-SNAPSHOT] - 2026-07-28

### Added

- Initial import of the Partitioned Binary Trie (PBT) library from `besu-partitioned-binary-trie`.
- `StoredPartitionedBinaryTrie` with Tuweni `Bytes` API (`putDeferred`, `commit`, `getRootHash`).
- EIP-8297 embedding layer: zone-based key derivation, account header encoding, bytecode chunking.
- Stored trie visitors (get, put, remove, commit, proof), node codec, and merkle proof support.
- `ParallelStoredPartitionedBinaryTrie` for parallel commit.
- JMH benchmarks under `src/jmh/java`.
- Comprehensive test suite (~200 tests) with execution-specs conformance oracle.
- GitHub Actions CI workflow (Java 21, `./gradlew clean test`, `compileJmhJava`).

### Removed

- Legacy Verkle / binary-trie prototype code previously in this repository.
