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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.trie;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.internal.bytes.ByteTrieOps;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieKey;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.StoredTrieNodeFactory;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.BranchNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.EmptyTrieNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.LeafNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.StoredTrieNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.TrieNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.visitor.CommitVisitor;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.visitor.PathNodeVisitor;
import org.hyperledger.besu.ethereum.trie.NodeLoader;
import org.hyperledger.besu.ethereum.trie.NodeUpdater;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.function.UnaryOperator;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Parallel stored partitioned binary trie that batches updates and applies them concurrently.
 *
 * <p>Mirrors Besu {@link
 * org.hyperledger.besu.ethereum.trie.patricia.ParallelStoredMerklePatriciaTrie}.
 */
@SuppressWarnings({"rawtypes", "ThreadPriorityCheck"})
public class ParallelStoredPartitionedBinaryTrie extends StoredPartitionedBinaryTrie {

  private static final ForkJoinPool DEFAULT_FORK_JOIN_POOL =
      new ForkJoinPool(Runtime.getRuntime().availableProcessors() * 2);

  private final Map<Bytes, PendingUpdate> pendingUpdates = new ConcurrentHashMap<>();
  private final ForkJoinPool forkJoinPool;

  public ParallelStoredPartitionedBinaryTrie(final NodeLoader nodeLoader) {
    this(nodeLoader, DEFAULT_FORK_JOIN_POOL);
  }

  public ParallelStoredPartitionedBinaryTrie(final NodeLoader nodeLoader, final Bytes32 rootHash) {
    this(nodeLoader, rootHash, DEFAULT_FORK_JOIN_POOL);
  }

  public ParallelStoredPartitionedBinaryTrie(
      final NodeLoader nodeLoader, final ForkJoinPool forkJoinPool) {
    this(new StoredTrieNodeFactory(nodeLoader), forkJoinPool);
  }

  public ParallelStoredPartitionedBinaryTrie(
      final NodeLoader nodeLoader, final Bytes32 rootHash, final ForkJoinPool forkJoinPool) {
    this(new StoredTrieNodeFactory(nodeLoader), rootHash, forkJoinPool);
  }

  public ParallelStoredPartitionedBinaryTrie(final StoredTrieNodeFactory nodeFactory) {
    this(nodeFactory, DEFAULT_FORK_JOIN_POOL);
  }

  public ParallelStoredPartitionedBinaryTrie(
      final StoredTrieNodeFactory nodeFactory, final ForkJoinPool forkJoinPool) {
    super(nodeFactory);
    this.forkJoinPool = forkJoinPool;
  }

  public ParallelStoredPartitionedBinaryTrie(
      final StoredTrieNodeFactory nodeFactory, final Bytes32 rootHash) {
    this(nodeFactory, rootHash, DEFAULT_FORK_JOIN_POOL);
  }

  public ParallelStoredPartitionedBinaryTrie(
      final StoredTrieNodeFactory nodeFactory,
      final Bytes32 rootHash,
      final ForkJoinPool forkJoinPool) {
    super(nodeFactory, rootHash);
    this.forkJoinPool = forkJoinPool;
  }

  @Override
  public void put(final byte[] key, final int keyLen, final byte[] value) {
    Objects.requireNonNull(key);
    Objects.requireNonNull(value);
    validateKey(key, keyLen);
    validateValue(value);
    pendingUpdates.put(
        Bytes.wrap(Arrays.copyOf(key, keyLen)),
        new Direct(Optional.of(Arrays.copyOf(value, value.length))));
  }

  @Override
  public void putDeferred(
      final byte[] key, final int keyLen, final UnaryOperator<Optional<byte[]>> merger) {
    Objects.requireNonNull(key);
    Objects.requireNonNull(merger);
    validateKey(key, keyLen);
    pendingUpdates.put(
        Bytes.wrap(Arrays.copyOf(key, keyLen)),
        new Merge(
            existing -> {
              final Optional<byte[]> merged =
                  Objects.requireNonNull(
                      merger.apply(existing.map(value -> Arrays.copyOf(value, value.length))));
              return merged.map(
                  value -> {
                    validateValue(value);
                    return Arrays.copyOf(value, value.length);
                  });
            }));
  }

  @Override
  public void remove(final byte[] key, final int keyLen) {
    Objects.requireNonNull(key);
    validateKey(key, keyLen);
    pendingUpdates.put(Bytes.wrap(Arrays.copyOf(key, keyLen)), new Direct(Optional.empty()));
  }

  /**
   * Applies the pending updates, then commits the final trie in parallel: only it shows where each
   * stem starts.
   */
  @Override
  public void commit(final NodeUpdater nodeUpdater) {
    processPendingUpdates();
    if (!root.isClean()) {
      final CommitCache commitCache = new CommitCache();
      final CommitVisitor commitVisitor = new ParallelCommitVisitor(commitCache);
      forkJoinPool.invoke(ForkJoinTask.adapt(() -> root.accept(Bytes.EMPTY, commitVisitor)));
      commitCache.flushTo(nodeUpdater);
    }
    // Code reference counts, and the root location when the trie became empty.
    super.commit(nodeUpdater);
  }

  @Override
  public Bytes32 getRootHash() {
    processPendingUpdates();
    return super.getRootHash();
  }

  private void processPendingUpdates() {
    if (pendingUpdates.isEmpty()) {
      return;
    }
    try {
      this.root = loadNode(root);
      final List<UpdateEntry> entries = new ArrayList<>();
      pendingUpdates.forEach(
          (keyBytes, update) ->
              entries.add(update.toEntry(keyBytes.toArrayUnsafe(), keyBytes.size())));
      // Removals first, as the batch order is lost: a key can then give way to a longer or shorter
      // key sharing its bits, as in a sequential trie.
      entries.sort(Comparator.comparing(entry -> entry.isMerge() || entry.value().isPresent()));
      this.root = forkJoinPool.invoke(ForkJoinTask.adapt(() -> processNode(root, 0, entries)));
    } finally {
      pendingUpdates.clear();
    }
  }

  /**
   * Applies {@code updates} to {@code node}, dispatching by node kind. Branch nodes split updates
   * across children and recurse, possibly in parallel; other nodes fall back to sequential
   * visitor-based updates.
   */
  private TrieNode processNode(
      final TrieNode node, final int depth, final List<UpdateEntry> updates) {

    final TrieNode loadedNode = loadNode(node);
    if (loadedNode instanceof BranchNode branch) {
      return handleBranchNode(branch, depth, updates);
    }
    if (loadedNode instanceof LeafNode leaf) {
      return handleLeafNode(leaf, depth, updates);
    }
    if (loadedNode instanceof EmptyTrieNode) {
      return handleEmptyNode(depth, updates);
    }
    return applyUpdatesSequentially(loadedNode, depth, updates);
  }

  private TrieNode handleBranchNode(
      final BranchNode branchNode, final int depth, final List<UpdateEntry> updates) {

    final byte[] prefix = branchNode.prefix();
    final int prefixLen = branchNode.prefixLength();

    // Find the earliest prefix bit that not all updates still follow (some update either
    // diverges or runs out of key bits before the prefix ends).
    final int divergenceIndex = findDivergenceInPrefix(updates, depth, prefix, prefixLen);
    if (divergenceIndex < prefixLen) {
      // An update leaves the prefix. A single one goes through the sequential visitor.
      if (updates.size() > 1) {
        return splitPrefixAtDivergence(
            branchNode, prefix, prefixLen, divergenceIndex, depth, updates);
      }
      return applyUpdatesSequentially(branchNode, depth, updates);
    }

    // Absolute bit position where the compressed prefix ends: the next key bit selects
    // which child receives the update, so this is where the partition happens.
    final int splitDepth = depth + prefixLen;
    // Partition updates into the left (bit 0) and right (bit 1) sides at splitDepth.
    final ChildUpdates childUpdates = splitUpdatesByBit(updates, splitDepth);

    final BranchWrapper branchWrapper = new BranchWrapper(branchNode);
    // Lazily materialize only the side that has work, so an untouched child stays a cheap
    // stored proxy instead of being loaded from storage.
    if (!childUpdates.left().isEmpty()) {
      branchWrapper.loadLeft();
    }
    if (!childUpdates.right().isEmpty()) {
      branchWrapper.loadRight();
    }

    // Forking only pays off when both sides have work; otherwise one fork sits idle.
    final boolean parallelize = childUpdates.bothSidesActive();
    // Within a side, fork only when there are multiple updates to share — a single update
    // has no internal parallelism to exploit.
    final boolean forkLeft = parallelize && childUpdates.left().size() > 1;
    final boolean forkRight = parallelize && childUpdates.right().size() > 1;

    final int childDepth = splitDepth + 1;
    final List<ForkJoinTask<Void>> forkJoinTasks = new ArrayList<>();

    // Submit all fork tasks before any sequential work (Besu parallel trie pattern): a fork
    // task could otherwise block waiting for the pool thread that is busy running the
    // sequential side, deadlocking the worker pool.
    if (!childUpdates.left().isEmpty() && forkLeft) {
      processBranchChild(
          branchWrapper, false, childUpdates.left(), childDepth, true, forkJoinTasks);
    }
    if (!childUpdates.right().isEmpty() && forkRight) {
      processBranchChild(
          branchWrapper, true, childUpdates.right(), childDepth, true, forkJoinTasks);
    }

    if (!childUpdates.left().isEmpty() && !forkLeft) {
      processBranchChild(
          branchWrapper, false, childUpdates.left(), childDepth, false, forkJoinTasks);
    }
    if (!childUpdates.right().isEmpty() && !forkRight) {
      processBranchChild(
          branchWrapper, true, childUpdates.right(), childDepth, false, forkJoinTasks);
    }

    // Wait for every forked side before reassembling the branch, so both children are final.
    forkJoinTasks.forEach(ForkJoinTask::join);

    // Replace the branch's children with their updated versions; collapse the node if one
    // side became empty (the remaining side is hoisted up by replaceChild).
    final TrieNode newBranch = branchWrapper.applyUpdates();
    // Hash here, so that both sides of a fork are hashed in parallel.
    newBranch.merkleHashBytes();
    return newBranch;
  }

  /**
   * Splits the prefix of {@code branchNode} where the first update leaves it, the original branch
   * on one side and the diverging updates on the other, then processes the result.
   */
  private TrieNode splitPrefixAtDivergence(
      final BranchNode branchNode,
      final byte[] prefix,
      final int prefixLen,
      final int divergenceIndex,
      final int depth,
      final List<UpdateEntry> updates) {
    final TrieNode continuation =
        new BranchNode(
            ByteTrieOps.sliceBits(prefix, divergenceIndex + 1, prefixLen),
            prefixLen - divergenceIndex - 1,
            branchNode.leftChild(),
            branchNode.rightChild(),
            false);
    final byte[] commonPrefix = ByteTrieOps.sliceBits(prefix, 0, divergenceIndex);
    final BranchNode split =
        ByteTrieOps.bitAt(prefix, divergenceIndex) == 0
            ? new BranchNode(commonPrefix, divergenceIndex, continuation, TrieNode.empty(), false)
            : new BranchNode(commonPrefix, divergenceIndex, TrieNode.empty(), continuation, false);
    return handleBranchNode(split, depth, updates);
  }

  /**
   * Returns the first index in {@code prefix} where some update diverges or runs out of key bits,
   * relative to {@code baseDepth}. Returns {@code prefixLen} when the whole prefix matches every
   * update.
   *
   * <p>The outer loop walks each prefix bit; the inner loop scans all updates for that bit and
   * returns early on the first mismatch (either a differing bit, or a key that has already ended).
   * Only when every update survives every prefix bit do we report a full match.
   */
  private int findDivergenceInPrefix(
      final List<UpdateEntry> updates,
      final int baseDepth,
      final byte[] prefix,
      final int prefixLen) {

    for (int i = 0; i < prefixLen; i++) {
      final int absolutePosition = baseDepth + i;
      final byte prefixBit = ByteTrieOps.bitAt(prefix, i);

      for (final UpdateEntry update : updates) {
        // Short key: this update ends inside the prefix, so it diverges here.
        if (update.bitCount() <= absolutePosition) {
          return i;
        }
        // Differing bit: this update takes a different branch at this position.
        if (update.getBit(absolutePosition) != prefixBit) {
          return i;
        }
      }
    }
    return prefixLen;
  }

  /**
   * If updates split across both sides of the bit at {@code depth}, promotes the leaf to a branch
   * and recurses via {@link #handleBranchNode}; otherwise applies updates sequentially.
   */
  private TrieNode handleLeafNode(
      final LeafNode leaf, final int depth, final List<UpdateEntry> updates) {
    if (updates.size() > 1 && updatesSpanBothSides(updates, depth)) {
      final BranchNode branch = buildBranchFromLeaf(leaf, depth);
      return handleBranchNode(branch, depth, updates);
    }
    return applyUpdatesSequentially(leaf, depth, updates);
  }

  /** Empty-node counterpart to {@link #handleLeafNode}. */
  private TrieNode handleEmptyNode(final int depth, final List<UpdateEntry> updates) {
    if (updates.size() > 1 && updatesSpanBothSides(updates, depth)) {
      final BranchNode branch = buildEmptyBranch();
      return handleBranchNode(branch, depth, updates);
    }
    return applyUpdatesSequentially(TrieNode.empty(), depth, updates);
  }

  private BranchNode buildBranchFromLeaf(final LeafNode leaf, final int depth) {
    // The new prefix-less branch occupies the leaf's former location, so the leaf itself is
    // pushed one bit deeper. Storage is location-keyed: if the leaf was loaded clean, commit
    // would skip it and leave the new location empty (mirrors BranchNode.maybeFlatten).
    leaf.markDirty();
    final TrieKey key = TrieKey.of(leaf.keyBytes(), leaf.keyLength());
    final int keyBits = key.bitCount();
    final boolean leafGoesLeft = depth >= keyBits || key.bitAt(depth) == 0;
    return leafGoesLeft
        ? new BranchNode(new byte[0], 0, leaf, TrieNode.empty(), false)
        : new BranchNode(new byte[0], 0, TrieNode.empty(), leaf, false);
  }

  private BranchNode buildEmptyBranch() {
    return new BranchNode(new byte[0], 0, TrieNode.empty(), TrieNode.empty(), false);
  }

  /**
   * Processes one side of a branch child either by forking or inline.
   *
   * <p>When {@code fork} is true the work is submitted to the {@link ForkJoinPool} and the task is
   * recorded in {@code forkJoinTasks} for later joining; otherwise it runs on the current thread.
   * Callers must submit all fork tasks before any sequential invocation to preserve the Besu
   * fork-before-sequential ordering.
   */
  private void processBranchChild(
      final BranchWrapper branchWrapper,
      final boolean goRight,
      final List<UpdateEntry> updates,
      final int childDepth,
      final boolean fork,
      final List<ForkJoinTask<Void>> forkJoinTasks) {

    final Runnable work =
        () -> {
          final TrieNode currentChild =
              goRight ? branchWrapper.getRightChild() : branchWrapper.getLeftChild();
          final TrieNode updatedChild = processNode(currentChild, childDepth, updates);
          branchWrapper.setChild(goRight, updatedChild);
        };
    if (fork) {
      final ForkJoinTask<Void> task =
          ForkJoinTask.adapt(
              () -> {
                work.run();
                return null;
              });
      task.fork();
      forkJoinTasks.add(task);
    } else {
      work.run();
    }
  }

  /** Partitions {@code updates} into the left (bit 0) and right (bit 1) sides at {@code depth}. */
  private ChildUpdates splitUpdatesByBit(final List<UpdateEntry> updates, final int depth) {
    final List<UpdateEntry> left = new ArrayList<>();
    final List<UpdateEntry> right = new ArrayList<>();
    for (final UpdateEntry update : updates) {
      if (update.getBit(depth) == 0) {
        left.add(update);
      } else {
        right.add(update);
      }
    }
    return new ChildUpdates(left, right);
  }

  /** Returns true once updates cover both the 0-bit and 1-bit sides at {@code depth}. */
  private boolean updatesSpanBothSides(final List<UpdateEntry> updates, final int depth) {
    boolean hasLeft = false;
    boolean hasRight = false;
    for (final UpdateEntry update : updates) {
      if (update.getBit(depth) == 0) {
        hasLeft = true;
      } else {
        hasRight = true;
      }
      if (hasLeft && hasRight) {
        return true;
      }
    }
    return false;
  }

  private record ChildUpdates(List<UpdateEntry> left, List<UpdateEntry> right) {
    boolean bothSidesActive() {
      return !left.isEmpty() && !right.isEmpty();
    }
  }

  /**
   * Non-parallel fallback used when a node cannot be split across multiple updates (e.g. a single
   * update, or a prefix-divergence case with no fork to model). Applies each update's matching
   * {@link PathNodeVisitor} in order, then commits or hashes the result.
   */
  private TrieNode applyUpdatesSequentially(
      final TrieNode node, final int depth, final List<UpdateEntry> updates) {

    TrieNode updatedNode = node;
    for (final UpdateEntry entry : updates) {
      final PathNodeVisitor visitor =
          entry.isMerge()
              ? getPutVisitor(entry.merger())
              : (entry.value().isPresent()
                  ? getPutVisitor(entry.value().get())
                  : getRemoveVisitor());
      updatedNode = updatedNode.accept(visitor, entry.trieKey(), depth);
    }

    updatedNode.merkleHashBytes();
    return updatedNode;
  }

  /** Materializes a {@link StoredTrieNode}; other nodes are returned unchanged. */
  private static TrieNode loadNode(final TrieNode node) {
    if (node instanceof StoredTrieNode stored) {
      return stored.load();
    }
    return node;
  }

  private sealed interface PendingUpdate permits Direct, Merge {
    UpdateEntry toEntry(byte[] key, int keyLen);
  }

  private record Direct(Optional<byte[]> value) implements PendingUpdate {
    @Override
    public UpdateEntry toEntry(final byte[] key, final int keyLen) {
      return new UpdateEntry(key, keyLen, value, null);
    }
  }

  private record Merge(UnaryOperator<Optional<byte[]>> merger) implements PendingUpdate {
    @Override
    public UpdateEntry toEntry(final byte[] key, final int keyLen) {
      return new UpdateEntry(key, keyLen, Optional.empty(), merger);
    }
  }

  private static final class UpdateEntry {
    private final TrieKey trieKey;
    private final Optional<byte[]> value;
    private final UnaryOperator<Optional<byte[]>> merger;

    UpdateEntry(
        final byte[] key,
        final int keyLen,
        final Optional<byte[]> value,
        final UnaryOperator<Optional<byte[]>> merger) {
      this.trieKey = TrieKey.of(key, keyLen);
      this.value = value;
      this.merger = merger;
    }

    TrieKey trieKey() {
      return trieKey;
    }

    Optional<byte[]> value() {
      return value;
    }

    UnaryOperator<Optional<byte[]>> merger() {
      return merger;
    }

    boolean isMerge() {
      return merger != null;
    }

    int bitCount() {
      return trieKey.bitCount();
    }

    byte getBit(final int index) {
      return index >= trieKey.bitCount() ? 0 : trieKey.bitAt(index);
    }
  }

  private static final class BranchWrapper {
    private final BranchNode originalBranch;
    private TrieNode leftChild;
    private TrieNode rightChild;

    BranchWrapper(final BranchNode branch) {
      this.originalBranch = branch;
      this.leftChild = branch.leftChild();
      this.rightChild = branch.rightChild();
    }

    void loadLeft() {
      leftChild = loadNode(leftChild);
    }

    void loadRight() {
      rightChild = loadNode(rightChild);
    }

    TrieNode getLeftChild() {
      return leftChild;
    }

    TrieNode getRightChild() {
      return rightChild;
    }

    void setChild(final boolean goRight, final TrieNode child) {
      if (goRight) {
        rightChild = child;
      } else {
        leftChild = child;
      }
    }

    /**
     * Writes the updated children back into the wrapped branch and collapses it when a side became
     * empty.
     */
    TrieNode applyUpdates() {
      originalBranch.setLeftChild(leftChild);
      originalBranch.setRightChild(rightChild);
      if (leftChild == TrieNode.empty()) {
        return originalBranch.replaceChild(false, TrieNode.empty(), true);
      }
      if (rightChild == TrieNode.empty()) {
        return originalBranch.replaceChild(true, TrieNode.empty(), true);
      }
      originalBranch.markDirty();
      return originalBranch;
    }
  }

  /** Commits the two dirty sides of a branch concurrently. */
  private static final class ParallelCommitVisitor extends CommitVisitor {

    ParallelCommitVisitor(final NodeUpdater nodeUpdater) {
      super(nodeUpdater);
    }

    @Override
    protected void commitChildren(
        final TrieNode left,
        final Bytes leftLocation,
        final TrieNode right,
        final Bytes rightLocation) {
      if (left.isClean() || right.isClean()) {
        super.commitChildren(left, leftLocation, right, rightLocation);
        return;
      }
      final ForkJoinTask<?> leftCommit =
          ForkJoinTask.adapt(() -> left.accept(leftLocation, this)).fork();
      right.accept(rightLocation, this);
      leftCommit.join();
    }
  }

  /** Thread-safe store for the parallel commit, flushed to the real updater afterwards. */
  private static final class CommitCache implements NodeUpdater {
    private final Map<Bytes, NodeData> cache = new ConcurrentHashMap<>();

    @Override
    public void store(final Bytes location, final Bytes32 hash, final Bytes encodedBytes) {
      cache.put(location, new NodeData(hash, encodedBytes));
    }

    void flushTo(final NodeUpdater nodeUpdater) {
      cache.forEach(
          (location, nodeData) ->
              nodeUpdater.store(location, nodeData.hash, nodeData.encodedBytes));
    }

    private record NodeData(Bytes32 hash, Bytes encodedBytes) {}
  }
}
