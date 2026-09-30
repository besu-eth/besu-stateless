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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.trie;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieConstants;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.BranchNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.LeafNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.TrieNode;

import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/** Adapts stored {@link TrieNode} instances to {@link PartitionedBinaryTrie.TrieNodeView}. */
final class TrieNodeViewAdapter implements PartitionedBinaryTrie.TrieNodeView {

  private final TrieNode node;

  private TrieNodeViewAdapter(final TrieNode node) {
    this.node = node;
  }

  static PartitionedBinaryTrie.TrieNodeView from(final TrieNode node) {
    return new TrieNodeViewAdapter(node);
  }

  @Override
  public boolean isEmpty() {
    return Bytes32.wrap(node.merkleHashBytes()).equals(TrieConstants.EMPTY_TRIE_ROOT)
        && node.encode().isEmpty();
  }

  @Override
  public boolean isLeaf() {
    return node instanceof LeafNode;
  }

  @Override
  public boolean isBranch() {
    return node instanceof BranchNode;
  }

  @Override
  public Optional<Bytes> getKey() {
    if (node instanceof LeafNode leaf) {
      return Optional.of(Bytes.wrap(leaf.keyBytes(), 0, leaf.keyLength()));
    }
    return Optional.empty();
  }

  @Override
  public Optional<Bytes> getValue() {
    if (node instanceof LeafNode leaf) {
      return Optional.of(Bytes.wrap(leaf.valueBytes()));
    }
    return Optional.empty();
  }

  @Override
  public Bytes32 getHash() {
    return Bytes32.wrap(node.merkleHashBytes());
  }

  @Override
  public Bytes getEncoded() {
    return node.encode();
  }
}
