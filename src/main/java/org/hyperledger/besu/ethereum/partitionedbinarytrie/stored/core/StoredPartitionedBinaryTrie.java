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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.core;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.factory.StoredTrieNodeFactory;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.node.TrieNode;
import org.hyperledger.besu.ethereum.trie.NodeLoader;
import org.hyperledger.besu.ethereum.trie.NodeUpdater;

import org.apache.tuweni.bytes.Bytes32;

/**
 * Storage-backed partitioned binary trie with lazy node loading from Besu {@code NodeLoader}.
 *
 * <p>After {@link #commit(NodeUpdater)}, the in-memory root is replaced with a clean stored-node
 * proxy so subsequent reads reload from disk only when needed.
 */
public class StoredPartitionedBinaryTrie extends PartitionedBinaryTrie {

  protected final StoredTrieNodeFactory nodeFactory;

  public StoredPartitionedBinaryTrie(final NodeLoader nodeLoader) {
    this(new StoredTrieNodeFactory(nodeLoader));
  }

  public StoredPartitionedBinaryTrie(final NodeLoader nodeLoader, final Bytes32 rootHash) {
    this(new StoredTrieNodeFactory(nodeLoader), rootHash);
  }

  public StoredPartitionedBinaryTrie(final StoredTrieNodeFactory nodeFactory) {
    super(nodeFactory.retrieveRoot());
    this.nodeFactory = nodeFactory;
  }

  public StoredPartitionedBinaryTrie(
      final StoredTrieNodeFactory nodeFactory, final Bytes32 rootHash) {
    super(
        rootHash.equals(Bytes32.ZERO)
            ? TrieNode.empty()
            : nodeFactory.wrapStored(org.apache.tuweni.bytes.Bytes.EMPTY, rootHash));
    this.nodeFactory = nodeFactory;
  }

  public void commit(final NodeUpdater nodeUpdater) {
    super.commit(nodeUpdater, nodeFactory);
  }
}
