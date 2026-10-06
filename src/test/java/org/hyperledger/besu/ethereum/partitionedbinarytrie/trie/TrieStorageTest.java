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
import static org.assertj.core.api.Assertions.entry;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.codec.BasicDataEncoder;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.codec.TrieNodeCodec;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieConstants;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieKeyDerivation;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.NodeLoaderMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.NodeUpdaterMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.PartitionedBinaryTrieFactory;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.reference.BinaryTrie;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Stored-mode commit and reload over location-keyed mock storage (Besu Bonsai semantics).
 *
 * <p>Layer: trie ({@link StoredPartitionedBinaryTrie} via factory). Nodes are persisted at trie
 * paths; reload opens the current root at {@link Bytes#EMPTY}. Root hashes compared against {@link
 * BinaryTrie} where applicable.
 */
class TrieStorageTest {

  private static final Bytes32 ADDRESS =
      Bytes32.fromHexString("0x000000000000000000000000abcdefabcdefabcdefabcdefabcdefabcdefabcd");

  private NodeUpdaterMock nodeUpdater;
  private PartitionedBinaryTrieFactory factory;

  @BeforeEach
  void setUp() {
    nodeUpdater = new NodeUpdaterMock();
    factory = new PartitionedBinaryTrieFactory(new NodeLoaderMock(nodeUpdater));
  }

  @Test
  void emptyTrieCommitAndReload() {
    final StoredPartitionedBinaryTrie trie = factory.create();
    trie.commit(nodeUpdater);
    assertThat(trie.getRootHash()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);

    final StoredPartitionedBinaryTrie reloaded = factory.create();
    assertThat(reloaded.getRootHash()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);
    assertThat(reloaded.get(new byte[] {1}, 1)).isEmpty();
  }

  @Test
  void commitIdempotentWhenClean() {
    final Bytes key = Bytes.fromHexString("0x42");
    final Bytes32 value = Bytes32.repeat((byte) 0x99);
    final StoredPartitionedBinaryTrie trie = factory.create();
    trie.put(key.toArray(), key.size(), value.toArray());
    trie.commit(nodeUpdater);
    final int nodesAfterFirstCommit = nodeUpdater.storage.size();

    trie.commit(nodeUpdater);
    assertThat(nodeUpdater.storage.size()).isEqualTo(nodesAfterFirstCommit);
    assertThat(trie.get(key.toArray(), key.size())).contains(value.toArray());
  }

  @Test
  void sequentialCommitsReloadCurrentState() {
    final Bytes[] keys = {
      Bytes.fromHexString("0x01"), Bytes.fromHexString("0x02"), Bytes.fromHexString("0x03")
    };
    final BinaryTrie spec = new BinaryTrie();
    final StoredPartitionedBinaryTrie trie = factory.create();

    for (int i = 0; i < keys.length; i++) {
      final Bytes32 value = Bytes32.repeat((byte) (0x10 + i));
      spec.put(keys[i], value);
      trie.put(keys[i].toArray(), keys[i].size(), value.toArray());
      trie.commit(nodeUpdater);
      assertThat(trie.getRootHash()).isEqualTo(spec.root());

      final StoredPartitionedBinaryTrie reloaded = factory.create();
      for (int j = 0; j <= i; j++) {
        final Bytes32 expected = Bytes32.repeat((byte) (0x10 + j));
        assertThat(reloaded.get(keys[j].toArray(), keys[j].size()))
            .as("after commit %d key %d", i, j)
            .contains(expected.toArray());
      }
      for (int j = i + 1; j < keys.length; j++) {
        assertThat(reloaded.get(keys[j].toArray(), keys[j].size()))
            .as("after commit %d absent key %d", i, j)
            .isEmpty();
      }
      assertThat(reloaded.getRootHash()).isEqualTo(trie.getRootHash());
    }

    // One-byte keys share the empty stem: a single entry at the root.
    assertThat(nodeUpdater.storage).containsOnlyKeys(Bytes.EMPTY);
  }

  @Test
  void eachStemIsStoredAsOneEntry() {
    final Map<Bytes, Bytes32> entries = accountHeaders(50, new Random(3));
    final StoredPartitionedBinaryTrie trie = factory.create();
    entries.forEach((key, value) -> trie.put(key, value));
    trie.commit(nodeUpdater);

    // 50 stems and the 49 branches joining them.
    assertThat(entriesByTag(nodeUpdater))
        .containsOnly(entry(TrieNodeCodec.STEM_TAG, 50L), entry(TrieNodeCodec.BRANCH_TAG, 49L));

    final StoredPartitionedBinaryTrie reloaded = factory.create();
    entries.forEach((key, value) -> assertThat(reloaded.get(key)).contains(value));
    assertThat(reloaded.getRootHash()).isEqualTo(trie.getRootHash());
  }

  @Test
  void updatingOneLeafRewritesOnlyItsStemAndTheBranchesAbove() {
    final Map<Bytes, Bytes32> entries = accountHeaders(50, new Random(5));
    final StoredPartitionedBinaryTrie trie = factory.create();
    entries.forEach((key, value) -> trie.put(key, value));
    trie.commit(nodeUpdater);

    final Bytes updatedKey = entries.keySet().iterator().next();
    final Bytes32 updatedValue = Bytes32.repeat((byte) 0x77);
    final StoredPartitionedBinaryTrie reloaded = factory.create();
    reloaded.put(updatedKey, updatedValue);
    final NodeUpdaterMock writes = new NodeUpdaterMock();
    reloaded.commit(writes);

    assertThat(entriesByTag(writes))
        .containsOnlyKeys(TrieNodeCodec.STEM_TAG, TrieNodeCodec.BRANCH_TAG);
    assertThat(entriesByTag(writes)).containsEntry(TrieNodeCodec.STEM_TAG, 1L);
    // The stem is rewritten whole.
    final Map<Bytes, Bytes> stem = new HashMap<>();
    writes.storage.values().stream()
        .filter(value -> value.get(0) == TrieNodeCodec.STEM_TAG)
        .forEach(
            value ->
                TrieNodeCodec.decodeStem(
                    value.toArrayUnsafe(),
                    (key, leafValue) -> stem.put(Bytes.wrap(key), Bytes.wrap(leafValue))));
    assertThat(stem).hasSize(4).containsEntry(updatedKey, updatedValue);
  }

  @Test
  void removeCommitReloadsEmptyTrie() {
    final Bytes key = Bytes.fromHexString("0xfeed");
    final Bytes32 value = Bytes32.repeat((byte) 0x55);
    final StoredPartitionedBinaryTrie trie = factory.create();

    trie.put(key.toArray(), key.size(), value.toArray());
    trie.commit(nodeUpdater);

    trie.remove(key.toArray(), key.size());
    trie.commit(nodeUpdater);
    assertThat(trie.getRootHash()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);

    final StoredPartitionedBinaryTrie reloaded = factory.create();
    assertThat(reloaded.get(key.toArray(), key.size())).isEmpty();
    assertThat(reloaded.getRootHash()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);
  }

  @Test
  void embeddingAccountBasicDataRoundTrip() {
    final Bytes basicKey = TrieKeyDerivation.getTreeKeyForBasicData(ADDRESS);
    final Bytes32 basicData = BasicDataEncoder.encodeBasicData(1, 2, UInt256.valueOf(42));
    final BinaryTrie spec = new BinaryTrie();
    spec.put(basicKey, basicData);

    final StoredPartitionedBinaryTrie trie = factory.create();
    trie.put(basicKey.toArray(), basicKey.size(), basicData.toArray());
    trie.commit(nodeUpdater);
    assertThat(trie.getRootHash()).isEqualTo(spec.root());

    final StoredPartitionedBinaryTrie reloaded = factory.create(trie.getRootHash());
    assertThat(reloaded.get(basicKey.toArray(), basicKey.size())).contains(basicData.toArray());
  }

  @Test
  void inMemoryPartitionedBinaryTrieMatchesSpecOracle() {
    final PartitionedBinaryTrie trie = new PartitionedBinaryTrie();
    final BinaryTrie spec = new BinaryTrie();
    final Bytes key = Bytes.fromHexString("0x070707");
    final Bytes32 value = Bytes32.repeat((byte) 0x42);

    trie.put(key.toArray(), key.size(), value.toArray());
    spec.put(key, value);
    assertThat(trie.getRootHash()).isEqualTo(spec.root());

    trie.remove(key.toArray(), key.size());
    spec.remove(key);
    assertThat(trie.getRootHash()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);
    assertThat(trie.getRootHash()).isEqualTo(spec.root());
  }

  /** Basic data, code hash and two header slots of random accounts. */
  private static Map<Bytes, Bytes32> accountHeaders(final int accounts, final Random random) {
    final Map<Bytes, Bytes32> entries = new HashMap<>();
    for (int i = 0; i < accounts; i++) {
      final Bytes32 address = Bytes32.random(random);
      entries.put(TrieKeyDerivation.getTreeKeyForBasicData(address), Bytes32.random(random));
      entries.put(TrieKeyDerivation.getTreeKeyForCodeHash(address), Bytes32.random(random));
      for (int slot = 0; slot < 2; slot++) {
        entries.put(
            TrieKeyDerivation.getTreeKeyForStorageSlot(address, UInt256.valueOf(slot)),
            Bytes32.random(random));
      }
    }
    return entries;
  }

  /** Number of stored entries per node tag. */
  private static Map<Byte, Long> entriesByTag(final NodeUpdaterMock updater) {
    return updater.storage.values().stream()
        .collect(Collectors.groupingBy(value -> value.get(0), Collectors.counting()));
  }
}
