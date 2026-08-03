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

import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieConstants;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.NodeLoaderMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.NodeUpdaterMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.reference.BinaryTrie;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tuweni {@link Bytes} API for {@link StoredPartitionedBinaryTrie} with mock persistence.
 *
 * <p>Layer: trie. Exercises put, get, commit, reload, putDeferred, remove, and parallel put
 * batching. Root hashes are compared against {@link BinaryTrie}.
 */
class TrieApiTest {

  private NodeUpdaterMock nodeUpdater;
  private NodeLoaderMock nodeLoader;

  @BeforeEach
  void setUp() {
    nodeUpdater = new NodeUpdaterMock();
    nodeLoader = new NodeLoaderMock(nodeUpdater);
  }

  @Test
  void putCommitReloadAtHistoricalRoot() {
    final Bytes key = Bytes.fromHexString("0x1122");
    final Bytes32 value = Bytes32.repeat((byte) 0xAB);
    final BinaryTrie spec = new BinaryTrie();

    final StoredPartitionedBinaryTrie trie = new StoredPartitionedBinaryTrie(nodeLoader);
    trie.put(key, value);
    spec.put(key, value);
    trie.commit(nodeUpdater);
    final Bytes32 root = trie.getRootHash();
    assertThat(root).isEqualTo(spec.root());

    final StoredPartitionedBinaryTrie reloaded = new StoredPartitionedBinaryTrie(nodeLoader, root);
    assertThat(reloaded.get(key)).contains(value);
    assertThat(reloaded.getRootHash()).isEqualTo(root);
  }

  @Test
  void putDeferredRemoveViaEmptyMerger() {
    final Bytes key = Bytes.fromHexString("0xbeef");
    final Bytes32 value = Bytes32.repeat((byte) 0x77);

    final StoredPartitionedBinaryTrie trie = new StoredPartitionedBinaryTrie(nodeLoader);
    trie.put(key, value);
    trie.commit(nodeUpdater);
    final Bytes32 rootWithValue = trie.getRootHash();

    trie.putDeferred(key, existing -> Optional.empty());
    trie.commit(nodeUpdater);
    assertThat(trie.getRootHash()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);
    assertThat(trie.get(key)).isEmpty();
    assertThat(trie.isEmpty()).isTrue();

    final StoredPartitionedBinaryTrie historical =
        new StoredPartitionedBinaryTrie(nodeLoader, rootWithValue);
    assertThat(historical.get(key)).contains(value);
  }

  @Test
  void removeRestoresPriorCommittedRoot() {
    final Bytes keyA = Bytes.fromHexString("0xaaaa");
    final Bytes keyB = Bytes.fromHexString("0xbbbb");
    final Bytes32 valueA = Bytes32.repeat((byte) 0x01);
    final Bytes32 valueB = Bytes32.repeat((byte) 0x02);

    final StoredPartitionedBinaryTrie trie = new StoredPartitionedBinaryTrie(nodeLoader);
    trie.put(keyA, valueA);
    trie.commit(nodeUpdater);
    final Bytes32 rootA = trie.getRootHash();

    trie.put(keyB, valueB);
    trie.commit(nodeUpdater);
    trie.remove(keyB);
    trie.commit(nodeUpdater);
    assertThat(trie.getRootHash()).isEqualTo(rootA);

    final StoredPartitionedBinaryTrie atRootA = new StoredPartitionedBinaryTrie(nodeLoader, rootA);
    assertThat(atRootA.get(keyA)).contains(valueA);
    assertThat(atRootA.get(keyB)).isEmpty();
  }

  @Test
  void entriesFromReturnsOrderedRange() {
    final Bytes keyLow = Bytes.fromHexString("0x01");
    final Bytes keyMid = Bytes.fromHexString("0x02");
    final Bytes keyHigh = Bytes.fromHexString("0x03");
    final Bytes32 valueLow = Bytes32.repeat((byte) 0x11);
    final Bytes32 valueMid = Bytes32.repeat((byte) 0x22);
    final Bytes32 valueHigh = Bytes32.repeat((byte) 0x33);

    final StoredPartitionedBinaryTrie trie = new StoredPartitionedBinaryTrie(nodeLoader);
    trie.put(keyLow, valueLow);
    trie.put(keyMid, valueMid);
    trie.put(keyHigh, valueHigh);
    trie.commit(nodeUpdater);

    final Map<Bytes32, Bytes> fromMid = trie.entriesFrom(Bytes32.rightPad(keyMid), 2);
    assertThat(fromMid).hasSize(2);
    assertThat(fromMid.get(Bytes32.rightPad(keyMid))).isEqualTo(valueMid);
    assertThat(fromMid.get(Bytes32.rightPad(keyHigh))).isEqualTo(valueHigh);
  }

  @Test
  void visitLeafsCollectsAllEntriesInOrder() {
    final Bytes keyA = Bytes.fromHexString("0x10");
    final Bytes keyB = Bytes.fromHexString("0x20");
    final Bytes32 valueA = Bytes32.repeat((byte) 0xAA);
    final Bytes32 valueB = Bytes32.repeat((byte) 0xBB);

    final StoredPartitionedBinaryTrie trie = new StoredPartitionedBinaryTrie(nodeLoader);
    trie.put(keyB, valueB);
    trie.put(keyA, valueA);

    final List<Bytes> keys = new ArrayList<>();
    trie.visitLeafs(
        (key, value) -> {
          keys.add(key);
          return PartitionedBinaryTrie.LeafHandler.State.CONTINUE;
        });

    assertThat(keys).containsExactly(keyA, keyB);
  }

  @Test
  void visitLeafsSupportsEarlyStop() {
    final Bytes keyA = Bytes.fromHexString("0x10");
    final Bytes keyB = Bytes.fromHexString("0x20");
    final Bytes32 valueA = Bytes32.repeat((byte) 0xAA);
    final Bytes32 valueB = Bytes32.repeat((byte) 0xBB);

    final StoredPartitionedBinaryTrie trie = new StoredPartitionedBinaryTrie(nodeLoader);
    trie.put(keyA, valueA);
    trie.put(keyB, valueB);

    final List<Bytes> keys = new ArrayList<>();
    trie.visitLeafs(
        (key, value) -> {
          keys.add(key);
          return PartitionedBinaryTrie.LeafHandler.State.STOP;
        });

    assertThat(keys).containsExactly(keyA);
  }

  @Test
  void visitAllVisitsEveryNodeType() {
    final Bytes keyA = Bytes.fromHexString("0x10");
    final Bytes keyB = Bytes.fromHexString("0x20");
    final Bytes32 valueA = Bytes32.repeat((byte) 0xAA);
    final Bytes32 valueB = Bytes32.repeat((byte) 0xBB);

    final StoredPartitionedBinaryTrie trie = new StoredPartitionedBinaryTrie(nodeLoader);
    trie.put(keyA, valueA);
    trie.put(keyB, valueB);
    trie.commit(nodeUpdater);

    final int[] leafCount = {0};
    final int[] branchCount = {0};
    trie.visitAll(
        node -> {
          if (node.isLeaf()) {
            leafCount[0]++;
          } else if (node.isBranch()) {
            branchCount[0]++;
          }
        });

    assertThat(leafCount[0]).isEqualTo(2);
    assertThat(branchCount[0]).isGreaterThanOrEqualTo(1);
  }

  @Test
  void visitAllParallelCompletes() throws Exception {
    final Bytes key = Bytes.fromHexString("0x99");
    final Bytes32 value = Bytes32.repeat((byte) 0x55);

    final StoredPartitionedBinaryTrie trie = new StoredPartitionedBinaryTrie(nodeLoader);
    trie.put(key, value);

    final List<PartitionedBinaryTrie.TrieNodeView> visited = new ArrayList<>();
    trie.visitAll(visited::add, Executors.newSingleThreadExecutor()).get();

    assertThat(visited).isNotEmpty();
    assertThat(visited.stream().anyMatch(PartitionedBinaryTrie.TrieNodeView::isLeaf)).isTrue();
  }
}
