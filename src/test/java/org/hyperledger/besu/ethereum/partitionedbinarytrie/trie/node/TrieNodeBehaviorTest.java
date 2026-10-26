/*
 * Copyright contributors to Besu.
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
 */
package org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieKey;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.NodeLoaderMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.NodeUpdaterMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.StoredTrieNodeFactory;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.visitor.GetVisitor;

import java.util.concurrent.atomic.AtomicInteger;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;

/** Empty node and stored node proxy. */
class TrieNodeBehaviorTest {

  private final NodeUpdaterMock updater = new NodeUpdaterMock();
  private final AtomicInteger loads = new AtomicInteger();
  private final NodeLoaderMock loader = new NodeLoaderMock(updater);
  private final StoredTrieNodeFactory factory =
      new StoredTrieNodeFactory(
          (location, hash) -> {
            loads.incrementAndGet();
            return loader.getNode(location, hash);
          });

  @Test
  void emptyNodeHashesToZerosAndEncodesToNothing() {
    assertThat(TrieNode.empty().merkleHashBytes()).isEqualTo(new byte[32]);
    assertThat(TrieNode.empty().encode()).isEqualTo(Bytes.EMPTY);
  }

  @Test
  void storedNodeLoadsOnceAndOnlyWhenRead() {
    final byte[] key = Bytes.fromHexString("0xabcd").toArrayUnsafe();
    final byte[] value = Bytes32.repeat((byte) 0x77).toArrayUnsafe();
    final LeafNode leaf = new LeafNode(key, key.length, value, false);
    leaf.commit(Bytes.EMPTY, updater);
    final StoredTrieNode proxy =
        new StoredTrieNode(factory, Bytes.EMPTY, Bytes32.wrap(leaf.merkleHashBytes()));

    assertThat(proxy.merkleHashBytes()).isEqualTo(leaf.merkleHashBytes());
    assertThat(proxy.isClean()).isTrue();
    assertThat(loads).hasValue(0);

    for (int i = 0; i < 2; i++) {
      assertThat(proxy.accept(new GetVisitor(), TrieKey.of(key, key.length), 0).leafValue())
          .contains(value);
    }
    assertThat(loads).hasValue(1);
  }

  @Test
  void lazyLocationIsComputedOnceAndOnlyWhenAskedFor() {
    final AtomicInteger calls = new AtomicInteger();
    final StoredTrieNode stub =
        new StoredTrieNode(
            factory,
            () -> {
              calls.incrementAndGet();
              return Bytes.of(0, 1, 1);
            },
            Bytes32.repeat((byte) 0x11));

    assertThat(stub.merkleHashBytes()).isEqualTo(Bytes32.repeat((byte) 0x11).toArrayUnsafe());
    assertThat(calls).hasValue(0);
    assertThat(stub.storageLocation()).isEqualTo(Bytes.of(0, 1, 1));
    assertThat(stub.storageLocation()).isEqualTo(Bytes.of(0, 1, 1));
    assertThat(calls).hasValue(1);
  }

  @Test
  void markDirtyIsRejected() {
    final StoredTrieNode proxy =
        new StoredTrieNode(factory, Bytes.EMPTY, Bytes32.repeat((byte) 0x11));
    assertThatThrownBy(proxy::markDirty)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot ever be dirty");
  }
}
