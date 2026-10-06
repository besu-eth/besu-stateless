/*
 * Copyright contributors to Besu.
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
 */
package org.hyperledger.besu.ethereum.partitionedbinarytrie.trie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.proof.TrieNodeProofVerifier.verify;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.codec.TrieNodeCodec;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieKeyDerivation;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.NodeLoaderMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.NodeUpdaterMock;
import org.hyperledger.besu.ethereum.trie.Proof;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.Test;

/**
 * Proofs of {@link StoredPartitionedBinaryTrie}, checked with {@code TrieNodeProofVerifier}.
 *
 * <p>Keys 0xfe01, 0xfe02 and 0xfe03 give a root branch whose prefix is 0xfe and six zero bits, leaf
 * 0xfe01 on its left and a branch over 0xfe02 and 0xfe03 on its right.
 */
class TrieProofTest {

  private static final Bytes KEY_1 = Bytes.fromHexString("0xfe01");
  private static final Bytes KEY_2 = Bytes.fromHexString("0xfe02");
  private static final Bytes KEY_3 = Bytes.fromHexString("0xfe03");

  private final NodeUpdaterMock nodeUpdater = new NodeUpdaterMock();
  private final NodeLoaderMock nodeLoader = new NodeLoaderMock(nodeUpdater);

  @Test
  void emptyTrieProvesEveryKeyAbsent() {
    final StoredPartitionedBinaryTrie trie = new StoredPartitionedBinaryTrie(nodeLoader);

    final Proof<Bytes> proof = trie.getValueWithProof(KEY_1);

    assertThat(proof.getValue()).isEmpty();
    assertThat(proof.getProofRelatedNodes()).isEmpty();
    assertThat(verify(trie.getRootHash(), KEY_1, proof.getProofRelatedNodes())).isEmpty();
  }

  @Test
  void proofOfAPresentKeyHoldsItsPath() {
    final StoredPartitionedBinaryTrie trie = threeKeyTrie();

    final Proof<Bytes> proof = trie.getValueWithProof(KEY_2);

    assertThat(proof.getValue()).contains(value(KEY_2));
    assertThat(tags(proof))
        .containsExactly(
            TrieNodeCodec.BRANCH_TAG, TrieNodeCodec.BRANCH_TAG, TrieNodeCodec.LEAF_TAG);
    assertThat(prefixLength(proof.getProofRelatedNodes().get(0))).isEqualTo(14);
    assertThat(verify(trie.getRootHash(), KEY_2, proof.getProofRelatedNodes()))
        .contains(value(KEY_2));
  }

  @Test
  void keyLeavingTheRootPrefixIsProvedAbsentByTheRootAlone() {
    // 0xfe04 leaves the root prefix at its 14th bit.
    final Bytes key = Bytes.fromHexString("0xfe04");
    final StoredPartitionedBinaryTrie trie = threeKeyTrie();

    final Proof<Bytes> proof = trie.getValueWithProof(key);

    assertThat(proof.getValue()).isEmpty();
    assertThat(tags(proof)).containsExactly(TrieNodeCodec.BRANCH_TAG);
    assertThat(verify(trie.getRootHash(), key, proof.getProofRelatedNodes())).isEmpty();
  }

  @Test
  void absentKeyOfAStemIsProvedByTheLeafInItsPlace() {
    // 0xfe00 follows the path of 0xfe01, whose leaf proves 0xfe00 absent.
    final Bytes key = Bytes.fromHexString("0xfe00");
    final StoredPartitionedBinaryTrie trie = threeKeyTrie();

    final Proof<Bytes> proof = trie.getValueWithProof(key);

    assertThat(proof.getValue()).isEmpty();
    assertThat(tags(proof)).containsExactly(TrieNodeCodec.BRANCH_TAG, TrieNodeCodec.LEAF_TAG);
    assertThat(verify(trie.getRootHash(), key, proof.getProofRelatedNodes())).isEmpty();
  }

  @Test
  void reloadedTrieGivesTheSameProofs() {
    // Stems rebuilt from their stored entries must give the very nodes the trie had in memory.
    final Random random = new Random(11);
    final StoredPartitionedBinaryTrie trie = new StoredPartitionedBinaryTrie(nodeLoader);
    final List<Bytes> keys = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      final Bytes32 address = Bytes32.random(random);
      for (final Bytes key :
          List.of(
              TrieKeyDerivation.getTreeKeyForBasicData(address),
              TrieKeyDerivation.getTreeKeyForCodeHash(address),
              TrieKeyDerivation.getTreeKeyForStorageSlot(address, UInt256.valueOf(256)),
              TrieKeyDerivation.getTreeKeyForStorageSlot(address, UInt256.valueOf(300)))) {
        trie.put(key, Bytes32.random(random));
        keys.add(key);
      }
      // Absent keys inside those stems.
      keys.add(TrieKeyDerivation.getTreeKeyForStorageSlot(address, UInt256.valueOf(5)));
      keys.add(TrieKeyDerivation.getTreeKeyForStorageSlot(address, UInt256.valueOf(400)));
    }
    final Map<Bytes, Proof<Bytes>> proofs = new HashMap<>();
    keys.forEach(key -> proofs.put(key, trie.getValueWithProof(key)));
    trie.commit(nodeUpdater);

    final StoredPartitionedBinaryTrie reloaded = new StoredPartitionedBinaryTrie(nodeLoader);
    for (final Bytes key : keys) {
      final Proof<Bytes> proof = reloaded.getValueWithProof(key);
      assertThat(proof.getProofRelatedNodes())
          .as("key %s", key)
          .isEqualTo(proofs.get(key).getProofRelatedNodes());
      assertThat(verify(reloaded.getRootHash(), key, proof.getProofRelatedNodes()))
          .isEqualTo(proof.getValue());
    }
  }

  @Test
  void proofNotMatchingTheRootIsRejected() {
    final StoredPartitionedBinaryTrie trie = threeKeyTrie();
    final Bytes32 root = trie.getRootHash();
    final List<Bytes> nodes = trie.getValueWithProof(KEY_2).getProofRelatedNodes();

    // A changed value no longer hashes to what its parent holds.
    final List<Bytes> tampered = new ArrayList<>(nodes);
    final Bytes leaf = tampered.getLast();
    tampered.set(
        tampered.size() - 1, Bytes.concatenate(leaf.slice(0, leaf.size() - 1), Bytes.of(7)));
    assertThatThrownBy(() -> verify(root, KEY_2, tampered))
        .isInstanceOf(IllegalArgumentException.class);
    // A node of the path missing.
    assertThatThrownBy(() -> verify(root, KEY_2, nodes.subList(0, nodes.size() - 1)))
        .isInstanceOf(IllegalArgumentException.class);
    // Another trie.
    assertThatThrownBy(() -> verify(Bytes32.repeat((byte) 1), KEY_2, nodes))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private StoredPartitionedBinaryTrie threeKeyTrie() {
    final StoredPartitionedBinaryTrie trie = new StoredPartitionedBinaryTrie(nodeLoader);
    for (final Bytes key : List.of(KEY_1, KEY_2, KEY_3)) {
      trie.put(key, value(key));
    }
    return trie;
  }

  private static Bytes32 value(final Bytes key) {
    return Bytes32.repeat(key.get(1));
  }

  private static List<Byte> tags(final Proof<Bytes> proof) {
    return proof.getProofRelatedNodes().stream().map(node -> node.get(0)).toList();
  }

  private static int prefixLength(final Bytes branch) {
    return TrieNodeCodec.branchPrefixLength(branch.toArrayUnsafe());
  }
}
