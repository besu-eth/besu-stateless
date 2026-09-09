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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.visitor;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieKey;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.NodeLoaderMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.NodeUpdaterMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.StoredTrieNodeFactory;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.BranchNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.EmptyTrieNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.LeafNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.StoredTrieNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.TrieNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.reference.BinaryTrie;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Behavior of stored trie visitors: get, put, remove, and commit over {@link TrieNode}.
 *
 * <p>Layer: trie visitor. Exercises the in-memory node graph directly; commit path compares root
 * hash against {@link BinaryTrie}.
 */
class TrieVisitorBehaviorTest {

  private static TrieNode traverse(
      final TrieNode node, final PathNodeVisitor visitor, final byte[] key, final int keyLen) {
    return node.accept(visitor, TrieKey.of(key, keyLen), 0);
  }

  private NodeUpdaterMock updater;
  private StoredTrieNodeFactory factory;

  @BeforeEach
  void setUp() {
    updater = new NodeUpdaterMock();
    factory = new StoredTrieNodeFactory(new NodeLoaderMock(updater));
  }

  /** Lookup paths through {@link GetVisitor} on empty, leaf, and branch nodes. */
  @Nested
  class GetVisitorTests {

    private final GetVisitor visitor = new GetVisitor();

    @Test
    void emptyTrieReturnsEmptyNode() {
      final Bytes key = Bytes.fromHexString("0x01");
      final TrieNode result = traverse(TrieNode.empty(), visitor, key.toArrayUnsafe(), key.size());
      assertThat(result).isInstanceOf(EmptyTrieNode.class);
      assertThat(result.leafValue()).isEmpty();
    }

    @Test
    void matchingLeafReturnsLeafWithValue() {
      final Bytes key = Bytes.fromHexString("0xbeef");
      final byte[] value = Bytes32.repeat((byte) 0x55).toArrayUnsafe();
      final LeafNode leaf = new LeafNode(key.toArrayUnsafe(), key.size(), value, false);
      final TrieNode result = traverse(leaf, visitor, key.toArrayUnsafe(), key.size());
      assertThat(result).isSameAs(leaf);
      assertThat(result.leafValue()).contains(value);
    }

    @Test
    void nonMatchingLeafReturnsEmptyNode() {
      final Bytes key = Bytes.fromHexString("0xbeef");
      final Bytes other = Bytes.fromHexString("0xcafe");
      final byte[] value = Bytes32.repeat((byte) 1).toArrayUnsafe();
      final LeafNode leaf = new LeafNode(key.toArrayUnsafe(), key.size(), value, false);
      final TrieNode result = traverse(leaf, visitor, other.toArrayUnsafe(), other.size());
      assertThat(result).isInstanceOf(EmptyTrieNode.class);
    }

    @Test
    void branchDescendsToMatchingLeaf() {
      final Bytes keyA = Bytes.fromHexString("0x10");
      final Bytes keyB = Bytes.fromHexString("0x20");
      final byte[] valueB = Bytes32.repeat((byte) 0xBB).toArrayUnsafe();
      final TrieNode root =
          traverse(
              new LeafNode(
                  keyA.toArrayUnsafe(),
                  keyA.size(),
                  Bytes32.repeat((byte) 0xAA).toArrayUnsafe(),
                  false),
              new PutVisitor(valueB),
              keyB.toArrayUnsafe(),
              keyB.size());
      assertThat(traverse(root, visitor, keyB.toArrayUnsafe(), keyB.size()).leafValue())
          .contains(valueB);
    }
  }

  /** Insert and update semantics via {@link PutVisitor}, including leaf-to-branch splits. */
  @Nested
  class PutVisitorTests {

    @Test
    void insertsIntoEmptyTrie() {
      final Bytes key = Bytes.fromHexString("0x01");
      final byte[] value = Bytes32.repeat((byte) 2).toArrayUnsafe();
      final TrieNode root =
          traverse(TrieNode.empty(), new PutVisitor(value), key.toArrayUnsafe(), key.size());
      assertThat(root).isInstanceOf(LeafNode.class);
      assertThat(root.leafValue()).contains(value);
    }

    @Test
    void replacesExistingLeafValue() {
      final Bytes key = Bytes.fromHexString("0xabcd");
      final byte[] v1 = Bytes32.repeat((byte) 1).toArrayUnsafe();
      final byte[] v2 = Bytes32.repeat((byte) 2).toArrayUnsafe();
      TrieNode root =
          traverse(TrieNode.empty(), new PutVisitor(v1), key.toArrayUnsafe(), key.size());
      root = traverse(root, new PutVisitor(v2), key.toArrayUnsafe(), key.size());
      assertThat(root.leafValue()).contains(v2);
    }

    @Test
    void splitsLeafIntoBranch() {
      final Bytes keyA = Bytes.fromHexString("0xaaaa");
      final Bytes keyB = Bytes.fromHexString("0xbbbb");
      final byte[] valueA = Bytes32.repeat((byte) 0x01).toArrayUnsafe();
      final byte[] valueB = Bytes32.repeat((byte) 0x02).toArrayUnsafe();
      TrieNode root =
          traverse(TrieNode.empty(), new PutVisitor(valueA), keyA.toArrayUnsafe(), keyA.size());
      root = traverse(root, new PutVisitor(valueB), keyB.toArrayUnsafe(), keyB.size());
      assertThat(root).isInstanceOf(BranchNode.class);
      assertThat(traverse(root, new GetVisitor(), keyA.toArrayUnsafe(), keyA.size()).leafValue())
          .contains(valueA);
      assertThat(traverse(root, new GetVisitor(), keyB.toArrayUnsafe(), keyB.size()).leafValue())
          .contains(valueB);
    }
  }

  /** Deletion and branch collapse via {@link RemoveVisitor}. */
  @Nested
  class RemoveVisitorTests {

    private final RemoveVisitor visitor = new RemoveVisitor();

    @Test
    void emptyTrieReturnsEmptyNode() {
      final Bytes key = Bytes.fromHexString("0x01");
      final TrieNode result = traverse(TrieNode.empty(), visitor, key.toArrayUnsafe(), key.size());
      assertThat(result).isInstanceOf(EmptyTrieNode.class);
    }

    @Test
    void matchingLeafReturnsEmptyNode() {
      final Bytes key = Bytes.fromHexString("0xbeef");
      final byte[] value = Bytes32.repeat((byte) 0x55).toArrayUnsafe();
      final LeafNode leaf = new LeafNode(key.toArrayUnsafe(), key.size(), value, false);
      final TrieNode result = traverse(leaf, visitor, key.toArrayUnsafe(), key.size());
      assertThat(result).isInstanceOf(EmptyTrieNode.class);
    }

    @Test
    void nonMatchingLeafIsNoOp() {
      final Bytes key = Bytes.fromHexString("0xbeef");
      final Bytes other = Bytes.fromHexString("0xcafe");
      final byte[] value = Bytes32.repeat((byte) 1).toArrayUnsafe();
      final LeafNode leaf = new LeafNode(key.toArrayUnsafe(), key.size(), value, false);
      final TrieNode result = traverse(leaf, visitor, other.toArrayUnsafe(), other.size());
      assertThat(result).isSameAs(leaf);
    }

    @Test
    void branchCollapsesToSingleChildOnRemove() {
      final Bytes keyA = Bytes.fromHexString("0xaaaa");
      final Bytes keyB = Bytes.fromHexString("0xbbbb");
      final byte[] valueA = Bytes32.repeat((byte) 0x01).toArrayUnsafe();
      final byte[] valueB = Bytes32.repeat((byte) 0x02).toArrayUnsafe();

      TrieNode root =
          traverse(
              traverse(TrieNode.empty(), new PutVisitor(valueA), keyA.toArrayUnsafe(), keyA.size()),
              new PutVisitor(valueB),
              keyB.toArrayUnsafe(),
              keyB.size());
      assertThat(root).isInstanceOf(BranchNode.class);

      root = traverse(root, visitor, keyB.toArrayUnsafe(), keyB.size());
      assertThat(root).isInstanceOf(LeafNode.class);
      assertThat(traverse(root, new GetVisitor(), keyA.toArrayUnsafe(), keyA.size()).leafValue())
          .contains(valueA);
      assertThat(traverse(root, new GetVisitor(), keyB.toArrayUnsafe(), keyB.size()).leafValue())
          .isEmpty();
    }

    @Test
    void removeAbsentKeyIsNoOp() {
      final Bytes key = Bytes.fromHexString("0xabcd");
      final byte[] value = Bytes32.repeat((byte) 0x77).toArrayUnsafe();
      final TrieNode root =
          traverse(TrieNode.empty(), new PutVisitor(value), key.toArrayUnsafe(), key.size());
      final Bytes other = Bytes.fromHexString("0xdead");
      final TrieNode result = traverse(root, visitor, other.toArrayUnsafe(), other.size());
      assertThat(result).isSameAs(root);
    }

    @Test
    void keyEndingExactlyAtBranchPrefixIsNoOp() {
      // Build a branch whose compressed prefix consumes the entire key bit length when depth is 0
      // for a shorter lookup key that matches the prefix but has no split bit.
      final Bytes keyA = Bytes.fromHexString("0x00");
      final Bytes keyB = Bytes.fromHexString("0xff");
      final byte[] valueA = Bytes32.repeat((byte) 0x01).toArrayUnsafe();
      final byte[] valueB = Bytes32.repeat((byte) 0x02).toArrayUnsafe();
      TrieNode root =
          traverse(
              traverse(TrieNode.empty(), new PutVisitor(valueA), keyA.toArrayUnsafe(), keyA.size()),
              new PutVisitor(valueB),
              keyB.toArrayUnsafe(),
              keyB.size());
      assertThat(root).isInstanceOf(BranchNode.class);

      // Empty key: depth 0, bitCount 0 → RemoveVisitor must return the branch unchanged.
      final TrieNode unchanged = traverse(root, visitor, new byte[0], 0);
      assertThat(unchanged).isSameAs(root);
    }

    @Test
    void removeOnEmptyChildSideFlattensWithoutTouchingEmptySingleton() {
      // Key 0x00 → left; right remains EmptyTrieNode. Removing a right-side key flattens.
      final byte[] key = Bytes.fromHexString("0x00").toArrayUnsafe();
      final byte[] value = Bytes32.repeat((byte) 0x11).toArrayUnsafe();
      final LeafNode leaf = new LeafNode(key, key.length, value, false);
      final BranchNode branch = new BranchNode(new byte[0], 0, leaf, TrieNode.empty(), false);

      final Bytes rightKey = Bytes.fromHexString("0x80");
      final TrieNode result = traverse(branch, visitor, rightKey.toArrayUnsafe(), rightKey.size());
      assertThat(result).isInstanceOf(LeafNode.class);
      assertThat(result.leafValue()).contains(value);
      assertThat(branch.rightChild()).isSameAs(TrieNode.empty());
    }

    @Test
    void removeOnEmptyChildSideWithFlattenDisabledKeepsBranch() {
      final byte[] key = Bytes.fromHexString("0x00").toArrayUnsafe();
      final byte[] value = Bytes32.repeat((byte) 0x11).toArrayUnsafe();
      final BranchNode branch =
          new BranchNode(
              new byte[0], 0, new LeafNode(key, key.length, value, false), TrieNode.empty(), false);

      final Bytes rightKey = Bytes.fromHexString("0x80");
      final TrieNode result =
          traverse(branch, new RemoveVisitor(false), rightKey.toArrayUnsafe(), rightKey.size());
      assertThat(result).isSameAs(branch);
      assertThat(branch.rightChild()).isSameAs(TrieNode.empty());
      assertThat(branch.leftChild()).isInstanceOf(LeafNode.class);
    }
  }

  /** Storage of dirty nodes via {@link CommitVisitor}; root hash matches {@link BinaryTrie}. */
  @Nested
  class CommitVisitorTests {

    @Test
    void persistsDirtyLeaf() {
      final Bytes key = Bytes.fromHexString("0x0102");
      final byte[] value = Bytes32.repeat((byte) 0x33).toArrayUnsafe();
      final LeafNode leaf = new LeafNode(key.toArrayUnsafe(), key.size(), value, false);
      leaf.accept(Bytes.EMPTY, new CommitVisitor(updater));
      assertThat(updater.storage).isNotEmpty();
      assertThat(leaf.isClean()).isTrue();
    }

    @Test
    void skipsCleanLeaf() {
      final Bytes key = Bytes.fromHexString("0x01");
      final byte[] value = Bytes32.repeat((byte) 1).toArrayUnsafe();
      final LeafNode leaf = new LeafNode(key.toArrayUnsafe(), key.size(), value, true);
      leaf.accept(Bytes.EMPTY, new CommitVisitor(updater));
      assertThat(updater.storage).isEmpty();
    }

    @Test
    void persistsBranchAndChildren() {
      final Bytes keyA = Bytes.fromHexString("0x10");
      final Bytes keyB = Bytes.fromHexString("0x20");
      final byte[] valueA = Bytes32.repeat((byte) 0xAA).toArrayUnsafe();
      final byte[] valueB = Bytes32.repeat((byte) 0xBB).toArrayUnsafe();
      TrieNode root =
          traverse(
              traverse(TrieNode.empty(), new PutVisitor(valueA), keyA.toArrayUnsafe(), keyA.size()),
              new PutVisitor(valueB),
              keyB.toArrayUnsafe(),
              keyB.size());
      root.accept(Bytes.EMPTY, new CommitVisitor(updater));
      assertThat(updater.storage).isNotEmpty();
      assertThat(root.isClean()).isTrue();
    }

    @Test
    void storedNodeReloadsAfterCommit() {
      final Bytes key = Bytes.fromHexString("0xabcd");
      final byte[] value = Bytes32.repeat((byte) 0x77).toArrayUnsafe();
      final LeafNode leaf = new LeafNode(key.toArrayUnsafe(), key.size(), value, false);
      leaf.accept(Bytes.EMPTY, new CommitVisitor(updater));
      final Bytes32 hash = Bytes32.wrap(leaf.merkleHashBytes());
      final StoredTrieNode proxy = new StoredTrieNode(factory, Bytes.EMPTY, hash);
      proxy.accept(Bytes.EMPTY, new CommitVisitor(updater));
      assertThat(proxy.isClean()).isTrue();
      assertThat(traverse(proxy, new GetVisitor(), key.toArrayUnsafe(), key.size()).leafValue())
          .contains(value);
    }

    @Test
    void rootHashMatchesSpecOracleAfterCommit() {
      final Bytes key = Bytes.fromHexString("0x0102");
      final Bytes32 value = Bytes32.repeat((byte) 0x33);
      final BinaryTrie spec = new BinaryTrie();
      spec.put(key, value);

      TrieNode root =
          traverse(
              TrieNode.empty(),
              new PutVisitor(value.toArrayUnsafe()),
              key.toArrayUnsafe(),
              key.size());
      root.accept(Bytes.EMPTY, new CommitVisitor(updater));
      assertThat(Bytes32.wrap(root.merkleHashBytes())).isEqualTo(spec.root());
    }
  }
}
