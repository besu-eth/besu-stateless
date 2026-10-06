/*
 * Copyright contributors to Besu.
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
 */
package org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.visitor;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.codec.TrieNodeCodec;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.BranchNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.EmptyTrieNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.LeafNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.TrieNode;
import org.hyperledger.besu.ethereum.trie.NodeUpdater;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Location visitor that persists dirty trie nodes, each stem as one entry at its top node.
 *
 * <p>Mirrors Besu {@link org.hyperledger.besu.ethereum.trie.CommitVisitor}.
 */
public class CommitVisitor implements LocationNodeVisitor {

  private final NodeUpdater nodeUpdater;

  public CommitVisitor(final NodeUpdater nodeUpdater) {
    this.nodeUpdater = nodeUpdater;
  }

  @Override
  public void visit(final Bytes location, final EmptyTrieNode emptyNode) {
    // Besu CommitVisitor.visit(NullNode) is a no-op: abandoned locations keep their old bytes and
    // are simply unreachable, because a parent branch stores the empty hash for that side and
    // never turns it back into a loadable child.
  }

  @Override
  public void visit(final Bytes location, final LeafNode leafNode) {
    if (leafNode.isClean()) {
      // Clean nodes are already represented in storage by their current hash.
      return;
    }
    storeStem(location, leafNode, List.of(leafNode));
  }

  @Override
  public void visit(final Bytes location, final BranchNode branchNode) {
    if (branchNode.isClean()) {
      // Nothing below this branch changed since the last commit.
      return;
    }
    final Optional<List<LeafNode>> stem = stemLeaves(branchNode, location.size());
    if (stem.isPresent()) {
      storeStem(location, branchNode, stem.get());
      return;
    }
    // Child locations are derived from this branch location plus its compressed prefix and split
    // bit. Children are committed first so the branch can store their final merkle hashes.
    commitChildren(
        branchNode.leftChild(),
        TrieNodeCodec.childLocation(
            location, branchNode.prefixBits(), branchNode.prefixLength(), 0),
        branchNode.rightChild(),
        TrieNodeCodec.childLocation(
            location, branchNode.prefixBits(), branchNode.prefixLength(), 1));
    nodeUpdater.store(
        location,
        Bytes32.wrap(branchNode.merkleHashBytes()),
        TrieNodeCodec.encodeBranch(
            branchNode.prefixBits(),
            branchNode.prefixLength(),
            branchNode.leftChild().merkleHashBytes(),
            branchNode.rightChild().merkleHashBytes()));
    branchNode.markClean();
  }

  /** Commits the dirty children of a branch above the stems; a subclass may do it concurrently. */
  protected void commitChildren(
      final TrieNode left,
      final Bytes leftLocation,
      final TrieNode right,
      final Bytes rightLocation) {
    if (!left.isClean()) {
      left.accept(leftLocation, this);
    }
    if (!right.isClean()) {
      right.accept(rightLocation, this);
    }
  }

  /** Stores the subtree of {@code top}, its {@code leaves}, as one stem entry. */
  private void storeStem(final Bytes location, final TrieNode top, final List<LeafNode> leaves) {
    final LeafNode first = leaves.getFirst();
    final int stemLen = first.keyLength() - 1;
    final byte[] suffixes = new byte[leaves.size()];
    final byte[][] values = new byte[leaves.size()][];
    for (int i = 0; i < leaves.size(); i++) {
      suffixes[i] = leaves.get(i).keyBytes()[stemLen];
      values[i] = leaves.get(i).valueBytes();
    }
    nodeUpdater.store(
        location,
        Bytes32.wrap(top.merkleHashBytes()),
        TrieNodeCodec.encodeStem(first.keyBytes(), stemLen, suffixes, values));
    markClean(top);
  }

  /** The leaves of {@code branch} if it splits inside their last key byte: a stem. */
  private static Optional<List<LeafNode>> stemLeaves(final BranchNode branch, final int depth) {
    // The split bit is in byte split / 8, the last byte of keys of split / 8 + 1 bytes.
    final int keyLen = (depth + branch.prefixLength()) / Byte.SIZE + 1;
    final List<LeafNode> leaves = new ArrayList<>();
    return collectLeaves(branch, keyLen, leaves) ? Optional.of(leaves) : Optional.empty();
  }

  /** Adds the leaves below {@code node} in key order while they all have {@code keyLen}. */
  private static boolean collectLeaves(
      final TrieNode node, final int keyLen, final List<LeafNode> leaves) {
    if (node instanceof LeafNode leaf) {
      leaves.add(leaf);
      return leaf.keyLength() == keyLen;
    }
    return node instanceof BranchNode branch
        && collectLeaves(branch.leftChild(), keyLen, leaves)
        && collectLeaves(branch.rightChild(), keyLen, leaves);
  }

  /** Marks a stem subtree as persisted. */
  private static void markClean(final TrieNode node) {
    node.markClean();
    if (node instanceof BranchNode branch) {
      markClean(branch.leftChild());
      markClean(branch.rightChild());
    }
  }
}
