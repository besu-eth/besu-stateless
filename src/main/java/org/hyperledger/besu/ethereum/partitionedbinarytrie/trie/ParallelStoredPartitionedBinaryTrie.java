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

import org.hyperledger.besu.ethereum.partitionedbinarytrie.codec.TrieNodeCodec;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieConstants;
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

  @Override
  public void commit(final NodeUpdater nodeUpdater) {
    flushCodeRefCounts(nodeUpdater);
    if (pendingUpdates.isEmpty()) {
      persistDirtyRoot(nodeUpdater);
      return;
    }
    processPendingUpdates(Optional.of(nodeUpdater));
  }

  @Override
  public Bytes32 getRootHash() {
    if (pendingUpdates.isEmpty()) {
      return super.getRootHash();
    }
    processPendingUpdates(Optional.empty());
    return super.getRootHash();
  }

  private void processPendingUpdates(final Optional<NodeUpdater> maybeNodeUpdater) {
    if (pendingUpdates.isEmpty()) {
      return;
    }

    try {
      this.root = loadNode(root);

      final List<UpdateEntry> entries = new ArrayList<>();
      pendingUpdates.forEach(
          (keyBytes, update) ->
              entries.add(update.toEntry(keyBytes.toArrayUnsafe(), keyBytes.size())));

      final CommitCache commitCache = new CommitCache();
      final boolean shouldCommit = maybeNodeUpdater.isPresent();

      this.root =
          forkJoinPool.invoke(
              ForkJoinTask.adapt(
                  () ->
                      processNode(
                          root,
                          Bytes.EMPTY,
                          0,
                          entries,
                          shouldCommit ? Optional.of(commitCache) : Optional.empty())));

      if (maybeNodeUpdater.isPresent()) {
        commitCache.flushTo(maybeNodeUpdater.get());
        storeAndResetRoot(maybeNodeUpdater.get());
      }
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
      final TrieNode node,
      final Bytes location,
      final int depth,
      final List<UpdateEntry> updates,
      final Optional<CommitCache> maybeCommitCache) {

    final TrieNode loadedNode = loadNode(node);
    if (loadedNode instanceof BranchNode branch) {
      return handleBranchNode(branch, location, depth, updates, maybeCommitCache);
    }
    if (loadedNode instanceof LeafNode leaf) {
      return handleLeafNode(leaf, location, depth, updates, maybeCommitCache);
    }
    if (loadedNode instanceof EmptyTrieNode) {
      return handleEmptyNode(location, depth, updates, maybeCommitCache);
    }
    return applyUpdatesSequentially(loadedNode, location, depth, updates, maybeCommitCache);
  }

  private TrieNode handleBranchNode(
      final BranchNode branchNode,
      final Bytes location,
      final int depth,
      final List<UpdateEntry> updates,
      final Optional<CommitCache> maybeCommitCache) {

    final byte[] prefixBits = branchNode.prefixBits();
    final int prefixLen = branchNode.prefixLength();

    // Find the earliest prefix bit that not all updates still follow (some update either
    // diverges or runs out of key bits before the prefix ends).
    final int divergenceIndex = findDivergenceInPrefix(updates, depth, prefixBits, prefixLen);
    if (divergenceIndex < prefixLen) {
      // At least one update exits the compressed prefix: the branch must be restructured
      // before its children can be touched. With multiple updates we rebuild the prefix
      // chain in parallel-friendly form; with a single update there is no fork to model,
      // so the sequential visitor path is cheaper.
      if (updates.size() > 1) {
        return expandBranchPrefixToDivergence(
            branchNode,
            prefixBits,
            prefixLen,
            divergenceIndex,
            location,
            depth,
            updates,
            maybeCommitCache);
      }
      return applyUpdatesSequentially(branchNode, location, depth, updates, maybeCommitCache);
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

    // Precompute the per-child context once: each value feeds two of the four calls below.
    final Bytes leftLocation = TrieNodeCodec.childLocation(location, prefixBits, prefixLen, 0);
    final Bytes rightLocation = TrieNodeCodec.childLocation(location, prefixBits, prefixLen, 1);
    final int childDepth = splitDepth + 1;
    final List<ForkJoinTask<Void>> forkJoinTasks = new ArrayList<>();

    // Submit all fork tasks before any sequential work (Besu parallel trie pattern): a fork
    // task could otherwise block waiting for the pool thread that is busy running the
    // sequential side, deadlocking the worker pool.
    if (!childUpdates.left().isEmpty() && forkLeft) {
      processBranchChild(
          branchWrapper,
          false,
          childUpdates.left(),
          leftLocation,
          childDepth,
          maybeCommitCache,
          true,
          forkJoinTasks);
    }
    if (!childUpdates.right().isEmpty() && forkRight) {
      processBranchChild(
          branchWrapper,
          true,
          childUpdates.right(),
          rightLocation,
          childDepth,
          maybeCommitCache,
          true,
          forkJoinTasks);
    }

    if (!childUpdates.left().isEmpty() && !forkLeft) {
      processBranchChild(
          branchWrapper,
          false,
          childUpdates.left(),
          leftLocation,
          childDepth,
          maybeCommitCache,
          false,
          forkJoinTasks);
    }
    if (!childUpdates.right().isEmpty() && !forkRight) {
      processBranchChild(
          branchWrapper,
          true,
          childUpdates.right(),
          rightLocation,
          childDepth,
          maybeCommitCache,
          false,
          forkJoinTasks);
    }

    // Wait for every forked side before reassembling the branch, so both children are final.
    forkJoinTasks.forEach(ForkJoinTask::join);

    // Replace the branch's children with their updated versions; collapse the node if one
    // side became empty (the remaining side is hoisted up by replaceChild).
    final TrieNode newBranch = branchWrapper.applyUpdates();
    // Persist via the commit cache when this is the root batch, otherwise just assert the
    // hash is materialized for the parent's commit.
    commitOrHashNode(newBranch, location, maybeCommitCache);
    return newBranch;
  }

  /**
   * Rebuilds a branch whose prefix no longer matches all updates by inserting empty prefix-less
   * branches along the diverging portion of the original prefix, then recursing via {@link
   * #processNode}.
   *
   * <p>The original compressed prefix is split into three parts at {@code divergenceIndex}: the
   * bits before it (shared by every update), the diverging bit itself (which routes the survivor
   * onto one side), and the bits after it (kept on the survivor so its subtree is unchanged). The
   * survivor is the original branch with its prefix trimmed to the remaining suffix; we then wrap
   * it outward, first at the diverging bit and then backwards through the shared prefix bits, to
   * reconstruct an equivalent un-compressed chain that {@link #processNode} can split again.
   *
   * @param divergenceIndex position within {@code prefixBits} where at least one update diverges;
   *     must be {@code < prefixLen}
   */
  private TrieNode expandBranchPrefixToDivergence(
      final BranchNode branchNode,
      final byte[] prefixBits,
      final int prefixLen,
      final int divergenceIndex,
      final Bytes location,
      final int depth,
      final List<UpdateEntry> updates,
      final Optional<CommitCache> maybeCommitCache) {

    // Split the compressed prefix: shared head, the diverging bit, and the tail carried by the
    // survivor branch.
    final byte[] commonPrefix = Arrays.copyOfRange(prefixBits, 0, divergenceIndex);
    final byte divergingBit = prefixBits[divergenceIndex];
    final byte[] remainingSuffix = Arrays.copyOfRange(prefixBits, divergenceIndex + 1, prefixLen);

    // Survivor keeps the original children under the trimmed suffix, so nothing below changes.
    // Keep stored proxies as-is; handleBranchNode loads only the side that receives updates.
    final TrieNode continuation =
        new BranchNode(
            remainingSuffix,
            remainingSuffix.length,
            branchNode.leftChild(),
            branchNode.rightChild(),
            false);

    // Rebuild the chain from the survivor outward: wrap at the diverging bit first, then wrap
    // backwards through the shared prefix so the outermost node corresponds to depth.
    TrieNode currentNode = wrapSingleChildBranch(continuation, divergingBit);
    for (int i = commonPrefix.length - 1; i >= 0; i--) {
      currentNode = wrapSingleChildBranch(currentNode, commonPrefix[i]);
    }

    return processNode(currentNode, location, depth, updates, maybeCommitCache);
  }

  /** Builds a prefix-less branch holding {@code child} on the side indicated by {@code bit}. */
  private static BranchNode wrapSingleChildBranch(final TrieNode child, final byte bit) {
    return bit == 0
        ? new BranchNode(new byte[0], 0, child, TrieNode.empty(), false)
        : new BranchNode(new byte[0], 0, TrieNode.empty(), child, false);
  }

  /**
   * Returns the first index in {@code prefixBits} where some update diverges or runs out of key
   * bits, relative to {@code baseDepth}. Returns {@code prefixLen} when the whole prefix matches
   * every update.
   *
   * <p>The outer loop walks each prefix bit; the inner loop scans all updates for that bit and
   * returns early on the first mismatch (either a differing bit, or a key that has already ended).
   * Only when every update survives every prefix bit do we report a full match.
   */
  private int findDivergenceInPrefix(
      final List<UpdateEntry> updates,
      final int baseDepth,
      final byte[] prefixBits,
      final int prefixLen) {

    for (int i = 0; i < prefixLen; i++) {
      final int absolutePosition = baseDepth + i;
      final byte prefixBit = prefixBits[i];

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
      final LeafNode leaf,
      final Bytes location,
      final int depth,
      final List<UpdateEntry> updates,
      final Optional<CommitCache> maybeCommitCache) {
    if (updates.size() > 1 && updatesSpanBothSides(updates, depth)) {
      final BranchNode branch = buildBranchFromLeaf(leaf, depth);
      return handleBranchNode(branch, location, depth, updates, maybeCommitCache);
    }
    return applyUpdatesSequentially(leaf, location, depth, updates, maybeCommitCache);
  }

  /** Empty-node counterpart to {@link #handleLeafNode}. */
  private TrieNode handleEmptyNode(
      final Bytes location,
      final int depth,
      final List<UpdateEntry> updates,
      final Optional<CommitCache> maybeCommitCache) {
    if (updates.size() > 1 && updatesSpanBothSides(updates, depth)) {
      final BranchNode branch = buildEmptyBranch();
      return handleBranchNode(branch, location, depth, updates, maybeCommitCache);
    }
    return applyUpdatesSequentially(TrieNode.empty(), location, depth, updates, maybeCommitCache);
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
      final Bytes childLocation,
      final int childDepth,
      final Optional<CommitCache> maybeCommitCache,
      final boolean fork,
      final List<ForkJoinTask<Void>> forkJoinTasks) {

    final Runnable work =
        () -> {
          final TrieNode currentChild =
              goRight ? branchWrapper.getRightChild() : branchWrapper.getLeftChild();
          final TrieNode updatedChild =
              processNode(currentChild, childLocation, childDepth, updates, maybeCommitCache);
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

  /** Commits the node when a {@link CommitCache} is present, otherwise just asserts its hash. */
  private void commitOrHashNode(
      final TrieNode node, final Bytes location, final Optional<CommitCache> maybeCommitCache) {
    if (maybeCommitCache.isPresent()) {
      node.accept(location, new CommitVisitor(maybeCommitCache.get()));
    } else {
      Objects.requireNonNull(node.merkleHashBytes());
    }
  }

  /**
   * Non-parallel fallback used when a node cannot be split across multiple updates (e.g. a single
   * update, or a prefix-divergence case with no fork to model). Applies each update's matching
   * {@link PathNodeVisitor} in order, then commits or hashes the result.
   */
  private TrieNode applyUpdatesSequentially(
      final TrieNode node,
      final Bytes location,
      final int depth,
      final List<UpdateEntry> updates,
      final Optional<CommitCache> maybeCommitCache) {

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

    commitOrHashNode(updatedNode, location, maybeCommitCache);
    return updatedNode;
  }

  /**
   * Writes dirty nodes after updates were already applied without a {@link NodeUpdater} (for
   * example by {@link #getRootHash()}). {@link #processPendingUpdates} is a no-op when {@code
   * pendingUpdates} is empty, so commit would otherwise persist nothing.
   */
  private void persistDirtyRoot(final NodeUpdater nodeUpdater) {
    this.root = loadNode(root);
    final CommitCache commitCache = new CommitCache();
    root.accept(Bytes.EMPTY, new CommitVisitor(commitCache));
    commitCache.flushTo(nodeUpdater);
    storeAndResetRoot(nodeUpdater);
  }

  /** Stores the root under the empty location and resets it to a clean stored proxy. */
  private void storeAndResetRoot(final NodeUpdater nodeUpdater) {
    final Bytes32 rootHash = Bytes32.wrap(root.merkleHashBytes());
    nodeUpdater.store(Bytes.EMPTY, rootHash, root.encode());

    this.root =
        rootHash.equals(TrieConstants.EMPTY_TRIE_ROOT)
            ? TrieNode.empty()
            : nodeFactory.wrapStored(Bytes.EMPTY, rootHash);
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
