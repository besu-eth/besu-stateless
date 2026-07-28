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

import java.util.Optional;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Read-path benchmarks for {@link StoredPartitionedBinaryTrie}.
 *
 * <p>Sample (JDK 21, M2 Pro, 1K keys): warm single get ~3.6 µs, cold reload get ~5 µs.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@State(Scope.Benchmark)
public class PartitionedBinaryTrieGetBenchmark {

  private static final int TRIE_SIZE = 1_000;

  @Param({"ACCOUNT_BASIC", "STORAGE_SLOT"})
  public KeyStyle keyStyle;

  @Param({"WARM", "COLD"})
  public String cacheMode;

  @Param({"SINGLE", "RANDOM"})
  public String keySelection;

  private InMemoryTrieBackend backend;
  private PartitionedBinaryTrieFactory factory;
  private StoredPartitionedBinaryTrie warmTrie;
  private Bytes32 committedRoot;
  private byte[][] keys;
  private int[] keyLens;
  private int randomIndex;

  @Setup(Level.Trial)
  public void prepareTrie() {
    backend = new InMemoryTrieBackend();
    factory = backend.factory();
    keys = TrieBenchmarkFixtures.keys(TRIE_SIZE, keyStyle);
    keyLens = TrieBenchmarkFixtures.keyLengths(keys);
    committedRoot =
        TrieBenchmarkFixtures.committedRoot(factory, backend.updater(), TRIE_SIZE, keyStyle);
    warmTrie = factory.create(committedRoot);
    randomIndex = 0;
  }

  @Benchmark
  public void get(final Blackhole blackhole) {
    final byte[] key;
    final int keyLen;
    if ("SINGLE".equals(keySelection)) {
      key = keys[0];
      keyLen = keyLens[0];
    } else {
      randomIndex = (randomIndex + 1) % keys.length;
      key = keys[randomIndex];
      keyLen = keyLens[randomIndex];
    }

    final Optional<byte[]> result;
    if ("COLD".equals(cacheMode)) {
      final StoredPartitionedBinaryTrie reloaded = factory.create(committedRoot);
      result = reloaded.get(key, keyLen);
    } else {
      result = warmTrie.get(key, keyLen);
    }
    blackhole.consume(result);
  }
}
