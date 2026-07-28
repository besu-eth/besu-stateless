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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.node;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.factory.NodeLoaderMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.factory.NodeUpdaterMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.factory.StoredTrieNodeFactory;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.reference.BinaryTrie;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Focused tests for concrete {@link TrieNode} implementations.
 *
 * <p>Layer: stored node. PBT is non-sparse: {@link MemoryLeafNode#remove} returns {@link
 * TrieNode#empty()}, not a zero-valued leaf. Root hashes compared against {@link BinaryTrie}.
 */
class StoredTrieNodeBehaviorTest {

  private NodeUpdaterMock updater;
  private StoredTrieNodeFactory factory;

  @BeforeEach
  void setUp() {
    updater = new NodeUpdaterMock();
    factory = new StoredTrieNodeFactory(new NodeLoaderMock(updater));
  }

  /**
   * Empty trie sentinel: get is always absent, put creates a leaf, remove is a no-op.
   */
  @Nested
  class EmptyTrieNodeTests {

    @Test
    void getAlwaysAbsent() {
      final Bytes key = Bytes.fromHexString("0x01");
      assertThat(TrieNode.empty().get(key.toArrayUnsafe(), key.size(), 0)).isEmpty();
    }

    @Test
    void putCreatesLeaf() {
      final Bytes key = Bytes.fromHexString("0x01");
      final byte[] value = Bytes32.repeat((byte) 1).toArrayUnsafe();
      final TrieNode leaf = TrieNode.empty().put(key.toArrayUnsafe(), key.size(), value, 0);
      assertThat(leaf).isInstanceOf(MemoryLeafNode.class);
      assertThat(leaf.get(key.toArrayUnsafe(), key.size(), 0)).contains(value);
    }

    @Test
    void removeIsNoOp() {
      assertThat(TrieNode.empty().remove(new byte[] {1}, 1, 0)).isSameAs(TrieNode.empty());
    }

    @Test
    void merkleHashIs32ZeroBytes() {
      assertThat(TrieNode.empty().merkleHashBytes()).isEqualTo(new byte[32]);
    }
  }

  /**
   * In-memory leaf CRUD: remove deletes the leaf, put replaces value on same key.
   */
  @Nested
  class MemoryLeafNodeTests {

    @Test
    void removeMatchingKeyDeletesLeaf() {
      final Bytes key = Bytes.fromHexString("0xbeef");
      final byte[] value = Bytes32.repeat((byte) 0x55).toArrayUnsafe();
      final TrieNode leaf = new MemoryLeafNode(key.toArrayUnsafe(), key.size(), value, false);
      assertThat(leaf.remove(key.toArrayUnsafe(), key.size(), 0)).isSameAs(TrieNode.empty());
    }

    @Test
    void removeNonMatchingKeyIsNoOp() {
      final Bytes key = Bytes.fromHexString("0xbeef");
      final Bytes other = Bytes.fromHexString("0xcafe");
      final byte[] value = Bytes32.repeat((byte) 1).toArrayUnsafe();
      final TrieNode leaf = new MemoryLeafNode(key.toArrayUnsafe(), key.size(), value, false);
      assertThat(leaf.remove(other.toArrayUnsafe(), other.size(), 0)).isSameAs(leaf);
    }

    @Test
    void putSameKeyReplacesValue() {
      final Bytes key = Bytes.fromHexString("0x01");
      final byte[] v1 = Bytes32.repeat((byte) 1).toArrayUnsafe();
      final byte[] v2 = Bytes32.repeat((byte) 2).toArrayUnsafe();
      final TrieNode leaf = new MemoryLeafNode(key.toArrayUnsafe(), key.size(), v1, false);
      final TrieNode updated = leaf.put(key.toArrayUnsafe(), key.size(), v2, 0);
      assertThat(updated.get(key.toArrayUnsafe(), key.size(), 0)).contains(v2);
    }
  }

  /**
   * Branch collapse to single child on remove; commit persists branch and children.
   */
  @Nested
  class MemoryBranchNodeTests {

    @Test
    void collapseToSingleChildOnRemove() {
      final Bytes keyA = Bytes.fromHexString("0xaaaa");
      final Bytes keyB = Bytes.fromHexString("0xbbbb");
      final byte[] valueA = Bytes32.repeat((byte) 0x01).toArrayUnsafe();
      final byte[] valueB = Bytes32.repeat((byte) 0x02).toArrayUnsafe();

      TrieNode root =
          new MemoryLeafNode(keyA.toArrayUnsafe(), keyA.size(), valueA, false)
              .put(keyB.toArrayUnsafe(), keyB.size(), valueB, 0);
      assertThat(root).isInstanceOf(MemoryBranchNode.class);

      root = root.remove(keyB.toArrayUnsafe(), keyB.size(), 0);
      assertThat(root).isInstanceOf(MemoryLeafNode.class);
      assertThat(root.get(keyA.toArrayUnsafe(), keyA.size(), 0)).contains(valueA);
      assertThat(root.get(keyB.toArrayUnsafe(), keyB.size(), 0)).isEmpty();
    }

    @Test
    void commitStoresBranchAndChildren() {
      final Bytes keyA = Bytes.fromHexString("0x10");
      final Bytes keyB = Bytes.fromHexString("0x20");
      final byte[] valueA = Bytes32.repeat((byte) 0xAA).toArrayUnsafe();
      final byte[] valueB = Bytes32.repeat((byte) 0xBB).toArrayUnsafe();

      TrieNode root =
          new MemoryLeafNode(keyA.toArrayUnsafe(), keyA.size(), valueA, false)
              .put(keyB.toArrayUnsafe(), keyB.size(), valueB, 0);
      root.commit(Bytes.EMPTY, updater);
      assertThat(updater.storage).isNotEmpty();
      assertThat(root.isClean()).isTrue();
    }
  }

  /**
   * Lazy-loaded {@link StoredTrieNode} proxy after commit; root hash matches {@link BinaryTrie}.
   */
  @Nested
  class StoredTrieNodeTests {

    @Test
    void lazyLoadFromFactory() {
      final Bytes key = Bytes.fromHexString("0xabcd");
      final byte[] value = Bytes32.repeat((byte) 0x77).toArrayUnsafe();
      final MemoryLeafNode leaf = new MemoryLeafNode(key.toArrayUnsafe(), key.size(), value, false);
      leaf.commit(Bytes.EMPTY, updater);
      final Bytes32 hash = Bytes32.wrap(leaf.merkleHashBytes());

      final StoredTrieNode proxy = new StoredTrieNode(factory, Bytes.EMPTY, hash);
      assertThat(proxy.get(key.toArrayUnsafe(), key.size(), 0)).contains(value);
      assertThat(proxy.isClean()).isTrue();
    }

    @Test
    void rootHashMatchesSpecOracle() {
      final Bytes key = Bytes.fromHexString("0x0102");
      final Bytes32 value = Bytes32.repeat((byte) 0x33);
      final BinaryTrie spec = new BinaryTrie();
      spec.put(key, value);

      final MemoryLeafNode leaf =
          new MemoryLeafNode(key.toArrayUnsafe(), key.size(), value.toArrayUnsafe(), false);
      leaf.commit(Bytes.EMPTY, updater);
      final StoredTrieNode proxy =
          new StoredTrieNode(factory, Bytes.EMPTY, Bytes32.wrap(leaf.merkleHashBytes()));
      assertThat(Bytes32.wrap(proxy.merkleHashBytes())).isEqualTo(spec.root());
    }
  }
}
