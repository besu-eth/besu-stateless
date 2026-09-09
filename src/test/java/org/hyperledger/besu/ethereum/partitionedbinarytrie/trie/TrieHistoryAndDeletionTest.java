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

import org.hyperledger.besu.ethereum.partitionedbinarytrie.codec.BasicDataEncoder;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieConstants;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieKeyDerivation;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.NodeLoaderMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.NodeUpdaterMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.PartitionedBinaryTrieFactory;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.reference.BinaryTrie;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.reference.MutableBinaryTrie;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * Rollback and removal semantics for the partitioned binary trie.
 *
 * <p>Layer: trie storage and in-memory {@link PartitionedBinaryTrie}. The raw trie is non-sparse:
 * absent keys occupy no nodes. Callers map EIP-8297 zero values to {@code remove}. Stored-mode
 * tests reload the current root from location-keyed storage; in-memory rollback semantics are
 * covered separately. Root hashes are compared against {@link BinaryTrie}.
 */
class TrieHistoryAndDeletionTest {

  private static final Bytes32 ADDRESS =
      Bytes32.fromHexString("0x000000000000000000000000abcdefabcdefabcdefabcdefabcdefabcdefabcd");

  private NodeUpdaterMock nodeUpdater;
  private PartitionedBinaryTrieFactory factory;

  @BeforeEach
  void setUp() {
    nodeUpdater = new NodeUpdaterMock();
    factory = new PartitionedBinaryTrieFactory(new NodeLoaderMock(nodeUpdater));
  }

  /** Raw trie absence and removal semantics. */
  @Nested
  class RemoveSemantics {

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
    void stateReadAbsentKeyReturnsZero() {
      final Bytes key = Bytes.fromHexString("0xabcd");
      final PartitionedBinaryTrie trie = new PartitionedBinaryTrie();

      assertThat(trie.readState(key)).isEqualTo(Bytes32.ZERO);
      assertThat(trie.readState(key.toArray(), key.size())).isEqualTo(Bytes32.ZERO.toArray());
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
      final Bytes key = Bytes.fromHexString("0xabcd");
      final PartitionedBinaryTrie trie = new PartitionedBinaryTrie();
      final Bytes32 rootBefore = trie.getRootHash();

      trie.remove(key);
      assertThat(trie.get(key.toArray(), key.size())).isEmpty();
      assertThat(trie.readState(key)).isEqualTo(Bytes32.ZERO);
      assertThat(trie.getRootHash()).isEqualTo(rootBefore);
      assertThat(trie.getRootHash()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);
    }

    @Test
    void binaryAndMutableAgreeOnTwoKeyRemove() {
      final Bytes keyA = Bytes.fromHexString("0xaaaa");
      final Bytes keyB = Bytes.fromHexString("0xbbbb");
      final Bytes32 valueA = Bytes32.repeat((byte) 0x01);
      final Bytes32 valueB = Bytes32.repeat((byte) 0x02);

      final BinaryTrie binary = new BinaryTrie();
      final MutableBinaryTrie mutable = new MutableBinaryTrie();
      final PartitionedBinaryTrie stored = new PartitionedBinaryTrie();

      binary.put(keyA, valueA);
      mutable.put(keyA, valueA);
      stored.put(keyA.toArray(), keyA.size(), valueA.toArray());
      final Bytes32 rootA = binary.root();
      assertThat(mutable.root()).isEqualTo(rootA);
      assertThat(stored.getRootHash()).isEqualTo(rootA);

      binary.put(keyB, valueB);
      mutable.put(keyB, valueB);
      stored.put(keyB.toArray(), keyB.size(), valueB.toArray());
      assertThat(stored.get(keyB.toArray(), keyB.size())).as("stored get B after put").isPresent();
      assertThat(stored.getRootHash()).as("stored root after put B").isNotEqualTo(rootA);
      assertThat(mutable.root()).isEqualTo(binary.root());
      assertThat(stored.getRootHash()).isEqualTo(binary.root());

      binary.remove(keyB);
      mutable.remove(keyB);
      stored.remove(keyB.toArray(), keyB.size());

      assertThat(mutable.root()).isEqualTo(binary.root());
      assertThat(stored.getRootHash()).as("stored after remove").isEqualTo(binary.root());
      assertThat(stored.get(keyB.toArray(), keyB.size())).isEmpty();
    }
  }

  /**
   * In-memory {@link PartitionedBinaryTrie} rollback via remove; prior root is restored.
   *
   * <p>Oracle: {@link BinaryTrie} and {@link MutableBinaryTrie}.
   */
  @Nested
  class InMemoryTrieRollback {

    @Test
    void singleLeafPutRemoveRestoresEmptyTrie() {
      final Bytes key = Bytes.fromHexString("0x01020304");
      final Bytes32 value = Bytes32.repeat((byte) 0x42);

      final PartitionedBinaryTrie trie = new PartitionedBinaryTrie();
      final MutableBinaryTrie spec = new MutableBinaryTrie();

      trie.put(key.toArray(), key.size(), value.toArray());
      spec.put(key, value);
      assertThat(trie.getRootHash()).isEqualTo(spec.root());

      trie.remove(key.toArray(), key.size());
      spec.remove(key);
      assertThat(trie.get(key.toArray(), key.size())).isEmpty();
      assertThat(spec.get(key)).isEmpty();
      assertThat(trie.getRootHash()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);
      assertThat(trie.getRootHash()).isEqualTo(spec.root());
    }

    @Test
    void removeOneOfTwoLeavesRestoresPreviousRoot() {
      final Bytes keyA = Bytes.fromHexString("0xaaaa");
      final Bytes keyB = Bytes.fromHexString("0xbbbb");
      final Bytes32 valueA = Bytes32.repeat((byte) 0x01);
      final Bytes32 valueB = Bytes32.repeat((byte) 0x02);

      final PartitionedBinaryTrie trie = new PartitionedBinaryTrie();
      final BinaryTrie spec = new BinaryTrie();

      trie.put(keyA.toArray(), keyA.size(), valueA.toArray());
      spec.put(keyA, valueA);
      final Bytes32 rootAfterA = trie.getRootHash();

      trie.put(keyB.toArray(), keyB.size(), valueB.toArray());
      spec.put(keyB, valueB);
      assertThat(trie.getRootHash()).isNotEqualTo(rootAfterA);

      trie.remove(keyB.toArray(), keyB.size());
      spec.remove(keyB);
      assertThat(trie.getRootHash()).isEqualTo(rootAfterA);
      assertThat(trie.getRootHash()).isEqualTo(spec.root());
      assertThat(trie.get(keyA.toArray(), keyA.size())).contains(valueA.toArray());
      assertThat(trie.get(keyB.toArray(), keyB.size())).isEmpty();
    }

    @Test
    void removeAndReputSameKey() {
      final Bytes key = Bytes.fromHexString("0xdeadbeef");
      final Bytes32 value1 = Bytes32.repeat((byte) 0x11);
      final Bytes32 value2 = Bytes32.repeat((byte) 0x22);

      final PartitionedBinaryTrie trie = new PartitionedBinaryTrie();
      final MutableBinaryTrie spec = new MutableBinaryTrie();

      trie.put(key.toArray(), key.size(), value1.toArray());
      spec.put(key, value1);
      final Bytes32 root1 = trie.getRootHash();

      trie.remove(key.toArray(), key.size());
      spec.remove(key);
      assertThat(trie.getRootHash()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);

      trie.put(key.toArray(), key.size(), value2.toArray());
      spec.put(key, value2);
      assertThat(trie.getRootHash()).isNotEqualTo(root1);
      assertThat(trie.getRootHash()).isEqualTo(spec.root());
      assertThat(trie.get(key.toArray(), key.size())).contains(value2.toArray());
    }

    @RepeatedTest(10)
    void randomPutRemoveSequencesMatchSpecOracle() {
      final Random rng = new Random(8297);
      final PartitionedBinaryTrie trie = new PartitionedBinaryTrie();
      final BinaryTrie spec = new BinaryTrie();
      final Map<Bytes, Bytes32> live = new HashMap<>();

      for (int step = 0; step < 40; step++) {
        final Bytes key = randomKey(rng);
        if (rng.nextBoolean() && live.containsKey(key)) {
          trie.remove(key.toArray(), key.size());
          spec.remove(key);
          live.remove(key);
        } else {
          final Bytes32 value = Bytes32.wrap(randomBytes(rng, 32));
          trie.put(key.toArray(), key.size(), value.toArray());
          spec.put(key, value);
          live.put(key, value);
        }
        assertThat(trie.getRootHash()).as("step %d", step).isEqualTo(spec.root());
        for (final Map.Entry<Bytes, Bytes32> e : live.entrySet()) {
          assertThat(trie.get(e.getKey().toArray(), e.getKey().size()))
              .as("step %d key %s", step, e.getKey())
              .contains(e.getValue().toArray());
        }
      }
    }
  }

  /**
   * Stored trie commit, remove, and reload of the current root from location-keyed storage.
   *
   * <p>Oracle: {@link BinaryTrie} for root hash; {@code factory.create()} for reload.
   */
  @Nested
  class StoredTrieCommitReload {

    @Test
    void commitRemoveCommitRestoresPriorRoot() {
      final Bytes key = Bytes.fromHexString("0x1122334455667788");
      final Bytes32 value = Bytes32.repeat((byte) 0x55);

      final StoredPartitionedBinaryTrie trie = factory.create();
      trie.put(key.toArray(), key.size(), value.toArray());
      trie.commit(nodeUpdater);

      trie.remove(key.toArray(), key.size());
      trie.commit(nodeUpdater);
      assertThat(trie.getRootHash()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);
      assertThat(trie.get(key.toArray(), key.size())).isEmpty();

      final StoredPartitionedBinaryTrie reloaded = factory.create();
      assertThat(reloaded.get(key.toArray(), key.size())).isEmpty();
      assertThat(reloaded.getRootHash()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);
    }

    @Test
    void removeThenAddRestoresEquivalentRoot() {
      final Bytes key1 = Bytes.fromHexString("0x1111");
      final Bytes key2 = Bytes.fromHexString("0x2222");
      final Bytes32 value1 = Bytes32.repeat((byte) 0xAA);
      final Bytes32 value2 = Bytes32.repeat((byte) 0xBB);

      final StoredPartitionedBinaryTrie trie = factory.create();

      trie.put(key1.toArray(), key1.size(), value1.toArray());
      trie.commit(nodeUpdater);
      final Bytes32 root1 = trie.getRootHash();

      trie.put(key2.toArray(), key2.size(), value2.toArray());
      trie.commit(nodeUpdater);
      assertThat(trie.getRootHash()).isNotEqualTo(root1);

      trie.remove(key2.toArray(), key2.size());
      trie.commit(nodeUpdater);
      assertThat(trie.getRootHash()).isEqualTo(root1);

      final StoredPartitionedBinaryTrie reloaded = factory.create();
      assertThat(reloaded.get(key1.toArray(), key1.size())).contains(value1.toArray());
      assertThat(reloaded.get(key2.toArray(), key2.size())).isEmpty();
      assertThat(reloaded.getRootHash()).isEqualTo(root1);
    }

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
      final Bytes key = Bytes.fromHexString("0xabcd");
      final StoredPartitionedBinaryTrie trie = factory.create();
      trie.commit(nodeUpdater);
      final Bytes32 rootBefore = trie.getRootHash();

      trie.remove(key.toArray(), key.size());
      trie.commit(nodeUpdater);
      assertThat(trie.getRootHash()).isEqualTo(rootBefore);
      assertThat(trie.getRootHash()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);
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

  /**
   * Remove and reload with EIP-8297 embedding keys (basic data, code hash) in stored mode.
   *
   * <p>Oracle: {@link BinaryTrie} root hash and current-root reload via {@code factory.create()}.
   */
  @Nested
  class EmbeddingKeyCommitReload {

    @Test
    void accountBasicDataRemoveReloadsEmptyTrie() {
      final Bytes basicKey = TrieKeyDerivation.getTreeKeyForBasicData(ADDRESS);
      final Bytes32 basicData = BasicDataEncoder.encodeBasicData(1, 2, UInt256.valueOf(100));

      final StoredPartitionedBinaryTrie trie = factory.create();
      final BinaryTrie spec = new BinaryTrie();

      trie.put(basicKey.toArray(), basicKey.size(), basicData.toArray());
      spec.put(basicKey, basicData);
      trie.commit(nodeUpdater);
      assertThat(trie.getRootHash()).isEqualTo(spec.root());

      trie.remove(basicKey.toArray(), basicKey.size());
      spec.remove(basicKey);
      trie.commit(nodeUpdater);
      assertThat(trie.getRootHash()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);
      assertThat(trie.getRootHash()).isEqualTo(spec.root());

      final StoredPartitionedBinaryTrie reloaded = factory.create();
      assertThat(reloaded.get(basicKey.toArray(), basicKey.size())).isEmpty();
      assertThat(reloaded.getRootHash()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);
    }

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
    }
  }

  private static Bytes randomKey(final Random rng) {
    final int len = rng.nextInt(TrieConstants.MAX_KEY_LENGTH) + 1;
    return Bytes.wrap(randomBytes(rng, len));
  }

  private static byte[] randomBytes(final Random rng, final int len) {
    final byte[] out = new byte[len];
    rng.nextBytes(out);
    return out;
  }
}
