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
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.BranchNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.EmptyTrieNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.LeafNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.StoredTrieNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.TrieNode;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Path visitor that inserts, updates, removes, or no-ops a leaf value.
 *
 * <p>A direct put is represented as a merger that always returns the replacement value. Deferred
 * puts supply their own merger, so both write paths share the same traversal and split logic.
 */
public class PutVisitor implements PathNodeVisitor {

  private final UnaryOperator<Optional<byte[]>> merger;

  /**
   * @param value 32-byte leaf value to store
   */
  public PutVisitor(final byte[] value) {
    this(existing -> Optional.of(value));
  }

  /**
   * @param merger function receiving the current value, or empty when absent
   */
  public PutVisitor(final UnaryOperator<Optional<byte[]>> merger) {
    this.merger = checkNotNull(merger);
  }

  @Override
  public TrieNode visit(final EmptyTrieNode emptyNode, final TrieKey key, final int depth) {
    // The key is absent. The merger can create a first leaf or keep the subtree empty.
    return merger
        .apply(Optional.empty())
        .<TrieNode>map(value -> new LeafNode(key.bytes(), key.length(), value, false))
        .orElseGet(EmptyTrieNode::instance);
  }

  @Override
  public TrieNode visit(final LeafNode leafNode, final TrieKey key, final int depth) {
    if (ByteTrieOps.keysEqual(
        leafNode.keyBytes(), leafNode.keyLength(), key.bytes(), key.length())) {
      // Exact leaf hit: merge against the current value. Empty result means delete this leaf.
      return merger
          .apply(Optional.of(leafNode.valueBytes()))
          .<TrieNode>map(value -> new LeafNode(key.bytes(), key.length(), value, false))
          .orElseGet(EmptyTrieNode::instance);
    }

    // Different leaf: for the target key the current value is absent. If the merger returns a
    // value, insertion follows the first-diverging-bit split; otherwise this leaf is unchanged.
    return merger
        .apply(Optional.empty())
        .map(value -> splitLeaf(leafNode, key, value, depth))
        .orElse(leafNode);
  }

  @Override
  public TrieNode visit(final BranchNode branchNode, final TrieKey key, final int depth) {
    final int keyBits = key.bitCount();
    final byte[] prefixBits = branchNode.prefixBits();
    final int prefixLen = branchNode.prefixLength();
    int matched = 0;
    while (matched < prefixLen
        && depth + matched < keyBits
        && key.bitAt(depth + matched) == prefixBits[matched]) {
      matched++;
    }
    if (matched == prefixLen) {
      if (depth + matched >= keyBits) {
        // The key ended at this branch. For an absent-key merge this can stay a no-op; creating a
        // value here would make this key a prefix of existing keys.
        if (merger.apply(Optional.empty()).isPresent()) {
          throw new IllegalArgumentException("Insert violates prefix-freedom");
        }
        return branchNode;
      }
      // The key consumed the compressed prefix, so descend through the next key bit.
      final int split = depth + prefixLen;
      if (key.bitAt(split) == 0) {
        branchNode.setLeftChild(branchNode.leftChild().accept(this, key, split + 1));
      } else {
        branchNode.setRightChild(branchNode.rightChild().accept(this, key, split + 1));
      }
      branchNode.markDirty();
      return branchNode;
    }

    final int divergence = matched;
    return merger
        .apply(Optional.empty())
        .map(value -> splitBranchPrefix(branchNode, key, value, depth, divergence))
        .orElse(branchNode);
  }

  @Override
  public TrieNode visit(final StoredTrieNode storedNode, final TrieKey key, final int depth) {
    return storedNode.load().accept(this, key, depth);
  }

  private static TrieNode splitLeaf(
      final LeafNode leafNode, final TrieKey key, final byte[] value, final int depth) {
    // Two different leaves cannot occupy the same slot. Walk both full keys bit-by-bit starting at
    // depth: run counts how many bits are still equal below the path already consumed. The first
    // unequal bit becomes the left/right selector for the new branch, while the equal run becomes
    // that branch's compressed prefix.
    final byte[] otherBits = ByteTrieOps.expandKeyBits(leafNode.keyBytes(), leafNode.keyLength());
    int run = 0;
    while (depth + run < key.bitCount()
        && depth + run < leafNode.keyLength() * 8
        && key.bitAt(depth + run) == otherBits[depth + run]) {
      run++;
    }
    if (depth + run >= key.bitCount() || depth + run >= leafNode.keyLength() * 8) {
      // No differing bit was found before one key ended, so one key is a prefix of the other. That
      // would make the trie ambiguous and violates EIP-8297's prefix-free key requirement.
      throw new IllegalArgumentException("Insert violates prefix-freedom");
    }
    final byte[] prefix = Arrays.copyOfRange(key.pathBits(), depth, depth + run);
    final TrieNode newLeaf = new LeafNode(key.bytes(), key.length(), value, false);
    final TrieNode oldLeaf =
        new LeafNode(leafNode.keyBytes(), leafNode.keyLength(), leafNode.valueBytes(), false);
    if (key.bitAt(depth + run) == 0) {
      return new BranchNode(prefix, run, newLeaf, oldLeaf, false);
    }
    return new BranchNode(prefix, run, oldLeaf, newLeaf, false);
  }

  private static TrieNode splitBranchPrefix(
      final BranchNode branchNode,
      final TrieKey key,
      final byte[] value,
      final int depth,
      final int matched) {
    if (depth + matched >= key.bitCount()) {
      throw new IllegalArgumentException("Insert violates prefix-freedom");
    }

    // The key diverges inside this branch's compressed prefix. Keep the unmatched suffix and both
    // existing children under a survivor branch, then place that survivor beside the new leaf.
    final byte[] prefixBits = branchNode.prefixBits();
    final TrieNode survivor =
        new BranchNode(
            Arrays.copyOfRange(prefixBits, matched + 1, branchNode.prefixLength()),
            branchNode.prefixLength() - matched - 1,
            branchNode.leftChild(),
            branchNode.rightChild(),
            false);
    final TrieNode leaf = new LeafNode(key.bytes(), key.length(), value, false);
    if (key.bitAt(depth + matched) == 0) {
      return new BranchNode(Arrays.copyOf(prefixBits, matched), matched, leaf, survivor, false);
    }
    return new BranchNode(Arrays.copyOf(prefixBits, matched), matched, survivor, leaf, false);
  }
}
