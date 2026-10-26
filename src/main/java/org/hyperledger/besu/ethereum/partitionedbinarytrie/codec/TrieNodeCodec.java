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
import org.apache.tuweni.bytes.MutableBytes;

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

  /** Offset of the packed prefix in an encoded branch. */
  public static final int BRANCH_PREFIX_OFFSET = 3;

  /** First byte of every node location but the root's, which is empty. */
  public static final byte NODE_KEY_PREFIX = 0x00;

  /** First byte of every code reference count key. */
  public static final byte CODE_REFCOUNT_KEY_PREFIX = 0x01;

  /** Offset of the first leaf in an encoded stem. */
  private static final int STEM_LEAVES_OFFSET = 2;

  private TrieNodeCodec() {}

  /**
   * Builds the storage key holding the reference count for {@code codeHash}: {@code 0x01 ||
   * codeHash}, apart from node locations, which are empty or start with {@code 0x00}.
   *
   * @param codeHash 32-byte code hash
   * @return the reference count key
   */
  public static Bytes codeRefCountKey(final Bytes32 codeHash) {
    return Bytes.concatenate(Bytes.of(CODE_REFCOUNT_KEY_PREFIX), codeHash);
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
   * Storage location of a branch child: {@code 0x00}, then the path bits of {@code parent}, the
   * branch prefix and the split bit, packed MSB-first and closed by a 1 bit. The root location is
   * empty.
   *
   * @param parent parent node location in the trie
   * @param prefix packed prefix bits, read up to {@code prefixLen}
   * @param prefixLen number of prefix bits to append
   * @param splitBit child direction at the split point
   * @return the child location
   */
  public static Bytes childLocation(
      final Bytes parent, final byte[] prefix, final int prefixLen, final int splitBit) {
    // Path bits start after the prefix byte.
    final int start = parent.isEmpty() ? Byte.SIZE : locationDepth(parent) + Byte.SIZE;
    final int end = start + prefixLen + 1;
    final byte[] location = new byte[(end >>> 3) + 1];
    location[0] = NODE_KEY_PREFIX;
    if (!parent.isEmpty()) {
      parent.copyTo(MutableBytes.wrap(location), 0);
      location[start >>> 3] &= (byte) ~(0x80 >>> (start & 7));
    }
    ByteTrieOps.orBits(prefix, prefixLen, location, start);
    if (splitBit == 1) {
      ByteTrieOps.setBit(location, start + prefixLen);
    }
    ByteTrieOps.setBit(location, end);
    return Bytes.wrap(location);
  }

  /**
   * Depth in bits of a node location built by {@link #childLocation}.
   *
   * @param location node location
   * @return number of path bits
   */
  public static int locationDepth(final Bytes location) {
    if (location.isEmpty()) {
      return 0;
    }
    final int last = location.get(location.size() - 1) & 0xFF;
    return (location.size() - 2) * Byte.SIZE + 7 - Integer.numberOfTrailingZeros(last);
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
   * Reads the packed prefix of a branch encoded by {@link #encodeBranch}.
   *
   * @param encoded encoded branch
   * @return packed prefix bits
   */
  public static byte[] branchPrefix(final byte[] encoded) {
    return Arrays.copyOfRange(
        encoded,
        BRANCH_PREFIX_OFFSET,
        BRANCH_PREFIX_OFFSET + (branchPrefixLength(encoded) + 7) / 8);
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
   * @param prefix packed prefix bits
   * @param prefixLen number of prefix bits to encode
   * @param leftHash 32-byte hash of the left child
   * @param rightHash 32-byte hash of the right child
   * @return serialized branch node
   */
  public static Bytes encodeBranch(
      final byte[] prefix, final int prefixLen, final byte[] leftHash, final byte[] rightHash) {
    final int leftOffset = BRANCH_PREFIX_OFFSET + (prefixLen + 7) / 8;
    final byte[] out = new byte[leftOffset + 2 * Bytes32.SIZE];
    out[0] = BRANCH_TAG;
    ByteTrieOps.encodeBitPrefix(prefix, prefixLen, out, 1);
    System.arraycopy(leftHash, 0, out, leftOffset, Bytes32.SIZE);
    System.arraycopy(rightHash, 0, out, leftOffset + Bytes32.SIZE, Bytes32.SIZE);
    return Bytes.wrap(out);
  }

  /**
   * Encodes the leaves of one stem: {@code STEM_TAG || count - 1 || (suffix || valueLen || value)*
   * || stem}, each value without its leading zero bytes.
   *
   * @param stem key bytes shared by the leaves
   * @param stemLen number of stem bytes
   * @param suffixes last key byte of each leaf, ascending
   * @param values 32-byte value of each leaf
   * @return serialized stem
   */
  public static Bytes encodeStem(
      final byte[] stem, final int stemLen, final byte[] suffixes, final byte[][] values) {
    int size = STEM_LEAVES_OFFSET + stemLen;
    for (final byte[] value : values) {
      size += 2 + TrieConstants.VALUE_LENGTH - leadingZeroBytes(value);
    }
    final byte[] out = new byte[size];
    out[0] = STEM_TAG;
    out[1] = (byte) (suffixes.length - 1);
    int offset = STEM_LEAVES_OFFSET;
    for (int i = 0; i < suffixes.length; i++) {
      final int skipped = leadingZeroBytes(values[i]);
      out[offset] = suffixes[i];
      out[offset + 1] = (byte) (TrieConstants.VALUE_LENGTH - skipped);
      System.arraycopy(values[i], skipped, out, offset + 2, TrieConstants.VALUE_LENGTH - skipped);
      offset += 2 + TrieConstants.VALUE_LENGTH - skipped;
    }
    System.arraycopy(stem, 0, out, offset, stemLen);
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
    int stemOffset = STEM_LEAVES_OFFSET;
    for (int i = 0; i < count; i++) {
      stemOffset += 2 + (encoded[stemOffset + 1] & 0xFF);
    }
    final int stemLen = encoded.length - stemOffset;
    int offset = STEM_LEAVES_OFFSET;
    for (int i = 0; i < count; i++) {
      final byte[] key = new byte[stemLen + 1];
      System.arraycopy(encoded, stemOffset, key, 0, stemLen);
      key[stemLen] = encoded[offset];
      final int valueLen = encoded[offset + 1] & 0xFF;
      final byte[] value = new byte[TrieConstants.VALUE_LENGTH];
      System.arraycopy(encoded, offset + 2, value, TrieConstants.VALUE_LENGTH - valueLen, valueLen);
      leaves.accept(key, value);
      offset += 2 + valueLen;
    }
  }

  private static int leadingZeroBytes(final byte[] value) {
    int zeros = 0;
    while (zeros < TrieConstants.VALUE_LENGTH && value[zeros] == 0) {
      zeros++;
    }
    return zeros;
  }
}
