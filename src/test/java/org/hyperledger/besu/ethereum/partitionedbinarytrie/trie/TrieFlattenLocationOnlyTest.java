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

import org.hyperledger.besu.ethereum.partitionedbinarytrie.codec.TrieNodeCodec;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.NodeLoaderMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.NodeUpdaterMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.reference.BinaryTrie;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Removals that move a stored node to its parent's location, on a trie reopened from storage so
 * that the moved node is a stub. Storage is keyed by location, so the moved node is rewritten
 * there; its old entry stays behind, unreachable.
 *
 * <p>The trie holds stems 0x00, 0x80, 0xC0 (two leaves) and 0xE0: a branch at the root, branch U at
 * path 1, branch V at path 11, and the stems at paths 0, 10, 110 and 111.
 */
class TrieFlattenLocationOnlyTest {

  private static final Bytes K00 = Bytes.fromHexString("0x0001");
  private static final Bytes K80 = Bytes.fromHexString("0x8001");
  private static final Bytes KC0 = Bytes.fromHexString("0xc001");
  private static final Bytes KC0B = Bytes.fromHexString("0xc002");
  private static final Bytes KE0 = Bytes.fromHexString("0xe001");

  // Locations: 0x00, then the path bits and a closing 1 bit.
  private static final Bytes PATH_0 = Bytes.fromHexString("0x0040");
  private static final Bytes PATH_1 = Bytes.fromHexString("0x00c0");
  private static final Bytes PATH_10 = Bytes.fromHexString("0x00a0");
  private static final Bytes PATH_11 = Bytes.fromHexString("0x00e0");
  private static final Bytes PATH_110 = Bytes.fromHexString("0x00d0");
  private static final Bytes PATH_111 = Bytes.fromHexString("0x00f0");

  private final NodeUpdaterMock store = new NodeUpdaterMock();
  private final NodeLoaderMock loader = new NodeLoaderMock(store);
  private final Map<Bytes, Bytes32> entries = new HashMap<>();

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void storedBranchSurvivorMergesWithItsParent(final boolean parallel) {
    final StoredPartitionedBinaryTrie trie = reopenedTrie(parallel);

    // U loses stem 0x80: U and V merge into one branch with prefix 1, at U's location.
    remove(trie, K80);
    trie.commit(store);

    final byte[] merged = store.storage.get(PATH_1).toArrayUnsafe();
    assertThat(merged[0]).isEqualTo(TrieNodeCodec.BRANCH_TAG);
    assertThat(TrieNodeCodec.branchPrefixLength(merged)).isEqualTo(1);
    assertReads(parallel);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void storedStemSurvivorIsRewrittenAtItsParentsLocation(final boolean parallel) {
    final StoredPartitionedBinaryTrie trie = reopenedTrie(parallel);

    // V loses stem 0xE0: stem 0xC0 takes V's place, as one entry with both leaves.
    remove(trie, KE0);
    trie.commit(store);

    assertThat(stemKeys(PATH_11)).containsExactly(KC0, KC0B);
    assertReads(parallel);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void storedOneLeafStemSurvivorIsRewrittenAtItsParentsLocation(final boolean parallel) {
    final StoredPartitionedBinaryTrie trie = reopenedTrie(parallel);

    // V loses stem 0xC0: stem 0xE0 takes V's place.
    remove(trie, KC0);
    remove(trie, KC0B);
    trie.commit(store);

    assertThat(stemKeys(PATH_11)).containsExactly(KE0);
    assertReads(parallel);
  }

  /** Commits the five keys, checks where each node lands, and reopens the trie from storage. */
  private StoredPartitionedBinaryTrie reopenedTrie(final boolean parallel) {
    final StoredPartitionedBinaryTrie trie = open(parallel);
    for (final Bytes key : List.of(K00, K80, KC0, KC0B, KE0)) {
      final Bytes32 value = Bytes32.repeat(key.get(1));
      trie.put(key, value);
      entries.put(key, value);
    }
    trie.commit(store);
    assertThat(store.storage)
        .containsOnlyKeys(Bytes.EMPTY, PATH_0, PATH_1, PATH_10, PATH_11, PATH_110, PATH_111);
    return open(parallel);
  }

  private void remove(final StoredPartitionedBinaryTrie trie, final Bytes key) {
    trie.remove(key);
    entries.remove(key);
  }

  private List<Bytes> stemKeys(final Bytes location) {
    final byte[] stem = store.storage.get(location).toArrayUnsafe();
    assertThat(stem[0]).isEqualTo(TrieNodeCodec.STEM_TAG);
    final List<Bytes> keys = new ArrayList<>();
    TrieNodeCodec.decodeStem(stem, (key, value) -> keys.add(Bytes.wrap(key)));
    return keys;
  }

  /** The trie reopened through its root location: root against the spec, and every value. */
  private void assertReads(final boolean parallel) {
    final BinaryTrie spec = new BinaryTrie();
    entries.forEach(spec::put);
    final StoredPartitionedBinaryTrie reloaded = open(parallel);
    assertThat(reloaded.getRootHash()).isEqualTo(spec.root());
    for (final Bytes key : List.of(K00, K80, KC0, KC0B, KE0)) {
      if (entries.containsKey(key)) {
        assertThat(reloaded.get(key)).as("key %s", key).contains(entries.get(key));
      } else {
        assertThat(reloaded.get(key)).as("key %s", key).isEmpty();
      }
    }
  }

  private StoredPartitionedBinaryTrie open(final boolean parallel) {
    return parallel
        ? new ParallelStoredPartitionedBinaryTrie(loader)
        : new StoredPartitionedBinaryTrie(loader);
  }
}
