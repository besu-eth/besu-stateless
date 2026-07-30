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

import org.hyperledger.besu.ethereum.partitionedbinarytrie.internal.TrieMutationSupport;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.internal.bytes.ByteTrieOps;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.node.BranchNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.node.EmptyTrieNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.node.LeafNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.node.StoredNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.node.TrieNode;

/**
 * Path visitor that inserts or updates a leaf value, splitting branches when paths diverge.
 *
 * <p>Mirrors Besu {@link org.hyperledger.besu.ethereum.trie.patricia.PutVisitor}.
 */
public class PutVisitor implements PathNodeVisitor {

  private final byte[] value;

  /**
   * @param value 32-byte leaf value to store
   */
  public PutVisitor(final byte[] value) {
    this.value = value;
  }

  @Override
  public TrieNode visit(
      final EmptyTrieNode emptyNode, final byte[] key, final int keyLen, final int depth) {
    return new LeafNode(key, keyLen, value, false);
  }

  @Override
  public TrieNode visit(
      final LeafNode leafNode, final byte[] key, final int keyLen, final int depth) {
    if (ByteTrieOps.keysEqual(leafNode.keyBytes(), leafNode.keyLength(), key, keyLen)) {
      return new LeafNode(key, keyLen, value, false);
    }
    return TrieMutationSupport.branchFromDivergingLeaves(leafNode, key, keyLen, value, depth);
  }

  @Override
  public TrieNode visit(
      final BranchNode branchNode, final byte[] key, final int keyLen, final int depth) {
    final int keyBits = keyLen * 8;
    final byte[] bits = ByteTrieOps.expandKeyBits(key, keyLen);
    final byte[] prefixBits = branchNode.prefixBits();
    final int prefixLen = branchNode.prefixLength();
    final int matched =
        TrieMutationSupport.matchingPrefixLength(bits, keyBits, prefixBits, prefixLen, depth);
    if (matched == prefixLen) {
      final int split = depth + prefixLen;
      // A branch consumes one more split bit after its compressed prefix.
      // If the key ends here, it would be a forbidden prefix of keys below this branch.
      TrieMutationSupport.ensureHasSplitBit(split, keyBits);
      if (bits[split] == 0) {
        branchNode.setLeftChild(branchNode.leftChild().accept(this, key, keyLen, split + 1));
      } else {
        branchNode.setRightChild(branchNode.rightChild().accept(this, key, keyLen, split + 1));
      }
      branchNode.markDirty();
      return branchNode;
    }
    return TrieMutationSupport.splitBranchWithLeaf(
        branchNode, key, keyLen, value, bits, keyBits, depth, matched);
  }

  @Override
  public TrieNode visit(
      final StoredNode storedNode, final byte[] key, final int keyLen, final int depth) {
    return storedNode.load().accept(this, key, keyLen, depth);
  }
}
