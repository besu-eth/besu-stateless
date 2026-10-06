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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.internal.bytes;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.hash.BitUtils;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.hash.PrefixEncoder;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.hash.TrieHasher;

import java.util.Random;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;

/**
 * Packed bit operations and node hashes, against the reference implementation: {@link BitUtils}
 * (one bit per byte), {@link PrefixEncoder} and {@link TrieHasher}.
 */
class ByteTrieOpsTest {

  private static final int[] PREFIX_LENGTHS = {0, 3, 8, 255, 256, 264, 65535};

  @Test
  void bitAtMatchesBitUtils() {
    final Bytes key = Bytes.fromHexString("0xa5f0");
    final byte[] keyBytes = key.toArrayUnsafe();
    final Bytes specBits = BitUtils.bytesToBitList(key);
    for (int i = 0; i < specBits.size(); i++) {
      assertThat(ByteTrieOps.bitAt(keyBytes, i)).isEqualTo((byte) BitUtils.bitAt(specBits, i));
    }
  }

  @Test
  void sliceBitsMatchesTheReferenceForEveryRange() {
    final Bytes input = Bytes.wrap(randomBytes(new Random(1), 8));
    final Bytes bits = BitUtils.bytesToBitList(input);
    for (int from = 0; from <= 64; from++) {
      for (int to = from; to <= 64; to++) {
        assertThat(ByteTrieOps.sliceBits(input.toArrayUnsafe(), from, to))
            .as("bits [%d, %d)", from, to)
            .isEqualTo(pack(bits.slice(from, to - from)));
      }
    }
  }

  @Test
  void orBitsMatchesTheReferenceAtEveryOffset() {
    final Bytes source = Bytes.wrap(randomBytes(new Random(2), 3));
    final Bytes sourceBits = BitUtils.bytesToBitList(source);
    for (int length = 0; length <= 24; length++) {
      for (int offset = 0; offset < 16; offset++) {
        // Exactly as long as the bits written, and junk past the length in the source.
        final byte[] target = new byte[(offset + length + 7) / 8];
        ByteTrieOps.orBits(source.toArrayUnsafe(), length, target, offset);
        final Bytes expected =
            Bytes.concatenate(Bytes.wrap(new byte[offset]), sourceBits.slice(0, length));
        assertThat(target).as("%d bits at %d", length, offset).isEqualTo(pack(expected));
      }
    }
  }

  @Test
  void concatBitsJoinsThroughTheSplitBit() {
    final byte[] a = {(byte) 0b1010_0000};
    final byte[] b = {(byte) 0b1100_0000};
    // 101 + 1 + 11
    assertThat(ByteTrieOps.concatBits(a, 3, 1, b, 2)).containsExactly((byte) 0b1011_1100);
    // Bits past the lengths are ignored: 111 + 0 + 11
    assertThat(ByteTrieOps.concatBits(new byte[] {(byte) 0xFF}, 3, 0, new byte[] {(byte) 0xFF}, 2))
        .containsExactly((byte) 0b1110_1100);
    // Two empty prefixes: the split bit alone.
    assertThat(ByteTrieOps.concatBits(new byte[0], 0, 1, new byte[0], 0))
        .containsExactly((byte) 0x80);
    assertThat(ByteTrieOps.concatBits(new byte[0], 0, 0, new byte[0], 0)).containsExactly(0);
  }

  @Test
  void encodeBitPrefixMatchesTheReference() {
    final Random random = new Random(3);
    for (final int length : PREFIX_LENGTHS) {
      // Random bits, set past the length too: they must not be written.
      final byte[] prefix = randomBytes(random, (length + 7) / 8 + 1);
      final byte[] out = new byte[5 + 2 + (length + 7) / 8];

      final int written = ByteTrieOps.encodeBitPrefix(prefix, length, out, 5);

      assertThat(written).isEqualTo(2 + (length + 7) / 8);
      assertThat(Bytes.wrap(out, 5, written))
          .as("prefix of %d bits", length)
          .isEqualTo(PrefixEncoder.encodeBitPrefix(bits(prefix, length)));
    }
  }

  @Test
  void hashesMatchTheReference() {
    final Random random = new Random(4);
    final byte[] key = randomBytes(random, 34);
    final byte[] value = randomBytes(random, 32);
    assertThat(Bytes.wrap(ByteTrieOps.leafHash(key, 34, value)))
        .isEqualTo(
            TrieHasher.blake3Hash(
                Bytes.concatenate(Bytes.of(0), Bytes.wrap(key), Bytes.wrap(value))));

    final byte[] left = randomBytes(random, 32);
    final byte[] right = randomBytes(random, 32);
    for (final int length : PREFIX_LENGTHS) {
      final byte[] prefix = randomBytes(random, (length + 7) / 8 + 1);
      assertThat(Bytes.wrap(ByteTrieOps.branchHash(prefix, length, left, right)))
          .as("prefix of %d bits", length)
          .isEqualTo(
              TrieHasher.blake3Hash(
                  Bytes.concatenate(
                      Bytes.of(1),
                      PrefixEncoder.encodeBitPrefix(bits(prefix, length)),
                      Bytes.wrap(left),
                      Bytes.wrap(right))));
    }
  }

  @Test
  void hashesAreNewArrays() {
    final byte[] key = Bytes.fromHexString("0x42").toArrayUnsafe();
    final byte[] value = Bytes32.repeat((byte) 0x11).toArrayUnsafe();
    assertThat(ByteTrieOps.leafHash(key, 1, value))
        .isNotSameAs(ByteTrieOps.leafHash(key, 1, value));
    assertThat(ByteTrieOps.branchHash(new byte[0], 0, value, value))
        .isNotSameAs(ByteTrieOps.branchHash(new byte[0], 0, value, value));
  }

  @Test
  void keysEqualComparesLengthsAndBytes() {
    final byte[] a = Bytes.fromHexString("0x0102").toArrayUnsafe();
    final byte[] b = Bytes.fromHexString("0x0102").toArrayUnsafe();
    final byte[] c = Bytes.fromHexString("0x0103").toArrayUnsafe();
    final byte[] longer = Bytes.fromHexString("0x010299").toArrayUnsafe();
    assertThat(ByteTrieOps.keysEqual(a, 2, b, 2)).isTrue();
    assertThat(ByteTrieOps.keysEqual(a, 2, c, 2)).isFalse();
    assertThat(ByteTrieOps.keysEqual(a, 1, b, 2)).isFalse();
    // Only the first keyLen bytes of a buffer count.
    assertThat(ByteTrieOps.keysEqual(longer, 2, a, 2)).isTrue();
  }

  /** The first {@code length} bits of {@code packed}, one per byte. */
  private static Bytes bits(final byte[] packed, final int length) {
    return BitUtils.bytesToBitList(Bytes.wrap(packed)).slice(0, length);
  }

  /** One bit per byte, packed MSB-first and zero-padded. */
  private static byte[] pack(final Bytes bits) {
    final byte[] packed = new byte[(bits.size() + 7) / 8];
    for (int i = 0; i < bits.size(); i++) {
      if (BitUtils.bitAt(bits, i) == 1) {
        packed[i / 8] |= (byte) (0x80 >>> (i % 8));
      }
    }
    return packed;
  }

  private static byte[] randomBytes(final Random random, final int length) {
    final byte[] bytes = new byte[length];
    random.nextBytes(bytes);
    return bytes;
  }
}
