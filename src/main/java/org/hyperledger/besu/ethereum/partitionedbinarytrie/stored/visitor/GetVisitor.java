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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.visitor;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.internal.bytes.ByteTrieOps;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.node.BranchNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.node.EmptyTrieNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.node.LeafNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.node.StoredNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.node.TrieNode;

/**
 * Read-only visitor that descends the trie to the node matching a key path.
 *
 * <p>Returns the matching {@link LeafNode} or {@link EmptyTrieNode#instance()} when absent. Mirrors
 * Besu {@link org.hyperledger.besu.ethereum.trie.patricia.GetVisitor}.
 */
public class GetVisitor implements PathNodeVisitor {

  private static final EmptyTrieNode NOT_FOUND = EmptyTrieNode.instance();

  @Override
  public TrieNode visit(
      final EmptyTrieNode emptyNode, final byte[] key, final int keyLen, final int depth) {
    return NOT_FOUND;
  }

  @Override
  public TrieNode visit(
      final LeafNode leafNode, final byte[] key, final int keyLen, final int depth) {
    if (ByteTrieOps.keysEqual(leafNode.keyBytes(), leafNode.keyLength(), key, keyLen)) {
      return leafNode;
    }
    return NOT_FOUND;
  }

  @Override
  public TrieNode visit(
      final BranchNode branchNode, final byte[] key, final int keyLen, final int depth) {
    final int keyBits = keyLen * 8;
    if (depth >= keyBits) {
      return NOT_FOUND;
    }
    final byte[] bits = ByteTrieOps.expandKeyBits(key, keyLen);
    final int prefixLen = branchNode.prefixLength();
    for (int i = 0; i < prefixLen; i++) {
      if (depth + i >= keyBits || bits[depth + i] != branchNode.prefixBits()[i]) {
        return NOT_FOUND;
      }
    }
    final int split = depth + prefixLen;
    if (bits[split] == 0) {
      return branchNode.leftChild().accept(this, key, keyLen, split + 1);
    }
    return branchNode.rightChild().accept(this, key, keyLen, split + 1);
  }

  @Override
  public TrieNode visit(
      final StoredNode storedNode, final byte[] key, final int keyLen, final int depth) {
    return storedNode.load().accept(this, key, keyLen, depth);
  }
}
