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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.trie;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieKey;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.StoredTrieNodeFactory;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.TrieNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.visitor.AscendingCollapsePutVisitor;

import java.util.Arrays;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Streaming partitioned binary trie for strictly ascending bulk inserts, with no storage.
 *
 * <p>Each insert runs {@link AscendingCollapsePutVisitor} on the root: once the path turns right,
 * the completed left subtree is hashed and replaced by a hash-only stub. Live memory is therefore
 * the rightmost path, O(depth).
 *
 * <p>Keys MUST arrive in strictly ascending unsigned byte-lexicographic order, the order in which
 * the trie descends bit by bit.
 */
public final class AscendingCollapseBinaryTrie {

  /** Builds the hash-only stubs; they are never loaded, so there is nothing to load them from. */
  private final StoredTrieNodeFactory stubs =
      new StoredTrieNodeFactory((location, hash) -> Optional.empty());

  private TrieNode root = TrieNode.empty();
  private byte[] lastKey;
  private long insertCount;
  private boolean sealed;

  /**
   * Inserts {@code (key, value)} in strictly ascending key order.
   *
   * @throws IllegalArgumentException if order is violated, or the key or value is malformed
   * @throws IllegalStateException if {@link #rootHash()} has already been called
   */
  public void insert(final Bytes key, final Bytes value) {
    if (sealed) {
      throw new IllegalStateException("ascending binary trie already sealed");
    }
    final byte[] keyBytes = key.toArray();
    final byte[] valueBytes = value.toArray();
    PartitionedBinaryTrie.validateKey(keyBytes, keyBytes.length);
    PartitionedBinaryTrie.validateValue(valueBytes);
    if (lastKey != null && Arrays.compareUnsigned(keyBytes, lastKey) <= 0) {
      throw new IllegalArgumentException("keys must be inserted in strictly ascending order");
    }
    root =
        root.accept(
            new AscendingCollapsePutVisitor(valueBytes, stubs),
            TrieKey.of(keyBytes, keyBytes.length),
            0);
    lastKey = keyBytes;
    insertCount++;
  }

  /** Returns the number of successful {@link #insert} calls since construction. */
  public long insertCount() {
    return insertCount;
  }

  /**
   * Seals the trie and returns its root hash. Further {@link #insert} calls are rejected.
   *
   * <p>Only the rightmost path is still unhashed at this point, so this is O(depth).
   *
   * @return merkle root of the sealed trie (32 zero bytes when empty)
   */
  public Bytes32 rootHash() {
    sealed = true;
    return Bytes32.wrap(root.merkleHashBytes());
  }
}
