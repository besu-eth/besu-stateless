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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.visitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Get, put and remove visitors over in-memory nodes. */
class TrieVisitorBehaviorTest {

  /** Tries with a key that leaves a branch prefix, ends inside it, or ends at its split. */
  private static final Map<List<String>, List<String>> ABSENT_KEYS_AT_A_BRANCH =
      Map.of(
          List.of("0xfe01", "0xfe02"), List.of("0xff01", "0xfe04", "0xfe"),
          List.of("0xaa00", "0xaa80"), List.of("0xaa"));

  private static TrieNode traverse(
      final TrieNode node, final PathNodeVisitor visitor, final String key) {
    final byte[] bytes = Bytes.fromHexString(key).toArrayUnsafe();
    return node.accept(visitor, TrieKey.of(bytes, bytes.length), 0);
  }

  private static TrieNode put(final TrieNode node, final String key) {
    return traverse(node, new PutVisitor(value(key)), key);
  }

  private static TrieNode trieOf(final List<String> keys) {
    TrieNode root = TrieNode.empty();
    for (final String key : keys) {
      root = put(root, key);
    }
    return root;
  }

  private static byte[] value(final String key) {
    return Bytes32.leftPad(Bytes.fromHexString(key)).toArray();
  }

  private static Bytes32 specRoot(final String... keys) {
    final BinaryTrie spec = new BinaryTrie();
    for (final String key : keys) {
      spec.put(Bytes.fromHexString(key), Bytes32.wrap(value(key)));
    }
    return spec.root();
  }

  @Nested
  class GetVisitorTests {

    private final GetVisitor visitor = new GetVisitor();

    @Test
    void emptyTrieHasNoValue() {
      assertThat(traverse(TrieNode.empty(), visitor, "0x01")).isInstanceOf(EmptyTrieNode.class);
    }

    @Test
    void leafAnswersOnlyItsOwnKey() {
      final TrieNode leaf = put(TrieNode.empty(), "0xbeef");
      assertThat(traverse(leaf, visitor, "0xbeef")).isSameAs(leaf);
      assertThat(traverse(leaf, visitor, "0xcafe")).isInstanceOf(EmptyTrieNode.class);
    }

    @Test
    void branchLeadsToTheLeafOfTheKey() {
      final TrieNode root = trieOf(List.of("0x10", "0x20"));
      assertThat(traverse(root, visitor, "0x20").leafValue()).contains(value("0x20"));
    }

    @Test
    void keyLeavingOrEndingInABranchPrefixIsAbsent() {
      ABSENT_KEYS_AT_A_BRANCH.forEach(
          (keys, absentKeys) -> {
            final TrieNode root = trieOf(keys);
            for (final String key : absentKeys) {
              assertThat(traverse(root, visitor, key)).as(key).isInstanceOf(EmptyTrieNode.class);
            }
          });
    }
  }

  @Nested
  class PutVisitorTests {

    @Test
    void insertsIntoAnEmptyTrie() {
      final TrieNode root = put(TrieNode.empty(), "0x01");
      assertThat(root).isInstanceOf(LeafNode.class);
      assertThat(root.leafValue()).contains(value("0x01"));
    }

    @Test
    void replacesTheValueOfItsKey() {
      final byte[] other = Bytes32.repeat((byte) 2).toArray();
      final TrieNode root =
          traverse(put(TrieNode.empty(), "0xabcd"), new PutVisitor(other), "0xabcd");
      assertThat(root.leafValue()).contains(other);
    }

    @Test
    void splitsALeafIntoABranch() {
      final TrieNode root = trieOf(List.of("0xaaaa", "0xbbbb"));
      assertThat(root).isInstanceOf(BranchNode.class);
      assertThat(Bytes32.wrap(root.merkleHashBytes())).isEqualTo(specRoot("0xaaaa", "0xbbbb"));
    }

    @Test
    void splitsABranchPrefixWhereTheKeyLeavesIt() {
      // 0xfe40 leaves the 14-bit prefix of the branch over 0xfe01 and 0xfe02 at its 10th bit.
      final TrieNode root = put(trieOf(List.of("0xfe01", "0xfe02")), "0xfe40");
      assertThat(((BranchNode) root).prefixLength()).isEqualTo(9);
      assertThat(Bytes32.wrap(root.merkleHashBytes()))
          .isEqualTo(specRoot("0xfe01", "0xfe02", "0xfe40"));
    }

    @Test
    void mergerReturningEmptyLeavesAnAbsentKeyOut() {
      final PutVisitor noValue = new PutVisitor(existing -> Optional.empty());
      assertThat(traverse(TrieNode.empty(), noValue, "0x01")).isSameAs(TrieNode.empty());
      final TrieNode leaf = put(TrieNode.empty(), "0xbeef");
      assertThat(traverse(leaf, noValue, "0xcafe")).isSameAs(leaf);
      ABSENT_KEYS_AT_A_BRANCH.forEach(
          (keys, absentKeys) -> {
            final TrieNode root = trieOf(keys);
            for (final String key : absentKeys) {
              assertThat(traverse(root, noValue, key)).as(key).isSameAs(root);
            }
          });
    }

    @Test
    void mergerRemovingALeafCollapsesItsBranch() {
      final TrieNode root =
          traverse(
              trieOf(List.of("0x10", "0x20")),
              new PutVisitor(existing -> Optional.empty()),
              "0x10");
      assertThat(root).isInstanceOf(LeafNode.class);
      assertThat(root.leafValue()).contains(value("0x20"));
    }
  }

  /** Left siblings collapse to stubs; out-of-order and duplicate keys are rejected. */
  @Nested
  class AscendingCollapsePutVisitorTests {

    private final StoredTrieNodeFactory factory =
        new StoredTrieNodeFactory(new NodeLoaderMock(new NodeUpdaterMock()));

    private AscendingCollapsePutVisitor visitor(final String key) {
      return new AscendingCollapsePutVisitor(value(key), factory, Optional.empty());
    }

    @Test
    void ascendingInsertCollapsesLeftSiblingToStoredStub() {
      TrieNode root = traverse(TrieNode.empty(), visitor("0xaaaa"), "0xaaaa");
      root = traverse(root, visitor("0xbbbb"), "0xbbbb");

      final BranchNode branch = (BranchNode) root;
      assertThat(branch.leftChild()).isInstanceOf(StoredTrieNode.class);
      assertThat(branch.rightChild().leafValue()).contains(value("0xbbbb"));
      assertThat(Bytes32.wrap(root.merkleHashBytes())).isEqualTo(specRoot("0xaaaa", "0xbbbb"));
    }

    @Test
    void descendingInsertIsRejected() {
      final TrieNode root = traverse(TrieNode.empty(), visitor("0xbbbb"), "0xbbbb");
      assertThatThrownBy(() -> traverse(root, visitor("0xaaaa"), "0xaaaa"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("ascending");
    }

    @Test
    void duplicateKeyIsRejected() {
      final TrieNode root = traverse(TrieNode.empty(), visitor("0xabcd"), "0xabcd");
      assertThatThrownBy(() -> traverse(root, visitor("0xabcd"), "0xabcd"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("duplicate");
    }
  }

  @Nested
  class RemoveVisitorTests {

    private final RemoveVisitor visitor = new RemoveVisitor();

    @Test
    void emptyTrieStaysEmpty() {
      assertThat(traverse(TrieNode.empty(), visitor, "0x01")).isSameAs(TrieNode.empty());
    }

    @Test
    void leafIsRemovedOnlyByItsOwnKey() {
      final TrieNode leaf = put(TrieNode.empty(), "0xbeef");
      assertThat(traverse(leaf, visitor, "0xcafe")).isSameAs(leaf);
      assertThat(traverse(leaf, visitor, "0xbeef")).isSameAs(TrieNode.empty());
    }

    @Test
    void branchCollapsesToItsOtherChild() {
      final TrieNode root = traverse(trieOf(List.of("0xaaaa", "0xbbbb")), visitor, "0xbbbb");
      assertThat(root).isInstanceOf(LeafNode.class);
      assertThat(root.leafValue()).contains(value("0xaaaa"));
    }

    @Test
    void collapseJoinsThePrefixesAroundTheSurvivingBranch() {
      // The root splits 0xf000 from a branch over 0xf0c0 and 0xf0e0: 8 + 1 + 1 prefix bits.
      final TrieNode root =
          traverse(trieOf(List.of("0xf000", "0xf0c0", "0xf0e0")), visitor, "0xf000");
      assertThat(((BranchNode) root).prefixLength()).isEqualTo(10);
      assertThat(Bytes32.wrap(root.merkleHashBytes())).isEqualTo(specRoot("0xf0c0", "0xf0e0"));
    }

    @Test
    void keyLeavingOrEndingInABranchPrefixIsNoOp() {
      ABSENT_KEYS_AT_A_BRANCH.forEach(
          (keys, absentKeys) -> {
            final TrieNode root = trieOf(keys);
            for (final String key : absentKeys) {
              assertThat(traverse(root, visitor, key)).as(key).isSameAs(root);
            }
          });
    }

    @Test
    void removeOnEmptyChildSideFlattensWithoutTouchingEmptySingleton() {
      final BranchNode branch =
          new BranchNode(new byte[0], 0, put(TrieNode.empty(), "0x00"), TrieNode.empty(), false);

      final TrieNode result = traverse(branch, visitor, "0x80");
      assertThat(result.leafValue()).contains(value("0x00"));
      assertThat(branch.rightChild()).isSameAs(TrieNode.empty());
    }

    @Test
    void removeOnEmptyChildSideWithFlattenDisabledKeepsBranch() {
      final BranchNode branch =
          new BranchNode(new byte[0], 0, put(TrieNode.empty(), "0x00"), TrieNode.empty(), false);

      assertThat(traverse(branch, new RemoveVisitor(false), "0x80")).isSameAs(branch);
      assertThat(branch.rightChild()).isSameAs(TrieNode.empty());
    }
  }
}
