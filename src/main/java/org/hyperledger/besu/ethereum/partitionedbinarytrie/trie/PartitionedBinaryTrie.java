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

import static com.google.common.base.Preconditions.checkNotNull;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieConstants;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.PartitionedBinaryTrieFactory;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.StoredTrieNodeFactory;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.TrieNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.TrieNodeTraversal;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.visitor.CommitVisitor;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.visitor.GetVisitor;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.visitor.PathNodeVisitor;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.visitor.ProofVisitor;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.visitor.PutVisitor;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.visitor.RemoveVisitor;
import org.hyperledger.besu.ethereum.trie.NodeUpdater;
import org.hyperledger.besu.ethereum.trie.Proof;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Incremental partitioned binary trie backed by a {@link TrieNode} graph.
 *
 * <p>Exposes both primitive {@code byte[]} operations on the hot path and Tuweni {@link Bytes} for
 * Besu world-state integration. The no-arg constructor supports in-memory use without disk backing;
 * production callers use {@link StoredPartitionedBinaryTrie} via {@link
 * PartitionedBinaryTrieFactory}.
 */
public class PartitionedBinaryTrie {

  /** Root hash of an empty trie: 32 zero bytes. */
  public static final Bytes32 EMPTY_TRIE_ROOT = Bytes32.ZERO;

  /**
   * Callback for ordered leaf visits ({@link #visitLeafs}).
   *
   * <p>Aligned with {@code TrieIterator.LeafHandler} in Besu's {@code MerkleTrie} API.
   */
  public interface LeafHandler {

    /** Continue or stop leaf iteration. */
    enum State {
      CONTINUE,
      STOP
    }

    /**
     * Invoked for each leaf in lexicographic key order.
     *
     * @param key trie key
     * @param value 32-byte leaf value
     * @return {@link State#STOP} to end iteration early
     */
    State onLeaf(Bytes key, Bytes value);
  }

  /**
   * Read-only view of a trie node for {@link #visitAll(Consumer)}.
   *
   * <p>Exposes encoded form and merkle hash without leaking stored-node implementation types.
   */
  public interface TrieNodeView {

    /** Returns {@code true} when this node represents an empty trie. */
    boolean isEmpty();

    /** Returns {@code true} when this node is a leaf. */
    boolean isLeaf();

    /** Returns {@code true} when this node is a branch. */
    boolean isBranch();

    /** Leaf key, or empty when not a leaf. */
    Optional<Bytes> getKey();

    /** Leaf value, or empty when not a leaf. */
    Optional<Bytes> getValue();

    /** BLAKE3 merkle hash of this node. */
    Bytes32 getHash();

    /** Canonical encoded node bytes. */
    Bytes getEncoded();
  }

  private final GetVisitor getVisitor = new GetVisitor();
  private final RemoveVisitor removeVisitor = new RemoveVisitor();

  protected TrieNode root;

  /** Creates an empty trie. */
  public PartitionedBinaryTrie() {
    this(TrieNode.empty());
  }

  /**
   * Creates a trie with the given root node.
   *
   * @param root subtree root (typically {@link TrieNode#empty()} or a stored-node proxy)
   */
  public PartitionedBinaryTrie(final TrieNode root) {
    this.root = root;
  }

  /**
   * Looks up a raw trie value using the first {@code keyLen} bytes of {@code key}.
   *
   * <p>The trie compares keys bit-by-bit internally, but public byte-array callers pass the useful
   * byte length separately so buffers may be larger than the key path.
   *
   * @param key byte buffer containing the trie key
   * @param keyLen number of key bytes to read from {@code key}
   * @return stored 32-byte value, or empty when absent
   */
  public Optional<byte[]> get(final byte[] key, final int keyLen) {
    checkNotNull(key);
    validateKey(key, keyLen);
    return root.accept(getGetVisitor(), key, keyLen, 0).leafValue();
  }

  /**
   * Looks up a value by key.
   *
   * @param key variable-length trie key
   * @return stored value, or empty if absent
   */
  public Optional<Bytes> get(final Bytes key) {
    checkNotNull(key);
    return get(key.toArray(), key.size()).map(Bytes::wrap);
  }

  /**
   * Reads an EIP-8297 state value.
   *
   * <p>At the state layer, an absent leaf reads as 32 zero bytes.
   *
   * @param key trie key bytes
   * @param keyLen valid key length
   * @return the stored value, or 32 zero bytes when absent
   */
  public byte[] readState(final byte[] key, final int keyLen) {
    return get(key, keyLen)
        .map(value -> Arrays.copyOf(value, TrieConstants.VALUE_LENGTH))
        .orElseGet(() -> Bytes32.ZERO.toArray());
  }

  /**
   * Reads an EIP-8297 state value.
   *
   * @param key variable-length trie key
   * @return the stored value, or {@link Bytes32#ZERO} when absent
   */
  public Bytes32 readState(final Bytes key) {
    checkNotNull(key);
    return Bytes32.wrap(readState(key.toArray(), key.size()));
  }

  /**
   * Looks up a value by path (alias for {@link #get(Bytes)}).
   *
   * @param path trie key path
   * @return stored value, or empty if absent
   */
  public Optional<Bytes> getPath(final Bytes path) {
    return get(path);
  }

  /**
   * Returns the value and ordered proof-related nodes for {@code key}.
   *
   * <p>Mirrors Besu {@link org.hyperledger.besu.ethereum.trie.MerkleTrie#getValueWithProof}.
   *
   * @param key trie key bytes
   * @param keyLen valid key length
   * @return value (if present) and encoded proof nodes along the lookup path
   */
  public Proof<byte[]> getValueWithProof(final byte[] key, final int keyLen) {
    checkNotNull(key);
    validateKey(key, keyLen);
    final ProofVisitor proofVisitor = new ProofVisitor(root);
    final Optional<byte[]> value = root.accept(proofVisitor, key, keyLen, 0).leafValue();
    final List<Bytes> proof =
        proofVisitor.getProof().stream().map(node -> node.encode()).collect(Collectors.toList());
    return new Proof<>(value, proof);
  }

  /**
   * Returns the value and ordered proof-related nodes for {@code key}.
   *
   * @param key variable-length trie key
   * @return value (if present) and encoded proof nodes along the lookup path
   */
  public Proof<Bytes> getValueWithProof(final Bytes key) {
    checkNotNull(key);
    final Proof<byte[]> proof = getValueWithProof(key.toArray(), key.size());
    return new Proof<>(proof.getValue().map(Bytes::wrap), proof.getProofRelatedNodes());
  }

  /**
   * Inserts or replaces a raw trie value.
   *
   * <p>This is the generic trie operation: a zero value is stored as a leaf. Use {@link
   * #writeState(byte[], int, byte[])} for EIP-8297 state semantics where zero means deletion.
   *
   * @param key byte buffer containing the trie key
   * @param keyLen number of key bytes to read from {@code key}
   * @param value 32-byte leaf value
   */
  public void put(final byte[] key, final int keyLen, final byte[] value) {
    checkNotNull(key);
    checkNotNull(value);
    validateKey(key, keyLen);
    validateValue(value);
    root = root.accept(getPutVisitor(value), key, keyLen, 0);
  }

  /**
   * Inserts or replaces a key-value pair.
   *
   * @param key variable-length trie key
   * @param value leaf value
   */
  public void put(final Bytes key, final Bytes value) {
    checkNotNull(key);
    checkNotNull(value);
    put(key.toArray(), key.size(), value.toArray());
  }

  /**
   * Applies an EIP-8297 state write.
   *
   * <p>Writing 32 zero bytes deletes the leaf instead of storing a zero-valued leaf.
   *
   * @param key trie key bytes
   * @param keyLen valid key length
   * @param value 32-byte state value
   */
  public void writeState(final byte[] key, final int keyLen, final byte[] value) {
    checkNotNull(key);
    checkNotNull(value);
    validateKey(key, keyLen);
    validateValue(value);
    if (isZeroValue(value)) {
      remove(key, keyLen);
    } else {
      put(key, keyLen, value);
    }
  }

  /**
   * Applies an EIP-8297 state write.
   *
   * @param key variable-length trie key
   * @param value 32-byte state value
   */
  public void writeState(final Bytes key, final Bytes value) {
    checkNotNull(key);
    checkNotNull(value);
    writeState(key.toArray(), key.size(), value.toArray());
  }

  /**
   * Inserts or replaces a path-value pair (alias for {@link #put(Bytes, Bytes)}).
   *
   * @param path trie key path
   * @param value leaf value
   */
  public void putPath(final Bytes path, final Bytes value) {
    put(path, value);
  }

  /**
   * Merges a raw trie value without exposing the traversal details to the caller.
   *
   * <p>The merger receives the current value for the exact key, or empty when absent. Returning a
   * value inserts/replaces the leaf; returning empty removes it.
   *
   * @param key byte buffer containing the trie key
   * @param keyLen number of key bytes to read from {@code key}
   * @param merger read-modify-write function for the target leaf
   */
  public void putDeferred(
      final byte[] key, final int keyLen, final UnaryOperator<Optional<byte[]>> merger) {
    checkNotNull(merger);
    final Optional<byte[]> merged = merger.apply(get(key, keyLen));
    merged.ifPresentOrElse(v -> put(key, keyLen, v), () -> remove(key, keyLen));
  }

  /**
   * Atomically merges a value via {@code merger}, inserting, updating, or removing the key.
   *
   * @param key variable-length trie key
   * @param merger function receiving the current value (if any) and returning the new value, or
   *     empty to delete
   */
  public void putDeferred(final Bytes key, final UnaryOperator<Optional<Bytes>> merger) {
    checkNotNull(key);
    checkNotNull(merger);
    putDeferred(
        key.toArray(),
        key.size(),
        existing -> merger.apply(existing.map(Bytes::wrap)).map(bytes -> bytes.toArrayUnsafe()));
  }

  /**
   * Removes a raw trie value if present.
   *
   * <p>Removal also restores the canonical compressed shape by collapsing branches that would have
   * only one non-empty child.
   *
   * @param key byte buffer containing the trie key
   * @param keyLen number of key bytes to read from {@code key}
   */
  public void remove(final byte[] key, final int keyLen) {
    checkNotNull(key);
    validateKey(key, keyLen);
    root = root.accept(getRemoveVisitor(), key, keyLen, 0);
  }

  /**
   * Removes a key if present.
   *
   * @param key variable-length trie key
   */
  public void remove(final Bytes key) {
    checkNotNull(key);
    remove(key.toArray(), key.size());
  }

  public Bytes32 getRootHash() {
    return Bytes32.wrap(root.merkleHashBytes());
  }

  /**
   * Returns {@code true} when the trie has no entries.
   *
   * @return whether the root hash is the empty trie root
   */
  public boolean isEmpty() {
    return getRootHash().equals(EMPTY_TRIE_ROOT);
  }

  public void commit(final NodeUpdater nodeUpdater, final StoredTrieNodeFactory factory) {
    root.accept(Bytes.EMPTY, new CommitVisitor(nodeUpdater));
    final Bytes32 rootHash = getRootHash();
    root =
        rootHash.equals(TrieConstants.EMPTY_TRIE_ROOT)
            ? TrieNode.empty()
            : factory.wrapStored(Bytes.EMPTY, rootHash);
  }

  /**
   * Returns up to {@code limit} entries with keys greater than or equal to {@code startKeyHash}.
   *
   * @param startKeyHash first key to include (right-padded to 32 bytes for ordering)
   * @param limit maximum number of entries
   * @return map of right-padded key to value
   */
  public Map<Bytes32, Bytes> entriesFrom(final Bytes32 startKeyHash, final int limit) {
    final Map<Bytes32, byte[]> raw =
        TrieNodeTraversal.entriesFrom(
            root, startKeyHash.toArrayUnsafe(), startKeyHash.size(), limit);
    final Map<Bytes32, Bytes> entries = new HashMap<>();
    raw.forEach((key, value) -> entries.put(key, Bytes.wrap(value)));
    return entries;
  }

  /** Visits every internal trie node in the trie (pre-order). */
  public void visitTrieNodes(final Consumer<TrieNode> nodeConsumer) {
    TrieNodeTraversal.visitAll(root, nodeConsumer);
  }

  /**
   * Visits every trie node (pre-order).
   *
   * @param nodeConsumer invoked for each {@link TrieNodeView}
   */
  public void visitAll(final Consumer<TrieNodeView> nodeConsumer) {
    visitTrieNodes(node -> nodeConsumer.accept(TrieNodeViewAdapter.from(node)));
  }

  /**
   * Visits every internal trie node in parallel.
   *
   * @param nodeConsumer invoked for each node
   * @param executorService executor for parallel child visits
   * @return future completing when all visits finish
   */
  public CompletableFuture<Void> visitTrieNodes(
      final Consumer<TrieNode> nodeConsumer, final ExecutorService executorService) {
    return TrieNodeTraversal.visitAllParallel(root, nodeConsumer, executorService);
  }

  /**
   * Visits every trie node in parallel.
   *
   * @param nodeConsumer invoked for each {@link TrieNodeView}
   * @param executorService executor for parallel child visits
   * @return future completing when all visits finish
   */
  public CompletableFuture<Void> visitAll(
      final Consumer<TrieNodeView> nodeConsumer, final ExecutorService executorService) {
    return visitTrieNodes(
        node -> nodeConsumer.accept(TrieNodeViewAdapter.from(node)), executorService);
  }

  /** Visits leaves in lexicographic key order. */
  public void visitLeaves(final TrieNodeTraversal.LeafHandler handler) {
    TrieNodeTraversal.visitLeaves(root, handler);
  }

  /**
   * Visits leaves in lexicographic key order.
   *
   * @param handler leaf callback
   */
  public void visitLeafs(final LeafHandler handler) {
    visitLeaves(
        (key, keyLen, value) ->
            handler.onLeaf(Bytes.wrap(key, 0, keyLen), Bytes.wrap(value)) == LeafHandler.State.STOP
                ? TrieNodeTraversal.LeafHandler.State.STOP
                : TrieNodeTraversal.LeafHandler.State.CONTINUE);
  }

  /** Returns the shared read visitor used by {@link #get(byte[], int)}. */
  protected PathNodeVisitor getGetVisitor() {
    return getVisitor;
  }

  /** Returns a put visitor for the given 32-byte value. */
  protected PathNodeVisitor getPutVisitor(final byte[] value) {
    return new PutVisitor(value);
  }

  /** Returns the shared remove visitor used by {@link #remove(byte[], int)}. */
  protected PathNodeVisitor getRemoveVisitor() {
    return removeVisitor;
  }

  /** Returns a put visitor for read-modify-write during batched commits. */
  protected PathNodeVisitor getPutVisitor(final UnaryOperator<Optional<byte[]>> merger) {
    return new PutVisitor(merger);
  }

  protected static void validateKey(final byte[] key, final int keyLen) {
    if (keyLen < 1) {
      throw new IllegalArgumentException("Key must not be empty");
    }
    if (keyLen > key.length) {
      throw new IllegalArgumentException("Key length exceeds provided key bytes");
    }
    if (keyLen > TrieConstants.MAX_KEY_LENGTH) {
      throw new IllegalArgumentException("Key exceeds maximum length");
    }
  }

  protected static void validateValue(final byte[] value) {
    if (value.length != TrieConstants.VALUE_LENGTH) {
      throw new IllegalArgumentException("Value must be 32 bytes");
    }
  }

  protected static boolean isZeroValue(final byte[] value) {
    for (final byte b : value) {
      if (b != 0) {
        return false;
      }
    }
    return true;
  }
}
