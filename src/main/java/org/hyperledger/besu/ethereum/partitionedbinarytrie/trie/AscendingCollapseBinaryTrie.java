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

import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.StoredTrieNodeFactory;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.BranchNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.EmptyTrieNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.TrieNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.visitor.AscendingCollapsePutVisitor;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.visitor.PathNodeVisitor;
import org.hyperledger.besu.ethereum.trie.NodeLoader;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Streaming partitioned binary trie for strictly ascending bulk inserts.
 *
 * <p>Thin wrapper over {@link StoredPartitionedBinaryTrie} that inserts with {@link
 * AscendingCollapsePutVisitor} so completed left siblings collapse to in-memory {@link
 * org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.StoredTrieNode} hash stubs (no DB).
 * Keys MUST arrive in strictly ascending key order.
 *
 * <p>On {@link #rootHash()}, only the two children of the root branch are hashed in parallel —
 * nothing deeper.
 *
 * <p>Public API: {@link #insert}, {@link #insertCount}, {@link #rootHash} (seals the trie).
 */
public final class AscendingCollapseBinaryTrie {

  private static final NodeLoader EMPTY_LOADER = (location, hash) -> Optional.empty();

  private final int startDepth;
  private final StoredTrieNodeFactory collapseFactory = new StoredTrieNodeFactory(EMPTY_LOADER);
  private final StoredPartitionedBinaryTrie trie =
      new StoredPartitionedBinaryTrie(EMPTY_LOADER, Bytes32.ZERO) {
        @Override
        protected PathNodeVisitor getPutVisitor(final byte[] value) {
          return new AscendingCollapsePutVisitor(value, collapseFactory);
        }
      };
  private Bytes lastKey;
  private long insertCount;
  private boolean sealed;

  /** Ascending builder starting at bit depth 0 (full trie / isolated root). */
  public AscendingCollapseBinaryTrie() {
    this(0);
  }

  /**
   * Ascending builder starting at {@code startDepth}.
   *
   * @param startDepth bit index already consumed by ancestors; child of a root split at bit {@code
   *     S} uses {@code startDepth = S + 1}
   */
  public AscendingCollapseBinaryTrie(final int startDepth) {
    if (startDepth < 0) {
      throw new IllegalArgumentException("startDepth must be non-negative");
    }
    this.startDepth = startDepth;
  }

  /**
   * Inserts {@code (key, value)} in strictly ascending key order.
   *
   * @throws IllegalArgumentException if order is violated
   * @throws IllegalStateException if {@link #rootHash()} has already been called
   */
  public void insert(final Bytes key, final Bytes value) {
    if (sealed) {
      throw new IllegalStateException("ascending binary trie already sealed");
    }
    if (lastKey != null && key.compareTo(lastKey) <= 0) {
      throw new IllegalArgumentException("keys must be inserted in strictly ascending order");
    }
    lastKey = key;
    insertCount++;
    trie.put(key, value, startDepth);
  }

  /** Returns the number of successful {@link #insert} calls since construction. */
  public long insertCount() {
    return insertCount;
  }

  /** Bit depth at which inserts begin. */
  public int startDepth() {
    return startDepth;
  }

  /**
   * Seals the trie and returns its root hash. Further {@link #insert} calls are rejected.
   *
   * <p>Hashes the root branch's left and right children in parallel (one level only), then hashes
   * the root.
   *
   * @return merkle root of the sealed trie
   */
  public Bytes32 rootHash() {
    sealed = true;
    prehashRootBranchChildren(trie.root);
    return trie.getRootHash();
  }

  /**
   * Pre-computes hashes of the root branch's two children concurrently. Deeper nodes are left to
   * the normal recursive {@link TrieNode#merkleHashBytes()}.
   */
  private static void prehashRootBranchChildren(final TrieNode root) {
    if (!(root instanceof BranchNode branch)) {
      return;
    }
    final TrieNode left = branch.leftChild();
    final TrieNode right = branch.rightChild();
    if (left instanceof EmptyTrieNode && right instanceof EmptyTrieNode) {
      return;
    }
    final CompletableFuture<byte[]> leftHash = CompletableFuture.supplyAsync(left::merkleHashBytes);
    final CompletableFuture<byte[]> rightHash =
        CompletableFuture.supplyAsync(right::merkleHashBytes);
    leftHash.join();
    rightHash.join();
  }
}
