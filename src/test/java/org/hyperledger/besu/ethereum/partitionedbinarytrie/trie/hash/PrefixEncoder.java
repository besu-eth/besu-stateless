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

/** Encoding of branch prefixes for hashing. */
public final class PrefixEncoder {

  private PrefixEncoder() {}

  /**
   * Encode a branch prefix for hashing: a two-byte big-endian bit count followed by the bits packed
   * most significant bit first, zero padded to a byte boundary.
   */
  public static Bytes encodeBitPrefix(final Bytes prefix) {
    if (prefix.size() >= (1 << 16)) {
      throw new IllegalArgumentException("Prefix bit count does not fit in two bytes");
    }
    final int packedLength = (prefix.size() + 7) / 8;
    final byte[] packed = new byte[packedLength];
    for (int bitIndex = 0; bitIndex < prefix.size(); bitIndex++) {
      if (BitUtils.bitAt(prefix, bitIndex) == 1) {
        packed[bitIndex / 8] |= (byte) (1 << (7 - bitIndex % 8));
      }
    }
    return Bytes.concatenate(
        Bytes.of((byte) (prefix.size() >> 8), (byte) (prefix.size() & 0xFF)), Bytes.wrap(packed));
  }
}
