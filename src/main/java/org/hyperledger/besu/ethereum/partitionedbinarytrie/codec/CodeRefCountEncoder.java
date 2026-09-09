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
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * Packs a code-hash reference count and its chunk count into a 32-byte trie leaf.
 *
 * <p>Layout: 16-byte chunk count || 16-byte reference count (both unsigned big-endian).
 */
public final class CodeRefCountEncoder {

  private CodeRefCountEncoder() {}

  /**
   * Encodes a reference count and the number of {@code CODE_ZONE} chunks it protects.
   *
   * @param refCount number of live insertions of this code hash ({@code >= 1})
   * @param chunkCount number of code chunks stored for this hash
   * @return 32-byte leaf value
   */
  public static Bytes32 encode(final long refCount, final int chunkCount) {
    if (refCount < 0) {
      throw new IllegalArgumentException("Reference count must be non-negative");
    }
    if (chunkCount < 0) {
      throw new IllegalArgumentException("Chunk count must be non-negative");
    }
    return Bytes32.wrap(
        Bytes.concatenate(
            Bytes32.leftPad(UInt256.valueOf(chunkCount)).slice(16),
            Bytes32.leftPad(UInt256.valueOf(refCount)).slice(16)));
  }

  /**
   * Reads the reference count from an encoded leaf.
   *
   * @param value 32-byte leaf
   * @return reference count
   */
  public static long refCount(final Bytes value) {
    return UInt256.fromBytes(value.slice(16, 16)).toLong();
  }

  /**
   * Reads the stored chunk count from an encoded leaf.
   *
   * @param value 32-byte leaf
   * @return chunk count
   */
  public static int chunkCount(final Bytes value) {
    return UInt256.fromBytes(value.slice(0, 16)).intValue();
  }
}
