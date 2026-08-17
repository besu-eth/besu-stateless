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

import org.hyperledger.besu.ethereum.partitionedbinarytrie.internal.bytes.ByteTrieOps;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieKey;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.BranchNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.EmptyTrieNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.LeafNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.StoredTrieNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.TrieNode;

/**
 * Path visitor that removes a leaf and collapses branches with a single child.
 *
 * <p>Mirrors Besu {@link org.hyperledger.besu.ethereum.trie.patricia.RemoveVisitor}.
 */
public class RemoveVisitor implements PathNodeVisitor {

  private static final EmptyTrieNode NULL_NODE = EmptyTrieNode.instance();

  private final boolean allowFlatten;

  public RemoveVisitor() {
    allowFlatten = true;
  }

  public RemoveVisitor(final boolean allowFlatten) {
    this.allowFlatten = allowFlatten;
  }

  @Override
  public TrieNode visit(final EmptyTrieNode emptyNode, final TrieKey key, final int depth) {
    // Removing an absent key is a no-op; keep the empty subtree (PMT NullNode semantics).
    return emptyNode;
  }

  @Override
  public TrieNode visit(final LeafNode leafNode, final TrieKey key, final int depth) {
    if (ByteTrieOps.keysEqual(
        leafNode.keyBytes(), leafNode.keyLength(), key.bytes(), key.length())) {
      // Exact leaf hit: replace it with the empty node. Parents may collapse this away.
      return NULL_NODE;
    }
    // Different leaf under this path means the target key is absent.
    return leafNode;
  }

  @Override
  public TrieNode visit(final BranchNode branchNode, final TrieKey key, final int depth) {
    final int keyBits = key.bitCount();
    if (depth >= keyBits) {
      // The key ended before this branch could be matched, so nothing can be removed below it.
      return branchNode;
    }
    final byte[] prefixBits = branchNode.prefixBits();
    final int prefixLen = branchNode.prefixLength();
    for (int i = 0; i < prefixLen; i++) {
      if (depth + i >= keyBits || key.bitAt(depth + i) != prefixBits[i]) {
        // The search key diverges inside the compressed prefix; this branch is untouched.
        return branchNode;
      }
    }
    final int split = depth + prefixLen;
    // Remove from the selected child, then let BranchNode decide whether the branch can be
    // collapsed to preserve the canonical "no branch with a single non-empty child" form.
    if (key.bitAt(split) == 0) {
      final TrieNode updatedChild = branchNode.leftChild().accept(this, key, split + 1);
      return branchNode.replaceChild(false, updatedChild, allowFlatten);
    }
    final TrieNode updatedChild = branchNode.rightChild().accept(this, key, split + 1);
    return branchNode.replaceChild(true, updatedChild, allowFlatten);
  }

  @Override
  public TrieNode visit(final StoredTrieNode storedNode, final TrieKey key, final int depth) {
    return storedNode.load().accept(this, key, depth);
  }
}
