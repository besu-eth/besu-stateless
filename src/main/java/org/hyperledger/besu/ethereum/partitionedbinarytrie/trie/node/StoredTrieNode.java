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

import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieKey;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.StoredTrieNodeFactory;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.visitor.LocationNodeVisitor;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.visitor.PathNodeVisitor;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Lazy-loading trie node proxy backed by a persisted hash and location.
 *
 * <p>The location can itself be lazy: a bulk loader that only hashes builds stubs it never loads,
 * so it passes the location as a supplier, computed only if something asks for it.
 *
 * <p>Always clean: mutations unwrap via {@link #load()} and return in-memory nodes. Commit never
 * visits this type directly; {@link #accept(Bytes, LocationNodeVisitor)} delegates to the loaded
 * node.
 */
public final class StoredTrieNode extends TrieNode {

  private final StoredTrieNodeFactory factory;
  private final Supplier<Bytes> location;
  private final Bytes32 hash;
  private TrieNode loaded;

  public StoredTrieNode(
      final StoredTrieNodeFactory factory, final Bytes location, final Bytes32 hash) {
    this(factory, Suppliers.ofInstance(location), hash);
  }

  /** A stub whose location is computed on first use, then kept. */
  public StoredTrieNode(
      final StoredTrieNodeFactory factory, final Supplier<Bytes> location, final Bytes32 hash) {
    super(true);
    this.factory = factory;
    this.location = Suppliers.memoize(location);
    this.hash = hash;
  }

  public TrieNode load() {
    if (loaded == null) {
      loaded = factory.retrieve(location.get(), hash);
    }
    return loaded;
  }

  /** Storage path prefix for this node. */
  public Bytes storageLocation() {
    return location.get();
  }

  @Override
  public boolean isClean() {
    return true;
  }

  @Override
  public void markDirty() {
    throw new IllegalStateException(
        "A stored node cannot ever be dirty since it's loaded from storage");
  }

  @Override
  public byte[] merkleHashBytes() {
    return hash.toArrayUnsafe();
  }

  @Override
  public Bytes encode() {
    return load().encode();
  }

  @Override
  public TrieNode accept(final PathNodeVisitor visitor, final TrieKey key, final int depth) {
    // The visitor decides: stock visitors load the node, a bulk loader rejects its stubs.
    return visitor.visit(this, key, depth);
  }

  @Override
  public void accept(final Bytes loc, final LocationNodeVisitor visitor) {
    load().accept(loc, visitor);
  }
}
