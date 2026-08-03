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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.reference.node;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.hash.BitUtils;

import java.util.HashMap;
import java.util.Map;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Build the canonical node structure for a set of entries whose keys all share their first {@code
 * depth} bits.
 */
public final class Binarizer {

  private Binarizer() {}

  /**
   * Recursively partitions {@code entries} into a canonical branch/leaf tree.
   *
   * @param entries key-value pairs sharing the first {@code depth} bits
   * @param depth bit offset at which all keys in {@code entries} are aligned
   * @return root of the canonical subtree
   */
  public static BinaryNode binarize(final Map<Bytes, Bytes32> entries, final int depth) {
    if (entries.isEmpty()) {
      throw new IllegalArgumentException("Cannot binarize empty entries");
    }
    if (entries.size() == 1) {
      final Map.Entry<Bytes, Bytes32> entry = entries.entrySet().iterator().next();
      return new LeafNode(entry.getKey(), entry.getValue());
    }

    final Map<Bytes, Bytes> bitLists = new HashMap<>();
    for (final Bytes key : entries.keySet()) {
      bitLists.put(key, BitUtils.bytesToBitList(key));
    }

    // Extend the shared prefix while every key agrees on the next bit.
    int prefixLength = 0;
    while (true) {
      final int position = depth + prefixLength;
      for (final Bytes bitList : bitLists.values()) {
        if (position >= bitList.size()) {
          throw new IllegalArgumentException("Key is a prefix of another key");
        }
      }
      int distinctBit = -1;
      for (final Bytes bitList : bitLists.values()) {
        final int bit = BitUtils.bitAt(bitList, position);
        if (distinctBit == -1) {
          distinctBit = bit;
        } else if (bit != distinctBit) {
          distinctBit = -2;
          break;
        }
      }
      if (distinctBit == -2) {
        break;
      }
      prefixLength++;
    }

    final int split = depth + prefixLength;
    final Map<Bytes, Bytes32> left = new HashMap<>();
    final Map<Bytes, Bytes32> right = new HashMap<>();
    for (final Map.Entry<Bytes, Bytes32> entry : entries.entrySet()) {
      if (BitUtils.bitAt(bitLists.get(entry.getKey()), split) == 0) {
        left.put(entry.getKey(), entry.getValue());
      } else {
        right.put(entry.getKey(), entry.getValue());
      }
    }

    final Bytes sharedBits = bitLists.values().iterator().next();
    return new BranchNode(
        BitUtils.sliceBits(sharedBits, depth, split),
        binarize(left, split + 1),
        binarize(right, split + 1));
  }
}
