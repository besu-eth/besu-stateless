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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.visitor;

import static com.google.common.base.Preconditions.checkNotNull;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.internal.bytes.ByteTrieOps;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieKey;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.StoredTrieNodeFactory;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.BranchNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.EmptyTrieNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.LeafNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.StoredTrieNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.TrieNode;
import org.hyperledger.besu.ethereum.trie.NodeUpdater;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.IntFunction;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Ascending-key {@link PutVisitor} that collapses finished left siblings to {@link StoredTrieNode}
 * hash stubs so live memory stays O(depth).
 *
 * <p>Keys MUST arrive in strictly ascending order. When the insert path takes the right child of a
 * branch (bit {@code 1}), the left child is a completed left sibling and is replaced by a hash stub
 * — that subtree will never receive another insert.
 *
 * <p>With a {@link NodeUpdater}, each completed subtree is first committed at its storage location
 * (the bit path from the root, as {@link CommitVisitor} derives it), so the resulting store is the
 * one {@link org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.StoredPartitionedBinaryTrie}
 * would write for the same entries. Without one, collapse only hashes.
 *
 * <p>Node-split / put rules match {@link PutVisitor}; the only additions are left-sibling collapse
 * and rejection of out-of-order (leftward) inserts, duplicate keys, and inserts under already
 * collapsed stubs.
 */
public final class AscendingCollapsePutVisitor extends PutVisitor {

  private final StoredTrieNodeFactory collapseFactory;
  private final Optional<NodeUpdater> nodeUpdater;
  private final Optional<IntFunction<TrieNode>> prebuilt;

  /**
   * @param value 32-byte leaf value to store
   * @param collapseFactory factory used to wrap completed left siblings as hash stubs
   * @param nodeUpdater where completed subtrees are persisted, or empty to only hash them
   */
  public AscendingCollapsePutVisitor(
      final byte[] value,
      final StoredTrieNodeFactory collapseFactory,
      final Optional<NodeUpdater> nodeUpdater) {
    this(value, Optional.empty(), collapseFactory, nodeUpdater);
  }

  /**
   * Places a prebuilt subtree instead of a leaf: {@code prebuilt} returns it rooted at a given bit
   * depth, and the visitor navigates with the subtree's first key and its value.
   */
  public AscendingCollapsePutVisitor(
      final byte[] firstValue,
      final Optional<IntFunction<TrieNode>> prebuilt,
      final StoredTrieNodeFactory collapseFactory,
      final Optional<NodeUpdater> nodeUpdater) {
    super(firstValue);
    this.prebuilt = checkNotNull(prebuilt);
    this.collapseFactory = checkNotNull(collapseFactory);
    this.nodeUpdater = checkNotNull(nodeUpdater);
  }

  @Override
  protected TrieNode newNode(final TrieKey key, final byte[] value, final int depth) {
    return prebuilt
        .map(subtree -> subtree.apply(depth))
        .orElseGet(() -> super.newNode(key, value, depth));
  }

  @Override
  public TrieNode visit(final LeafNode leafNode, final TrieKey key, final int depth) {
    if (ByteTrieOps.keysEqual(
        leafNode.keyBytes(), leafNode.keyLength(), key.bytes(), key.length())) {
      throw new IllegalArgumentException("duplicate key insert");
    }
    return super.visit(leafNode, key, depth);
  }

  @Override
  public TrieNode visit(final StoredTrieNode storedNode, final TrieKey key, final int depth) {
    throw new IllegalArgumentException(
        "cannot insert into collapsed subtree (keys must be strictly ascending)");
  }

  @Override
  protected void beforeDescendChild(
      final BranchNode branchNode, final TrieKey key, final int split, final int childBit) {
    // Rightward descent: the left child is a completed sibling under ascending inserts.
    if (childBit == 1) {
      branchNode.setLeftChild(collapse(branchNode.leftChild(), key, split, 0));
    }
  }

  @Override
  protected TrieNode mapAttachedSibling(
      final TrieNode sibling, final TrieKey key, final int split, final int siblingBit) {
    return collapse(sibling, key, split, siblingBit);
  }

  @Override
  protected void validateSplitDirection(final int newLeafBit) {
    if (newLeafBit == 0) {
      throw new IllegalArgumentException("keys must be inserted in strictly ascending order");
    }
  }

  /**
   * Replaces a completed subtree, the {@code side} child of the split at bit {@code split} on
   * {@code key}'s path, with a hash stub, persisting it first when a {@link NodeUpdater} is set.
   */
  private TrieNode collapse(
      final TrieNode node, final TrieKey key, final int split, final int side) {
    if (node instanceof EmptyTrieNode || node instanceof StoredTrieNode) {
      return node;
    }
    if (nodeUpdater.isEmpty()) {
      // Hash-only: nothing is written and the stub is never loaded, so its location is only
      // computed if something asks for it.
      return collapseFactory.wrapStored(
          () -> childLocation(key, split, side), Bytes32.wrap(node.merkleHashBytes()));
    }
    final Bytes location = childLocation(key, split, side);
    node.commit(location, nodeUpdater.get());
    return collapseFactory.wrapStored(location, Bytes32.wrap(node.merkleHashBytes()));
  }

  /** Storage location of a split's child: the key's first {@code split} bits, then {@code side}. */
  public static Bytes childLocation(final TrieKey key, final int split, final int side) {
    final byte[] path = Arrays.copyOf(ByteTrieOps.expandBits(key.bytes(), 0, split), split + 1);
    path[split] = (byte) side;
    return Bytes.wrap(path);
  }
}
