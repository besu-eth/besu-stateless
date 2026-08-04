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

import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieKey;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.BranchNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.LeafNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.TrieNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects hash-referenced nodes along a key lookup path for merkle proofs.
 *
 * <p>Mirrors Besu {@link org.hyperledger.besu.ethereum.trie.ProofVisitor}.
 */
public class ProofVisitor extends GetVisitor {

  private final TrieNode rootNode;
  private final List<TrieNode> proof = new ArrayList<>();

  public ProofVisitor(final TrieNode rootNode) {
    this.rootNode = rootNode;
  }

  @Override
  public TrieNode visit(final LeafNode leafNode, final TrieKey key, final int depth) {
    maybeTrackNode(leafNode);
    return super.visit(leafNode, key, depth);
  }

  @Override
  public TrieNode visit(final BranchNode branchNode, final TrieKey key, final int depth) {
    maybeTrackNode(branchNode);
    return super.visit(branchNode, key, depth);
  }

  public List<TrieNode> getProof() {
    return proof;
  }

  private void maybeTrackNode(final TrieNode node) {
    // Proofs include the root plus any child node that is referenced by hash. Inline children are
    // already covered by their parent encoding, so adding them separately would be redundant.
    if (node == rootNode || node.isReferencedByHash()) {
      proof.add(node);
    }
  }
}
