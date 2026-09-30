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
 * Read-only visitor that descends the trie to the node matching a key path.
 *
 * <p>Returns the matching {@link LeafNode} or {@link EmptyTrieNode#instance()} when absent. Mirrors
 * Besu {@link org.hyperledger.besu.ethereum.trie.patricia.GetVisitor}.
 */
public class GetVisitor implements PathNodeVisitor {

  private static final EmptyTrieNode NOT_FOUND = EmptyTrieNode.instance();

  @Override
  public TrieNode visit(final EmptyTrieNode emptyNode, final TrieKey key, final int depth) {
    // Reaching an empty subtree means the searched key has no leaf in this trie.
    return NOT_FOUND;
  }

  @Override
  public TrieNode visit(final LeafNode leafNode, final TrieKey key, final int depth) {
    if (ByteTrieOps.keysEqual(
        leafNode.keyBytes(), leafNode.keyLength(), key.bytes(), key.length())) {
      return leafNode;
    }
    // A leaf is terminal. If its full key differs, there is no alternate path to continue.
    return NOT_FOUND;
  }

  @Override
  public TrieNode visit(final BranchNode branchNode, final TrieKey key, final int depth) {
    final int keyBits = key.bitCount();
    if (depth >= keyBits) {
      // The lookup key ended before this branch could select a child.
      return NOT_FOUND;
    }
    final int prefixLen = branchNode.prefixLength();
    for (int i = 0; i < prefixLen; i++) {
      if (depth + i >= keyBits || key.bitAt(depth + i) != branchNode.prefixBits()[i]) {
        // Compressed branch prefixes must match exactly; a mismatch proves absence.
        return NOT_FOUND;
      }
    }
    final int split = depth + prefixLen;
    if (split >= keyBits) {
      // The key matched the branch prefix but has no next bit for left/right selection.
      return NOT_FOUND;
    }
    // After the compressed prefix, the next key bit chooses the only possible child.
    if (key.bitAt(split) == 0) {
      return branchNode.leftChild().accept(this, key, split + 1);
    }
    return branchNode.rightChild().accept(this, key, split + 1);
  }

  @Override
  public TrieNode visit(final StoredTrieNode storedNode, final TrieKey key, final int depth) {
    return storedNode.load().accept(this, key, depth);
  }
}
