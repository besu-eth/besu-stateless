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

import org.hyperledger.besu.ethereum.partitionedbinarytrie.codec.TrieNodeCodec;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.BranchNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.EmptyTrieNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.LeafNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.TrieNode;
import org.hyperledger.besu.ethereum.trie.NodeUpdater;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Location visitor that persists dirty trie nodes.
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
    // Clear any previously committed node bytes at this path (required for location-keyed reload).
    nodeUpdater.store(location, null, null);
  }

  @Override
  public void visit(final Bytes location, final LeafNode leafNode) {
    if (leafNode.isClean()) {
      // Clean nodes are already represented in storage by their current hash.
      return;
    }
    nodeUpdater.store(
        location,
        Bytes32.wrap(leafNode.merkleHashBytes()),
        TrieNodeCodec.encodeLeaf(leafNode.keyBytes(), leafNode.keyLength(), leafNode.valueBytes()));
    leafNode.markClean();
  }

  @Override
  public void visit(final Bytes location, final BranchNode branchNode) {
    if (branchNode.isClean()) {
      // Nothing below this branch changed since the last commit.
      return;
    }
    // Child locations are derived from this branch location plus its compressed prefix and split
    // bit. Children are committed first so the branch can store their final merkle hashes.
    final Bytes leftLoc =
        TrieNodeCodec.childLocation(
            location, branchNode.prefixBits(), branchNode.prefixLength(), 0);
    final Bytes rightLoc =
        TrieNodeCodec.childLocation(
            location, branchNode.prefixBits(), branchNode.prefixLength(), 1);
    final TrieNode leftChild = branchNode.leftChild();
    if (!leftChild.isClean()) {
      leftChild.commit(leftLoc, nodeUpdater);
    }
    final TrieNode rightChild = branchNode.rightChild();
    if (!rightChild.isClean()) {
      rightChild.commit(rightLoc, nodeUpdater);
    }
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
}
