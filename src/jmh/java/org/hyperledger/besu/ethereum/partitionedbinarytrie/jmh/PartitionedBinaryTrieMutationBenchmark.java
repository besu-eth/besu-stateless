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
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.core.StoredPartitionedBinaryTrie;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.factory.PartitionedBinaryTrieFactory;

import java.util.concurrent.TimeUnit;

import org.apache.tuweni.bytes.Bytes32;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Write-path benchmarks: put, remove, and root-hash after updates.
 *
 * <p>Sample (JDK 21, M2 Pro): single 34-byte put ~3.5 µs, batch 1K puts ~2.9 ms.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@State(Scope.Benchmark)
public class PartitionedBinaryTrieMutationBenchmark {

  @Param({"ACCOUNT_BASIC", "STORAGE_SLOT"})
  public KeyStyle keyStyle;

  private InMemoryTrieBackend backend;
  private PartitionedBinaryTrieFactory factory;
  private byte[][] keys;
  private int[] keyLens;
  private byte[][] values;
  private StoredPartitionedBinaryTrie trie;
  private int putIndex;

  @Setup(Level.Trial)
  public void prepareKeys() {
    backend = new InMemoryTrieBackend();
    factory = backend.factory();
    keys = TrieBenchmarkFixtures.keys(1_000, keyStyle);
    keyLens = TrieBenchmarkFixtures.keyLengths(keys);
    values = TrieBenchmarkFixtures.values(1_000);
    putIndex = 0;
  }

  @Setup(Level.Invocation)
  public void freshTrie() {
    trie = factory.create();
  }

  @TearDown(Level.Invocation)
  public void resetPutIndex() {
    putIndex = 0;
  }

  @Benchmark
  public void putSingle(final Blackhole blackhole) {
    trie.put(keys[putIndex], keyLens[putIndex], values[putIndex]);
    putIndex = (putIndex + 1) % keys.length;
    blackhole.consume(trie.getRootHash());
  }

  @Benchmark
  public void putBatchSequential(final Blackhole blackhole) {
    for (int i = 0; i < keys.length; i++) {
      trie.put(keys[i], keyLens[i], values[i]);
    }
    blackhole.consume(trie.getRootHash());
  }

  @Benchmark
  public void removeSingle(final Blackhole blackhole) {
    TrieBenchmarkFixtures.populate(trie, keys.length, keyStyle);
    trie.remove(keys[0], keyLens[0]);
    blackhole.consume(trie.getRootHash());
  }

  @Benchmark
  public void getRootHashAfterPuts(final Blackhole blackhole) {
    for (int i = 0; i < keys.length; i++) {
      trie.put(keys[i], keyLens[i], values[i]);
    }
    final Bytes32 root = trie.getRootHash();
    blackhole.consume(root);
  }
}
