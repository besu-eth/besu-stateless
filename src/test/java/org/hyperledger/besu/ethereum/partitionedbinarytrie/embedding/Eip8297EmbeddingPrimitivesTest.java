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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.codec.BasicDataEncoder;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.codec.CodeChunkifier;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.codec.DelegationEncoder;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieKeyDerivation;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.params.EmbeddingParameters;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.hash.TrieHasher;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.bouncycastle.crypto.digests.Blake3Digest;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for EIP-8297 embedding primitives: key derivation, code chunking, and basic-data
 * encoding.
 *
 * <p>Layer: embedding (codec and keys packages). Uses pinned test vectors and independent BLAKE3
 * computations; no full trie oracle.
 */
class Eip8297EmbeddingPrimitivesTest {

  private static final Bytes32 ADDRESS =
      Bytes32.fromHexString("000000000000000000000000aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

  private static Bytes blake3Bytes(final Bytes data) {
    return TrieHasher.blake3Hash(data);
  }

  private static byte[] headerStem(final Bytes32 address) {
    final Blake3Digest digest = new Blake3Digest(256);
    digest.update(address.toArrayUnsafe(), 0, 32);
    final byte[] hash = new byte[32];
    digest.doFinal(hash, 0);
    final byte[] stem = new byte[33];
    stem[0] = 0;
    System.arraycopy(hash, 0, stem, 1, 32);
    return stem;
  }

  @Test
  void embeddingConstants() {
    assertThat(EmbeddingParameters.BASIC_DATA_LEAF_KEY).isEqualTo(0);
    assertThat(EmbeddingParameters.CODE_HASH_LEAF_KEY).isEqualTo(1);
    assertThat(EmbeddingParameters.DELEGATION_LEAF_KEY).isEqualTo(2);
    assertThat(EmbeddingParameters.DELEGATION_CODE_SIZE).isEqualTo(23);
    assertThat(EmbeddingParameters.HEADER_STORAGE_OFFSET).isEqualTo(64);
    assertThat(EmbeddingParameters.HEADER_STORAGE_SLOTS).isEqualTo(64);
    assertThat(EmbeddingParameters.STEM_SUBTREE_WIDTH).isEqualTo(256);
    assertThat(EmbeddingParameters.ACCOUNT_ZONE).isEqualTo(0);
    assertThat(EmbeddingParameters.CODE_ZONE).isEqualTo(1);
    assertThat(EmbeddingParameters.STORAGE_ZONE).isEqualTo(255);
    assertThat(EmbeddingParameters.ACCOUNT_KEY_LENGTH).isEqualTo(34);
    assertThat(EmbeddingParameters.CODE_KEY_LENGTH).isEqualTo(34);
    assertThat(EmbeddingParameters.STORAGE_KEY_LENGTH).isEqualTo(66);
  }

  @Test
  void address20ToAddress32PrependsZeros() {
    final Bytes address = Bytes.repeat((byte) 0xAA, 20);
    assertThat(TrieKeyDerivation.address20ToAddress32(address)).isEqualTo(ADDRESS);
  }

  @Test
  void address20ToAddress32RejectsWrongLength() {
    assertThatThrownBy(() -> TrieKeyDerivation.address20ToAddress32(Bytes.repeat((byte) 0xAA, 19)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TrieKeyDerivation.address20ToAddress32(Bytes.repeat((byte) 0xAA, 21)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void emptyCodeHashIsKeccakOfEmpty() {
    assertThat(TrieKeyDerivation.EMPTY_CODE_HASH)
        .isEqualTo(
            Bytes32.fromHexString(
                "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470"));
  }

  @Test
  void keyHashIsBlake3() {
    assertThat(TrieKeyDerivation.keyHash(ADDRESS)).isEqualTo(blake3Bytes(ADDRESS));
  }

  @Test
  void getTreeKeyConcatenatesItsThreeParts() {
    final Bytes digest =
        blake3Bytes(
            Bytes.of((byte) 'd', (byte) 'i', (byte) 'g', (byte) 'e', (byte) 's', (byte) 't'));
    for (final int zone : new int[] {0, 1, 2, 254, 255}) {
      final Bytes key = TrieKeyDerivation.getTreeKey(zone, digest, 7);
      assertThat(key.size()).isEqualTo(34);
      assertThat(key)
          .isEqualTo(Bytes.concatenate(Bytes.of((byte) zone), digest, Bytes.of((byte) 7)));
    }
  }

  @Test
  void getTreeKeyRejectsZoneOutsideOneByte() {
    final Bytes digest = blake3Bytes(Bytes.of((byte) 'x'));

    assertThatThrownBy(() -> TrieKeyDerivation.getTreeKey(-1, digest, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TrieKeyDerivation.getTreeKey(256, digest, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void headerSubIndexWiderThanOneByteIsRejected() {
    assertThatThrownBy(() -> TrieKeyDerivation.getTreeKeyForHeader(ADDRESS, 256))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void headerKeyVectors() {
    final Bytes stem = Bytes.wrap(headerStem(ADDRESS));
    assertThat(TrieKeyDerivation.getTreeKeyForBasicData(ADDRESS))
        .isEqualTo(Bytes.concatenate(stem, Bytes.of((byte) 0)));
    assertThat(TrieKeyDerivation.getTreeKeyForCodeHash(ADDRESS))
        .isEqualTo(Bytes.concatenate(stem, Bytes.of((byte) 1)));
    assertThat(TrieKeyDerivation.getTreeKeyForDelegation(ADDRESS))
        .isEqualTo(Bytes.concatenate(stem, Bytes.of((byte) 2)));
    assertThat(TrieKeyDerivation.getTreeKeyForBasicData(ADDRESS).size()).isEqualTo(34);
  }

  @Test
  void delegationLeafValueVector() {
    final Bytes target = Bytes.fromHexString("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    assertThat(DelegationEncoder.encodeDelegation(target))
        .isEqualTo(
            Bytes32.fromHexString(
                "ef0100bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb000000000000000000"));
  }

  @Test
  void encodeDelegationRejectsWrongTargetLength() {
    assertThatThrownBy(() -> DelegationEncoder.encodeDelegation(Bytes.repeat((byte) 0xBB, 19)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DelegationEncoder.encodeDelegation(Bytes.repeat((byte) 0xBB, 21)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void storageSlotInHeaderVector() {
    final Bytes key = TrieKeyDerivation.getTreeKeyForStorageSlot(ADDRESS, UInt256.valueOf(5));
    assertThat(key)
        .isEqualTo(Bytes.concatenate(Bytes.wrap(headerStem(ADDRESS)), Bytes.of((byte) 0x45)));
  }

  @Test
  void storageSlotOverflowVector() {
    final Blake3Digest digest = new Blake3Digest(256);
    digest.update(ADDRESS.toArrayUnsafe(), 0, 32);
    final byte[] prefix = new byte[32];
    digest.doFinal(prefix, 0);

    final Blake3Digest suffixDigest = new Blake3Digest(256);
    final byte[] treeIndexBytes = new byte[32];
    treeIndexBytes[31] = 3;
    suffixDigest.update(ADDRESS.toArrayUnsafe(), 0, 32);
    suffixDigest.update(treeIndexBytes, 0, 32);
    final byte[] suffix = new byte[32];
    suffixDigest.doFinal(suffix, 0);

    final Bytes stem =
        Bytes.concatenate(Bytes.of((byte) 0xFF), Bytes.wrap(prefix), Bytes.wrap(suffix));
    final Bytes key = TrieKeyDerivation.getTreeKeyForStorageSlot(ADDRESS, UInt256.valueOf(1000));
    assertThat(key).isEqualTo(Bytes.concatenate(stem, Bytes.of((byte) 0xE8)));
    assertThat(key.size()).isEqualTo(66);
    assertThat(key.get(0)).isEqualTo((byte) 0xFF);
  }

  @Test
  void storageSlotBoundaryIs64() {
    assertThat(TrieKeyDerivation.getTreeKeyForStorageSlot(ADDRESS, UInt256.valueOf(63)))
        .isEqualTo(Bytes.concatenate(Bytes.wrap(headerStem(ADDRESS)), Bytes.of((byte) 127)));

    final Bytes overflowStem =
        Bytes.concatenate(
            Bytes.of((byte) 0xFF),
            blake3Bytes(ADDRESS),
            TrieKeyDerivation.keyHash(Bytes.concatenate(ADDRESS, Bytes32.ZERO)));
    assertThat(TrieKeyDerivation.getTreeKeyForStorageSlot(ADDRESS, UInt256.valueOf(64)))
        .isEqualTo(Bytes.concatenate(overflowStem, Bytes.of((byte) 64)));
  }

  @Test
  void codeChunkVector() {
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
    final Bytes digest = TrieKeyDerivation.keyHash(Bytes.concatenate(codeHash, Bytes32.ZERO));
    final Bytes stem = Bytes.concatenate(Bytes.of((byte) 1), digest);

    final Bytes key = TrieKeyDerivation.getTreeKeyForCodeChunk(codeHash, 5);
    assertThat(key).isEqualTo(Bytes.concatenate(stem, Bytes.of((byte) 0x05)));
    assertThat(key.size()).isEqualTo(34);
    assertThat(key.get(0)).isEqualTo((byte) 1);
  }

  @Test
  void codeChunkLargeIndexVector() {
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
    final Bytes digest =
        TrieKeyDerivation.keyHash(Bytes.concatenate(codeHash, Bytes32.leftPad(UInt256.valueOf(1))));
    final Bytes stem = Bytes.concatenate(Bytes.of((byte) 1), digest);

    final Bytes key = TrieKeyDerivation.getTreeKeyForCodeChunk(codeHash, 300);
    assertThat(key).isEqualTo(Bytes.concatenate(stem, Bytes.of((byte) 0x2C)));
    assertThat(key.size()).isEqualTo(34);
  }

  @Test
  void negativeCodeChunkIndexIsRejected() {
    assertThatThrownBy(
            () -> TrieKeyDerivation.getTreeKeyForCodeChunk(Bytes32.repeat((byte) 0x01), -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void codeIsContentAddressed() {
    final Bytes32 codeHash =
        TrieHasher.blake3Hash(
            Bytes.of(
                (byte) 's',
                (byte) 'h',
                (byte) 'a',
                (byte) 'r',
                (byte) 'e',
                (byte) 'd',
                (byte) ' ',
                (byte) 'b',
                (byte) 'y',
                (byte) 't',
                (byte) 'e',
                (byte) 'c',
                (byte) 'o',
                (byte) 'd',
                (byte) 'e'));
    final Bytes32 otherCodeHash = TrieHasher.blake3Hash(Bytes.of((byte) 'x'));

    assertThat(TrieKeyDerivation.getTreeKeyForCodeChunk(codeHash, 5).get(0))
        .isEqualTo((byte) EmbeddingParameters.CODE_ZONE);
    assertThat(TrieKeyDerivation.getTreeKeyForCodeChunk(codeHash, 5))
        .isNotEqualTo(TrieKeyDerivation.getTreeKeyForCodeChunk(otherCodeHash, 5));
    assertThat(TrieKeyDerivation.getTreeKeyForCodeChunk(codeHash, 200))
        .isNotEqualTo(TrieKeyDerivation.getTreeKeyForCodeChunk(otherCodeHash, 200));
  }

  @Test
  void chunkifyEmptyCode() {
    assertThat(CodeChunkifier.chunkifyCode(Bytes.EMPTY)).isEmpty();
  }

  @Test
  void chunkifyCodeWithoutPushesPadsTo31Bytes() {
    final Bytes code = Bytes.fromHexString("010203");
    assertThat(CodeChunkifier.chunkifyCode(code))
        .containsExactly(
            Bytes32.fromHexString(
                "0001020300000000000000000000000000000000000000000000000000000000"));
  }

  @Test
  void chunkifyCodeEipExample() {
    final Bytes code =
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
                (byte) 0x52));
    final var chunks = CodeChunkifier.chunkifyCode(code);
    assertThat(chunks).hasSize(2);
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

  @Test
  void chunkifyCodeCapsLeadingPushDataCountAt31() {
    final Bytes code =
        Bytes.concatenate(
            Bytes.repeat((byte) 0, 30),
            Bytes.fromHexString("7f"),
            Bytes.wrap(
                new byte[] {
                  1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23,
                  24, 25, 26, 27, 28, 29, 30, 31, 32
                }));
    final var chunks = CodeChunkifier.chunkifyCode(code);
    assertThat(chunks).hasSize(3);
    assertThat(chunks.get(0))
        .isEqualTo(
            Bytes32.fromHexString(
                "000000000000000000000000000000000000000000000000000000000000007f"));
    assertThat(chunks.get(1).get(0)).isEqualTo((byte) 31);
    assertThat(chunks.get(2).get(0)).isEqualTo((byte) 1);
  }

  @Test
  void chunkifyCodePushDataTruncatedByEndOfCode() {
    final Bytes code = Bytes.of((byte) 0x7F);
    final byte[] expected = new byte[32];
    expected[1] = (byte) 0x7F;
    assertThat(CodeChunkifier.chunkifyCode(code)).containsExactly(Bytes32.wrap(expected));
  }

  @Test
  void encodeBasicDataLayout() {
    final Bytes32 value =
        BasicDataEncoder.encodeBasicData(
            0x11223344L,
            0x5566778899aabbccl,
            UInt256.fromHexString("0123456789abcdef0123456789abcdef"));
    assertThat(value.size()).isEqualTo(32);
    assertThat(value.get(0)).isZero();
    assertThat(value.slice(1, 3)).isEqualTo(Bytes.repeat((byte) 0, 3));
    assertThat(value.slice(4, 4)).isEqualTo(Bytes.fromHexString("11223344"));
    assertThat(value.slice(8, 8)).isEqualTo(Bytes.fromHexString("5566778899aabbcc"));
    assertThat(value.slice(16, 16))
        .isEqualTo(Bytes.fromHexString("0123456789abcdef0123456789abcdef"));
  }
}
