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

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.internal.TrieConstants;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.factory.NodeLoaderMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.factory.NodeUpdaterMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.factory.PartitionedBinaryTrieFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Correctness of {@link ParallelStoredPartitionedBinaryTrie} against the sequential stored trie.
 *
 * <p>Layer: stored core (parallel commit path). Each operation is mirrored on {@link
 * StoredPartitionedBinaryTrie}; root hash, values, and persisted node sets must match.
 */
class ParallelStoredPartitionedBinaryTrieCorrectnessTest {

  private NodeUpdaterMock parallelUpdater;
  private NodeUpdaterMock sequentialUpdater;
  private NodeLoaderMock parallelLoader;
  private NodeLoaderMock sequentialLoader;
  private ParallelStoredPartitionedBinaryTrie parallelTrie;
  private StoredPartitionedBinaryTrie sequentialTrie;

  @BeforeEach
  void setUp() {
    parallelUpdater = new NodeUpdaterMock();
    sequentialUpdater = new NodeUpdaterMock();
    parallelLoader = new NodeLoaderMock(parallelUpdater);
    sequentialLoader = new NodeLoaderMock(sequentialUpdater);

    parallelTrie = new ParallelStoredPartitionedBinaryTrie(parallelLoader);
    sequentialTrie = new StoredPartitionedBinaryTrie(sequentialLoader);
  }

  @Test
  void shouldCreateEmptyTrie() {
    assertThat(parallelTrie.getRootHash()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);
    assertThat(parallelTrie.isEmpty()).isTrue();
  }

  @Test
  void shouldPutAndGetSingleKey() {
    final Bytes key = Bytes.fromHexString("0x1122");
    final Bytes32 value = Bytes32.repeat((byte) 0xAB);

    parallelTrie.put(key, value);
    parallelTrie.commit(parallelUpdater);

    assertThat(parallelTrie.get(key)).contains(value);
  }

  @Test
  void shouldHandleMultipleKeys() {
    final int numEntries = 100;

    for (int i = 1; i <= numEntries; i++) {
      final Bytes key = createKey(i);
      final Bytes32 value = createValue(i);
      parallelTrie.put(key, value);
      sequentialTrie.put(key, value);
    }

    parallelTrie.commit(parallelUpdater);
    sequentialTrie.commit(sequentialUpdater);

    for (int i = 1; i <= numEntries; i++) {
      final Bytes key = createKey(i);
      final Bytes32 value = createValue(i);
      assertThat(parallelTrie.get(key)).contains(value);
    }

    assertThat(parallelTrie.getRootHash()).isEqualTo(sequentialTrie.getRootHash());
  }

  @Test
  void shouldRemoveKey() {
    final Bytes key = Bytes.fromHexString("0x01020304");
    final Bytes32 value = createValue(100);

    parallelTrie.put(key, value);
    sequentialTrie.put(key, value);
    parallelTrie.commit(parallelUpdater);
    sequentialTrie.commit(sequentialUpdater);

    parallelTrie.remove(key);
    sequentialTrie.remove(key);
    parallelTrie.commit(parallelUpdater);
    sequentialTrie.commit(sequentialUpdater);

    assertThat(parallelTrie.get(key)).isEmpty();
    assertThat(parallelTrie.getRootHash()).isEqualTo(sequentialTrie.getRootHash());
  }

  @Test
  void shouldUpdateKey() {
    final Bytes key = Bytes.fromHexString("0x01020304");
    final Bytes32 value1 = createValue(100);
    final Bytes32 value2 = createValue(200);

    parallelTrie.put(key, value1);
    sequentialTrie.put(key, value1);
    parallelTrie.commit(parallelUpdater);
    sequentialTrie.commit(sequentialUpdater);

    parallelTrie.put(key, value2);
    sequentialTrie.put(key, value2);
    parallelTrie.commit(parallelUpdater);
    sequentialTrie.commit(sequentialUpdater);

    assertThat(parallelTrie.get(key)).contains(value2);
    assertThat(parallelTrie.getRootHash()).isEqualTo(sequentialTrie.getRootHash());
  }

  @Test
  void shouldHandleKeysWithCommonPrefix() {
    for (int i = 0; i < 20; i++) {
      final Bytes key = createKey(0x01020300 | i);
      final Bytes32 value = createValue(i);
      parallelTrie.put(key, value);
      sequentialTrie.put(key, value);
    }

    parallelTrie.commit(parallelUpdater);
    sequentialTrie.commit(sequentialUpdater);

    for (int i = 0; i < 20; i++) {
      final Bytes key = createKey(0x01020300 | i);
      assertThat(parallelTrie.get(key)).contains(createValue(i));
    }

    assertThat(parallelTrie.getRootHash()).isEqualTo(sequentialTrie.getRootHash());
  }

  @Test
  void shouldHandleDivergingKeysAtDifferentDepths() {
    final Bytes key1 = createKey(0x01000000);
    final Bytes key2 = createKey(0x02000000);
    final Bytes key3 = createKey(0x03040501);
    final Bytes key4 = createKey(0x03040502);

    parallelTrie.put(key1, createValue(1));
    parallelTrie.put(key2, createValue(2));
    parallelTrie.put(key3, createValue(3));
    parallelTrie.put(key4, createValue(4));

    sequentialTrie.put(key1, createValue(1));
    sequentialTrie.put(key2, createValue(2));
    sequentialTrie.put(key3, createValue(3));
    sequentialTrie.put(key4, createValue(4));

    parallelTrie.commit(parallelUpdater);
    sequentialTrie.commit(sequentialUpdater);

    assertThat(parallelTrie.get(key1)).contains(createValue(1));
    assertThat(parallelTrie.get(key2)).contains(createValue(2));
    assertThat(parallelTrie.get(key3)).contains(createValue(3));
    assertThat(parallelTrie.get(key4)).contains(createValue(4));
    assertThat(parallelTrie.getRootHash()).isEqualTo(sequentialTrie.getRootHash());
  }

  @Test
  void shouldBatchRemoveTwoKeysAfterCommit() {
    parallelTrie.put(createKey(1), createValue(1));
    parallelTrie.put(createKey(2), createValue(2));
    parallelTrie.commit(parallelUpdater);

    parallelTrie.remove(createKey(1));
    parallelTrie.remove(createKey(2));
    parallelTrie.commit(parallelUpdater);

    assertThat(parallelTrie.get(createKey(1))).isEmpty();
    assertThat(parallelTrie.get(createKey(2))).isEmpty();
  }

  @Test
  void shouldBatchRemoveAfterCommit() {
    for (int i = 1; i <= 10; i++) {
      parallelTrie.put(createKey(i), createValue(i));
      sequentialTrie.put(createKey(i), createValue(i));
    }
    parallelTrie.commit(parallelUpdater);
    sequentialTrie.commit(sequentialUpdater);

    for (int i = 1; i <= 10; i++) {
      parallelTrie.remove(createKey(i));
      sequentialTrie.remove(createKey(i));
    }
    parallelTrie.commit(parallelUpdater);
    sequentialTrie.commit(sequentialUpdater);

    for (int i = 1; i <= 10; i++) {
      assertThat(parallelTrie.get(createKey(i))).isEmpty();
    }
    assertThat(parallelTrie.getRootHash()).isEqualTo(sequentialTrie.getRootHash());
  }

  @Test
  void shouldHandleMixedOperationsOnKeys() {
    final int numKeys = 50;
    final Bytes[] keys = new Bytes[numKeys];

    for (int i = 0; i < numKeys; i++) {
      keys[i] = createKey(i + 1);
      parallelTrie.put(keys[i], createValue(i));
      sequentialTrie.put(keys[i], createValue(i));
    }
    parallelTrie.commit(parallelUpdater);
    sequentialTrie.commit(sequentialUpdater);

    for (int i = 0; i < 20; i++) {
      parallelTrie.remove(keys[i]);
      sequentialTrie.remove(keys[i]);
    }

    for (int i = 20; i < 35; i++) {
      parallelTrie.put(keys[i], createValue(i * 10));
      sequentialTrie.put(keys[i], createValue(i * 10));
    }

    parallelTrie.commit(parallelUpdater);
    sequentialTrie.commit(sequentialUpdater);

    for (int i = 0; i < 20; i++) {
      assertThat(parallelTrie.get(keys[i])).isEmpty();
    }
    for (int i = 20; i < 35; i++) {
      assertThat(parallelTrie.get(keys[i])).contains(createValue(i * 10));
    }
    for (int i = 35; i < numKeys; i++) {
      assertThat(parallelTrie.get(keys[i])).contains(createValue(i));
    }

    assertThat(parallelTrie.getRootHash()).isEqualTo(sequentialTrie.getRootHash());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 10, 50, 100, 200})
  void shouldHandleVariousKeyBatchSizes(final int size) {
    for (int i = 1; i <= size; i++) {
      final Bytes key = createKey(i);
      final Bytes32 value = createValue(i);
      parallelTrie.put(key, value);
      sequentialTrie.put(key, value);
    }

    parallelTrie.commit(parallelUpdater);
    sequentialTrie.commit(sequentialUpdater);

    for (int i = 1; i <= size; i++) {
      assertThat(parallelTrie.get(createKey(i))).contains(createValue(i));
    }

    assertThat(parallelTrie.getRootHash()).isEqualTo(sequentialTrie.getRootHash());
  }

  @Test
  void shouldPutDeferredUpdateAndRemove() {
    final Bytes key = Bytes.fromHexString("0x01020304");
    final Bytes32 value = createValue(100);
    final Bytes32 updatedValue = createValue(200);

    parallelTrie.put(key, value);
    sequentialTrie.put(key, value);
    parallelTrie.commit(parallelUpdater);
    sequentialTrie.commit(sequentialUpdater);

    parallelTrie.putDeferred(key, prior -> Optional.of(updatedValue));
    sequentialTrie.putDeferred(key, prior -> Optional.of(updatedValue));
    parallelTrie.commit(parallelUpdater);
    sequentialTrie.commit(sequentialUpdater);

    assertThat(parallelTrie.get(key)).contains(updatedValue);
    assertThat(parallelTrie.getRootHash()).isEqualTo(sequentialTrie.getRootHash());

    parallelTrie.putDeferred(key, prior -> Optional.empty());
    sequentialTrie.putDeferred(key, prior -> Optional.empty());
    parallelTrie.commit(parallelUpdater);
    sequentialTrie.commit(sequentialUpdater);

    assertThat(parallelTrie.get(key)).isEmpty();
    assertThat(parallelTrie.getRootHash()).isEqualTo(sequentialTrie.getRootHash());
  }

  @Test
  void shouldLoadStoredKeys() {
    final int numKeys = 10;
    final Bytes[] keys = new Bytes[numKeys];

    for (int i = 0; i < numKeys; i++) {
      keys[i] = createKey(i + 1);
      parallelTrie.put(keys[i], createValue(i));
    }
    parallelTrie.commit(parallelUpdater);

    final Bytes32 rootHash = parallelTrie.getRootHash();
    final ParallelStoredPartitionedBinaryTrie reloaded =
        new ParallelStoredPartitionedBinaryTrie(parallelLoader, rootHash);

    for (int i = 0; i < numKeys; i++) {
      assertThat(reloaded.get(keys[i])).contains(createValue(i));
    }
  }

  @Test
  void shouldProduceConsistentRootHashAcrossMultipleBuilds() {
    final int numKeys = 50;
    final Bytes[] keys = new Bytes[numKeys];
    for (int i = 0; i < numKeys; i++) {
      keys[i] = createKey(i + 1);
    }

    final Bytes32[] rootHashes = new Bytes32[3];

    for (int iteration = 0; iteration < 3; iteration++) {
      final NodeUpdaterMock freshUpdater = new NodeUpdaterMock();
      final NodeLoaderMock freshLoader = new NodeLoaderMock(freshUpdater);
      final ParallelStoredPartitionedBinaryTrie freshTrie =
          new ParallelStoredPartitionedBinaryTrie(freshLoader);

      for (int i = 0; i < numKeys; i++) {
        freshTrie.put(keys[i], createValue(i));
      }
      freshTrie.commit(freshUpdater);
      rootHashes[iteration] = freshTrie.getRootHash();
    }

    assertThat(rootHashes[0]).isEqualTo(rootHashes[1]);
    assertThat(rootHashes[1]).isEqualTo(rootHashes[2]);
  }

  @Test
  void visitAllParallelCompletes() throws Exception {
    for (int i = 1; i <= 10; i++) {
      parallelTrie.put(createKey(i), createValue(i));
    }
    parallelTrie.commit(parallelUpdater);

    final List<PartitionedBinaryTrie.TrieNodeView> visited = new ArrayList<>();
    parallelTrie.visitAll(visited::add, Executors.newFixedThreadPool(2)).get();

    assertThat(visited).isNotEmpty();
    assertThat(visited.stream().anyMatch(PartitionedBinaryTrie.TrieNodeView::isLeaf)).isTrue();
  }

  @Test
  void factoryCreatesParallelTrie() {
    final PartitionedBinaryTrieFactory factory =
        new PartitionedBinaryTrieFactory(parallelLoader);
    final ParallelStoredPartitionedBinaryTrie trie = factory.createParallel();
    assertThat(trie.getRootHash()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);
  }

  private static Bytes createKey(final int seed) {
    return Bytes.of(
        (byte) ((seed >> 24) & 0xFF),
        (byte) ((seed >> 16) & 0xFF),
        (byte) ((seed >> 8) & 0xFF),
        (byte) (seed & 0xFF));
  }

  private static Bytes32 createValue(final int value) {
    return Bytes32.repeat((byte) (value & 0xFF));
  }
}
