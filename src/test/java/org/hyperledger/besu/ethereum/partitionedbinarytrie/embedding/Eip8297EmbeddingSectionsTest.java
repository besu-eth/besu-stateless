/*
 * Copyright Hyperledger Besu Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */
package org.hyperledger.besu.ethereum.partitionedbinarytrie.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.codec.BasicDataEncoder;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.codec.CodeChunkifier;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieConstants;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieKeyDerivation;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.PartitionedBinaryTrie;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.StoredPartitionedBinaryTrie;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.NodeLoaderMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.NodeUpdaterMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.PartitionedBinaryTrieFactory;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.hash.TrieHasher;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.reference.BinaryTrie;

import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.EnumSource.Mode;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * End-to-end trie coverage for every EIP-8297 embedding section across in-memory and stored
 * backends.
 *
 * <p>Layer: embedding (key derivation, basic data, code chunks, storage) over trie storage and
 * {@link PartitionedBinaryTrie}. Each parameterized test compares root hash and values against
 * {@link BinaryTrie}.
 */
class Eip8297EmbeddingSectionsTest {

  private static final Bytes32 ADDRESS_A =
      Bytes32.fromHexString("0x000000000000000000000000abcdefabcdefabcdefabcdefabcdefabcdefabcd");
  private static final Bytes32 ADDRESS_B =
      Bytes32.fromHexString("0x0000000000000000000000001234567890123456789012345678901234567890");

  private static final Bytes32 CODE_HASH =
      TrieHasher.blake3Hash(
          Bytes.of(
              (byte) 's',
              (byte) 'o',
              (byte) 'm',
              (byte) 'e',
              (byte) ' ',
              (byte) 'c',
              (byte) 'o',
              (byte) 'd',
              (byte) 'e'));

  @ParameterizedTest
  @EnumSource(TrieKind.class)
  void basicDataPutGetCommitReload(final TrieKind kind) {
    final Bytes key = TrieKeyDerivation.getTreeKeyForBasicData(ADDRESS_A);
    final Bytes32 value = BasicDataEncoder.encodeBasicData(10, 20, UInt256.valueOf(999));

    try (EmbeddingTrieSession session = kind.open()) {
      session.put(key, value);
      session.commitAndReload();
      assertThat(session.get(key)).contains(value.toArray());
      assertThat(session.rootHash()).isEqualTo(session.spec().root());
    }
  }

  @ParameterizedTest
  @EnumSource(TrieKind.class)
  void codeHashPutGetCommitReload(final TrieKind kind) {
    final Bytes key = TrieKeyDerivation.getTreeKeyForCodeHash(ADDRESS_A);
    final Bytes32 value = CODE_HASH;

    try (EmbeddingTrieSession session = kind.open()) {
      session.put(key, value);
      session.commitAndReload();
      assertThat(session.get(key)).contains(value.toArray());
      assertThat(session.rootHash()).isEqualTo(session.spec().root());
    }
  }

  @ParameterizedTest
  @EnumSource(TrieKind.class)
  void headerStorageSlotPutGetCommitReload(final TrieKind kind) {
    final Bytes key = TrieKeyDerivation.getTreeKeyForStorageSlot(ADDRESS_A, UInt256.valueOf(5));
    final Bytes32 value = Bytes32.repeat((byte) 0x05);

    try (EmbeddingTrieSession session = kind.open()) {
      session.put(key, value);
      session.commitAndReload();
      assertThat(session.get(key)).contains(value.toArray());
      assertThat(key.size()).isEqualTo(34);
      assertThat(session.rootHash()).isEqualTo(session.spec().root());
    }
  }

  @ParameterizedTest
  @EnumSource(TrieKind.class)
  void headerStorageBoundarySlot63(final TrieKind kind) {
    final Bytes key = TrieKeyDerivation.getTreeKeyForStorageSlot(ADDRESS_A, UInt256.valueOf(63));
    final Bytes32 value = Bytes32.repeat((byte) 0x3F);

    try (EmbeddingTrieSession session = kind.open()) {
      session.put(key, value);
      session.commitAndReload();
      assertThat(session.get(key)).contains(value.toArray());
      assertThat(key.size()).isEqualTo(34);
    }
  }

  @ParameterizedTest
  @EnumSource(TrieKind.class)
  void overflowStorageSlotPutGetCommitReload(final TrieKind kind) {
    final Bytes key = TrieKeyDerivation.getTreeKeyForStorageSlot(ADDRESS_A, UInt256.valueOf(1000));
    final Bytes32 value = Bytes32.repeat((byte) 0xE8);

    try (EmbeddingTrieSession session = kind.open()) {
      session.put(key, value);
      session.commitAndReload();
      assertThat(session.get(key)).contains(value.toArray());
      assertThat(key.size()).isEqualTo(66);
      assertThat(key.get(0)).isEqualTo((byte) 0xFF);
      assertThat(session.rootHash()).isEqualTo(session.spec().root());
    }
  }

  @ParameterizedTest
  @EnumSource(TrieKind.class)
  void overflowStorageBoundarySlot64(final TrieKind kind) {
    final Bytes key = TrieKeyDerivation.getTreeKeyForStorageSlot(ADDRESS_A, UInt256.valueOf(64));
    final Bytes32 value = Bytes32.repeat((byte) 0x40);

    try (EmbeddingTrieSession session = kind.open()) {
      session.put(key, value);
      session.commitAndReload();
      assertThat(session.get(key)).contains(value.toArray());
      assertThat(key.size()).isEqualTo(66);
    }
  }

  @ParameterizedTest
  @EnumSource(TrieKind.class)
  void headerCodeChunkPutGetCommitReload(final TrieKind kind) {
    final Bytes key = TrieKeyDerivation.getTreeKeyForCodeChunk(ADDRESS_A, CODE_HASH, 5);
    final Bytes32 value = CodeChunkifier.chunkifyCode(Bytes.fromHexString("010203")).getFirst();

    try (EmbeddingTrieSession session = kind.open()) {
      session.put(key, value);
      session.commitAndReload();
      assertThat(session.get(key)).contains(value.toArray());
      assertThat(key.size()).isEqualTo(34);
      assertThat(session.rootHash()).isEqualTo(session.spec().root());
    }
  }

  @ParameterizedTest
  @EnumSource(TrieKind.class)
  void overflowCodeChunkPutGetCommitReload(final TrieKind kind) {
    final Bytes key = TrieKeyDerivation.getTreeKeyForCodeChunk(ADDRESS_A, CODE_HASH, 300);
    final Bytes32 value = Bytes32.repeat((byte) 0xAC);

    try (EmbeddingTrieSession session = kind.open()) {
      session.put(key, value);
      session.commitAndReload();
      assertThat(session.get(key)).contains(value.toArray());
      assertThat(key.size()).isEqualTo(34);
      assertThat(key.get(0)).isEqualTo((byte) 1);
      assertThat(session.rootHash()).isEqualTo(session.spec().root());
    }
  }

  @ParameterizedTest
  @EnumSource(TrieKind.class)
  void multiSectionSameAccount(final TrieKind kind) {
    final Bytes basicKey = TrieKeyDerivation.getTreeKeyForBasicData(ADDRESS_A);
    final Bytes codeHashKey = TrieKeyDerivation.getTreeKeyForCodeHash(ADDRESS_A);
    final Bytes headerStorageKey =
        TrieKeyDerivation.getTreeKeyForStorageSlot(ADDRESS_A, UInt256.valueOf(5));
    final Bytes overflowStorageKey =
        TrieKeyDerivation.getTreeKeyForStorageSlot(ADDRESS_A, UInt256.valueOf(1000));
    final Bytes headerCodeKey = TrieKeyDerivation.getTreeKeyForCodeChunk(ADDRESS_A, CODE_HASH, 5);
    final Bytes overflowCodeKey =
        TrieKeyDerivation.getTreeKeyForCodeChunk(ADDRESS_A, CODE_HASH, 300);

    final Bytes32 basicValue = BasicDataEncoder.encodeBasicData(3, 4, UInt256.valueOf(42));
    final Bytes32 codeHashValue = CODE_HASH;
    final Bytes32 headerStorageValue = Bytes32.repeat((byte) 0x11);
    final Bytes32 overflowStorageValue = Bytes32.repeat((byte) 0x22);
    final Bytes32 headerCodeValue =
        CodeChunkifier.chunkifyCode(Bytes.fromHexString("0x6001")).getFirst();
    final Bytes32 overflowCodeValue = Bytes32.repeat((byte) 0x33);

    try (EmbeddingTrieSession session = kind.open()) {
      session.put(basicKey, basicValue);
      session.put(codeHashKey, codeHashValue);
      session.put(headerStorageKey, headerStorageValue);
      session.put(overflowStorageKey, overflowStorageValue);
      session.put(headerCodeKey, headerCodeValue);
      session.put(overflowCodeKey, overflowCodeValue);
      session.commitAndReload();

      assertThat(session.get(basicKey)).contains(basicValue.toArray());
      assertThat(session.get(codeHashKey)).contains(codeHashValue.toArray());
      assertThat(session.get(headerStorageKey)).contains(headerStorageValue.toArray());
      assertThat(session.get(overflowStorageKey)).contains(overflowStorageValue.toArray());
      assertThat(session.get(headerCodeKey)).contains(headerCodeValue.toArray());
      assertThat(session.get(overflowCodeKey)).contains(overflowCodeValue.toArray());
      assertThat(session.rootHash()).isEqualTo(session.spec().root());
    }
  }

  @ParameterizedTest
  @EnumSource(TrieKind.class)
  void crossAccountIsolation(final TrieKind kind) {
    final Bytes keyA = TrieKeyDerivation.getTreeKeyForBasicData(ADDRESS_A);
    final Bytes keyB = TrieKeyDerivation.getTreeKeyForBasicData(ADDRESS_B);
    final Bytes32 valueA = BasicDataEncoder.encodeBasicData(1, 0, UInt256.ONE);
    final Bytes32 valueB = BasicDataEncoder.encodeBasicData(2, 0, UInt256.valueOf(2));

    try (EmbeddingTrieSession session = kind.open()) {
      session.put(keyA, valueA);
      session.put(keyB, valueB);
      session.commitAndReload();

      assertThat(session.get(keyA)).contains(valueA.toArray());
      assertThat(session.get(keyB)).contains(valueB.toArray());
      assertThat(keyA).isNotEqualTo(keyB);
    }
  }

  @ParameterizedTest
  @MethodSource("storedWithEmbeddingSections")
  void removeSectionPreservesHistoricalRoot(final TrieKind kind, final EmbeddingSection section) {
    final Bytes key = section.key();
    final Bytes32 value = section.value();

    try (EmbeddingTrieSession session = kind.open()) {
      session.put(key, value);
      session.commit();
      final Bytes32 rootWithValue = session.rootHash();

      session.remove(key);
      session.commit();
      assertThat(session.get(key)).isEmpty();
      assertThat(session.rootHash()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);
      assertThat(session.rootHash()).isEqualTo(session.spec().root());

      try (EmbeddingTrieSession historical = session.atRoot(rootWithValue)) {
        assertThat(historical.get(key)).contains(value.toArray());
        assertThat(historical.rootHash()).isEqualTo(rootWithValue);
      }
    }
  }

  @ParameterizedTest
  @MethodSource("storedWithEmbeddingSections")
  void removeOneSectionKeepsOthers(final TrieKind kind, final EmbeddingSection sectionToRemove) {
    try (EmbeddingTrieSession session = kind.open()) {
      for (final EmbeddingSection section : EmbeddingSection.values()) {
        session.put(section.key(), section.value());
      }
      session.commit();
      final Bytes32 rootAll = session.rootHash();

      final Bytes removedKey = sectionToRemove.key();
      final Bytes32 removedValue = sectionToRemove.value();
      session.remove(removedKey);
      session.commit();
      assertThat(session.get(removedKey)).isEmpty();
      assertThat(session.rootHash()).isEqualTo(session.spec().root());

      for (final EmbeddingSection section : EmbeddingSection.values()) {
        final Bytes key = section.key();
        if (key.equals(removedKey)) {
          assertThat(session.get(key)).isEmpty();
        } else {
          assertThat(session.get(key)).contains(section.value().toArray());
        }
      }

      try (EmbeddingTrieSession historical = session.atRoot(rootAll)) {
        assertThat(historical.get(removedKey)).contains(removedValue.toArray());
        assertThat(historical.rootHash()).isEqualTo(rootAll);
      }
    }
  }

  @ParameterizedTest
  @EnumSource(value = TrieKind.class, mode = Mode.EXCLUDE, names = "IN_MEMORY")
  void removeAbsentSectionIsNoOp(final TrieKind kind) {
    final Bytes key = TrieKeyDerivation.getTreeKeyForBasicData(ADDRESS_A);

    try (EmbeddingTrieSession session = kind.open()) {
      session.commit();
      final Bytes32 rootBefore = session.rootHash();

      session.remove(key);
      session.commit();
      assertThat(session.rootHash()).isEqualTo(rootBefore);
      assertThat(session.rootHash()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);
    }
  }

  @ParameterizedTest
  @MethodSource("storedWithEmbeddingSections")
  void removeAndReputSameSection(final TrieKind kind, final EmbeddingSection section) {
    final Bytes key = section.key();
    final Bytes32 value1 = section.value();
    final Bytes32 value2 = Bytes32.repeat((byte) 0xFE);

    try (EmbeddingTrieSession session = kind.open()) {
      session.put(key, value1);
      session.commit();
      final Bytes32 root1 = session.rootHash();

      session.remove(key);
      session.commit();
      assertThat(session.get(key)).isEmpty();
      assertThat(session.rootHash()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);

      try (EmbeddingTrieSession historical = session.atRoot(root1)) {
        assertThat(historical.get(key)).contains(value1.toArray());
      }

      session.put(key, value2);
      session.commit();
      final Bytes32 root2 = session.rootHash();
      assertThat(root2).isNotEqualTo(root1);
      assertThat(session.get(key)).contains(value2.toArray());
      assertThat(session.rootHash()).isEqualTo(session.spec().root());

      try (EmbeddingTrieSession atRoot2 = session.atRoot(root2)) {
        assertThat(atRoot2.get(key)).contains(value2.toArray());
      }
    }
  }

  @ParameterizedTest
  @MethodSource("storedWithEmbeddingSections")
  void putDeferredRemoveSectionPreservesHistoricalRoot(
      final TrieKind kind, final EmbeddingSection section) {
    final Bytes key = section.key();
    final Bytes32 value = section.value();

    try (EmbeddingTrieSession session = kind.open()) {
      session.put(key, value);
      session.commit();
      final Bytes32 rootWithValue = session.rootHash();

      session.putDeferred(key, existing -> Optional.empty());
      session.commit();
      assertThat(session.get(key)).isEmpty();
      assertThat(session.rootHash()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);
      assertThat(session.rootHash()).isEqualTo(session.spec().root());

      try (EmbeddingTrieSession historical = session.atRoot(rootWithValue)) {
        assertThat(historical.get(key)).contains(value.toArray());
        assertThat(historical.rootHash()).isEqualTo(rootWithValue);
      }
    }
  }

  private static Stream<Arguments> storedWithEmbeddingSections() {
    return Stream.of(TrieKind.STORED)
        .flatMap(
            kind ->
                Stream.of(EmbeddingSection.values()).map(section -> Arguments.of(kind, section)));
  }

  private enum EmbeddingSection {
    BASIC_DATA {
      @Override
      Bytes key() {
        return TrieKeyDerivation.getTreeKeyForBasicData(ADDRESS_A);
      }

      @Override
      Bytes32 value() {
        return BasicDataEncoder.encodeBasicData(10, 20, UInt256.valueOf(999));
      }
    },
    CODE_HASH_SECTION {
      @Override
      Bytes key() {
        return TrieKeyDerivation.getTreeKeyForCodeHash(ADDRESS_A);
      }

      @Override
      Bytes32 value() {
        return Eip8297EmbeddingSectionsTest.CODE_HASH;
      }
    },
    HEADER_STORAGE {
      @Override
      Bytes key() {
        return TrieKeyDerivation.getTreeKeyForStorageSlot(ADDRESS_A, UInt256.valueOf(5));
      }

      @Override
      Bytes32 value() {
        return Bytes32.repeat((byte) 0x05);
      }
    },
    OVERFLOW_STORAGE {
      @Override
      Bytes key() {
        return TrieKeyDerivation.getTreeKeyForStorageSlot(ADDRESS_A, UInt256.valueOf(1000));
      }

      @Override
      Bytes32 value() {
        return Bytes32.repeat((byte) 0xE8);
      }
    },
    HEADER_CODE_CHUNK {
      @Override
      Bytes key() {
        return TrieKeyDerivation.getTreeKeyForCodeChunk(
            ADDRESS_A, Eip8297EmbeddingSectionsTest.CODE_HASH, 5);
      }

      @Override
      Bytes32 value() {
        return CodeChunkifier.chunkifyCode(Bytes.fromHexString("010203")).getFirst();
      }
    },
    OVERFLOW_CODE_CHUNK {
      @Override
      Bytes key() {
        return TrieKeyDerivation.getTreeKeyForCodeChunk(
            ADDRESS_A, Eip8297EmbeddingSectionsTest.CODE_HASH, 300);
      }

      @Override
      Bytes32 value() {
        return Bytes32.repeat((byte) 0xAC);
      }
    };

    abstract Bytes key();

    abstract Bytes32 value();
  }

  private enum TrieKind {
    IN_MEMORY {
      @Override
      EmbeddingTrieSession open() {
        return EmbeddingTrieSession.inMemory();
      }
    },
    STORED {
      @Override
      EmbeddingTrieSession open() {
        return EmbeddingTrieSession.stored();
      }
    };

    abstract EmbeddingTrieSession open();
  }

  private static final class EmbeddingTrieSession implements AutoCloseable {

    private final BinaryTrie spec;
    private final NodeUpdaterMock nodeUpdater;
    private final PartitionedBinaryTrieFactory factory;
    private PartitionedBinaryTrie trie;

    private EmbeddingTrieSession(
        final PartitionedBinaryTrie trie,
        final BinaryTrie spec,
        final NodeUpdaterMock nodeUpdater,
        final PartitionedBinaryTrieFactory factory) {
      this.trie = trie;
      this.spec = spec;
      this.nodeUpdater = nodeUpdater;
      this.factory = factory;
    }

    static EmbeddingTrieSession inMemory() {
      return new EmbeddingTrieSession(new PartitionedBinaryTrie(), new BinaryTrie(), null, null);
    }

    static EmbeddingTrieSession stored() {
      final NodeUpdaterMock updater = new NodeUpdaterMock();
      final PartitionedBinaryTrieFactory trieFactory =
          new PartitionedBinaryTrieFactory(new NodeLoaderMock(updater));
      return new EmbeddingTrieSession(trieFactory.create(), new BinaryTrie(), updater, trieFactory);
    }

    BinaryTrie spec() {
      return spec;
    }

    void put(final Bytes key, final Bytes32 value) {
      trie.put(key, value);
      spec.put(key, value);
      assertThat(rootHash()).isEqualTo(spec.root());
    }

    Optional<byte[]> get(final Bytes key) {
      return trie.get(key).map(Bytes::toArray);
    }

    void remove(final Bytes key) {
      trie.remove(key);
      spec.remove(key);
    }

    void putDeferred(final Bytes key, final UnaryOperator<Optional<byte[]>> merger) {
      trie.putDeferred(
          key,
          existing ->
              merger
                  .apply(
                      existing.map(Bytes::toArrayUnsafe).map(Optional::of).orElse(Optional.empty()))
                  .map(Bytes::wrap));
      final Optional<byte[]> merged =
          merger.apply(
              spec.get(key).map(Bytes32::toArrayUnsafe).map(Optional::of).orElse(Optional.empty()));
      merged.ifPresentOrElse(v -> spec.put(key, Bytes32.wrap(v)), () -> spec.remove(key));
      assertThat(rootHash()).isEqualTo(spec.root());
    }

    Bytes32 rootHash() {
      return trie.getRootHash();
    }

    void commit() {
      if (nodeUpdater == null) {
        return;
      }
      ((StoredPartitionedBinaryTrie) trie).commit(nodeUpdater);
    }

    void commitAndReload() {
      commit();
      final Bytes32 root = rootHash();
      reloadAt(root);
      assertThat(rootHash()).isEqualTo(root);
    }

    EmbeddingTrieSession atRoot(final Bytes32 root) {
      if (factory != null) {
        return new EmbeddingTrieSession(factory.create(root), spec, nodeUpdater, factory);
      }
      throw new UnsupportedOperationException("Historical roots require stored mode");
    }

    private void reloadAt(final Bytes32 root) {
      if (factory != null) {
        trie = factory.create(root);
      }
    }

    @Override
    public void close() {
      // no resources to release; satisfies try-with-resources in tests
    }
  }
}
