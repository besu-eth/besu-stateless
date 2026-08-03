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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.reference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.codec.BasicDataEncoder;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.codec.CodeChunkifier;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieConstants;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieKeyDerivation;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.PartitionedBinaryTrie;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.hash.TrieHasher;

import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.Test;

/**
 * Cross-client and execution-specs interop vectors for EIP-8297.
 *
 * <p>Layer: interop (embedding + trie storage vs reference). Neutral oracle: {@code
 * ethereum.binary_trie} on the {@code bin-trie} branch of execution-specs
 * (kevaundray/execution-specs#9). This library follows that branch/leaf model with domain-tagged
 * BLAKE3 preimages and 34/66-byte variable-length embedding keys.
 *
 * <p><b>Nethermind PR 12573 is not directly comparable for state roots.</b> Its production trie
 * uses a stem-trie + leaf-blob layout ({@code EipReferenceTree}, {@code PbtPartitionRoots}) with:
 *
 * <ul>
 *   <li>32-byte keys (31-byte stem + sub-index) instead of 34/66-byte variable-length keys
 *   <li>Stem-node merkleization ({@code blake3(stem || 0 || subtree)}) instead of tagged
 *       branch/leaf preimages
 *   <li>Partition-root folding (account, code, storage) instead of a single monolithic trie root
 * </ul>
 *
 * <p>Key derivation, basic-data encoding, and code chunkification <em>do</em> align with the EIP
 * test vectors that both clients pin independently ({@code KeyDerivationTests} in Nethermind,
 * {@link BinaryTrieReferenceVectorsTest} here). Root comparison requires spec alignment on trie
 * structure first.
 */
class PartitionedBinaryTrieInteropTest {

  private static final Bytes32 ADDRESS =
      Bytes32.fromHexString("000000000000000000000000aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

  @Test
  void emptyTrieRootMatchesExecutionSpecs() {
    assertThat(new BinaryTrie().root()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);
    assertThat(new PartitionedBinaryTrie().getRootHash()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);
  }

  @Test
  void executionSpecsSingleLeafRoot() {
    final Bytes key =
        Bytes.concatenate(Bytes.of((byte) 0), Bytes.repeat((byte) 0x42, 32), Bytes.of((byte) 0x07));
    final Bytes32 value = Bytes32.repeat((byte) 0x11);

    final BinaryTrie trie = new BinaryTrie();
    trie.put(key, value);

    assertThat(trie.root())
        .isEqualTo(
            Bytes32.fromHexString(
                "11a3af6a4865f503813b05f45e42a2a1cc1b1498cf809e9d1446c1ed1f28b19f"));

    final byte[] keyBytes = key.toArrayUnsafe();
    final byte[] valueBytes = value.toArrayUnsafe();
    final PartitionedBinaryTrie storedTrie = new PartitionedBinaryTrie();
    storedTrie.put(keyBytes, keyBytes.length, valueBytes);
    assertThat(storedTrie.getRootHash()).isEqualTo(trie.root());
  }

  @Test
  void executionSpecsStemSplitRoot() {
    final byte[] stem = new byte[33];
    stem[0] = 0;
    for (int i = 1; i < 33; i++) {
      stem[i] = 0x42;
    }
    final Bytes lowKey = Bytes.concatenate(Bytes.wrap(stem), Bytes.of((byte) 0));
    final Bytes highKey = Bytes.concatenate(Bytes.wrap(stem), Bytes.of((byte) 0xFF));

    final BinaryTrie trie = new BinaryTrie();
    trie.put(lowKey, Bytes32.repeat((byte) 0x01));
    trie.put(highKey, Bytes32.repeat((byte) 0x02));

    assertThat(trie.root())
        .isEqualTo(
            Bytes32.fromHexString(
                "236789e96c40914f04ac2418aca5a8e71540e78a355326ad26aab3db9107016d"));
  }

  @Test
  void prefixFreeViolationIsRejectedWhenExistingKeyIsShorter() {
    final PartitionedBinaryTrie trie = new PartitionedBinaryTrie();
    trie.put(Bytes.fromHexString("0xaa"), Bytes32.repeat((byte) 0x01));

    assertThatThrownBy(() -> trie.put(Bytes.fromHexString("0xaabb"), Bytes32.repeat((byte) 0x02)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void prefixFreeViolationIsRejectedWhenInsertedKeyIsShorter() {
    final PartitionedBinaryTrie trie = new PartitionedBinaryTrie();
    trie.put(Bytes.fromHexString("0xaa00"), Bytes32.repeat((byte) 0x01));
    trie.put(Bytes.fromHexString("0xaa80"), Bytes32.repeat((byte) 0x02));

    assertThatThrownBy(() -> trie.put(Bytes.fromHexString("0xaa"), Bytes32.repeat((byte) 0x03)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void prefixFreeViolationIsRejectedForDeferredPut() {
    final PartitionedBinaryTrie trie = new PartitionedBinaryTrie();
    trie.put(Bytes.fromHexString("0xaa"), Bytes32.repeat((byte) 0x01));

    assertThatThrownBy(
            () ->
                trie.putDeferred(
                    Bytes.fromHexString("0xaabb"),
                    existing -> Optional.of(Bytes32.repeat((byte) 0x02))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void lookupOfPrefixKeyIsAbsentRatherThanOutOfBounds() {
    final PartitionedBinaryTrie trie = new PartitionedBinaryTrie();
    trie.put(Bytes.fromHexString("0xaa00"), Bytes32.repeat((byte) 0x01));
    trie.put(Bytes.fromHexString("0xaa80"), Bytes32.repeat((byte) 0x02));

    assertThat(trie.get(Bytes.fromHexString("0xaa"))).isEmpty();
    assertThat(trie.getValueWithProof(Bytes.fromHexString("0xaa")).getValue()).isEmpty();
  }

  @Test
  void keyLengthCannotExceedProvidedBytes() {
    final PartitionedBinaryTrie trie = new PartitionedBinaryTrie();
    final byte[] key = Bytes.fromHexString("0xaa").toArrayUnsafe();
    final byte[] value = Bytes32.repeat((byte) 0x01).toArrayUnsafe();

    assertThatThrownBy(() -> trie.put(key, key.length + 1, value))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> trie.get(key, key.length + 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> trie.remove(key, key.length + 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void accountBasicDataAndCodeHashRoot() {
    final Bytes basicDataKey = TrieKeyDerivation.getTreeKeyForBasicData(ADDRESS);
    final Bytes codeHashKey = TrieKeyDerivation.getTreeKeyForCodeHash(ADDRESS);
    final Bytes32 basicData = BasicDataEncoder.encodeBasicData(1, 42, UInt256.valueOf(1000));
    final Bytes32 codeHash = TrieKeyDerivation.EMPTY_CODE_HASH;

    final BinaryTrie specTrie = new BinaryTrie();
    specTrie.put(basicDataKey, basicData);
    specTrie.put(codeHashKey, codeHash);

    final PartitionedBinaryTrie storedTrie = new PartitionedBinaryTrie();
    putEntry(storedTrie, basicDataKey, basicData);
    putEntry(storedTrie, codeHashKey, codeHash);

    assertThat(storedTrie.getRootHash()).isEqualTo(specTrie.root());
  }

  @Test
  void multiKeyEmbeddingScenarioRoot() {
    final Bytes basicDataKey = TrieKeyDerivation.getTreeKeyForBasicData(ADDRESS);
    final Bytes storageKey =
        TrieKeyDerivation.getTreeKeyForStorageSlot(ADDRESS, UInt256.valueOf(5));
    final Bytes32 basicData = BasicDataEncoder.encodeBasicData(0, 0, UInt256.ZERO);
    final Bytes32 slotValue =
        Bytes32.fromHexString("00000000000000000000000000000000000000000000000000000000deadbeef");

    final BinaryTrie specTrie = new BinaryTrie();
    specTrie.put(basicDataKey, basicData);
    specTrie.put(storageKey, slotValue);

    final PartitionedBinaryTrie storedTrie = new PartitionedBinaryTrie();
    putEntry(storedTrie, basicDataKey, basicData);
    putEntry(storedTrie, storageKey, slotValue);

    assertThat(storedTrie.getRootHash()).isEqualTo(specTrie.root());
  }

  private static void putEntry(
      final PartitionedBinaryTrie trie, final Bytes key, final Bytes32 value) {
    final byte[] keyBytes = key.toArrayUnsafe();
    trie.put(keyBytes, keyBytes.length, value.toArrayUnsafe());
  }

  @Test
  void sharedEipEmbeddingVectorsAlignWithNethermindKeyDerivation() {
    // Pinned independently in Nethermind KeyDerivationTests and execution-specs
    // test_binary_trie_embedding.py — stems/sub-indices only, not trie roots.
    assertThat(TrieKeyDerivation.getTreeKeyForBasicData(ADDRESS))
        .isEqualTo(
            Bytes.fromHexString(
                "00d9ae2d236f8713a5bf808cda488167a56cc97e4b83006f42b1c06c0c3f053bbf00"));
    assertThat(TrieKeyDerivation.getTreeKeyForStorageSlot(ADDRESS, UInt256.valueOf(5)))
        .isEqualTo(
            Bytes.fromHexString(
                "00d9ae2d236f8713a5bf808cda488167a56cc97e4b83006f42b1c06c0c3f053bbf45"));

    final Bytes storage1000Key =
        TrieKeyDerivation.getTreeKeyForStorageSlot(ADDRESS, UInt256.valueOf(1000));
    assertThat(storage1000Key.slice(storage1000Key.size() - 4, 4))
        .isEqualTo(Bytes.fromHexString("7650f9e8"));

    final Bytes32 codeHash =
        TrieHasher.blake3Hash(
            Bytes.of(
                (byte) 's',
                (byte) 'o',
                (byte) 'm',
                (byte) 'e',
                (byte) ' ',
                (byte) 'c',
                (byte) 'o',
                (byte) 'd',
                (byte) 'e'));
    final Bytes codeChunk300Key = TrieKeyDerivation.getTreeKeyForCodeChunk(ADDRESS, codeHash, 300);
    assertThat(codeChunk300Key.slice(codeChunk300Key.size() - 4, 4))
        .isEqualTo(Bytes.fromHexString("a4ecadac"));

    assertThat(
            BasicDataEncoder.encodeBasicData(
                0x11223344L,
                0x5566778899aabbccl,
                UInt256.fromHexString("0123456789abcdef0123456789abcdef")))
        .isEqualTo(
            Bytes32.fromHexString(
                "00000000112233445566778899aabbcc0123456789abcdef0123456789abcdef"));

    final var chunks =
        CodeChunkifier.chunkifyCode(
            Bytes.concatenate(
                Bytes.repeat((byte) 0, 28),
                Bytes.of(
                    (byte) 0x63,
                    (byte) 99,
                    (byte) 98,
                    (byte) 97,
                    (byte) 96,
                    (byte) 0x60,
                    (byte) 128,
                    (byte) 0x52)));
    assertThat(chunks.get(0))
        .isEqualTo(
            Bytes32.fromHexString(
                "0000000000000000000000000000000000000000000000000000000000636362"));
    assertThat(chunks.get(1))
        .isEqualTo(
            Bytes32.wrap(
                new byte[] {
                  2,
                  97,
                  96,
                  (byte) 0x60,
                  (byte) 128,
                  (byte) 0x52,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0
                }));
  }
}
