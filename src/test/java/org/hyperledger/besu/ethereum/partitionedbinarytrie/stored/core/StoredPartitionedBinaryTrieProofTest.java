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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.internal.TrieConstants;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.core.PartitionedBinaryTrie;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.factory.NodeLoaderMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.factory.NodeUpdaterMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.proof.TrieNodeProofVerifier;
import org.hyperledger.besu.ethereum.trie.Proof;

import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Merkle proof generation and verification for the stored partitioned binary trie.
 *
 * <p>Layer: stored core ({@link StoredPartitionedBinaryTrie}, {@link PartitionedBinaryTrie}).
 * Proofs are verified with {@link TrieNodeProofVerifier}; in-memory and stored variants must agree
 * on the same root and witness nodes.
 */
class StoredPartitionedBinaryTrieProofTest {

  private NodeUpdaterMock nodeUpdater;
  private NodeLoaderMock nodeLoader;

  @BeforeEach
  void setUp() {
    nodeUpdater = new NodeUpdaterMock();
    nodeLoader = new NodeLoaderMock(nodeUpdater);
  }

  @Test
  void getValueWithProof_emptyTrie() {
    final Bytes key = Bytes.fromHexString("0xfe01");
    final StoredPartitionedBinaryTrie trie = new StoredPartitionedBinaryTrie(nodeLoader);

    final Proof<Bytes> proof = trie.getValueWithProof(key);
    assertThat(proof.getValue()).isEmpty();
    assertThat(proof.getProofRelatedNodes()).isEmpty();
    assertThat(TrieNodeProofVerifier.verifyRoot(trie.getRootHash(), proof.getProofRelatedNodes()))
        .isTrue();
  }

  @Test
  void getValueWithProof_singleLeafTrie() {
    final Bytes key = Bytes.fromHexString("0xfe01");
    final Bytes32 value = Bytes32.repeat((byte) 0x01);

    final StoredPartitionedBinaryTrie trie = new StoredPartitionedBinaryTrie(nodeLoader);
    trie.put(key, value);

    final Proof<Bytes> proof = trie.getValueWithProof(key);
    assertThat(proof.getValue()).contains(value);
    assertThat(proof.getProofRelatedNodes()).hasSize(1);
    assertThat(TrieNodeProofVerifier.verifyRoot(trie.getRootHash(), proof.getProofRelatedNodes()))
        .isTrue();
    assertVerifiedValue(
        trie.getRootHash(), key, proof.getProofRelatedNodes(), Optional.of(value.toArrayUnsafe()));
  }

  private static void assertVerifiedValue(
      final Bytes32 root,
      final Bytes key,
      final java.util.List<Bytes> proofNodes,
      final Optional<byte[]> expectedValue) {
    final Optional<Optional<byte[]>> verified =
        TrieNodeProofVerifier.verifyAndGetValue(
            root, key.toArrayUnsafe(), key.size(), proofNodes);
    assertThat(verified).isPresent();
    assertThat(verified.get().map(Bytes::wrap))
        .isEqualTo(expectedValue.map(Bytes::wrap));
  }

  @Test
  void getValueWithProof_multiKeyTrie() {
    final Bytes key1 = Bytes.fromHexString("0xfe01");
    final Bytes key2 = Bytes.fromHexString("0xfe02");
    final Bytes key3 = Bytes.fromHexString("0xfe03");
    final Bytes32 value1 = Bytes32.repeat((byte) 0x11);
    final Bytes32 value2 = Bytes32.repeat((byte) 0x22);
    final Bytes32 value3 = Bytes32.repeat((byte) 0x33);

    final StoredPartitionedBinaryTrie trie = new StoredPartitionedBinaryTrie(nodeLoader);
    trie.put(key1, value1);
    trie.put(key2, value2);
    trie.put(key3, value3);

    final Proof<Bytes> proof = trie.getValueWithProof(key1);
    assertThat(proof.getValue()).contains(value1);
    assertThat(proof.getProofRelatedNodes()).isNotEmpty();
    assertThat(TrieNodeProofVerifier.verifyRoot(trie.getRootHash(), proof.getProofRelatedNodes()))
        .isTrue();
    assertVerifiedValue(trie.getRootHash(), key1, proof.getProofRelatedNodes(), Optional.of(value1.toArrayUnsafe()));
  }

  @Test
  void getValueWithProof_missingKey() {
    final Bytes key1 = Bytes.fromHexString("0xfe01");
    final Bytes key2 = Bytes.fromHexString("0xfe02");
    final Bytes key3 = Bytes.fromHexString("0xfe03");
    final Bytes missingKey = Bytes.fromHexString("0xfe04");
    final Bytes32 value1 = Bytes32.repeat((byte) 0x11);
    final Bytes32 value2 = Bytes32.repeat((byte) 0x22);
    final Bytes32 value3 = Bytes32.repeat((byte) 0x33);

    final StoredPartitionedBinaryTrie trie = new StoredPartitionedBinaryTrie(nodeLoader);
    trie.put(key1, value1);
    trie.put(key2, value2);
    trie.put(key3, value3);

    final Proof<Bytes> proof = trie.getValueWithProof(missingKey);
    assertThat(proof.getValue()).isEmpty();
    assertThat(proof.getProofRelatedNodes()).isNotEmpty();
    assertThat(TrieNodeProofVerifier.verifyRoot(trie.getRootHash(), proof.getProofRelatedNodes()))
        .isTrue();
    assertVerifiedValue(
        trie.getRootHash(), missingKey, proof.getProofRelatedNodes(), Optional.empty());
  }

  @Test
  void getValueWithProof_afterCommitAndReload() {
    final Bytes key = Bytes.fromHexString("0xabcd");
    final Bytes32 value = Bytes32.repeat((byte) 0x99);

    final StoredPartitionedBinaryTrie trie = new StoredPartitionedBinaryTrie(nodeLoader);
    trie.put(key, value);
    trie.commit(nodeUpdater);
    final Bytes32 root = trie.getRootHash();

    final StoredPartitionedBinaryTrie reloaded =
        new StoredPartitionedBinaryTrie(nodeLoader, root);
    final Proof<Bytes> proof = reloaded.getValueWithProof(key);

    assertThat(proof.getValue()).contains(value);
    assertThat(TrieNodeProofVerifier.verifyRoot(root, proof.getProofRelatedNodes())).isTrue();
    assertVerifiedValue(root, key, proof.getProofRelatedNodes(), Optional.of(value.toArrayUnsafe()));
  }

  @Test
  void inMemoryTrieProofMatchesStoredTrie() {
    final Bytes key = Bytes.fromHexString("0x42");
    final Bytes32 value = Bytes32.repeat((byte) 0x5A);
    final byte[] keyBytes = key.toArrayUnsafe();

    final PartitionedBinaryTrie trie = new PartitionedBinaryTrie();
    trie.put(keyBytes, key.size(), value.toArrayUnsafe());
    final Proof<byte[]> coreProof = trie.getValueWithProof(keyBytes, key.size());

    final StoredPartitionedBinaryTrie storedTrie =
        new StoredPartitionedBinaryTrie(nodeLoader);
    storedTrie.put(key, value);
    final Proof<Bytes> storedProof = storedTrie.getValueWithProof(key);

    assertThat(coreProof.getValue()).contains(value.toArrayUnsafe());
    assertThat(storedProof.getValue()).contains(value);
    assertThat(TrieNodeProofVerifier.verifyRoot(trie.getRootHash(), coreProof.getProofRelatedNodes()))
        .isTrue();
    assertThat(TrieNodeProofVerifier.verifyRoot(
            storedTrie.getRootHash(), storedProof.getProofRelatedNodes()))
        .isTrue();
  }
}
