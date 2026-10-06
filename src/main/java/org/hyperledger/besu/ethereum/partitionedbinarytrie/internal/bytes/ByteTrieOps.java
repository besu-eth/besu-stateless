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

import org.hyperledger.besu.ethereum.partitionedbinarytrie.internal.hash.Blake3Hasher;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieConstants;

/**
 * Hot-path trie operations on primitive byte arrays.
 *
 * <p>Thread-local buffers avoid per-operation allocations during stored-trie get/put/hash. Not part
 * of the public API.
 */
public final class ByteTrieOps {

  private static final ThreadLocal<byte[]> PREFIX_PACK_BUFFER =
      ThreadLocal.withInitial(() -> new byte[(1 << 16) / 8 + 2]);

  private ByteTrieOps() {}

  /** Bit {@code index} of {@code key}, MSB first ({@code 0} or {@code 1}). */
  public static byte bitAt(final byte[] key, final int index) {
    return (byte) ((key[index >>> 3] >>> (7 - (index & 7))) & 1);
  }

  /** Sets bit {@code index} of {@code bits}, MSB first. */
  public static void setBit(final byte[] bits, final int index) {
    bits[index >>> 3] |= (byte) (0x80 >>> (index & 7));
  }

  /** ORs bits {@code [0, length)} of {@code src} into {@code dst} from bit {@code offset}. */
  public static void orBits(
      final byte[] src, final int length, final byte[] dst, final int offset) {
    final int shift = offset & 7;
    final int first = offset >>> 3;
    final int bytes = (length + 7) >>> 3;
    for (int i = 0; i < bytes; i++) {
      int value = src[i] & 0xFF;
      if (i == bytes - 1 && (length & 7) != 0) {
        value &= 0xFF << (8 - (length & 7));
      }
      dst[first + i] |= (byte) (value >>> shift);
      if (shift != 0 && first + i + 1 < dst.length) {
        dst[first + i + 1] |= (byte) (value << (8 - shift));
      }
    }
  }

  /** Bits {@code [from, to)} of {@code bits}, packed MSB-first from bit 0 and zero-padded. */
  public static byte[] sliceBits(final byte[] bits, final int from, final int to) {
    final int length = to - from;
    final byte[] out = new byte[(length + 7) >>> 3];
    final int first = from >>> 3;
    final int shift = from & 7;
    for (int i = 0; i < out.length; i++) {
      int value = (bits[first + i] & 0xFF) << shift;
      if (shift != 0 && first + i + 1 < bits.length) {
        value |= (bits[first + i + 1] & 0xFF) >>> (8 - shift);
      }
      out[i] = (byte) value;
    }
    if ((length & 7) != 0) {
      out[out.length - 1] &= (byte) (0xFF << (8 - (length & 7)));
    }
    return out;
  }

  /** Packed {@code a[0, aLen) || bit || b[0, bLen)}, zero-padded. */
  public static byte[] concatBits(
      final byte[] a, final int aLen, final int bit, final byte[] b, final int bLen) {
    final byte[] out = new byte[(aLen + bLen + 8) >>> 3];
    System.arraycopy(a, 0, out, 0, (aLen + 7) >>> 3);
    if ((aLen & 7) != 0) {
      out[aLen >>> 3] &= (byte) (0xFF << (8 - (aLen & 7)));
    }
    if (bit == 1) {
      setBit(out, aLen);
    }
    for (int i = 0; i < bLen; i++) {
      if (bitAt(b, i) == 1) {
        setBit(out, aLen + 1 + i);
      }
    }
    return out;
  }

  /**
   * Writes the EIP-8297 encoding of a packed prefix, its length in bits then its bits, into {@code
   * out} and returns the number of bytes written.
   */
  public static int encodeBitPrefix(
      final byte[] prefix, final int prefixLen, final byte[] out, final int outOff) {
    out[outOff] = (byte) (prefixLen >> 8);
    out[outOff + 1] = (byte) prefixLen;
    final int packedLen = (prefixLen + 7) >>> 3;
    System.arraycopy(prefix, 0, out, outOff + 2, packedLen);
    if ((prefixLen & 7) != 0) {
      out[outOff + 1 + packedLen] &= (byte) (0xFF << (8 - (prefixLen & 7)));
    }
    return 2 + packedLen;
  }

  /** Compares two key byte slices for equality. */
  public static boolean keysEqual(final byte[] a, final int aLen, final byte[] b, final int bLen) {
    if (aLen != bLen) {
      return false;
    }
    for (int i = 0; i < aLen; i++) {
      if (a[i] != b[i]) {
        return false;
      }
    }
    return true;
  }

  /** Computes the BLAKE3 leaf node hash per EIP-8297. */
  public static byte[] leafHash(final byte[] key, final int keyLen, final byte[] value) {
    return Blake3Hasher.hash(TrieConstants.LEAF_NODE_TAG, key, 0, keyLen, value, 0, 32);
  }

  /** Computes the BLAKE3 branch node hash per EIP-8297, from a packed prefix. */
  public static byte[] branchHash(
      final byte[] prefix, final int prefixLen, final byte[] leftHash, final byte[] rightHash) {
    final byte[] prefixPacked = PREFIX_PACK_BUFFER.get();
    final int prefixPackedLen = encodeBitPrefix(prefix, prefixLen, prefixPacked, 0);
    return Blake3Hasher.hash(
        TrieConstants.BRANCH_NODE_TAG,
        prefixPacked,
        0,
        prefixPackedLen,
        leftHash,
        0,
        32,
        rightHash,
        0,
        32);
  }
}
