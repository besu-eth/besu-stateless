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
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.reference.BinaryTrie;

import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Create-read-update-delete for {@link StoredPartitionedBinaryTrie} through the factory and mock
 * persistence layer.
 *
 * <p>Layer: stored core ({@link PartitionedBinaryTrieFactory}, {@link NodeLoaderMock}). Root hashes
 * and deferred puts are compared against the in-memory {@link BinaryTrie} spec oracle.
 */
class StoredPartitionedBinaryTrieCrudTest {

  private NodeUpdaterMock nodeUpdater;
  private NodeLoaderMock nodeLoader;
  private PartitionedBinaryTrieFactory factory;

  @BeforeEach
  void setUp() {
    nodeUpdater = new NodeUpdaterMock();
    nodeLoader = new NodeLoaderMock(nodeUpdater);
    factory = new PartitionedBinaryTrieFactory(nodeLoader);
  }

  @Test
  void emptyTrieRoundTrip() {
    final StoredPartitionedBinaryTrie trie = factory.create();
    trie.commit(nodeUpdater);

    final StoredPartitionedBinaryTrie reloaded = factory.create();
    assertThat(reloaded.getRootHash()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);
  }

  @Test
  void putCommitAndReload() {
    final Bytes key =
        Bytes.fromHexString("0x00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff");
    final Bytes32 value =
        Bytes32.fromHexString("0x1000000000000000000000000000000000000000000000000000000000000000");

    final StoredPartitionedBinaryTrie trie = factory.create();
    trie.put(key.toArray(), key.size(), value.toArray());
    trie.commit(nodeUpdater);

    final StoredPartitionedBinaryTrie reloaded = factory.create(trie.getRootHash());
    assertThat(reloaded.get(key.toArray(), key.size())).contains(value.toArray());
    assertThat(reloaded.getRootHash()).isEqualTo(trie.getRootHash());
  }

  @Test
  void rootMatchesSpecTrie() {
    final Bytes key =
        Bytes.concatenate(Bytes.of((byte) 0), Bytes.repeat((byte) 0x42, 32), Bytes.of((byte) 0x07));
    final Bytes32 value = Bytes32.repeat((byte) 0x11);

    final BinaryTrie specTrie = new BinaryTrie();
    specTrie.put(key, value);

    final StoredPartitionedBinaryTrie storedTrie = factory.create();
    storedTrie.put(key.toArray(), key.size(), value.toArray());
    assertThat(storedTrie.getRootHash()).isEqualTo(specTrie.root());
  }

  @Test
  void putDeferredAndRemove() {
    final Bytes key = Bytes.fromHexString("0xabcd");
    final Bytes32 value = Bytes32.repeat((byte) 0x02);

    final StoredPartitionedBinaryTrie trie = new StoredPartitionedBinaryTrie(nodeLoader);
    trie.putDeferred(key, existing -> Optional.of(value));
    assertThat(trie.get(key)).contains(value);

    trie.putDeferred(key, existing -> existing);
    assertThat(trie.get(key)).contains(value);

    trie.remove(key);
    assertThat(trie.get(key)).isEmpty();
  }
}
