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
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.NodeLoaderMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.NodeUpdaterMock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;

/**
 * {@link AscendingCollapseBinaryTrie} must produce the same root as the stored trie for the same
 * entries, accept keys in unsigned byte-lexicographic order, and reject anything else.
 */
class AscendingCollapseBinaryTrieTest {

  private static final Bytes32 VALUE = Bytes32.repeat((byte) 1);

  @Test
  void emptyTrieHasTheEmptyRoot() {
    assertThat(new AscendingCollapseBinaryTrie().rootHash())
        .isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);
  }

  @Test
  void acceptsVariableLengthKeysInByteOrder() {
    // Prefix-free keys whose numeric order (tuweni Bytes.compareTo) is the reverse of byte order.
    final Bytes shortKey = Bytes.fromHexString("0x0002");
    final Bytes longKey = Bytes.fromHexString("0x01");
    final AscendingCollapseBinaryTrie ascending = new AscendingCollapseBinaryTrie();
    ascending.insert(shortKey, VALUE);
    ascending.insert(longKey, VALUE);

    final StoredPartitionedBinaryTrie reference = emptyStoredTrie();
    reference.put(longKey, VALUE);
    reference.put(shortKey, VALUE);
    assertThat(ascending.rootHash()).isEqualTo(reference.getRootHash());
  }

  @Test
  void rejectsDescendingAndDuplicateKeys() {
    final AscendingCollapseBinaryTrie trie = new AscendingCollapseBinaryTrie();
    trie.insert(Bytes.fromHexString("0x0002"), VALUE);
    assertThatThrownBy(() -> trie.insert(Bytes.fromHexString("0x0002"), VALUE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> trie.insert(Bytes.fromHexString("0x0001"), VALUE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsInsertAfterRootHash() {
    final AscendingCollapseBinaryTrie trie = new AscendingCollapseBinaryTrie();
    trie.insert(Bytes.fromHexString("0x01"), VALUE);
    trie.rootHash();
    assertThatThrownBy(() -> trie.insert(Bytes.fromHexString("0x02"), VALUE))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void matchesStoredTrieOnRandomZoneShapedKeys() {
    final Random random = new Random(7);
    for (int run = 0; run < 100; run++) {
      final Map<byte[], byte[]> entries = new TreeMap<>(Arrays::compareUnsigned);
      final int stems = 1 + random.nextInt(run < 70 ? 20 : 500);
      for (int s = 0; s < stems; s++) {
        final int zone = new int[] {0x00, 0x01, 0xff}[random.nextInt(3)];
        final byte[] stem = new byte[zone == 0xff ? 65 : 33];
        random.nextBytes(stem);
        stem[0] = (byte) zone;
        final int leaves = 1 + random.nextInt(run % 3 == 0 ? 256 : 4);
        for (int l = 0; l < leaves; l++) {
          final byte[] key = Arrays.copyOf(stem, stem.length + 1);
          key[stem.length] = (byte) random.nextInt(256);
          final byte[] value = new byte[32];
          random.nextBytes(value);
          entries.put(key, value);
        }
      }

      final AscendingCollapseBinaryTrie ascending = new AscendingCollapseBinaryTrie();
      entries.forEach((key, value) -> ascending.insert(Bytes.wrap(key), Bytes.wrap(value)));

      final StoredPartitionedBinaryTrie reference = emptyStoredTrie();
      final List<byte[]> shuffled = new ArrayList<>(entries.keySet());
      Collections.shuffle(shuffled, random);
      shuffled.forEach(key -> reference.put(Bytes.wrap(key), Bytes.wrap(entries.get(key))));

      assertThat(ascending.rootHash()).as("run %d", run).isEqualTo(reference.getRootHash());
    }
  }

  @Test
  void persistingTrieWritesTheSameNodesAsAStoredTrieCommit() {
    final Random random = new Random(11);
    for (int run = 0; run < 30; run++) {
      final Map<byte[], byte[]> entries = new TreeMap<>(Arrays::compareUnsigned);
      for (int s = 0, stems = 1 + random.nextInt(200); s < stems; s++) {
        final int zone = new int[] {0x00, 0x01, 0xff}[random.nextInt(3)];
        final byte[] stem = new byte[zone == 0xff ? 65 : 33];
        random.nextBytes(stem);
        stem[0] = (byte) zone;
        for (int l = 0, leaves = 1 + random.nextInt(run % 3 == 0 ? 256 : 4); l < leaves; l++) {
          final byte[] key = Arrays.copyOf(stem, stem.length + 1);
          key[stem.length] = (byte) random.nextInt(256);
          final byte[] value = new byte[32];
          random.nextBytes(value);
          entries.put(key, value);
        }
      }

      final NodeUpdaterMock bulkLoaded = new NodeUpdaterMock();
      final AscendingCollapseBinaryTrie ascending = new AscendingCollapseBinaryTrie(bulkLoaded);
      entries.forEach((key, value) -> ascending.insert(Bytes.wrap(key), Bytes.wrap(value)));
      final Bytes32 root = ascending.rootHash();

      final NodeUpdaterMock committed = new NodeUpdaterMock();
      final StoredPartitionedBinaryTrie reference = emptyStoredTrie();
      entries.forEach((key, value) -> reference.put(Bytes.wrap(key), Bytes.wrap(value)));
      reference.commit(committed);

      assertThat(root).as("run %d", run).isEqualTo(reference.getRootHash());
      assertThat(bulkLoaded.storage).as("run %d", run).isEqualTo(committed.storage);

      // And the bulk-loaded store reads back like any stored trie.
      final StoredPartitionedBinaryTrie reloaded =
          new StoredPartitionedBinaryTrie(new NodeLoaderMock(bulkLoaded), root);
      entries.forEach(
          (key, value) -> assertThat(reloaded.get(Bytes.wrap(key))).contains(Bytes.wrap(value)));
    }
  }

  @Test
  void stemsPreparedInParallelWriteTheSameNodesAsPlainInserts() {
    final Random random = new Random(13);
    for (int run = 0; run < 30; run++) {
      // Stems (all but the last key byte) of 1 to 256 leaves.
      final Map<byte[], Map<byte[], byte[]>> stems = new TreeMap<>(Arrays::compareUnsigned);
      for (int s = 0, count = 1 + random.nextInt(200); s < count; s++) {
        final int zone = new int[] {0x00, 0x01, 0xff}[random.nextInt(3)];
        final byte[] stem = new byte[zone == 0xff ? 65 : 33];
        random.nextBytes(stem);
        stem[0] = (byte) zone;
        final Map<byte[], byte[]> leaves = new TreeMap<>(Arrays::compareUnsigned);
        for (int l = 0, n = 1 + random.nextInt(run % 3 == 0 ? 256 : 4); l < n; l++) {
          final byte[] key = Arrays.copyOf(stem, stem.length + 1);
          key[stem.length] = (byte) random.nextInt(256);
          final byte[] value = new byte[32];
          random.nextBytes(value);
          leaves.put(key, value);
        }
        stems.put(stem, leaves);
      }

      // Every other stem is prepared, all of them in parallel; the rest go through plain inserts.
      final NodeUpdaterMock bulkLoaded = new NodeUpdaterMock();
      final AscendingCollapseBinaryTrie ascending = new AscendingCollapseBinaryTrie(bulkLoaded);
      final List<Map<byte[], byte[]>> ordered = new ArrayList<>(stems.values());
      final List<AscendingCollapseBinaryTrie.Subtree> prepared =
          ordered.parallelStream()
              .map(
                  leaves ->
                      ascending.prepare(
                          leaves.keySet().stream().map(Bytes::wrap).toList(),
                          leaves.values().stream().map(Bytes::wrap).toList()))
              .toList();
      for (int i = 0; i < ordered.size(); i++) {
        if (i % 2 == 0) {
          ascending.insert(prepared.get(i));
        } else {
          ordered
              .get(i)
              .forEach((key, value) -> ascending.insert(Bytes.wrap(key), Bytes.wrap(value)));
        }
      }
      final Bytes32 root = ascending.rootHash();

      final NodeUpdaterMock committed = new NodeUpdaterMock();
      final StoredPartitionedBinaryTrie reference = emptyStoredTrie();
      ordered.forEach(
          leaves ->
              leaves.forEach((key, value) -> reference.put(Bytes.wrap(key), Bytes.wrap(value))));
      reference.commit(committed);

      assertThat(root).as("run %d", run).isEqualTo(reference.getRootHash());
      assertThat(bulkLoaded.storage).as("run %d", run).isEqualTo(committed.storage);
      assertThat(ascending.insertCount()).isEqualTo(ordered.stream().mapToInt(Map::size).sum());
    }
  }

  @Test
  void rejectsALaterKeyInsideAPreparedSubtree() {
    final AscendingCollapseBinaryTrie trie = new AscendingCollapseBinaryTrie();
    trie.insert(
        trie.prepare(
            List.of(Bytes.fromHexString("0x0100"), Bytes.fromHexString("0x0102")),
            List.of(VALUE, VALUE)));

    // 0x0103 is after both prepared keys but under their common prefix: they were not a complete
    // subtree, and the stubbed subtree cannot take it.
    assertThatThrownBy(() -> trie.insert(Bytes.fromHexString("0x0103"), VALUE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void prepareRejectsKeysOutOfOrder() {
    final AscendingCollapseBinaryTrie trie = new AscendingCollapseBinaryTrie();
    assertThatThrownBy(
            () ->
                trie.prepare(
                    List.of(Bytes.fromHexString("0x02"), Bytes.fromHexString("0x01")),
                    List.of(VALUE, VALUE)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static StoredPartitionedBinaryTrie emptyStoredTrie() {
    return new StoredPartitionedBinaryTrie((location, hash) -> Optional.empty(), Bytes32.ZERO);
  }
}
