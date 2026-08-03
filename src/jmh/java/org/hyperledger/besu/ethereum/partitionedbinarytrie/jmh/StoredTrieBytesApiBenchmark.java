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
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.StoredPartitionedBinaryTrie;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.apache.tuweni.bytes.Bytes;
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
 * Stored trie Bytes API overhead: Tuweni {@link Bytes} boundary vs primitive trie operations.
 *
 * <p>Sample (JDK 21, M2 Pro): stored trie get ~3.6 µs, put ~3.5 µs for 34-byte keys.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@State(Scope.Benchmark)
public class StoredTrieBytesApiBenchmark {

  private static final int TRIE_SIZE = 1_000;

  @Param({"ACCOUNT_BASIC", "STORAGE_SLOT"})
  public KeyStyle keyStyle;

  private InMemoryTrieBackend backend;
  private StoredPartitionedBinaryTrie trie;
  private Bytes key;
  private Bytes value;
  private Bytes32 committedRoot;

  @Setup(Level.Trial)
  public void prepareTrie() {
    backend = new InMemoryTrieBackend();
    final var factory = backend.factory();
    committedRoot =
        TrieBenchmarkFixtures.committedRoot(factory, backend.updater(), TRIE_SIZE, keyStyle);
    trie = factory.create(committedRoot);
    key = TrieBenchmarkFixtures.keyBytes(0, keyStyle);
    value = Bytes.wrap(TrieBenchmarkFixtures.valueForIndex(0));
  }

  @Benchmark
  public void storedTrieGet(final Blackhole blackhole) {
    final Optional<Bytes> result = trie.get(key);
    blackhole.consume(result);
  }

  @Benchmark
  public void storedTriePut(final Blackhole blackhole) {
    // Overwrite an existing key on the committed trie (steady-state, no trie growth).
    trie.put(key, value);
    blackhole.consume(trie.getRootHash());
  }
}
