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
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.node.EmptyTrieNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.node.MemoryBranchNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.node.MemoryLeafNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.node.StoredTrieNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.node.TrieNode;

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
  public TrieNode visit(
      final EmptyTrieNode emptyNode, final byte[] key, final int keyLen, final int depth) {
    return NULL_NODE;
  }

  @Override
  public TrieNode visit(
      final MemoryLeafNode leafNode, final byte[] key, final int keyLen, final int depth) {
    if (ByteTrieOps.keysEqual(leafNode.keyBytes(), leafNode.keyLength(), key, keyLen)) {
      return NULL_NODE;
    }
    return leafNode;
  }

  @Override
  public TrieNode visit(
      final MemoryBranchNode branchNode, final byte[] key, final int keyLen, final int depth) {
    final int keyBits = keyLen * 8;
    if (depth >= keyBits) {
      return branchNode;
    }
    final byte[] bits = ByteTrieOps.expandKeyBits(key, keyLen);
    final byte[] prefixBits = branchNode.prefixBits();
    final int prefixLen = branchNode.prefixLength();
    for (int i = 0; i < prefixLen; i++) {
      if (depth + i >= keyBits || bits[depth + i] != prefixBits[i]) {
        return branchNode;
      }
    }
    final int split = depth + prefixLen;
    if (bits[split] == 0) {
      final TrieNode updatedChild = branchNode.leftChild().accept(this, key, keyLen, split + 1);
      return branchNode.replaceChild(false, updatedChild, allowFlatten);
    }
    final TrieNode updatedChild = branchNode.rightChild().accept(this, key, keyLen, split + 1);
    return branchNode.replaceChild(true, updatedChild, allowFlatten);
  }

  @Override
  public TrieNode visit(
      final StoredTrieNode storedNode, final byte[] key, final int keyLen, final int depth) {
    return storedNode.load().accept(this, key, keyLen, depth);
  }
}
