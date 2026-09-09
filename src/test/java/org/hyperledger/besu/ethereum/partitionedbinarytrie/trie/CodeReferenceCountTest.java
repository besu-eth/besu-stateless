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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.trie;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.codec.CodeChunkifier;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.codec.CodeRefCountEncoder;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.codec.TrieNodeCodec;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieKeyDerivation;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.NodeLoaderMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.NodeUpdaterMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.hash.TrieHasher;

import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Reference counting for content-addressed code.
 *
 * <p>Counts are written through the same {@code NodeUpdater} as the trie nodes, under {@code 0x02
 * || codeHash}, so only the first insert and the last delete change the trie itself.
 */
class CodeReferenceCountTest {

  private static final Bytes CODE_A = Bytes.fromHexString("0x6001600201");
  private static final Bytes CODE_B = Bytes.fromHexString("0x6001600155");
  private static final Bytes32 HASH_A = TrieHasher.blake3Hash(CODE_A);
  private static final Bytes32 HASH_B = TrieHasher.blake3Hash(CODE_B);

  private NodeUpdaterMock nodeUpdater;
  private NodeLoaderMock nodeLoader;
  private StoredPartitionedBinaryTrie trie;

  @BeforeEach
  void setUp() {
    nodeUpdater = new NodeUpdaterMock();
    nodeLoader = new NodeLoaderMock(nodeUpdater);
    trie = new StoredPartitionedBinaryTrie(nodeLoader);
  }

  @Test
  void firstInsertWritesChunksAndSetsCountToOne() {
    trie.insertCode(HASH_A, CODE_A);

    assertThat(refCount(trie, HASH_A)).isEqualTo(1);
    assertChunksPresent(trie, HASH_A, CODE_A);
  }

  @Test
  void repeatedInsertRaisesCountAndLeavesTrieUntouched() {
    trie.insertCode(HASH_A, CODE_A);
    final Bytes32 rootAfterFirstInsert = trie.getRootHash();

    trie.insertCode(HASH_A, CODE_A);
    assertThat(refCount(trie, HASH_A)).isEqualTo(2);
    assertThat(trie.getRootHash()).isEqualTo(rootAfterFirstInsert);

    trie.insertCode(HASH_A, CODE_A);
    assertThat(refCount(trie, HASH_A)).isEqualTo(3);
    assertThat(trie.getRootHash()).isEqualTo(rootAfterFirstInsert);
    assertChunksPresent(trie, HASH_A, CODE_A);
  }

  @Test
  void deleteWhileSharedLowersCountAndKeepsChunks() {
    trie.insertCode(HASH_A, CODE_A);
    trie.insertCode(HASH_A, CODE_A);
    trie.insertCode(HASH_A, CODE_A);
    final Bytes32 rootWithChunks = trie.getRootHash();

    trie.deleteCode(HASH_A);
    assertThat(refCount(trie, HASH_A)).isEqualTo(2);
    assertThat(trie.getRootHash()).isEqualTo(rootWithChunks);

    trie.deleteCode(HASH_A);
    assertThat(refCount(trie, HASH_A)).isEqualTo(1);
    assertThat(trie.getRootHash()).isEqualTo(rootWithChunks);
    assertChunksPresent(trie, HASH_A, CODE_A);
  }

  @Test
  void lastDeleteDropsCountAndChunks() {
    trie.insertCode(HASH_A, CODE_A);
    trie.commit(nodeUpdater);

    trie.deleteCode(HASH_A);
    trie.commit(nodeUpdater);

    assertThat(trie.getCodeRefCount(HASH_A)).isEmpty();
    assertThat(nodeUpdater.storage).doesNotContainKey(TrieNodeCodec.codeRefCountKey(HASH_A));
    assertChunksAbsent(trie, HASH_A, CODE_A);
    assertThat(trie.isEmpty()).isTrue();
  }

  @Test
  void deleteUnknownHashIsNoOp() {
    trie.deleteCode(HASH_A);
    trie.commit(nodeUpdater);

    assertThat(trie.getCodeRefCount(HASH_A)).isEmpty();
    assertThat(trie.isEmpty()).isTrue();
  }

  @Test
  void hashesAreCountedIndependently() {
    trie.insertCode(HASH_A, CODE_A);
    trie.insertCode(HASH_A, CODE_A);
    trie.insertCode(HASH_B, CODE_B);

    assertThat(refCount(trie, HASH_A)).isEqualTo(2);
    assertThat(refCount(trie, HASH_B)).isEqualTo(1);

    trie.deleteCode(HASH_B);
    assertThat(trie.getCodeRefCount(HASH_B)).isEmpty();
    assertChunksAbsent(trie, HASH_B, CODE_B);
    assertThat(refCount(trie, HASH_A)).isEqualTo(2);
    assertChunksPresent(trie, HASH_A, CODE_A);
  }

  @Test
  void countIsWrittenWithTheCommitBatchUnderTheCodeHashKey() {
    trie.insertCode(HASH_A, CODE_A);
    assertThat(nodeUpdater.storage).doesNotContainKey(TrieNodeCodec.codeRefCountKey(HASH_A));

    trie.commit(nodeUpdater);

    final Bytes countKey = TrieNodeCodec.codeRefCountKey(HASH_A);
    assertThat(countKey).isEqualTo(Bytes.concatenate(Bytes.of((byte) 0x02), HASH_A));
    assertThat(nodeUpdater.storage).containsKey(countKey);
    assertThat(CodeRefCountEncoder.refCount(nodeUpdater.storage.get(countKey))).isEqualTo(1);
  }

  @Test
  void countKeyCannotCollideWithANodeLocation() {
    trie.insertCode(HASH_A, CODE_A);
    trie.insertCode(HASH_B, CODE_B);
    trie.commit(nodeUpdater);

    final Bytes countKeyA = TrieNodeCodec.codeRefCountKey(HASH_A);
    final Bytes countKeyB = TrieNodeCodec.codeRefCountKey(HASH_B);
    assertThat(nodeUpdater.storage).containsKeys(countKeyA, countKeyB);

    // Node locations hold one byte per path bit, so they never contain a byte above 0x01.
    for (final Bytes key : nodeUpdater.storage.keySet()) {
      if (key.equals(countKeyA) || key.equals(countKeyB)) {
        continue;
      }
      for (int i = 0; i < key.size(); i++) {
        assertThat(key.get(i)).isLessThanOrEqualTo((byte) 0x01);
      }
    }
  }

  @Test
  void rootMatchesPlainChunkWritesWithoutCounting() {
    trie.insertCode(HASH_A, CODE_A);
    trie.insertCode(HASH_A, CODE_A);

    final PartitionedBinaryTrie chunksOnly = new PartitionedBinaryTrie();
    final List<Bytes32> chunks = CodeChunkifier.chunkifyCode(CODE_A);
    for (int i = 0; i < chunks.size(); i++) {
      chunksOnly.put(TrieKeyDerivation.getTreeKeyForCodeChunk(HASH_A, i), chunks.get(i));
    }

    assertThat(trie.getRootHash()).isEqualTo(chunksOnly.getRootHash());
  }

  @Test
  void countAndChunksPersistAcrossReload() {
    trie.insertCode(HASH_A, CODE_A);
    trie.commit(nodeUpdater);

    trie.insertCode(HASH_A, CODE_A);
    trie.commit(nodeUpdater);
    final Bytes32 root = trie.getRootHash();

    final StoredPartitionedBinaryTrie reloaded = new StoredPartitionedBinaryTrie(nodeLoader, root);
    assertChunksPresent(reloaded, HASH_A, CODE_A);
    assertThat(refCount(reloaded, HASH_A)).isEqualTo(2);

    reloaded.deleteCode(HASH_A);
    reloaded.commit(nodeUpdater);
    assertThat(refCount(reloaded, HASH_A)).isEqualTo(1);
    assertThat(reloaded.getRootHash()).isEqualTo(root);

    reloaded.deleteCode(HASH_A);
    reloaded.commit(nodeUpdater);
    assertThat(reloaded.getCodeRefCount(HASH_A)).isEmpty();
    assertChunksAbsent(reloaded, HASH_A, CODE_A);
  }

  @Test
  void emptyBytecodeIsCountedWithoutChunks() {
    final Bytes32 emptyHash = TrieKeyDerivation.EMPTY_CODE_HASH;
    trie.insertCode(emptyHash, Bytes.EMPTY);

    assertThat(refCount(trie, emptyHash)).isEqualTo(1);
    assertThat(CodeRefCountEncoder.chunkCount(trie.getCodeRefCount(emptyHash).orElseThrow()))
        .isZero();
    assertThat(trie.isEmpty()).isTrue();

    trie.deleteCode(emptyHash);
    assertThat(trie.getCodeRefCount(emptyHash)).isEmpty();
  }

  @Test
  void zeroChunksAreCountedButRemainAbsentFromTrie() {
    final Bytes code = Bytes.repeat((byte) 0, 64);
    final Bytes32 codeHash = TrieHasher.blake3Hash(code);

    trie.insertCode(codeHash, code);

    assertThat(refCount(trie, codeHash)).isEqualTo(1);
    assertChunksAbsent(trie, codeHash, code);
    assertThat(trie.isEmpty()).isTrue();
  }

  private static long refCount(final PartitionedBinaryTrie target, final Bytes32 codeHash) {
    return CodeRefCountEncoder.refCount(
        target
            .getCodeRefCount(codeHash)
            .orElseThrow(() -> new AssertionError("missing count for " + codeHash)));
  }

  private static void assertChunksPresent(
      final PartitionedBinaryTrie target, final Bytes32 codeHash, final Bytes code) {
    final List<Bytes32> chunks = CodeChunkifier.chunkifyCode(code);
    for (int i = 0; i < chunks.size(); i++) {
      assertThat(target.get(TrieKeyDerivation.getTreeKeyForCodeChunk(codeHash, i)))
          .contains(chunks.get(i));
    }
  }

  private static void assertChunksAbsent(
      final PartitionedBinaryTrie target, final Bytes32 codeHash, final Bytes code) {
    final List<Bytes32> chunks = CodeChunkifier.chunkifyCode(code);
    for (int i = 0; i < chunks.size(); i++) {
      assertThat(target.get(TrieKeyDerivation.getTreeKeyForCodeChunk(codeHash, i))).isEmpty();
    }
  }
}
