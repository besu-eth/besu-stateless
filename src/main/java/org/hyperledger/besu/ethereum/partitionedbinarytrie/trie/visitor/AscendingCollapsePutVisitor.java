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
 * <p>Node-split / put rules match {@link PutVisitor}; the only additions are left-sibling collapse
 * and rejection of out-of-order (leftward) inserts, duplicate keys, and inserts under already
 * collapsed stubs.
 *
 * <p>Collapse does not require a backing DB — stubs carry the merkle hash only via {@link
 * StoredTrieNodeFactory#wrapStored}.
 */
public final class AscendingCollapsePutVisitor extends PutVisitor {

  private final StoredTrieNodeFactory collapseFactory;

  /**
   * @param value 32-byte leaf value to store
   * @param collapseFactory factory used to wrap completed left siblings as hash stubs
   */
  public AscendingCollapsePutVisitor(
      final byte[] value, final StoredTrieNodeFactory collapseFactory) {
    super(value);
    this.collapseFactory = checkNotNull(collapseFactory);
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
  protected void beforeDescendChild(final BranchNode branchNode, final int childBit) {
    // Rightward descent: the left child is a completed sibling under ascending inserts.
    if (childBit == 1) {
      branchNode.setLeftChild(collapse(branchNode.leftChild()));
    }
  }

  @Override
  protected TrieNode mapAttachedSibling(final TrieNode sibling) {
    return collapse(sibling);
  }

  @Override
  protected void validateSplitDirection(final int newLeafBit) {
    if (newLeafBit == 0) {
      throw new IllegalArgumentException("keys must be inserted in strictly ascending order");
    }
  }

  /**
   * Replaces a completed subtree with a {@link StoredTrieNode} hash stub. Does not require a
   * backing DB — the stub carries the merkle hash only.
   */
  private TrieNode collapse(final TrieNode node) {
    if (node instanceof EmptyTrieNode || node instanceof StoredTrieNode) {
      return node;
    }
    return collapseFactory.wrapStored(Bytes.EMPTY, Bytes32.wrap(node.merkleHashBytes()));
  }
}
