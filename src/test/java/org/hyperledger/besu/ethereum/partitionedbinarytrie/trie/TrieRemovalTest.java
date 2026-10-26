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

import org.hyperledger.besu.ethereum.partitionedbinarytrie.codec.BasicDataEncoder;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieConstants;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieKeyDerivation;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.NodeLoaderMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.NodeUpdaterMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.PartitionedBinaryTrieFactory;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.reference.BinaryTrie;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;

/**
 * Removals, in memory and through storage: absent keys occupy no node, and a zero value is a leaf.
 * Root hashes are compared against {@link BinaryTrie}.
 */
class TrieRemovalTest {

  private static final Bytes32 ADDRESS =
      Bytes32.fromHexString("0x000000000000000000000000abcdefabcdefabcdefabcdefabcdefabcdefabcd");

  private NodeUpdaterMock nodeUpdater;
  private PartitionedBinaryTrieFactory factory;

  @BeforeEach
  void setUp() {
    nodeUpdater = new NodeUpdaterMock();
    factory = new PartitionedBinaryTrieFactory(new NodeLoaderMock(nodeUpdater));
  }

  /** In-memory {@link PartitionedBinaryTrie}. */
  @Nested
  class InMemory {

    @Test
    void rawTrieCanStoreZeroValue() {
      final Bytes key =
          Bytes.fromHexString(
              "0x070707070707070707070707070707070707070707070707070707070707070700");
      final PartitionedBinaryTrie trie = new PartitionedBinaryTrie();
      final BinaryTrie spec = new BinaryTrie();

      trie.put(key.toArray(), key.size(), Bytes32.ZERO.toArray());
      spec.put(key, Bytes32.ZERO);
      assertThat(trie.get(key.toArray(), key.size())).contains(Bytes32.ZERO.toArray());
      assertThat(trie.getRootHash()).isEqualTo(spec.root());
      assertThat(trie.getRootHash()).isNotEqualTo(TrieConstants.EMPTY_TRIE_ROOT);

      trie.remove(key.toArray(), key.size());
      spec.remove(key);
      assertThat(trie.get(key.toArray(), key.size())).isEmpty();
      assertThat(trie.getRootHash()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);
      assertThat(trie.getRootHash()).isEqualTo(spec.root());
    }

    @Test
    void removeDeletesExistingLeaf() {
      final Bytes key =
          Bytes.fromHexString(
              "0x070707070707070707070707070707070707070707070707070707070707070700");
      final Bytes32 value = Bytes32.repeat((byte) 0x42);
      final PartitionedBinaryTrie trie = new PartitionedBinaryTrie();
      final BinaryTrie spec = new BinaryTrie();

      trie.put(key, value);
      spec.put(key, value);
      assertThat(trie.get(key.toArray(), key.size())).contains(value.toArray());
      assertThat(trie.readState(key)).isEqualTo(value);
      assertThat(trie.getRootHash()).isEqualTo(spec.root());

      trie.remove(key);
      spec.remove(key);
      assertThat(trie.get(key.toArray(), key.size())).isEmpty();
      assertThat(trie.readState(key)).isEqualTo(Bytes32.ZERO);
      assertThat(trie.getRootHash()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);
      assertThat(trie.getRootHash()).isEqualTo(spec.root());
    }

    @Test
    void removeAbsentKeyIsNoOp() {
      final Bytes present = Bytes.fromHexString("0xabcd");
      final Bytes absent = Bytes.fromHexString("0xabce");
      final PartitionedBinaryTrie trie = new PartitionedBinaryTrie();
      trie.put(present, Bytes32.repeat((byte) 1));
      final Bytes32 rootBefore = trie.getRootHash();

      trie.remove(absent);
      assertThat(trie.get(absent)).isEmpty();
      assertThat(trie.readState(absent)).isEqualTo(Bytes32.ZERO);
      assertThat(trie.readState(absent.toArray(), absent.size())).isEqualTo(new byte[32]);
      assertThat(trie.getRootHash()).isEqualTo(rootBefore);
    }

    @RepeatedTest(10)
    void randomPutRemoveSequencesMatchSpecOracle(final RepetitionInfo repetition) {
      // Short keys over a few byte values share prefixes, so branches split and merge.
      final Random rng = new Random(repetition.getCurrentRepetition());
      final byte[] alphabet = {0x00, 0x01, (byte) 0x80, (byte) 0xFF};
      final PartitionedBinaryTrie trie = new PartitionedBinaryTrie();
      final BinaryTrie spec = new BinaryTrie();
      final Map<Bytes, Bytes32> live = new HashMap<>();

      for (int step = 0; step < 60; step++) {
        if (!live.isEmpty() && rng.nextInt(3) == 0) {
          final List<Bytes> keys = new ArrayList<>(live.keySet());
          keys.sort(Comparator.naturalOrder());
          final Bytes key = keys.get(rng.nextInt(keys.size()));
          trie.remove(key);
          spec.remove(key);
          live.remove(key);
        } else {
          final Bytes key =
              Bytes.of(
                  alphabet[rng.nextInt(alphabet.length)],
                  alphabet[rng.nextInt(alphabet.length)],
                  (byte) rng.nextInt(256));
          final Bytes32 value = Bytes32.random(rng);
          trie.put(key, value);
          spec.put(key, value);
          live.put(key, value);
        }
        assertThat(trie.getRootHash()).as("step %d", step).isEqualTo(spec.root());
        for (final Map.Entry<Bytes, Bytes32> e : live.entrySet()) {
          assertThat(trie.get(e.getKey()))
              .as("step %d key %s", step, e.getKey())
              .contains(e.getValue());
        }
      }
    }
  }

  /** Stored trie, reloaded from its root location after each commit. */
  @Nested
  class Stored {

    @Test
    void sequentialRemoveChainReloadsCurrentState() {
      final StoredPartitionedBinaryTrie trie = factory.create();
      final BinaryTrie spec = new BinaryTrie();

      final Bytes[] keys = {
        Bytes.fromHexString("0x01"), Bytes.fromHexString("0x02"), Bytes.fromHexString("0x03")
      };
      for (int i = 0; i < keys.length; i++) {
        final Bytes32 value = Bytes32.repeat((byte) (0x10 + i));
        spec.put(keys[i], value);
        trie.put(keys[i].toArray(), keys[i].size(), value.toArray());
        trie.commit(nodeUpdater);
        assertThat(trie.getRootHash()).isEqualTo(spec.root());
      }

      trie.remove(keys[2].toArray(), keys[2].size());
      spec.remove(keys[2]);
      trie.commit(nodeUpdater);
      assertThat(trie.getRootHash()).isEqualTo(spec.root());

      trie.remove(keys[1].toArray(), keys[1].size());
      spec.remove(keys[1]);
      trie.commit(nodeUpdater);
      assertThat(trie.getRootHash()).isEqualTo(spec.root());

      final StoredPartitionedBinaryTrie reloaded = factory.create();
      assertThat(reloaded.get(keys[0].toArray(), keys[0].size()))
          .contains(Bytes32.repeat((byte) 0x10).toArray());
      assertThat(reloaded.get(keys[1].toArray(), keys[1].size())).isEmpty();
      assertThat(reloaded.get(keys[2].toArray(), keys[2].size())).isEmpty();
      assertThat(reloaded.getRootHash()).isEqualTo(spec.root());
    }

    @Test
    void removeAbsentKeyIsNoOp() {
      final Bytes present = Bytes.fromHexString("0xabcd");
      final Bytes absent = Bytes.fromHexString("0xabce");
      final StoredPartitionedBinaryTrie trie = factory.create();
      trie.put(present, Bytes32.repeat((byte) 1));
      trie.commit(nodeUpdater);
      final Bytes32 rootBefore = trie.getRootHash();

      trie.remove(absent);
      trie.commit(nodeUpdater);
      final StoredPartitionedBinaryTrie reloaded = factory.create();
      assertThat(reloaded.getRootHash()).isEqualTo(rootBefore);
      assertThat(reloaded.get(present)).contains(Bytes32.repeat((byte) 1));
      assertThat(reloaded.get(absent)).isEmpty();
    }

    @Test
    void removeOneOfTwoLeavesRestoresPreviousRoot() {
      final Bytes keyA = Bytes.fromHexString("0xaaaa");
      final Bytes keyB = Bytes.fromHexString("0xbbbb");
      final Bytes32 valueA = Bytes32.repeat((byte) 0x01);
      final Bytes32 valueB = Bytes32.repeat((byte) 0x02);

      final StoredPartitionedBinaryTrie trie = factory.create();
      final BinaryTrie spec = new BinaryTrie();

      trie.put(keyA.toArray(), keyA.size(), valueA.toArray());
      spec.put(keyA, valueA);
      trie.commit(nodeUpdater);
      final Bytes32 rootAfterA = trie.getRootHash();

      trie.put(keyB.toArray(), keyB.size(), valueB.toArray());
      spec.put(keyB, valueB);
      trie.commit(nodeUpdater);
      assertThat(trie.getRootHash()).isNotEqualTo(rootAfterA);

      trie.remove(keyB.toArray(), keyB.size());
      spec.remove(keyB);
      trie.commit(nodeUpdater);
      assertThat(trie.getRootHash()).isEqualTo(rootAfterA);
      assertThat(trie.getRootHash()).isEqualTo(spec.root());

      final StoredPartitionedBinaryTrie reloaded = factory.create();
      assertThat(reloaded.get(keyA.toArray(), keyA.size())).contains(valueA.toArray());
      assertThat(reloaded.get(keyB.toArray(), keyB.size())).isEmpty();
      assertThat(reloaded.getRootHash()).isEqualTo(rootAfterA);
    }

    @Test
    void removeAndReputSameKey() {
      final Bytes key = Bytes.fromHexString("0xdeadbeef");
      final Bytes32 value1 = Bytes32.repeat((byte) 0x11);
      final Bytes32 value2 = Bytes32.repeat((byte) 0x22);

      final StoredPartitionedBinaryTrie trie = factory.create();
      final BinaryTrie spec = new BinaryTrie();

      trie.put(key.toArray(), key.size(), value1.toArray());
      spec.put(key, value1);
      trie.commit(nodeUpdater);
      final Bytes32 root1 = trie.getRootHash();

      trie.remove(key.toArray(), key.size());
      spec.remove(key);
      trie.commit(nodeUpdater);
      assertThat(trie.getRootHash()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);

      trie.put(key.toArray(), key.size(), value2.toArray());
      spec.put(key, value2);
      trie.commit(nodeUpdater);
      final Bytes32 root2 = trie.getRootHash();
      assertThat(root2).isNotEqualTo(root1);
      assertThat(trie.getRootHash()).isEqualTo(spec.root());
      assertThat(trie.get(key.toArray(), key.size())).contains(value2.toArray());

      final StoredPartitionedBinaryTrie reloaded = factory.create();
      assertThat(reloaded.get(key.toArray(), key.size())).contains(value2.toArray());
      assertThat(reloaded.getRootHash()).isEqualTo(root2);
    }

    @Test
    void putDeferredRemoveViaEmptyMerger() {
      final Bytes key = Bytes.fromHexString("0xbeef");
      final Bytes32 value = Bytes32.repeat((byte) 0x77);

      final StoredPartitionedBinaryTrie trie = factory.create();
      trie.put(key.toArray(), key.size(), value.toArray());
      trie.commit(nodeUpdater);

      trie.putDeferred(key.toArray(), key.size(), existing -> Optional.empty());
      trie.commit(nodeUpdater);

      assertThat(trie.getRootHash()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);
      assertThat(trie.get(key.toArray(), key.size())).isEmpty();

      final StoredPartitionedBinaryTrie reloaded = factory.create();
      assertThat(reloaded.get(key.toArray(), key.size())).isEmpty();
      assertThat(reloaded.getRootHash()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);
    }
  }

  /** Stored trie with EIP-8297 account keys. */
  @Nested
  class EmbeddingKeys {

    @Test
    void accountRemoveOneLeafKeepsOther() {
      final Bytes basicKey = TrieKeyDerivation.getTreeKeyForBasicData(ADDRESS);
      final Bytes codeHashKey = TrieKeyDerivation.getTreeKeyForCodeHash(ADDRESS);
      final Bytes32 basicData = BasicDataEncoder.encodeBasicData(0, 0, UInt256.ONE);
      final Bytes32 codeHash = TrieKeyDerivation.EMPTY_CODE_HASH;

      final StoredPartitionedBinaryTrie trie = factory.create();
      trie.put(basicKey.toArray(), basicKey.size(), basicData.toArray());
      trie.put(codeHashKey.toArray(), codeHashKey.size(), codeHash.toArray());
      trie.commit(nodeUpdater);

      trie.remove(codeHashKey.toArray(), codeHashKey.size());
      trie.commit(nodeUpdater);
      assertThat(trie.get(codeHashKey.toArray(), codeHashKey.size())).isEmpty();
      assertThat(trie.get(basicKey.toArray(), basicKey.size())).contains(basicData.toArray());

      final StoredPartitionedBinaryTrie reloaded = factory.create();
      assertThat(reloaded.get(codeHashKey.toArray(), codeHashKey.size())).isEmpty();
      assertThat(reloaded.get(basicKey.toArray(), basicKey.size())).contains(basicData.toArray());
      final BinaryTrie spec = new BinaryTrie();
      spec.put(basicKey, basicData);
      assertThat(reloaded.getRootHash()).isEqualTo(spec.root());
    }
  }
}
