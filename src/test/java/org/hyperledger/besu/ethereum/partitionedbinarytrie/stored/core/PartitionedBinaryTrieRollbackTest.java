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

import org.hyperledger.besu.ethereum.partitionedbinarytrie.embedding.codec.AccountBasicDataEncoder;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.embedding.keys.Eip8297TreeKeyDerivation;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.internal.TrieConstants;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.factory.NodeLoaderMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.factory.NodeUpdaterMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.factory.PartitionedBinaryTrieFactory;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.reference.BinaryTrie;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.reference.MutableBinaryTrie;

import java.util.ArrayList;
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
import org.junit.jupiter.api.Test;

/**
 * Rollback and removal semantics for the partitioned binary trie.
 *
 * <p>Layer: stored core and in-memory {@link PartitionedBinaryTrie}. PBT is non-sparse: absent keys
 * occupy no nodes and {@code remove(key)} is not the same as storing a zero value. Historical roots
 * remain loadable after Besu-style rollback; root hashes are compared against {@link BinaryTrie}.
 */
class PartitionedBinaryTrieRollbackTest {

  private static final Bytes32 ADDRESS =
      Bytes32.fromHexString("0x000000000000000000000000abcdefabcdefabcdefabcdefabcdefabcdefabcd");

  private NodeUpdaterMock nodeUpdater;
  private PartitionedBinaryTrieFactory factory;

  @BeforeEach
  void setUp() {
    nodeUpdater = new NodeUpdaterMock();
    factory = new PartitionedBinaryTrieFactory(new NodeLoaderMock(nodeUpdater));
  }

  /** PBT absence semantics: zero values are not deletion; removing absent keys is a no-op. */
  @Nested
  class RemoveSemantics {

    @Test
    void zeroValueIsNotAbsence() {
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
    void removeAbsentKeyIsNoOp() {
      final Bytes key = Bytes.fromHexString("0xabcd");
      final PartitionedBinaryTrie trie = new PartitionedBinaryTrie();
      final Bytes32 rootBefore = trie.getRootHash();

      trie.remove(key.toArray(), key.size());
      assertThat(trie.getRootHash()).isEqualTo(rootBefore);
      assertThat(trie.getRootHash()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);
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
   * Besu-style rollback on stored trie: reopen at historical root after commit/remove chains.
   *
   * <p>Oracle: {@link BinaryTrie} for root hash; factory reload for value presence.
   */
  @Nested
  class StoredTrieHistoricalRoots {

    @Test
    void commitRemoveCommitRestoresPriorRoot() {
      final Bytes key = Bytes.fromHexString("0x1122334455667788");
      final Bytes32 value = Bytes32.repeat((byte) 0x55);

      final StoredPartitionedBinaryTrie trie = factory.create();
      trie.put(key.toArray(), key.size(), value.toArray());
      trie.commit(nodeUpdater);
      final Bytes32 rootWithData = trie.getRootHash();

      trie.remove(key.toArray(), key.size());
      trie.commit(nodeUpdater);
      assertThat(trie.getRootHash()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);
      assertThat(trie.get(key.toArray(), key.size())).isEmpty();

      final StoredPartitionedBinaryTrie reloaded = factory.create(rootWithData);
      assertThat(reloaded.get(key.toArray(), key.size())).contains(value.toArray());
    }

    @Test
    void besuRollbackReopensHistoricalRoot() {
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
      final Bytes32 root2 = trie.getRootHash();
      assertThat(root2).isNotEqualTo(root1);

      trie.remove(key2.toArray(), key2.size());
      trie.commit(nodeUpdater);
      assertThat(trie.getRootHash()).isEqualTo(root1);

      final StoredPartitionedBinaryTrie atRoot1 = factory.create(root1);
      assertThat(atRoot1.get(key1.toArray(), key1.size())).contains(value1.toArray());
      assertThat(atRoot1.get(key2.toArray(), key2.size())).isEmpty();

      final StoredPartitionedBinaryTrie atRoot2 = factory.create(root2);
      assertThat(atRoot2.get(key1.toArray(), key1.size())).contains(value1.toArray());
      assertThat(atRoot2.get(key2.toArray(), key2.size())).contains(value2.toArray());
    }

    @Test
    void besuRollbackChainOfThreeStates() {
      final List<Bytes32> roots = new ArrayList<>();
      final List<Map<Bytes, Bytes32>> snapshots = new ArrayList<>();
      final StoredPartitionedBinaryTrie trie = factory.create();
      final BinaryTrie spec = new BinaryTrie();
      final Map<Bytes, Bytes32> live = new HashMap<>();

      roots.add(trie.getRootHash());
      snapshots.add(Map.copyOf(live));

      final Bytes[] keys = {
        Bytes.fromHexString("0x01"), Bytes.fromHexString("0x02"), Bytes.fromHexString("0x03")
      };
      for (int i = 0; i < keys.length; i++) {
        final Bytes32 value = Bytes32.repeat((byte) (0x10 + i));
        live.put(keys[i], value);
        spec.put(keys[i], value);
        trie.put(keys[i].toArray(), keys[i].size(), value.toArray());
        trie.commit(nodeUpdater);
        roots.add(trie.getRootHash());
        snapshots.add(Map.copyOf(live));
        assertThat(trie.getRootHash()).isEqualTo(spec.root());
      }

      trie.remove(keys[2].toArray(), keys[2].size());
      spec.remove(keys[2]);
      live.remove(keys[2]);
      trie.commit(nodeUpdater);
      assertThat(trie.getRootHash()).isEqualTo(roots.get(2));
      assertThat(trie.getRootHash()).isEqualTo(spec.root());

      trie.remove(keys[1].toArray(), keys[1].size());
      spec.remove(keys[1]);
      live.remove(keys[1]);
      trie.commit(nodeUpdater);
      assertThat(trie.getRootHash()).isEqualTo(roots.get(1));

      for (int i = 0; i < roots.size(); i++) {
        final StoredPartitionedBinaryTrie view = factory.create(roots.get(i));
        final Map<Bytes, Bytes32> expected = snapshots.get(i);
        for (final Map.Entry<Bytes, Bytes32> e : expected.entrySet()) {
          assertThat(view.get(e.getKey().toArray(), e.getKey().size()))
              .as("root index %d key %s", i, e.getKey())
              .contains(e.getValue().toArray());
        }
        for (final Bytes key : keys) {
          if (!expected.containsKey(key)) {
            assertThat(view.get(key.toArray(), key.size()))
                .as("root index %d absent key %s", i, key)
                .isEmpty();
          }
        }
      }
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
      final Bytes32 rootBoth = trie.getRootHash();
      assertThat(rootBoth).isNotEqualTo(rootAfterA);

      trie.remove(keyB.toArray(), keyB.size());
      spec.remove(keyB);
      trie.commit(nodeUpdater);
      assertThat(trie.getRootHash()).isEqualTo(rootAfterA);
      assertThat(trie.getRootHash()).isEqualTo(spec.root());

      assertThat(factory.create(rootAfterA).get(keyA.toArray(), keyA.size()))
          .contains(valueA.toArray());
      assertThat(factory.create(rootBoth).get(keyB.toArray(), keyB.size()))
          .contains(valueB.toArray());
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
      assertThat(factory.create(root1).get(key.toArray(), key.size())).contains(value1.toArray());

      trie.put(key.toArray(), key.size(), value2.toArray());
      spec.put(key, value2);
      trie.commit(nodeUpdater);
      final Bytes32 root2 = trie.getRootHash();
      assertThat(root2).isNotEqualTo(root1);
      assertThat(trie.getRootHash()).isEqualTo(spec.root());
      assertThat(trie.get(key.toArray(), key.size())).contains(value2.toArray());
      assertThat(factory.create(root2).get(key.toArray(), key.size())).contains(value2.toArray());
    }

    @Test
    void putDeferredRemoveViaEmptyMerger() {
      final Bytes key = Bytes.fromHexString("0xbeef");
      final Bytes32 value = Bytes32.repeat((byte) 0x77);

      final StoredPartitionedBinaryTrie trie = factory.create();
      trie.put(key.toArray(), key.size(), value.toArray());
      trie.commit(nodeUpdater);
      final Bytes32 rootWithValue = trie.getRootHash();

      trie.putDeferred(key.toArray(), key.size(), existing -> Optional.empty());
      trie.commit(nodeUpdater);

      assertThat(trie.getRootHash()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);
      assertThat(factory.create(rootWithValue).get(key.toArray(), key.size()))
          .contains(value.toArray());
    }
  }

  /**
   * Rollback with EIP-8297 embedding keys (basic data, code hash) in stored mode.
   *
   * <p>Oracle: {@link BinaryTrie} root hash and factory reload for historical snapshots.
   */
  @Nested
  class EmbeddingKeyRollback {

    @Test
    void accountBasicDataRemoveRestoresEmptyAccount() {
      final Bytes basicKey = Eip8297TreeKeyDerivation.getTreeKeyForBasicData(ADDRESS);
      final Bytes32 basicData = AccountBasicDataEncoder.encodeBasicData(1, 2, UInt256.valueOf(100));

      final StoredPartitionedBinaryTrie trie = factory.create();
      final BinaryTrie spec = new BinaryTrie();

      trie.put(basicKey.toArray(), basicKey.size(), basicData.toArray());
      spec.put(basicKey, basicData);
      trie.commit(nodeUpdater);
      final Bytes32 rootWithAccount = trie.getRootHash();
      assertThat(trie.getRootHash()).isEqualTo(spec.root());

      trie.remove(basicKey.toArray(), basicKey.size());
      spec.remove(basicKey);
      trie.commit(nodeUpdater);
      assertThat(trie.getRootHash()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);
      assertThat(trie.getRootHash()).isEqualTo(spec.root());

      final StoredPartitionedBinaryTrie rolledBack = factory.create(rootWithAccount);
      assertThat(rolledBack.get(basicKey.toArray(), basicKey.size())).contains(basicData.toArray());
    }

    @Test
    void accountRemoveOneLeafKeepsOther() {
      final Bytes basicKey = Eip8297TreeKeyDerivation.getTreeKeyForBasicData(ADDRESS);
      final Bytes codeHashKey = Eip8297TreeKeyDerivation.getTreeKeyForCodeHash(ADDRESS);
      final Bytes32 basicData = AccountBasicDataEncoder.encodeBasicData(0, 0, UInt256.ONE);
      final Bytes32 codeHash = Eip8297TreeKeyDerivation.EMPTY_CODE_HASH;

      final StoredPartitionedBinaryTrie trie = factory.create();
      trie.put(basicKey.toArray(), basicKey.size(), basicData.toArray());
      trie.put(codeHashKey.toArray(), codeHashKey.size(), codeHash.toArray());
      trie.commit(nodeUpdater);
      final Bytes32 rootBoth = trie.getRootHash();

      trie.remove(codeHashKey.toArray(), codeHashKey.size());
      trie.commit(nodeUpdater);
      assertThat(trie.get(codeHashKey.toArray(), codeHashKey.size())).isEmpty();
      assertThat(trie.get(basicKey.toArray(), basicKey.size())).contains(basicData.toArray());

      final StoredPartitionedBinaryTrie atBoth = factory.create(rootBoth);
      assertThat(atBoth.get(codeHashKey.toArray(), codeHashKey.size()))
          .contains(codeHash.toArray());
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
