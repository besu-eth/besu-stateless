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

import java.util.Arrays;

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
    return new MemoryLeafNode(key, keyLen, value, false);
  }

  @Override
  public TrieNode visit(
      final MemoryLeafNode leafNode, final byte[] key, final int keyLen, final int depth) {
    if (ByteTrieOps.keysEqual(leafNode.keyBytes(), leafNode.keyLength(), key, keyLen)) {
      return new MemoryLeafNode(key, keyLen, value, false);
    }
    final byte[] bits = ByteTrieOps.expandKeyBitsCopy(key, keyLen);
    final byte[] otherBits = ByteTrieOps.expandKeyBits(leafNode.keyBytes(), leafNode.keyLength());
    int run = 0;
    while (depth + run < keyLen * 8
        && depth + run < leafNode.keyLength() * 8
        && bits[depth + run] == otherBits[depth + run]) {
      run++;
    }
    final byte[] prefix = Arrays.copyOfRange(bits, depth, depth + run);
    final TrieNode newLeaf = new MemoryLeafNode(key, keyLen, value, false);
    final TrieNode oldLeaf =
        new MemoryLeafNode(leafNode.keyBytes(), leafNode.keyLength(), leafNode.valueBytes(), false);
    if (bits[depth + run] == 0) {
      return new MemoryBranchNode(prefix, run, newLeaf, oldLeaf, false);
    }
    return new MemoryBranchNode(prefix, run, oldLeaf, newLeaf, false);
  }

  @Override
  public TrieNode visit(
      final MemoryBranchNode branchNode, final byte[] key, final int keyLen, final int depth) {
    final int keyBits = keyLen * 8;
    final byte[] bits = ByteTrieOps.expandKeyBits(key, keyLen);
    final byte[] prefixBits = branchNode.prefixBits();
    final int prefixLen = branchNode.prefixLength();
    int matched = 0;
    while (matched < prefixLen
        && depth + matched < keyBits
        && bits[depth + matched] == prefixBits[matched]) {
      matched++;
    }
    if (matched == prefixLen) {
      final int split = depth + prefixLen;
      if (bits[split] == 0) {
        branchNode.setLeftChild(branchNode.leftChild().accept(this, key, keyLen, split + 1));
      } else {
        branchNode.setRightChild(branchNode.rightChild().accept(this, key, keyLen, split + 1));
      }
      branchNode.markDirty();
      return branchNode;
    }
    final TrieNode survivor =
        new MemoryBranchNode(
            Arrays.copyOfRange(prefixBits, matched + 1, prefixLen),
            prefixLen - matched - 1,
            branchNode.leftChild(),
            branchNode.rightChild(),
            false);
    final TrieNode leaf = new MemoryLeafNode(key, keyLen, value, false);
    if (bits[depth + matched] == 0) {
      return new MemoryBranchNode(
          Arrays.copyOf(prefixBits, matched), matched, leaf, survivor, false);
    }
    return new MemoryBranchNode(Arrays.copyOf(prefixBits, matched), matched, survivor, leaf, false);
  }

  @Override
  public TrieNode visit(
      final StoredTrieNode storedNode, final byte[] key, final int keyLen, final int depth) {
    return storedNode.load().accept(this, key, keyLen, depth);
  }
}
