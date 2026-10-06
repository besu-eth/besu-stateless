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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.codec;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.internal.bytes.ByteTrieOps;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieConstants;

import java.util.Arrays;
import java.util.function.BiConsumer;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Binary serialization for persisted trie nodes: branches above the stems ({@code 0x10}) and stems
 * ({@code 0x12}), each holding the leaves sharing every key byte but the last. A leaf alone ({@code
 * 0x11}) only appears in proofs.
 */
public final class TrieNodeCodec {

  /** Serialization tag for branch nodes. */
  public static final byte BRANCH_TAG = 0x10;

  /** Serialization tag for a leaf alone, in proofs. */
  public static final byte LEAF_TAG = 0x11;

  /** Serialization tag for a stem. */
  public static final byte STEM_TAG = 0x12;

  /** Storage key prefix for code-hash reference counts. */
  public static final byte CODE_REFCOUNT_PREFIX = 0x0F;

  /** Offset of the packed prefix in an encoded branch. */
  public static final int BRANCH_PREFIX_OFFSET = 3;

  /** Offset of the stem in an encoded stem. */
  private static final int STEM_OFFSET = 2;

  /** Size of one leaf in an encoded stem: last key byte and value. */
  private static final int STEM_LEAF_SIZE = 1 + TrieConstants.VALUE_LENGTH;

  private TrieNodeCodec() {}

  /**
   * Builds the storage key holding the reference count for {@code codeHash}.
   *
   * <p>Shares the node key space without any risk of collision: {@link #childLocation} writes one
   * byte per path bit, so every node location consists of {@code 0x00} and {@code 0x01} bytes only
   * and no location can start with {@link #CODE_REFCOUNT_PREFIX}.
   *
   * @param codeHash 32-byte code hash
   * @return {@code 0x0F || codeHash}
   */
  public static Bytes codeRefCountKey(final Bytes32 codeHash) {
    return Bytes.concatenate(Bytes.of(CODE_REFCOUNT_PREFIX), codeHash);
  }

  /**
   * Number of bits of the stem of a {@code keyLen}-byte key: every byte but the last.
   *
   * @param keyLen key length in bytes
   * @return stem length in bits
   */
  public static int stemBits(final int keyLen) {
    return (keyLen - 1) * Byte.SIZE;
  }

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
   * Reads the prefix length, in bits, of a branch encoded by {@link #encodeBranch}.
   *
   * @param encoded encoded branch
   * @return number of prefix bits
   */
  public static int branchPrefixLength(final byte[] encoded) {
    return ((encoded[1] & 0xFF) << 8) | (encoded[2] & 0xFF);
  }

  /**
   * Expands a packed branch prefix back into one byte per bit ({@code 0} or {@code 1}).
   *
   * <p>Inverse of the packing in {@link #encodeBranch}: bits are stored MSB-first within each byte
   * ({@code packed[i/8]}, bit {@code 7 - i%8}). The trie operates on the expanded form for path
   * construction and traversal; persistence uses the compact form to save space.
   *
   * @param packed bytes holding the packed prefix
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
   * Encodes a leaf node on its own, as proofs carry it: {@code LEAF_TAG || key || value (32
   * bytes)}.
   *
   * @param key leaf key bytes
   * @param keyLen number of key bytes to encode
   * @param value 32-byte leaf value
   * @return serialized leaf node
   */
  public static Bytes encodeLeaf(final byte[] key, final int keyLen, final byte[] value) {
    final byte[] out = new byte[1 + keyLen + TrieConstants.VALUE_LENGTH];
    out[0] = LEAF_TAG;
    System.arraycopy(key, 0, out, 1, keyLen);
    System.arraycopy(value, 0, out, 1 + keyLen, TrieConstants.VALUE_LENGTH);
    return Bytes.wrap(out);
  }

  /**
   * Encodes a branch node for persistence: {@code BRANCH_TAG || prefixLen (2 bytes) || packed
   * prefix || leftHash || rightHash}, the prefix as in the EIP-8297 branch hash.
   *
   * @param prefixBits expanded prefix bits ({@code 0} or {@code 1} per element)
   * @param prefixLen number of prefix bits to encode
   * @param leftHash 32-byte hash of the left child
   * @param rightHash 32-byte hash of the right child
   * @return serialized branch node
   */
  public static Bytes encodeBranch(
      final byte[] prefixBits, final int prefixLen, final byte[] leftHash, final byte[] rightHash) {
    final int leftOffset = BRANCH_PREFIX_OFFSET + (prefixLen + 7) / 8;
    final byte[] out = new byte[leftOffset + 2 * Bytes32.SIZE];
    out[0] = BRANCH_TAG;
    ByteTrieOps.encodeBitPrefix(prefixBits, prefixLen, out, 1);
    System.arraycopy(leftHash, 0, out, leftOffset, Bytes32.SIZE);
    System.arraycopy(rightHash, 0, out, leftOffset + Bytes32.SIZE, Bytes32.SIZE);
    return Bytes.wrap(out);
  }

  /**
   * Encodes the leaves of one stem: {@code STEM_TAG || count - 1 || stem || (suffix || value)*}.
   *
   * @param stem key bytes shared by the leaves
   * @param stemLen number of stem bytes
   * @param suffixes last key byte of each leaf, ascending
   * @param values 32-byte value of each leaf
   * @return serialized stem
   */
  public static Bytes encodeStem(
      final byte[] stem, final int stemLen, final byte[] suffixes, final byte[][] values) {
    final byte[] out = new byte[STEM_OFFSET + stemLen + suffixes.length * STEM_LEAF_SIZE];
    out[0] = STEM_TAG;
    out[1] = (byte) (suffixes.length - 1);
    System.arraycopy(stem, 0, out, STEM_OFFSET, stemLen);
    int offset = STEM_OFFSET + stemLen;
    for (int i = 0; i < suffixes.length; i++) {
      out[offset] = suffixes[i];
      System.arraycopy(values[i], 0, out, offset + 1, TrieConstants.VALUE_LENGTH);
      offset += STEM_LEAF_SIZE;
    }
    return Bytes.wrap(out);
  }

  /**
   * Decodes a stem, passing each leaf key and value to {@code leaves} in key order.
   *
   * @param encoded encoded stem
   * @param leaves receives each leaf key and value
   */
  public static void decodeStem(final byte[] encoded, final BiConsumer<byte[], byte[]> leaves) {
    final int count = (encoded[1] & 0xFF) + 1;
    final int stemLen = encoded.length - STEM_OFFSET - count * STEM_LEAF_SIZE;
    int offset = STEM_OFFSET + stemLen;
    for (int i = 0; i < count; i++) {
      final byte[] key = Arrays.copyOfRange(encoded, STEM_OFFSET, STEM_OFFSET + stemLen + 1);
      key[stemLen] = encoded[offset];
      leaves.accept(key, Arrays.copyOfRange(encoded, offset + 1, offset + STEM_LEAF_SIZE));
      offset += STEM_LEAF_SIZE;
    }
  }
}
