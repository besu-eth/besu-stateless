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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.factory;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.core.ParallelStoredPartitionedBinaryTrie;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.core.StoredPartitionedBinaryTrie;
import org.hyperledger.besu.ethereum.trie.NodeLoader;

import java.util.concurrent.ForkJoinPool;

import org.apache.tuweni.bytes.Bytes32;

/**
 * Single entry point for constructing stored partitioned binary trie instances.
 *
 * <p>Creates sequential and parallel stored tries from a shared {@link StoredTrieNodeFactory}
 * backed by the given {@link NodeLoader}.
 */
public final class PartitionedBinaryTrieFactory {

  private final StoredTrieNodeFactory nodeFactory;

  public PartitionedBinaryTrieFactory(final NodeLoader nodeLoader) {
    this.nodeFactory = new StoredTrieNodeFactory(nodeLoader);
  }

  public StoredPartitionedBinaryTrie create() {
    return new StoredPartitionedBinaryTrie(nodeFactory);
  }

  public StoredPartitionedBinaryTrie create(final Bytes32 rootHash) {
    return new StoredPartitionedBinaryTrie(nodeFactory, rootHash);
  }

  public ParallelStoredPartitionedBinaryTrie createParallel() {
    return new ParallelStoredPartitionedBinaryTrie(nodeFactory);
  }

  public ParallelStoredPartitionedBinaryTrie createParallel(final Bytes32 rootHash) {
    return new ParallelStoredPartitionedBinaryTrie(nodeFactory, rootHash);
  }

  public ParallelStoredPartitionedBinaryTrie createParallel(final ForkJoinPool forkJoinPool) {
    return new ParallelStoredPartitionedBinaryTrie(nodeFactory, forkJoinPool);
  }

  public ParallelStoredPartitionedBinaryTrie createParallel(
      final Bytes32 rootHash, final ForkJoinPool forkJoinPool) {
    return new ParallelStoredPartitionedBinaryTrie(nodeFactory, rootHash, forkJoinPool);
  }
}
