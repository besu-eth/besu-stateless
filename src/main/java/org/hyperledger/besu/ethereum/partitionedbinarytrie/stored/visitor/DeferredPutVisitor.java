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

import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Path visitor that reads the existing value at the target path, applies a deferred merger, then
 * updates or removes the leaf in a single descent.
 *
 * <p>Mirrors Besu {@link org.hyperledger.besu.ethereum.trie.patricia.DeferredPutVisitor}.
 */
public class DeferredPutVisitor implements PathNodeVisitor {

  private final UnaryOperator<Optional<byte[]>> merger;

  public DeferredPutVisitor(final UnaryOperator<Optional<byte[]>> merger) {
    this.merger = merger;
  }

  @Override
  public TrieNode visit(
      final EmptyTrieNode emptyNode, final byte[] key, final int keyLen, final int depth) {
    final Optional<byte[]> merged = merger.apply(Optional.empty());
    if (merged.isPresent()) {
      TrieMutationSupport.validateValue(merged.get());
      return new LeafNode(key, keyLen, merged.get(), false);
    }
    return EmptyTrieNode.instance();
  }

  @Override
  public TrieNode visit(
      final LeafNode leafNode, final byte[] key, final int keyLen, final int depth) {
    if (ByteTrieOps.keysEqual(leafNode.keyBytes(), leafNode.keyLength(), key, keyLen)) {
      final Optional<byte[]> merged = merger.apply(Optional.of(leafNode.valueBytes()));
      if (merged.isPresent()) {
        TrieMutationSupport.validateValue(merged.get());
        return new LeafNode(key, keyLen, merged.get(), false);
      }
      return EmptyTrieNode.instance();
    }

    final Optional<byte[]> newValue = merger.apply(Optional.empty());
    if (newValue.isEmpty()) {
      return leafNode;
    }
    TrieMutationSupport.validateValue(newValue.get());
    return TrieMutationSupport.branchFromDivergingLeaves(
        leafNode, key, keyLen, newValue.get(), depth);
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
      if (split >= keyBits) {
        // Only an inserting merge can violate prefix-freeness here. A deleting/no-op merge for a
        // non-existent prefix key should leave the existing subtree unchanged.
        final Optional<byte[]> newValue = merger.apply(Optional.empty());
        if (newValue.isPresent()) {
          TrieMutationSupport.validateValue(newValue.get());
          throw new IllegalArgumentException(TrieMutationSupport.PREFIX_FREE_VIOLATION);
        }
        return branchNode;
      }
      if (bits[split] == 0) {
        branchNode.setLeftChild(branchNode.leftChild().accept(this, key, keyLen, split + 1));
      } else {
        branchNode.setRightChild(branchNode.rightChild().accept(this, key, keyLen, split + 1));
      }
      branchNode.markDirty();
      return branchNode;
    }

    final Optional<byte[]> newValue = merger.apply(Optional.empty());
    if (newValue.isEmpty()) {
      return branchNode;
    }
    TrieMutationSupport.validateValue(newValue.get());
    return TrieMutationSupport.splitBranchWithLeaf(
        branchNode, key, keyLen, newValue.get(), bits, keyBits, depth, matched);
  }

  @Override
  public TrieNode visit(
      final StoredNode storedNode, final byte[] key, final int keyLen, final int depth) {
    return storedNode.load().accept(this, key, keyLen, depth);
  }
}
