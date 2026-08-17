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

import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.NodeLoaderMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.NodeUpdaterMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.PartitionedBinaryTrieFactory;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Branch flatten after remove survives commit and reload from location-keyed storage (Besu Bonsai
 * semantics).
 */
class TrieFlattenLocationOnlyTest {

  private NodeUpdaterMock nodeUpdater;
  private PartitionedBinaryTrieFactory factory;

  @BeforeEach
  void setUp() {
    nodeUpdater = new NodeUpdaterMock();
    factory = new PartitionedBinaryTrieFactory(new NodeLoaderMock(nodeUpdater));
  }

  @Test
  void flattenAfterCommitSurvivesLocationOnlyReload() {
    // Three keys that share a long common prefix so that removing keyB forces a multi-level
    // branch collapse (flatten) deep in the trie, hoisting keyC's subtree upward.
    final Bytes keyA = Bytes.fromHexString("0x0000000000000000000000000000000000000001");
    final Bytes keyB = Bytes.fromHexString("0x0000000000000000000000000000000000000002");
    final Bytes keyC = Bytes.fromHexString("0x00000000000000000000000000000000000000ff");
    final Bytes32 valueA = Bytes32.repeat((byte) 0x0A);
    final Bytes32 valueB = Bytes32.repeat((byte) 0x0B);
    final Bytes32 valueC = Bytes32.repeat((byte) 0x0C);

    final StoredPartitionedBinaryTrie trie = factory.create();
    trie.put(keyA.toArray(), keyA.size(), valueA.toArray());
    trie.put(keyB.toArray(), keyB.size(), valueB.toArray());
    trie.put(keyC.toArray(), keyC.size(), valueC.toArray());
    trie.commit(nodeUpdater);
    final Bytes32 rootWithAll = trie.getRootHash();

    // Remove keyB: keyA/keyC's shared branch should collapse (flatten), hoisting whatever
    // survives up by at least one level.
    trie.remove(keyB.toArray(), keyB.size());
    trie.commit(nodeUpdater);
    final Bytes32 rootAfterRemove = trie.getRootHash();

    // Fresh trie instance, forcing every node to be re-decoded from location-only storage.
    final StoredPartitionedBinaryTrie reloaded = factory.create(rootAfterRemove);

    assertThat(reloaded.get(keyA.toArray(), keyA.size()))
        .as("keyA must survive the flatten + location-only reload")
        .contains(valueA.toArray());
    assertThat(reloaded.get(keyC.toArray(), keyC.size()))
        .as("keyC must survive the flatten + location-only reload")
        .contains(valueC.toArray());
    assertThat(reloaded.get(keyB.toArray(), keyB.size())).isEmpty();
    assertThat(reloaded.getRootHash()).isEqualTo(rootAfterRemove);
    assertThat(rootAfterRemove).isNotEqualTo(rootWithAll);
  }

  @Test
  void flattenWithBranchSurvivorSurvivesLocationOnlyReload() {
    // keyA diverges at bit 0, so it lives untouched on the other side of the root and must not
    // need relocation. keyD/keyE/keyF share their first 20 bytes (bit 0 = 0); keyD alone forms
    // the left side of an inner branch, keyE/keyF form its right side as a two-leaf BranchNode.
    // Removing keyD leaves that inner branch's *branch* child (keyE/keyF) as the sole survivor,
    // exercising BranchNode.maybeFlatten's "loaded instanceof BranchNode" merge path rather than
    // the leaf-survivor path covered above.
    final Bytes keyA = keyWithFirstAndLastByte(21, 0xFF, 0x00);
    final Bytes keyD = keyWithFirstAndLastByte(21, 0x00, 0x01); // 0000_0001
    final Bytes keyE = keyWithFirstAndLastByte(21, 0x00, 0x84); // 1000_0100
    final Bytes keyF = keyWithFirstAndLastByte(21, 0x00, 0xC4); // 1100_0100
    final Bytes32 valueA = Bytes32.repeat((byte) 0x0A);
    final Bytes32 valueD = Bytes32.repeat((byte) 0x0D);
    final Bytes32 valueE = Bytes32.repeat((byte) 0x0E);
    final Bytes32 valueF = Bytes32.repeat((byte) 0x0F);

    final StoredPartitionedBinaryTrie trie = factory.create();
    trie.put(keyA.toArray(), keyA.size(), valueA.toArray());
    trie.put(keyD.toArray(), keyD.size(), valueD.toArray());
    trie.put(keyE.toArray(), keyE.size(), valueE.toArray());
    trie.put(keyF.toArray(), keyF.size(), valueF.toArray());
    trie.commit(nodeUpdater);
    final Bytes32 rootWithAll = trie.getRootHash();

    // Remove keyD: the branch holding {D, E, F} loses its leaf side, so its survivor is the
    // {E, F} BranchNode itself (not a leaf) — the merge path in maybeFlatten.
    trie.remove(keyD.toArray(), keyD.size());
    trie.commit(nodeUpdater);
    final Bytes32 rootAfterRemove = trie.getRootHash();

    // Fresh trie instance, forcing every node to be re-decoded from location-only storage.
    final StoredPartitionedBinaryTrie reloaded = factory.create(rootAfterRemove);

    assertThat(reloaded.get(keyA.toArray(), keyA.size()))
        .as("untouched sibling keyA must still resolve at its original location")
        .contains(valueA.toArray());
    assertThat(reloaded.get(keyE.toArray(), keyE.size()))
        .as("keyE (branch survivor) must survive the flatten + location-only reload")
        .contains(valueE.toArray());
    assertThat(reloaded.get(keyF.toArray(), keyF.size()))
        .as("keyF (branch survivor) must survive the flatten + location-only reload")
        .contains(valueF.toArray());
    assertThat(reloaded.get(keyD.toArray(), keyD.size())).isEmpty();
    assertThat(reloaded.getRootHash()).isEqualTo(rootAfterRemove);
    assertThat(rootAfterRemove).isNotEqualTo(rootWithAll);
  }

  private static Bytes keyWithFirstAndLastByte(
      final int length, final int firstByte, final int lastByte) {
    final byte[] key = new byte[length];
    key[0] = (byte) firstByte;
    key[length - 1] = (byte) lastByte;
    return Bytes.wrap(key);
  }
}
