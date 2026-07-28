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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.jmh;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.jmh.support.InMemoryTrieBackend;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.jmh.support.TrieBenchmarkFixtures;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.jmh.support.TrieBenchmarkFixtures.KeyStyle;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.core.ParallelStoredPartitionedBinaryTrie;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.core.StoredPartitionedBinaryTrie;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.factory.PartitionedBinaryTrieFactory;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Commit benchmarks and parallel vs sequential batch commit.
 *
 * <p>Sample (JDK 21, M2 Pro, ACCOUNT_BASIC): sequential commit 1K keys ~5.7 ms, parallel ~7.1
 * ms (parallel overhead dominates for in-memory batches at this scale).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@State(Scope.Benchmark)
public class PartitionedBinaryTrieCommitBenchmark {

  @Param({"100", "1000", "10000"})
  public int trieSize;

  @Param({"ACCOUNT_BASIC", "STORAGE_SLOT"})
  public KeyStyle keyStyle;

  private InMemoryTrieBackend backend;
  private PartitionedBinaryTrieFactory factory;

  @Setup(Level.Trial)
  public void prepareFactory() {
    backend = new InMemoryTrieBackend();
    factory = backend.factory();
  }

  @Benchmark
  public void commitSequential(final Blackhole blackhole) {
    final StoredPartitionedBinaryTrie trie = factory.create();
    TrieBenchmarkFixtures.populate(trie, trieSize, keyStyle);
    trie.commit(backend.updater());
    blackhole.consume(trie.getRootHash());
  }

  @Benchmark
  public void commitParallel(final Blackhole blackhole) {
    final ParallelStoredPartitionedBinaryTrie trie = factory.createParallel();
    TrieBenchmarkFixtures.populateParallel(trie, trieSize, keyStyle);
    trie.commit(backend.updater());
    blackhole.consume(trie.getRootHash());
  }
}
