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

import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.visitor.CommitVisitor;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.visitor.GetVisitor;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.visitor.LocationNodeVisitor;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.visitor.PathNodeVisitor;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.visitor.PutVisitor;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.visitor.RemoveVisitor;
import org.hyperledger.besu.ethereum.trie.NodeUpdater;

import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;

/**
 * Mutable trie node graph supporting lazy disk loading, dirty tracking, and commit.
 *
 * <p>Concrete implementations are {@link LeafNode}, {@link BranchNode}, and {@link StoredNode}. The
 * empty trie is represented by a singleton returned from {@link #empty()}.
 *
 * <p>Get, put, and commit delegate to {@link PathNodeVisitor} and {@link LocationNodeVisitor}
 * implementations via double dispatch ({@link #accept(PathNodeVisitor, byte[], int, int)} and
 * {@link #accept(Bytes, LocationNodeVisitor)}).
 */
public abstract class TrieNode {

  private static final GetVisitor GET_VISITOR = new GetVisitor();
  private static final RemoveVisitor REMOVE_VISITOR = new RemoveVisitor();

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

  public Optional<byte[]> get(final byte[] key, final int keyLen, final int depth) {
    return accept(GET_VISITOR, key, keyLen, depth).leafValue();
  }

  public TrieNode put(final byte[] key, final int keyLen, final byte[] value, final int depth) {
    return accept(new PutVisitor(value), key, keyLen, depth);
  }

  public TrieNode remove(final byte[] key, final int keyLen, final int depth) {
    return accept(REMOVE_VISITOR, key, keyLen, depth);
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
   * Accepts a path-keyed visitor starting at {@code depth} into the expanded key.
   *
   * @param visitor path visitor (get, put, remove, …)
   * @param key key bytes
   * @param keyLen valid key length
   * @param depth current bit depth
   * @return updated subtree root
   */
  public abstract TrieNode accept(PathNodeVisitor visitor, byte[] key, int keyLen, int depth);

  /**
   * Accepts a storage-location-keyed visitor.
   *
   * @param location path prefix for this node in the backing store
   * @param visitor location visitor (commit, …)
   */
  public abstract void accept(Bytes location, LocationNodeVisitor visitor);
}
