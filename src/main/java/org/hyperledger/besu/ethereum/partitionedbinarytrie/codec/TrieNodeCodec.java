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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.codec;

import org.apache.tuweni.bytes.Bytes;

/**
 * Binary serialization for persisted trie nodes (tags {@code 0x10} branch / {@code 0x11} leaf).
 *
 * <p>Distinct from EIP-8297 hash preimages (BLAKE3 domain tags via {@code internal}): persistence
 * uses explicit key lengths and child hashes, not BLAKE3 domain tags.
 */
public final class TrieNodeCodec {

  /** Serialization tag for branch nodes. */
  public static final byte BRANCH_TAG = 0x10;

  /** Serialization tag for leaf nodes. */
  public static final byte LEAF_TAG = 0x11;

  private TrieNodeCodec() {}

  /**
   * Builds the storage location for a branch child by extending {@code parent} with the branch
   * prefix bits and the split direction ({@code 0} = left, {@code 1} = right).
   *
   * @param parent parent node location in the trie
   * @param prefixBits expanded prefix bits ({@code 0} or {@code 1} per element)
   * @param prefixLen number of prefix bits to append
   * @param splitBit child direction at the split point
   * @return {@code parent || prefixBits[0..prefixLen) || splitBit}
   */
  public static Bytes childLocation(
      final Bytes parent, final byte[] prefixBits, final int prefixLen, final int splitBit) {
    final byte[] path = new byte[parent.size() + prefixLen + 1];
    parent.copyTo(org.apache.tuweni.bytes.MutableBytes.wrap(path), 0);
    for (int i = 0; i < prefixLen; i++) {
      path[parent.size() + i] = prefixBits[i];
    }
    path[parent.size() + prefixLen] = (byte) splitBit;
    return Bytes.wrap(path);
  }

  /**
   * Expands a packed branch prefix back into one byte per bit ({@code 0} or {@code 1}).
   *
   * <p>Inverse of the packing in {@link #encodeBranch}: bits are stored MSB-first within each byte
   * ({@code packed[i/8]}, bit {@code 7 - i%8}). The trie operates on the expanded form for path
   * construction and traversal; persistence uses the compact form to save space.
   *
   * @param packed compact prefix bytes as written by {@link #encodeBranch}
   * @param prefixLen number of prefix bits to expand
   * @return expanded prefix bits ({@code 0} or {@code 1} per element)
   */
  public static byte[] unpackPrefix(final Bytes packed, final int prefixLen) {
    return unpackPrefix(packed.toArrayUnsafe(), 0, prefixLen);
  }

  /**
   * Expands a packed branch prefix slice back into one byte per bit ({@code 0} or {@code 1}).
   *
   * @param packed compact prefix bytes
   * @param offset start offset of the packed prefix in {@code packed}
   * @param prefixLen number of prefix bits to expand
   * @return expanded prefix bits ({@code 0} or {@code 1} per element)
   */
  public static byte[] unpackPrefix(final byte[] packed, final int offset, final int prefixLen) {
    final byte[] bits = new byte[prefixLen];
    for (int i = 0; i < prefixLen; i++) {
      // Read bit i from packed bytes: pick byte i/8, shift down MSB-first position (7 - i%8)
      bits[i] = (byte) ((packed[offset + i / 8] >> (7 - i % 8)) & 1);
    }
    return bits;
  }

  /**
   * Encodes a leaf node for persistence: {@code LEAF_TAG || keyLen (4 bytes) || key || value (32
   * bytes)}.
   *
   * @param key leaf key bytes
   * @param keyLen number of key bytes to encode
   * @param value 32-byte leaf value
   * @return serialized leaf node
   */
  public static Bytes encodeLeaf(final byte[] key, final int keyLen, final byte[] value) {
    final int packedLen = 5 + keyLen + 32;
    final byte[] out = new byte[1 + packedLen];
    out[0] = LEAF_TAG;
    out[1] = (byte) (keyLen >> 24);
    out[2] = (byte) (keyLen >> 16);
    out[3] = (byte) (keyLen >> 8);
    out[4] = (byte) keyLen;
    System.arraycopy(key, 0, out, 5, keyLen);
    System.arraycopy(value, 0, out, 5 + keyLen, 32);
    return Bytes.wrap(out);
  }

  /**
   * Encodes a branch node for persistence: {@code BRANCH_TAG || prefixLen (4 bytes) || packed
   * prefix || leftHash (32 bytes) || rightHash (32 bytes)}.
   *
   * <p>Prefix bits are packed MSB-first into {@code ceil(prefixLen / 8)} bytes; see {@link
   * #unpackPrefix} for the inverse.
   *
   * @param prefixBits expanded prefix bits ({@code 0} or {@code 1} per element)
   * @param prefixLen number of prefix bits to encode
   * @param leftHash 32-byte hash of the left child
   * @param rightHash 32-byte hash of the right child
   * @return serialized branch node
   */
  public static Bytes encodeBranch(
      final byte[] prefixBits, final int prefixLen, final byte[] leftHash, final byte[] rightHash) {
    final int packedLen = (prefixLen + 7) / 8;
    final byte[] packed = new byte[packedLen];
    // Pack prefix bits into bytes: 8 bits per byte, MSB-first (inverse of unpackPrefix)
    for (int i = 0; i < prefixLen; i++) {
      if (prefixBits[i] == 1) {
        // Set bit i in byte i/8 at MSB-first position (7 - i%8)
        packed[i / 8] |= (byte) (1 << (7 - i % 8));
      }
    }
    final byte[] out = new byte[1 + 4 + packedLen + 64];
    out[0] = BRANCH_TAG;
    out[1] = (byte) (prefixLen >> 24);
    out[2] = (byte) (prefixLen >> 16);
    out[3] = (byte) (prefixLen >> 8);
    out[4] = (byte) prefixLen;
    System.arraycopy(packed, 0, out, 5, packedLen);
    System.arraycopy(leftHash, 0, out, 5 + packedLen, 32);
    System.arraycopy(rightHash, 0, out, 5 + packedLen + 32, 32);
    return Bytes.wrap(out);
  }
}
