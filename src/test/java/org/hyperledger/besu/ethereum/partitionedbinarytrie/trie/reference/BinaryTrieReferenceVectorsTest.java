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

import org.hyperledger.besu.ethereum.partitionedbinarytrie.embedding.codec.AccountBasicDataEncoder;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.embedding.codec.CodeChunkifier;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.embedding.keys.Eip8297TreeKeyDerivation;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.hash.TrieHasher;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.Test;

/**
 * Exact root-hash vectors from the Python {@code ethereum.binary_trie} reference.
 *
 * <p>Layer: reference oracle ({@link BinaryTrie} with embedding codecs). Hand-computed expected
 * roots for basic-data, code-chunk, and variable-length key scenarios.
 */
class BinaryTrieReferenceVectorsTest {

  private static final Bytes32 ADDRESS =
      Bytes32.fromHexString("000000000000000000000000aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

  @Test
  void matchesPythonReferenceVectors() {
    final Bytes key =
        Bytes.concatenate(Bytes.of((byte) 0), Bytes.repeat((byte) 0x42, 32), Bytes.of((byte) 0x07));
    final BinaryTrie trie = new BinaryTrie();
    trie.put(key, Bytes32.repeat((byte) 0x11));
    assertThat(trie.root())
        .isEqualTo(
            Bytes32.fromHexString(
                "11a3af6a4865f503813b05f45e42a2a1cc1b1498cf809e9d1446c1ed1f28b19f"));

    final byte[] stem = new byte[33];
    stem[0] = 0;
    for (int i = 1; i < 33; i++) {
      stem[i] = 0x42;
    }
    final BinaryTrie stemTrie = new BinaryTrie();
    stemTrie.put(Bytes.concatenate(Bytes.wrap(stem), Bytes.of((byte) 0)), Bytes32.repeat((byte) 1));
    stemTrie.put(
        Bytes.concatenate(Bytes.wrap(stem), Bytes.of((byte) 0xFF)), Bytes32.repeat((byte) 2));
    assertThat(stemTrie.root())
        .isEqualTo(
            Bytes32.fromHexString(
                "236789e96c40914f04ac2418aca5a8e71540e78a355326ad26aab3db9107016d"));

    assertThat(Eip8297TreeKeyDerivation.getTreeKeyForBasicData(ADDRESS))
        .isEqualTo(
            Bytes.fromHexString(
                "00d9ae2d236f8713a5bf808cda488167a56cc97e4b83006f42b1c06c0c3f053bbf00"));
    assertThat(Eip8297TreeKeyDerivation.getTreeKeyForStorageSlot(ADDRESS, UInt256.valueOf(5)))
        .isEqualTo(
            Bytes.fromHexString(
                "00d9ae2d236f8713a5bf808cda488167a56cc97e4b83006f42b1c06c0c3f053bbf45"));
    final Bytes storage1000Key =
        Eip8297TreeKeyDerivation.getTreeKeyForStorageSlot(ADDRESS, UInt256.valueOf(1000));
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
    final Bytes codeChunk300Key =
        Eip8297TreeKeyDerivation.getTreeKeyForCodeChunk(ADDRESS, codeHash, 300);
    assertThat(codeChunk300Key.slice(codeChunk300Key.size() - 4, 4))
        .isEqualTo(Bytes.fromHexString("a4ecadac"));

    assertThat(
            AccountBasicDataEncoder.encodeBasicData(
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
