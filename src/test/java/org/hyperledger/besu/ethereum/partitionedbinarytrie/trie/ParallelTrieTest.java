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

import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieConstants;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieKeyDerivation;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.NodeLoaderMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.NodeUpdaterMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.reference.BinaryTrie;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.BiFunction;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link ParallelStoredPartitionedBinaryTrie} against the sequential {@link
 * StoredPartitionedBinaryTrie}: every commit must give the same root and write the same entries.
 */
class ParallelTrieTest {

  private NodeUpdaterMock parallelUpdater;
  private NodeUpdaterMock sequentialUpdater;
  private NodeLoaderMock parallelLoader;
  private NodeLoaderMock sequentialLoader;
  private ParallelStoredPartitionedBinaryTrie parallelTrie;
  private StoredPartitionedBinaryTrie sequentialTrie;

  @BeforeEach
  void setUp() {
    parallelUpdater = new NodeUpdaterMock();
    sequentialUpdater = new NodeUpdaterMock();
    parallelLoader = new NodeLoaderMock(parallelUpdater);
    sequentialLoader = new NodeLoaderMock(sequentialUpdater);

    parallelTrie = new ParallelStoredPartitionedBinaryTrie(parallelLoader);
    sequentialTrie = new StoredPartitionedBinaryTrie(sequentialLoader);
  }

  @Test
  void emptyTrieHasTheEmptyRoot() {
    assertThat(parallelTrie.getRootHash()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);
    assertThat(parallelTrie.isEmpty()).isTrue();
  }

  @Test
  void getSeesPendingUpdatesOnceProcessed() {
    // As in Besu's ParallelStoredMerklePatriciaTrie, get reads the trie as last processed.
    parallelTrie.put(createKey(1), createValue(1));
    assertThat(parallelTrie.get(createKey(1))).isEmpty();

    parallelTrie.getRootHash();
    assertThat(parallelTrie.get(createKey(1))).contains(createValue(1));
  }

  @Test
  void commitAfterGetRootHashStoresTheProcessedUpdates() {
    final Map<Bytes, Bytes32> entries = new HashMap<>();
    for (int i = 1; i <= 50; i++) {
      entries.put(createKey(i), createValue(i));
    }
    entries.forEach(this::putBoth);
    // Processes the batch without storing anything: the commit must still store all of it.
    parallelTrie.getRootHash();
    commitBoth();

    assertReloadReads(entries);
  }

  @Test
  void removeThenPutOfTheSameKeyKeepsTheNewValue() {
    // Regression: put(Bytes, Bytes) used to bypass the pending batch while remove was queued, so
    // the queued remove erased the newer put at commit.
    final Bytes key = Bytes.fromHexString("0x00" + "11".repeat(32) + "00");
    final Bytes other = Bytes.fromHexString("0x00" + "22".repeat(32) + "00");
    putBoth(key, Bytes32.repeat((byte) 1));
    putBoth(other, Bytes32.repeat((byte) 1));
    commitBoth();

    removeBoth(key);
    putBoth(key, Bytes32.repeat((byte) 2));
    commitBoth();

    assertReloadReads(Map.of(key, Bytes32.repeat((byte) 2), other, Bytes32.repeat((byte) 1)));
  }

  @Test
  void deleteThenReinsertOfTheSameCodeKeepsItsChunks() {
    // Last reference deleted and the same code deployed again in one block: chunks must survive.
    final Bytes32 codeHash = Bytes32.repeat((byte) 0x0c);
    final Bytes code = Bytes.fromHexString("0x6001600155");
    for (final StoredPartitionedBinaryTrie trie : List.of(parallelTrie, sequentialTrie)) {
      trie.insertCode(codeHash, code);
    }
    commitBoth();

    for (final StoredPartitionedBinaryTrie trie : List.of(parallelTrie, sequentialTrie)) {
      trie.deleteCode(codeHash);
      trie.insertCode(codeHash, code);
    }
    commitBoth();

    assertThat(reloadParallel().get(TrieKeyDerivation.getTreeKeyForCodeChunk(codeHash, 0)))
        .isPresent();
  }

  @Test
  void removingTheLastKeyEmptiesTheTrie() {
    final Bytes key = createKey(1);
    putBoth(key, createValue(1));
    commitBoth();

    removeBoth(key);
    commitBoth();

    assertThat(parallelTrie.readState(key)).isEqualTo(Bytes32.ZERO);
    // Reopened through the root location, which now holds the empty-root marker.
    assertThat(reloadParallel().getRootHash()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);
  }

  @Test
  void pendingUpdatesAreIsolatedFromCallerArrayMutation() {
    final byte[] key = createKey(1).toArray();
    final byte[] value = createValue(100).toArray();
    final Bytes originalKey = Bytes.wrap(key.clone());
    final Bytes32 originalValue = Bytes32.wrap(value.clone());

    parallelTrie.put(key, key.length, value);
    key[0] ^= (byte) 0x7F;
    value[0] = (byte) 0x7F;
    parallelTrie.commit(parallelUpdater);

    assertThat(parallelTrie.get(originalKey)).contains(originalValue);
    assertThat(parallelTrie.get(Bytes.wrap(key))).isEmpty();
  }

  @Test
  void updateOfAKeyMatchesSequential() {
    final Map<Bytes, Bytes32> entries = new HashMap<>();
    for (int i = 1; i <= 20; i++) {
      entries.put(createKey(i), createValue(i));
    }
    entries.forEach(this::putBoth);
    commitBoth();

    entries.put(createKey(7), createValue(200));
    putBoth(createKey(7), createValue(200));
    commitBoth();

    assertReloadReads(entries);
  }

  @Test
  void manyLeavesOfOneStemMatchSequential() {
    final Map<Bytes, Bytes32> entries = new HashMap<>();
    for (int i = 0; i < 20; i++) {
      entries.put(Bytes.of(1, 2, 3, i), createValue(i));
    }
    entries.forEach(this::putBoth);
    commitBoth();

    assertReloadReads(entries);
  }

  @Test
  void keysDivergingAtDifferentDepthsMatchSequential() {
    final Map<Bytes, Bytes32> entries =
        Map.of(
            Bytes.fromHexString("0x01000000"), createValue(1),
            Bytes.fromHexString("0x02000000"), createValue(2),
            Bytes.fromHexString("0x03040501"), createValue(3),
            Bytes.fromHexString("0x03040502"), createValue(4));
    entries.forEach(this::putBoth);
    commitBoth();

    assertReloadReads(entries);
  }

  @Test
  void batchRemovingEveryKeyEmptiesTheTrie() {
    for (int i = 1; i <= 10; i++) {
      putBoth(createKey(i), createValue(i));
    }
    commitBoth();

    for (int i = 1; i <= 10; i++) {
      removeBoth(createKey(i));
    }
    commitBoth();

    assertReloadReads(Map.of());
  }

  @Test
  void removalsAndUpdatesInOneBatchMatchSequential() {
    final Map<Bytes, Bytes32> entries = new HashMap<>();
    for (int i = 0; i < 50; i++) {
      entries.put(createKey(i + 1), createValue(i));
    }
    entries.forEach(this::putBoth);
    commitBoth();

    for (int i = 0; i < 20; i++) {
      removeBoth(createKey(i + 1));
      entries.remove(createKey(i + 1));
    }
    for (int i = 20; i < 35; i++) {
      putBoth(createKey(i + 1), createValue(i * 10));
      entries.put(createKey(i + 1), createValue(i * 10));
    }
    commitBoth();

    assertReloadReads(entries);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 10, 50, 100, 200})
  void batchesOfAnySizeMatchSequential(final int size) {
    final Map<Bytes, Bytes32> entries = new HashMap<>();
    for (int i = 1; i <= size; i++) {
      entries.put(createKey(i), createValue(i));
    }
    entries.forEach(this::putBoth);
    commitBoth();

    assertReloadReads(entries);
  }

  @Test
  void putDeferredMergesWithThePriorValue() {
    final Bytes key = createKey(1);
    putBoth(key, createValue(1));
    commitBoth();

    for (final StoredPartitionedBinaryTrie trie : List.of(parallelTrie, sequentialTrie)) {
      trie.putDeferred(key, prior -> prior.map(value -> createValue(value.get(0) + 1)));
    }
    commitBoth();
    assertThat(reloadParallel().get(key)).contains(createValue(2));

    for (final StoredPartitionedBinaryTrie trie : List.of(parallelTrie, sequentialTrie)) {
      trie.putDeferred(key, prior -> Optional.empty());
    }
    commitBoth();
    assertThat(reloadParallel().get(key)).isEmpty();
  }

  @Test
  void deferredRemovalBesideANewKeyMatchesSequential() {
    // Both keys start with a 0 bit: the new key turns the stored leaf into a branch, unless the
    // merge removes the leaf first.
    for (int i = 0; i < 16; i++) {
      setUp();
      final Bytes stored = Bytes.of(0x10, i);
      final Bytes added = Bytes.of(0x20, i);
      putBoth(stored, createValue(1));
      commitBoth();

      putBoth(added, createValue(2));
      for (final StoredPartitionedBinaryTrie trie : List.of(parallelTrie, sequentialTrie)) {
        trie.putDeferred(stored, prior -> Optional.empty());
      }
      commitBoth();
      assertReloadReads(Map.of(added, createValue(2)));
    }
  }

  @Test
  void keyThatIsAPrefixOfAnotherFailsTheBatch() {
    putBoth(Bytes.fromHexString("0xaa00"), createValue(1));
    putBoth(Bytes.fromHexString("0xaa80"), createValue(1));
    commitBoth();
    final Bytes32 root = parallelTrie.getRootHash();

    // 0xaa ends at the split of the branch over 0xaa00 and 0xaa80.
    parallelTrie.put(Bytes.fromHexString("0xaa"), createValue(2));
    parallelTrie.put(Bytes.fromHexString("0xbb"), createValue(2));
    assertThatThrownBy(parallelTrie::getRootHash)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("prefix-freedom");
    assertThat(parallelTrie.getRootHash()).isEqualTo(root);
  }

  @Test
  void commitsOverManyAccountsMatchSequential() {
    final Random random = new Random(17);
    final Map<Bytes, Bytes32> entries = new HashMap<>();
    final List<Bytes32> addresses = new ArrayList<>();
    for (int i = 0; i < 300; i++) {
      final Bytes32 address = Bytes32.random(random);
      addresses.add(address);
      entries.put(TrieKeyDerivation.getTreeKeyForBasicData(address), Bytes32.random(random));
      final int slots = 1 + random.nextInt(6);
      for (int slot = 0; slot < slots; slot++) {
        entries.put(storageSlot(address, random.nextInt(300)), Bytes32.random(random));
      }
    }
    entries.forEach(this::putBoth);
    commitBoth();

    // Then updates of existing keys, with a few new slots, then removals of existing keys.
    final List<Bytes> keys = new ArrayList<>(entries.keySet());
    keys.sort(Bytes::compareTo);
    for (int i = 0; i < keys.size(); i += 3) {
      entries.put(keys.get(i), Bytes32.random(random));
    }
    for (int i = 0; i < addresses.size(); i += 10) {
      entries.put(storageSlot(addresses.get(i), 1000 + i), Bytes32.random(random));
    }
    entries.forEach(this::putBoth);
    commitBoth();

    for (int i = 1; i < keys.size(); i += 2) {
      removeBoth(keys.get(i));
      entries.remove(keys.get(i));
    }
    commitBoth();

    assertReloadReads(entries);
  }

  @Test
  void updatesLeavingAStemPrefixMatchSequential() {
    // A stem's top branch carries the rest of the stem in its prefix: hundreds of bits.
    final Random random = new Random(23);
    final List<Bytes32> addresses = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      addresses.add(Bytes32.random(random));
    }
    final Map<Bytes, Bytes32> entries = new HashMap<>();
    for (final Bytes32 address : addresses) {
      entries.put(TrieKeyDerivation.getTreeKeyForBasicData(address), Bytes32.random(random));
      entries.put(TrieKeyDerivation.getTreeKeyForCodeHash(address), Bytes32.random(random));
      entries.put(storageSlot(address, 256), Bytes32.random(random));
      entries.put(storageSlot(address, 257), Bytes32.random(random));
    }
    entries.forEach(this::putBoth);
    commitBoth();

    // Header slot 0 (sub-index 64) and storage slot 384 (0x80) leave those prefixes.
    final Map<Bytes, Bytes32> batch = new HashMap<>();
    for (final Bytes32 address : addresses) {
      batch.put(TrieKeyDerivation.getTreeKeyForBasicData(address), Bytes32.random(random));
      batch.put(storageSlot(address, 0), Bytes32.random(random));
      batch.put(storageSlot(address, 257), Bytes32.random(random));
      batch.put(storageSlot(address, 384), Bytes32.random(random));
    }
    batch.forEach(this::putBoth);
    entries.putAll(batch);
    commitBoth();

    assertReloadReads(entries);
  }

  @Test
  void updatesLeavingOnePrefixAtSeveralBitsMatchSequential() {
    // Sub-indices 0 and 1 share seven bits; 0x10, 0x40 and 0x80 each leave them at another bit.
    final Random random = new Random(31);
    final Map<Bytes, Bytes32> entries = new HashMap<>();
    final List<Bytes32> addresses = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      final Bytes32 address = Bytes32.random(random);
      addresses.add(address);
      entries.put(storageSlot(address, 256), Bytes32.random(random));
      entries.put(storageSlot(address, 257), Bytes32.random(random));
    }
    entries.forEach(this::putBoth);
    commitBoth();

    for (final Bytes32 address : addresses) {
      for (final int slot : new int[] {257, 256 + 0x10, 256 + 0x40, 256 + 0x80}) {
        final Bytes32 value = Bytes32.random(random);
        putBoth(storageSlot(address, slot), value);
        entries.put(storageSlot(address, slot), value);
      }
    }
    commitBoth();

    assertReloadReads(entries);
  }

  @Test
  void splitPrefixCollapsesWhenASideEndsEmpty() {
    // Each batch splits the stems' prefixes, then leaves one side or both empty.
    final List<BiFunction<Bytes32, Random, Map<Bytes, Optional<Bytes32>>>> batches =
        List.of(
            // Nothing lands on the new side.
            (address, random) ->
                Map.of(
                    TrieKeyDerivation.getTreeKeyForBasicData(address),
                    Optional.of(Bytes32.random(random)),
                    storageSlot(address, 0),
                    Optional.empty(),
                    storageSlot(address, 257),
                    Optional.of(Bytes32.random(random)),
                    storageSlot(address, 384),
                    Optional.empty()),
            // The original side empties.
            (address, random) ->
                Map.of(
                    TrieKeyDerivation.getTreeKeyForBasicData(address),
                    Optional.empty(),
                    TrieKeyDerivation.getTreeKeyForCodeHash(address),
                    Optional.empty(),
                    storageSlot(address, 0),
                    Optional.of(Bytes32.random(random)),
                    storageSlot(address, 256),
                    Optional.empty(),
                    storageSlot(address, 257),
                    Optional.empty(),
                    storageSlot(address, 384),
                    Optional.of(Bytes32.random(random))),
            // Both sides end empty.
            (address, random) ->
                Map.of(
                    TrieKeyDerivation.getTreeKeyForBasicData(address),
                    Optional.empty(),
                    TrieKeyDerivation.getTreeKeyForCodeHash(address),
                    Optional.empty(),
                    storageSlot(address, 0),
                    Optional.empty(),
                    storageSlot(address, 256),
                    Optional.empty(),
                    storageSlot(address, 257),
                    Optional.empty(),
                    storageSlot(address, 384),
                    Optional.empty()));

    for (int c = 0; c < batches.size(); c++) {
      setUp();
      final Random random = new Random(29 + c);
      final List<Bytes32> addresses = new ArrayList<>();
      final Map<Bytes, Bytes32> entries = new HashMap<>();
      for (int i = 0; i < 20; i++) {
        final Bytes32 address = Bytes32.random(random);
        addresses.add(address);
        entries.put(TrieKeyDerivation.getTreeKeyForBasicData(address), Bytes32.random(random));
        entries.put(TrieKeyDerivation.getTreeKeyForCodeHash(address), Bytes32.random(random));
        entries.put(storageSlot(address, 256), Bytes32.random(random));
        entries.put(storageSlot(address, 257), Bytes32.random(random));
      }
      entries.forEach(this::putBoth);
      commitBoth();

      // Half the accounts take the batch.
      final Map<Bytes, Optional<Bytes32>> batch = new HashMap<>();
      for (int i = 0; i < addresses.size(); i += 2) {
        batch.putAll(batches.get(c).apply(addresses.get(i), random));
      }
      batch.forEach(
          (key, value) -> {
            if (value.isPresent()) {
              putBoth(key, value.get());
              entries.put(key, value.get());
            } else {
              removeBoth(key);
              entries.remove(key);
            }
          });
      commitBoth();

      assertReloadReads(entries);
      for (final Bytes key : batch.keySet()) {
        if (!entries.containsKey(key)) {
          assertThat(reloadParallel().get(key)).as("case %d", c).isEmpty();
        }
      }
    }
  }

  @Test
  void batchRemovingAKeyAndAddingALongerOneAppliesTheRemovalFirst() {
    final Bytes shortKey = Bytes.fromHexString("0x0100");
    final Bytes longKey = Bytes.fromHexString("0x010005");
    putBoth(shortKey, Bytes32.repeat((byte) 1));
    commitBoth();

    // The short key is a prefix of the long one, so it must go before the long one comes in.
    removeBoth(shortKey);
    putBoth(longKey, Bytes32.repeat((byte) 2));
    commitBoth();

    assertReloadReads(Map.of(longKey, Bytes32.repeat((byte) 2)));
    assertThat(reloadParallel().get(shortKey)).isEmpty();
  }

  @Test
  void stemJoinedByALongerKeyKeepsItsLeaves() {
    final Map<Bytes, Bytes32> entries =
        Map.of(
            Bytes.fromHexString("0x0100"), Bytes32.repeat((byte) 1),
            Bytes.fromHexString("0x0103"), Bytes32.repeat((byte) 2),
            Bytes.fromHexString("0x010205"), Bytes32.repeat((byte) 3));
    putBoth(Bytes.fromHexString("0x0100"), Bytes32.repeat((byte) 1));
    putBoth(Bytes.fromHexString("0x0103"), Bytes32.repeat((byte) 2));
    commitBoth();
    putBoth(Bytes.fromHexString("0x010205"), Bytes32.repeat((byte) 3));
    commitBoth();

    assertReloadReads(entries);
  }

  private void putBoth(final Bytes key, final Bytes32 value) {
    parallelTrie.put(key, value);
    sequentialTrie.put(key, value);
  }

  private void removeBoth(final Bytes key) {
    parallelTrie.remove(key);
    sequentialTrie.remove(key);
  }

  /** Commits both tries: same root, same entries written. */
  private void commitBoth() {
    parallelTrie.commit(parallelUpdater);
    sequentialTrie.commit(sequentialUpdater);
    assertThat(parallelTrie.getRootHash()).isEqualTo(sequentialTrie.getRootHash());
    assertThat(parallelUpdater.storage).isEqualTo(sequentialUpdater.storage);
  }

  /** The parallel trie reopened through its root location. */
  private ParallelStoredPartitionedBinaryTrie reloadParallel() {
    return new ParallelStoredPartitionedBinaryTrie(parallelLoader);
  }

  /** Root of the reopened parallel trie against the spec, and every expected value. */
  private void assertReloadReads(final Map<Bytes, Bytes32> expected) {
    final BinaryTrie spec = new BinaryTrie();
    expected.forEach(spec::put);
    final ParallelStoredPartitionedBinaryTrie reloaded = reloadParallel();
    assertThat(reloaded.getRootHash()).isEqualTo(spec.root());
    expected.forEach(
        (key, value) -> assertThat(reloaded.get(key)).as("key %s", key).contains(value));
  }

  /** Four-byte keys spread over the key space, so that they fall in different stems. */
  private static Bytes createKey(final int seed) {
    return Bytes.ofUnsignedInt(Integer.toUnsignedLong(seed * 0x9E3779B1));
  }

  private static Bytes32 createValue(final int value) {
    return Bytes32.repeat((byte) (value & 0xFF));
  }

  private static Bytes storageSlot(final Bytes32 address, final long slot) {
    return TrieKeyDerivation.getTreeKeyForStorageSlot(address, UInt256.valueOf(slot));
  }
}
