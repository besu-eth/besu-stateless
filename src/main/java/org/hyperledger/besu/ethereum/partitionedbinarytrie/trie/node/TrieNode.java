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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieKey;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.visitor.CommitVisitor;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.visitor.LocationNodeVisitor;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.visitor.PathNodeVisitor;
import org.hyperledger.besu.ethereum.trie.NodeUpdater;

import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;

/**
 * Mutable trie node graph supporting lazy disk loading, dirty tracking, and commit.
 *
 * <p>Concrete implementations are {@link LeafNode}, {@link BranchNode}, and {@link StoredTrieNode}.
 * The empty trie is represented by a singleton returned from {@link #empty()}.
 *
 * <p>Traversal and mutation use {@link PathNodeVisitor} and {@link LocationNodeVisitor} via double
 * dispatch ({@link #accept(PathNodeVisitor, TrieKey, int)} and {@link #accept(Bytes,
 * LocationNodeVisitor)}). High-level get/put/remove live on {@link
 * org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.PartitionedBinaryTrie}.
 */
public abstract class TrieNode {

  protected boolean clean;

  protected TrieNode(final boolean clean) {
    this.clean = clean;
  }

  public static TrieNode empty() {
    return EmptyTrieNode.instance();
  }

  public boolean isClean() {
    return clean;
  }

  public void markDirty() {
    clean = false;
  }

  public void markClean() {
    clean = true;
  }

  /**
   * Returns the 32-byte leaf value when this node is a matching leaf; empty otherwise.
   *
   * <p>Used to extract results from {@link GetVisitor}.
   */
  public Optional<byte[]> leafValue() {
    return Optional.empty();
  }

  public abstract byte[] merkleHashBytes();

  public void commit(final Bytes location, final NodeUpdater updater) {
    accept(location, new CommitVisitor(updater));
  }

  public abstract Bytes encode();

  /**
   * Whether this node is referenced by hash in its parent (encoded size at least 32 bytes).
   *
   * <p>Aligned with Besu {@link org.hyperledger.besu.ethereum.trie.Node#isReferencedByHash()}.
   */
  public boolean isReferencedByHash() {
    return encode().size() >= 32;
  }

  /**
   * Accepts a path-keyed visitor starting at {@code depth} into the lookup key.
   *
   * @param visitor path visitor (get, put, remove, …)
   * @param key lookup key (bytes and expanded path bits)
   * @param depth current bit depth
   * @return updated subtree root
   */
  public abstract TrieNode accept(PathNodeVisitor visitor, TrieKey key, int depth);

  /**
   * Accepts a storage-location-keyed visitor.
   *
   * @param location path prefix for this node in the backing store
   * @param visitor location visitor (commit, …)
   */
  public abstract void accept(Bytes location, LocationNodeVisitor visitor);
}
