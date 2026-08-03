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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.jmh.support;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieConstants;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieKeyDerivation;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.ParallelStoredPartitionedBinaryTrie;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.StoredPartitionedBinaryTrie;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.PartitionedBinaryTrieFactory;
import org.hyperledger.besu.ethereum.trie.NodeUpdater;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

/** Shared trie data and key generation for JMH benchmarks. */
public final class TrieBenchmarkFixtures {

  public enum KeyStyle {
    /** 34-byte account basic-data keys from {@link TrieKeyDerivation}. */
    ACCOUNT_BASIC,
    /** 66-byte storage slot keys from {@link TrieKeyDerivation}. */
    STORAGE_SLOT
  }

  private TrieBenchmarkFixtures() {}

  public static byte[] valueForIndex(final int index) {
    final byte[] value = new byte[TrieConstants.VALUE_LENGTH];
    for (int i = 0; i < value.length; i++) {
      value[i] = (byte) (index + i);
    }
    return value;
  }

  public static byte[][] keys(final int count, final KeyStyle keyStyle) {
    final byte[][] keys = new byte[count][];
    for (int i = 0; i < count; i++) {
      keys[i] = keyForIndex(i, keyStyle);
    }
    return keys;
  }

  public static int[] keyLengths(final byte[][] keys) {
    final int[] lengths = new int[keys.length];
    for (int i = 0; i < keys.length; i++) {
      lengths[i] = keys[i].length;
    }
    return lengths;
  }

  public static byte[][] values(final int count) {
    final byte[][] values = new byte[count][];
    for (int i = 0; i < count; i++) {
      values[i] = valueForIndex(i);
    }
    return values;
  }

  public static Bytes keyBytes(final int index, final KeyStyle keyStyle) {
    return Bytes.wrap(keyForIndex(index, keyStyle));
  }

  public static Bytes32 committedRoot(
      final PartitionedBinaryTrieFactory factory,
      final NodeUpdater updater,
      final int keyCount,
      final KeyStyle keyStyle) {
    final StoredPartitionedBinaryTrie trie = factory.create();
    populate(trie, keyCount, keyStyle);
    trie.commit(updater);
    return trie.getRootHash();
  }

  public static void populate(
      final StoredPartitionedBinaryTrie trie, final int keyCount, final KeyStyle keyStyle) {
    for (int i = 0; i < keyCount; i++) {
      trie.put(keyForIndex(i, keyStyle), keyForIndex(i, keyStyle).length, valueForIndex(i));
    }
  }

  public static void populateParallel(
      final ParallelStoredPartitionedBinaryTrie trie, final int keyCount, final KeyStyle keyStyle) {
    for (int i = 0; i < keyCount; i++) {
      trie.put(keyForIndex(i, keyStyle), keyForIndex(i, keyStyle).length, valueForIndex(i));
    }
  }

  private static byte[] keyForIndex(final int index, final KeyStyle keyStyle) {
    final Bytes32 address = addressForIndex(index);
    return switch (keyStyle) {
      case ACCOUNT_BASIC -> TrieKeyDerivation.getTreeKeyForBasicData(address).toArrayUnsafe();
      case STORAGE_SLOT ->
          TrieKeyDerivation.getTreeKeyForStorageSlot(address, UInt256.valueOf(1_000L + index))
              .toArrayUnsafe();
    };
  }

  private static Bytes32 addressForIndex(final int index) {
    final byte[] address = new byte[32];
    address[0] = (byte) (index >> 24);
    address[1] = (byte) (index >> 16);
    address[2] = (byte) (index >> 8);
    address[3] = (byte) index;
    return Bytes32.wrap(address);
  }
}
