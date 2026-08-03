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

import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.BranchNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.EmptyTrieNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.LeafNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.StoredTrieNode;

import org.apache.tuweni.bytes.Bytes;

/**
 * Visitor for storage-location-keyed trie traversal (commit, persist).
 *
 * <p>Mirrors Besu {@link org.hyperledger.besu.ethereum.trie.LocationNodeVisitor} for the
 * partitioned binary trie.
 */
public interface LocationNodeVisitor {

  /** Visits an empty trie node at {@code location}. */
  void visit(Bytes location, EmptyTrieNode emptyNode);

  /** Visits a leaf node at {@code location}. */
  void visit(Bytes location, LeafNode leafNode);

  /** Visits a branch node at {@code location}. */
  void visit(Bytes location, BranchNode branchNode);

  /** Visits a stored node proxy at {@code location}. */
  void visit(Bytes location, StoredTrieNode storedNode);
}
