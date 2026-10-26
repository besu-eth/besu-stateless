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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.reference;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieConstants;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieKeyDerivation;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.PartitionedBinaryTrie;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.StoredPartitionedBinaryTrie;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.NodeLoaderMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.NodeUpdaterMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.hash.TrieHasher;

import java.util.Map;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.Test;

/** Vectors of the execution-specs {@code ethereum.binary_trie} reference. */
class BinaryTrieReferenceVectorsTest {

  private static final Bytes32 ADDRESS =
      Bytes32.fromHexString("000000000000000000000000aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
  private static final Bytes STEM = Bytes.concatenate(Bytes.of(0), Bytes.repeat((byte) 0x42, 32));

  @Test
  void emptyRoot() {
    assertRoot(Map.of(), TrieConstants.EMPTY_TRIE_ROOT);
  }

  @Test
  void singleLeafRoot() {
    assertRoot(
        Map.of(Bytes.concatenate(STEM, Bytes.of(7)), Bytes32.repeat((byte) 0x11)),
        Bytes32.fromHexString("11a3af6a4865f503813b05f45e42a2a1cc1b1498cf809e9d1446c1ed1f28b19f"));
  }

  @Test
  void stemSplitRoot() {
    assertRoot(
        Map.of(
            Bytes.concatenate(STEM, Bytes.of(0)), Bytes32.repeat((byte) 1),
            Bytes.concatenate(STEM, Bytes.of(0xff)), Bytes32.repeat((byte) 2)),
        Bytes32.fromHexString("236789e96c40914f04ac2418aca5a8e71540e78a355326ad26aab3db9107016d"));
  }

  @Test
  void accountKeys() {
    assertThat(TrieKeyDerivation.getTreeKeyForBasicData(ADDRESS))
        .isEqualTo(
            Bytes.fromHexString(
                "00d9ae2d236f8713a5bf808cda488167a56cc97e4b83006f42b1c06c0c3f053bbf00"));
    assertThat(TrieKeyDerivation.getTreeKeyForStorageSlot(ADDRESS, UInt256.valueOf(5)))
        .isEqualTo(
            Bytes.fromHexString(
                "00d9ae2d236f8713a5bf808cda488167a56cc97e4b83006f42b1c06c0c3f053bbf45"));
    assertThat(
            lastFourBytes(
                TrieKeyDerivation.getTreeKeyForStorageSlot(ADDRESS, UInt256.valueOf(1000))))
        .isEqualTo(Bytes.fromHexString("7650f9e8"));
  }

  @Test
  void codeChunkKey() {
    final Bytes32 codeHash = TrieHasher.blake3Hash(Bytes.wrap("some code".getBytes(US_ASCII)));
    assertThat(lastFourBytes(TrieKeyDerivation.getTreeKeyForCodeChunk(codeHash, 300)))
        .isEqualTo(Bytes.fromHexString("37f0f32c"));
  }

  private static Bytes lastFourBytes(final Bytes key) {
    return key.slice(key.size() - 4);
  }

  /** The oracle, the in-memory trie and a committed then reloaded trie all give {@code root}. */
  private static void assertRoot(final Map<Bytes, Bytes32> entries, final Bytes32 root) {
    final BinaryTrie spec = new BinaryTrie();
    final PartitionedBinaryTrie inMemory = new PartitionedBinaryTrie();
    final NodeUpdaterMock nodeUpdater = new NodeUpdaterMock();
    final StoredPartitionedBinaryTrie stored =
        new StoredPartitionedBinaryTrie(new NodeLoaderMock(nodeUpdater));
    entries.forEach(
        (key, value) -> {
          spec.put(key, value);
          inMemory.put(key, value);
          stored.put(key, value);
        });
    stored.commit(nodeUpdater);

    assertThat(spec.root()).isEqualTo(root);
    assertThat(inMemory.getRootHash()).isEqualTo(root);
    assertThat(new StoredPartitionedBinaryTrie(new NodeLoaderMock(nodeUpdater)).getRootHash())
        .isEqualTo(root);
  }
}
