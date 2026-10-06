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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.embedding;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.codec.BasicDataEncoder;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.codec.CodeChunkifier;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.codec.DelegationEncoder;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieConstants;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieKeyDerivation;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.StoredPartitionedBinaryTrie;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.NodeLoaderMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.NodeUpdaterMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.PartitionedBinaryTrieFactory;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.hash.TrieHasher;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.reference.BinaryTrie;

import java.util.HashMap;
import java.util.Map;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Every EIP-8297 embedding section through the stored trie, against {@link BinaryTrie}. */
class Eip8297EmbeddingSectionsTest {

  private static final Bytes32 ADDRESS_A =
      Bytes32.fromHexString("0x000000000000000000000000abcdefabcdefabcdefabcdefabcdefabcdefabcd");
  private static final Bytes32 ADDRESS_B =
      Bytes32.fromHexString("0x0000000000000000000000001234567890123456789012345678901234567890");
  private static final Bytes32 CODE_HASH =
      TrieHasher.blake3Hash(Bytes.wrap("some code".getBytes(US_ASCII)));

  private enum Section {
    BASIC_DATA(
        TrieKeyDerivation.getTreeKeyForBasicData(ADDRESS_A),
        BasicDataEncoder.encodeBasicData(10, 20, UInt256.valueOf(999))),
    CODE_HASH_LEAF(TrieKeyDerivation.getTreeKeyForCodeHash(ADDRESS_A), CODE_HASH),
    DELEGATION(
        TrieKeyDerivation.getTreeKeyForDelegation(ADDRESS_A),
        DelegationEncoder.encodeDelegation(Bytes.repeat((byte) 0xbb, 20))),
    HEADER_STORAGE(slotKey(5), Bytes32.repeat((byte) 0x05)),
    LAST_HEADER_STORAGE(slotKey(63), Bytes32.repeat((byte) 0x3f)),
    FIRST_OVERFLOW_STORAGE(slotKey(64), Bytes32.repeat((byte) 0x40)),
    OVERFLOW_STORAGE(slotKey(1000), Bytes32.repeat((byte) 0xe8)),
    CODE_CHUNK(
        TrieKeyDerivation.getTreeKeyForCodeChunk(CODE_HASH, 5),
        CodeChunkifier.chunkifyCode(Bytes.fromHexString("0x010203")).getFirst()),
    LARGE_CODE_CHUNK(
        TrieKeyDerivation.getTreeKeyForCodeChunk(CODE_HASH, 300), Bytes32.repeat((byte) 0xac));

    private final Bytes key;
    private final Bytes32 value;

    Section(final Bytes key, final Bytes32 value) {
      this.key = key;
      this.value = value;
    }

    private static Bytes slotKey(final long slot) {
      return TrieKeyDerivation.getTreeKeyForStorageSlot(ADDRESS_A, UInt256.valueOf(slot));
    }
  }

  private final NodeUpdaterMock nodeUpdater = new NodeUpdaterMock();
  private final PartitionedBinaryTrieFactory factory =
      new PartitionedBinaryTrieFactory(new NodeLoaderMock(nodeUpdater));

  @ParameterizedTest
  @EnumSource(Section.class)
  void sectionSurvivesCommitAndRemoval(final Section section) {
    final StoredPartitionedBinaryTrie trie = factory.create();
    trie.put(section.key, section.value);
    trie.commit(nodeUpdater);

    final StoredPartitionedBinaryTrie reloaded = factory.create();
    assertThat(reloaded.get(section.key)).contains(section.value);
    assertThat(reloaded.getRootHash()).isEqualTo(specRoot(Map.of(section.key, section.value)));

    reloaded.remove(section.key);
    reloaded.commit(nodeUpdater);
    assertThat(factory.create().getRootHash()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);
  }

  @Test
  void sectionsOfTwoAccountsShareTheTrie() {
    final Map<Bytes, Bytes32> entries = new HashMap<>();
    for (final Section section : Section.values()) {
      entries.put(section.key, section.value);
    }
    entries.put(
        TrieKeyDerivation.getTreeKeyForBasicData(ADDRESS_B),
        BasicDataEncoder.encodeBasicData(2, 0, UInt256.valueOf(2)));
    final StoredPartitionedBinaryTrie trie = factory.create();
    entries.forEach(trie::put);
    trie.commit(nodeUpdater);

    // Removing each section in turn leaves the others intact.
    for (final Section section : Section.values()) {
      final StoredPartitionedBinaryTrie reloaded = factory.create();
      assertThat(reloaded.getRootHash()).isEqualTo(specRoot(entries));
      entries.forEach((key, value) -> assertThat(reloaded.get(key)).contains(value));

      reloaded.remove(section.key);
      reloaded.commit(nodeUpdater);
      entries.remove(section.key);
    }
    assertThat(factory.create().getRootHash()).isEqualTo(specRoot(entries));
  }

  private static Bytes32 specRoot(final Map<Bytes, Bytes32> entries) {
    final BinaryTrie spec = new BinaryTrie();
    entries.forEach(spec::put);
    return spec.root();
  }
}
