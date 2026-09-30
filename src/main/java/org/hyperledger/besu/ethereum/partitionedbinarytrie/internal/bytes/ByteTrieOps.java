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

  /** Expands bits {@code [from, to)} of {@code key} into one byte per bit (branch prefix form). */
  public static byte[] expandBits(final byte[] key, final int from, final int to) {
    final byte[] bits = new byte[to - from];
    for (int i = from; i < to; i++) {
      bits[i - from] = bitAt(key, i);
    }
    return bits;
  }

  /** Writes a branch-prefix encoding into {@code out} and returns the number of bytes written. */
  public static int encodeBitPrefix(
      final byte[] prefixBits, final int prefixLen, final byte[] out, final int outOff) {
    out[outOff] = (byte) (prefixLen >> 8);
    out[outOff + 1] = (byte) (prefixLen & 0xFF);
    final int packedLen = (prefixLen + 7) / 8;
    for (int i = 0; i < packedLen; i++) {
      out[outOff + 2 + i] = 0;
    }
    for (int bitIndex = 0; bitIndex < prefixLen; bitIndex++) {
      if (prefixBits[bitIndex] == 1) {
        out[outOff + 2 + bitIndex / 8] |= (byte) (1 << (7 - bitIndex % 8));
      }
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

  /** Computes the BLAKE3 branch node hash per EIP-8297. */
  public static byte[] branchHash(
      final byte[] prefixBits, final int prefixLen, final byte[] leftHash, final byte[] rightHash) {
    final byte[] prefixPacked = PREFIX_PACK_BUFFER.get();
    final int prefixPackedLen = encodeBitPrefix(prefixBits, prefixLen, prefixPacked, 0);
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
