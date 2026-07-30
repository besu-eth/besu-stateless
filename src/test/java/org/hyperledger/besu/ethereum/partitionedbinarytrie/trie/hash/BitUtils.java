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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.hash;

import org.apache.tuweni.bytes.Bytes;

/** Bit-level utilities for trie key manipulation. */
public final class BitUtils {

  private BitUtils() {}

  /**
   * Expand each input byte into eight bits, most significant bit first.
   *
   * <p>Bit {@code d} of the result is the bit selected at depth {@code d} of the tree.
   */
  public static Bytes bytesToBitList(final Bytes data) {
    final byte[] bitList = new byte[8 * data.size()];
    for (int byteIndex = 0; byteIndex < data.size(); byteIndex++) {
      final int value = data.get(byteIndex) & 0xFF;
      for (int offset = 0; offset < 8; offset++) {
        bitList[byteIndex * 8 + offset] = (byte) ((value >> (7 - offset)) & 1);
      }
    }
    return Bytes.wrap(bitList);
  }

  /** Return the bit at {@code position} in {@code bits}, where each byte holds one bit (0 or 1). */
  public static int bitAt(final Bytes bits, final int position) {
    return bits.get(position) & 1;
  }

  /** Extract a sub-range of bits from a bit list (one bit per byte). */
  public static Bytes sliceBits(final Bytes bits, final int from, final int to) {
    return bits.slice(from, to - from);
  }

  /** Concatenate a bit list, one split bit, and another bit list. */
  public static Bytes concatBits(final Bytes prefix, final int splitBit, final Bytes suffix) {
    final byte[] bits = new byte[prefix.size() + 1 + suffix.size()];
    prefix.copyTo(org.apache.tuweni.bytes.MutableBytes.wrap(bits), 0);
    bits[prefix.size()] = (byte) splitBit;
    suffix.copyTo(org.apache.tuweni.bytes.MutableBytes.wrap(bits), prefix.size() + 1);
    return Bytes.wrap(bits);
  }
}
