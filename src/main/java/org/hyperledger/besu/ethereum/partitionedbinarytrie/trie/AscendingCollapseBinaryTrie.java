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

import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieKey;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.StoredTrieNodeFactory;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.BranchNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.EmptyTrieNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.TrieNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.visitor.AscendingCollapsePutVisitor;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.visitor.PutVisitor;
import org.hyperledger.besu.ethereum.trie.NodeUpdater;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Streaming partitioned binary trie for strictly ascending bulk inserts, with no storage.
 *
 * <p>Each insert runs {@link AscendingCollapsePutVisitor} on the root: once the path turns right,
 * the completed left subtree is hashed and replaced by a hash-only stub. Live memory is therefore
 * the rightmost path, O(depth).
 *
 * <p>Keys MUST arrive in strictly ascending unsigned byte-lexicographic order, the order in which
 * the trie descends bit by bit.
 *
 * <p>Built with a {@link NodeUpdater}, the trie also persists every node, each completed subtree as
 * it collapses and the root on {@link #rootHash()}, at the same locations and encodings {@link
 * StoredPartitionedBinaryTrie#commit} uses: a bulk load in one sequential pass with O(depth) heap.
 *
 * <p>Most of the hashing can move off the inserting thread: {@link #prepare} builds, hashes and
 * encodes a group of keys that forms a complete subtree (e.g. every leaf of one stem), from any
 * thread, and {@link #insert(Subtree)} then attaches it. Only the subtree's top node depends on
 * where it lands, so that node alone is hashed on the inserting thread.
 */
public final class AscendingCollapseBinaryTrie {

  /**
   * Keys prepared off the inserting thread: their subtree, below its top node, is built, hashed and
   * (when persisting) encoded, and replaced by hash stubs.
   */
  public static final class Subtree {
    private final byte[] firstKey;
    private final byte[] firstValue;
    private final byte[] lastKey;
    private final int size;
    private final TrieNode top;
    private final List<NodeWrite> writes;

    private Subtree(
        final byte[] firstKey,
        final byte[] firstValue,
        final byte[] lastKey,
        final int size,
        final TrieNode top,
        final List<NodeWrite> writes) {
      this.firstKey = firstKey;
      this.firstValue = firstValue;
      this.lastKey = lastKey;
      this.size = size;
      this.top = top;
      this.writes = writes;
    }

    /**
     * The top node attached at bit {@code depth}. A branch was built from depth 0, so its prefix is
     * cut down to the bits below {@code depth}; a leaf does not depend on where it is.
     */
    private TrieNode at(final int depth) {
      if (!(top instanceof BranchNode branch)) {
        return top;
      }
      if (depth > branch.prefixLength()) {
        throw new IllegalArgumentException("prepared keys do not form a complete subtree");
      }
      return new BranchNode(
          Arrays.copyOfRange(branch.prefixBits(), depth, branch.prefixLength()),
          branch.prefixLength() - depth,
          branch.leftChild(),
          branch.rightChild(),
          false);
    }
  }

  /** A node write recorded by {@link #prepare}, replayed by {@link #insert(Subtree)}. */
  private record NodeWrite(Bytes location, Bytes32 hash, Bytes value) {}

  /** Builds the hash-only stubs; they are never loaded, so there is nothing to load them from. */
  private final StoredTrieNodeFactory stubs =
      new StoredTrieNodeFactory((location, hash) -> Optional.empty());

  private final Optional<NodeUpdater> nodeUpdater;
  private TrieNode root = TrieNode.empty();
  private byte[] lastKey;
  private long insertCount;
  private boolean sealed;

  /** Hash-only trie: completed subtrees are hashed and dropped. */
  public AscendingCollapseBinaryTrie() {
    this.nodeUpdater = Optional.empty();
  }

  /** Persisting trie: every node is written to {@code nodeUpdater} before it is dropped. */
  public AscendingCollapseBinaryTrie(final NodeUpdater nodeUpdater) {
    this.nodeUpdater = Optional.of(nodeUpdater);
  }

  /**
   * Inserts {@code (key, value)} in strictly ascending key order.
   *
   * @throws IllegalArgumentException if order is violated, or the key or value is malformed
   * @throws IllegalStateException if {@link #rootHash()} has already been called
   */
  public void insert(final Bytes key, final Bytes value) {
    if (sealed) {
      throw new IllegalStateException("ascending binary trie already sealed");
    }
    final byte[] keyBytes = key.toArray();
    final byte[] valueBytes = value.toArray();
    PartitionedBinaryTrie.validateKey(keyBytes, keyBytes.length);
    PartitionedBinaryTrie.validateValue(valueBytes);
    if (lastKey != null && Arrays.compareUnsigned(keyBytes, lastKey) <= 0) {
      throw new IllegalArgumentException("keys must be inserted in strictly ascending order");
    }
    root =
        root.accept(
            new AscendingCollapsePutVisitor(valueBytes, stubs, nodeUpdater),
            TrieKey.of(keyBytes, keyBytes.length),
            0);
    lastKey = keyBytes;
    insertCount++;
  }

  /**
   * Builds, hashes and encodes {@code keys} (strictly ascending, with their {@code values}) as a
   * subtree to {@link #insert(Subtree)} later. Thread-safe: it reads no state of this trie. The
   * keys must be every key the trie will hold under their common prefix, e.g. all the leaves of a
   * stem.
   *
   * @param keys the keys, strictly ascending
   * @param values their 32-byte values
   * @return the subtree to insert
   * @throws IllegalArgumentException if the keys are empty, out of order or malformed
   */
  public Subtree prepare(final List<Bytes> keys, final List<Bytes> values) {
    if (keys.isEmpty() || keys.size() != values.size()) {
      throw new IllegalArgumentException("prepare needs as many values as keys, at least one");
    }
    TrieNode top = TrieNode.empty();
    byte[] previous = null;
    for (int i = 0; i < keys.size(); i++) {
      final byte[] keyBytes = keys.get(i).toArray();
      final byte[] valueBytes = values.get(i).toArray();
      PartitionedBinaryTrie.validateKey(keyBytes, keyBytes.length);
      PartitionedBinaryTrie.validateValue(valueBytes);
      if (previous != null && Arrays.compareUnsigned(keyBytes, previous) <= 0) {
        throw new IllegalArgumentException("keys must be inserted in strictly ascending order");
      }
      top = top.accept(new PutVisitor(valueBytes), TrieKey.of(keyBytes, keyBytes.length), 0);
      previous = keyBytes;
    }
    final byte[] firstKey = keys.getFirst().toArray();
    final List<NodeWrite> writes = new ArrayList<>();
    if (top instanceof BranchNode branch) {
      final TrieKey first = TrieKey.of(firstKey, firstKey.length);
      final int split = branch.prefixLength();
      branch.setLeftChild(completed(branch.leftChild(), first, split, 0, writes));
      branch.setRightChild(completed(branch.rightChild(), first, split, 1, writes));
    } else {
      top.merkleHashBytes();
    }
    return new Subtree(
        firstKey, values.getFirst().toArray(), previous, keys.size(), top, List.copyOf(writes));
  }

  /** Hashes a child below the prepared top node, records its writes, and stubs it. */
  private TrieNode completed(
      final TrieNode child,
      final TrieKey firstKey,
      final int split,
      final int side,
      final List<NodeWrite> writes) {
    if (nodeUpdater.isEmpty()) {
      // Hash-only: nothing is written and the stub is never loaded, so its location is only
      // computed if something asks for it.
      return stubs.wrapStored(
          () -> AscendingCollapsePutVisitor.childLocation(firstKey, split, side),
          Bytes32.wrap(child.merkleHashBytes()));
    }
    final Bytes location = AscendingCollapsePutVisitor.childLocation(firstKey, split, side);
    child.commit(location, (loc, hash, value) -> writes.add(new NodeWrite(loc, hash, value)));
    return stubs.wrapStored(location, Bytes32.wrap(child.merkleHashBytes()));
  }

  /**
   * Attaches a {@link #prepare prepared} subtree; its keys count as inserted.
   *
   * @param subtree prepared by this trie
   * @throws IllegalArgumentException if its keys are not after every key inserted so far
   * @throws IllegalStateException if {@link #rootHash()} has already been called
   */
  public void insert(final Subtree subtree) {
    if (sealed) {
      throw new IllegalStateException("ascending binary trie already sealed");
    }
    if (lastKey != null && Arrays.compareUnsigned(subtree.firstKey, lastKey) <= 0) {
      throw new IllegalArgumentException("keys must be inserted in strictly ascending order");
    }
    root =
        root.accept(
            new AscendingCollapsePutVisitor(
                subtree.firstValue, Optional.of(subtree::at), stubs, nodeUpdater),
            TrieKey.of(subtree.firstKey, subtree.firstKey.length),
            0);
    nodeUpdater.ifPresent(
        updater -> subtree.writes.forEach(w -> updater.store(w.location(), w.hash(), w.value())));
    lastKey = subtree.lastKey;
    insertCount += subtree.size;
  }

  /** Returns the number of keys inserted since construction. */
  public long insertCount() {
    return insertCount;
  }

  /**
   * Seals the trie and returns its root hash. Further {@link #insert} calls are rejected.
   *
   * <p>Only the rightmost path is still unhashed (and, when persisting, unwritten), so this is
   * O(depth). Calling it again returns the same root without writing anything.
   *
   * @return merkle root of the sealed trie (32 zero bytes when empty)
   */
  public Bytes32 rootHash() {
    final Bytes32 rootHash = Bytes32.wrap(root.merkleHashBytes());
    if (!sealed) {
      sealed = true;
      nodeUpdater.ifPresent(
          updater -> {
            if (root instanceof EmptyTrieNode) {
              updater.store(Bytes.EMPTY, rootHash, Bytes.EMPTY);
            } else {
              root.commit(Bytes.EMPTY, updater);
            }
          });
    }
    return rootHash;
  }
}
