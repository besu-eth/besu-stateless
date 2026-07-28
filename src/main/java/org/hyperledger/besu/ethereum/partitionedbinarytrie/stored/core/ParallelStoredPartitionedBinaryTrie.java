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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.core;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.internal.TrieConstants;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.internal.bytes.ByteTrieOps;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.codec.TrieNodeCodec;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.factory.StoredTrieNodeFactory;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.node.EmptyTrieNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.node.MemoryBranchNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.node.MemoryLeafNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.node.StoredTrieNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.node.TrieNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.visitor.ParallelCommitVisitor;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.visitor.PathNodeVisitor;
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
import java.util.stream.Collectors;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Parallel stored partitioned binary trie that batches updates and applies them concurrently.
 *
 * <p>Mirrors Besu {@link org.hyperledger.besu.ethereum.trie.patricia.ParallelStoredMerklePatriciaTrie}.
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

  public ParallelStoredPartitionedBinaryTrie(
      final NodeLoader nodeLoader, final Bytes32 rootHash) {
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
    pendingUpdates.put(Bytes.wrap(key, 0, keyLen), new Direct(Optional.of(value)));
  }

  @Override
  public void putDeferred(
      final byte[] key, final int keyLen, final UnaryOperator<Optional<byte[]>> merger) {
    pendingUpdates.put(Bytes.wrap(key, 0, keyLen), new Merge(merger));
  }

  @Override
  public void remove(final byte[] key, final int keyLen) {
    pendingUpdates.put(Bytes.wrap(key, 0, keyLen), new Direct(Optional.empty()));
  }

  @Override
  public void commit(final NodeUpdater nodeUpdater) {
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

  private TrieNode processNode(
      final TrieNode node,
      final Bytes location,
      final int depth,
      final List<UpdateEntry> updates,
      final Optional<CommitCache> maybeCommitCache) {

    final TrieNode loadedNode = loadNode(node);
    if (loadedNode instanceof MemoryBranchNode branch) {
      return handleBranchNode(branch, location, depth, updates, maybeCommitCache);
    }
    if (loadedNode instanceof MemoryLeafNode leaf) {
      return handleLeafNode(leaf, location, depth, updates, maybeCommitCache);
    }
    if (loadedNode instanceof EmptyTrieNode) {
      return handleEmptyNode(location, depth, updates, maybeCommitCache);
    }
    return applyUpdatesSequentially(loadedNode, location, depth, updates, maybeCommitCache);
  }

  private TrieNode handleBranchNode(
      final MemoryBranchNode branchNode,
      final Bytes location,
      final int depth,
      final List<UpdateEntry> updates,
      final Optional<CommitCache> maybeCommitCache) {

    final byte[] prefixBits = branchNode.prefixBits();
    final int prefixLen = branchNode.prefixLength();

    final int divergenceIndex = findDivergenceInPrefix(updates, depth, prefixBits, prefixLen);
    if (divergenceIndex < prefixLen) {
      if (updates.size() > 1) {
        return expandBranchPrefixToDivergence(
            branchNode, prefixBits, prefixLen, location, depth, updates, maybeCommitCache);
      }
      return applyUpdatesSequentially(branchNode, location, depth, updates, maybeCommitCache);
    }

    final int splitDepth = depth + prefixLen;
    final Map<Byte, List<UpdateEntry>> groupedUpdates = groupUpdatesByBit(updates, splitDepth);

    final BranchWrapper branchWrapper = new BranchWrapper(branchNode);
    branchWrapper.loadChildren();

    final Map<Boolean, Map<Byte, List<UpdateEntry>>> partitionedGroups =
        groupedUpdates.entrySet().stream()
            .collect(
                Collectors.partitioningBy(
                    entry -> entry.getValue().size() > 1 && groupedUpdates.size() > 1,
                    Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    final Map<Byte, List<UpdateEntry>> largeGroups = partitionedGroups.get(true);
    final Map<Byte, List<UpdateEntry>> smallGroups = partitionedGroups.get(false);

    final List<ForkJoinTask<Void>> forkJoinTasks = new ArrayList<>();

    for (final Map.Entry<Byte, List<UpdateEntry>> entry : largeGroups.entrySet()) {
      final boolean goRight = entry.getKey() == 1;
      final List<UpdateEntry> childUpdates = entry.getValue();
      final Bytes childLocation =
          TrieNodeCodec.childLocation(location, prefixBits, prefixLen, goRight ? 1 : 0);

      final ForkJoinTask<Void> task =
          ForkJoinTask.adapt(
              () -> {
                final TrieNode currentChild =
                    goRight ? branchWrapper.getRightChild() : branchWrapper.getLeftChild();
                final TrieNode updatedChild =
                    processNode(
                        currentChild, childLocation, splitDepth + 1, childUpdates, maybeCommitCache);
                branchWrapper.setChild(goRight, updatedChild);
                return null;
              });
      task.fork();
      forkJoinTasks.add(task);
    }

    for (final Map.Entry<Byte, List<UpdateEntry>> entry : smallGroups.entrySet()) {
      final boolean goRight = entry.getKey() == 1;
      final List<UpdateEntry> childUpdates = entry.getValue();
      final Bytes childLocation =
          TrieNodeCodec.childLocation(location, prefixBits, prefixLen, goRight ? 1 : 0);

      final TrieNode currentChild =
          goRight ? branchWrapper.getRightChild() : branchWrapper.getLeftChild();
      final TrieNode updatedChild =
          processNode(currentChild, childLocation, splitDepth + 1, childUpdates, maybeCommitCache);
      branchWrapper.setChild(goRight, updatedChild);
    }

    forkJoinTasks.forEach(ForkJoinTask::join);

    final TrieNode newBranch = branchWrapper.applyUpdates();
    commitOrHashNode(newBranch, location, maybeCommitCache);
    return newBranch;
  }

  private TrieNode expandBranchPrefixToDivergence(
      final MemoryBranchNode branchNode,
      final byte[] prefixBits,
      final int prefixLen,
      final Bytes location,
      final int depth,
      final List<UpdateEntry> updates,
      final Optional<CommitCache> maybeCommitCache) {

    final int divergenceIndex = findDivergenceInPrefix(updates, depth, prefixBits, prefixLen);
    final byte[] commonPrefix = Arrays.copyOfRange(prefixBits, 0, divergenceIndex);
    final byte divergingBit = prefixBits[divergenceIndex];
    final byte[] remainingSuffix =
        Arrays.copyOfRange(prefixBits, divergenceIndex + 1, prefixLen);

    final TrieNode continuation =
        remainingSuffix.length == 0
            ? new MemoryBranchNode(
                new byte[0],
                0,
                loadNode(branchNode.leftChild()),
                loadNode(branchNode.rightChild()),
                false)
            : new MemoryBranchNode(
                remainingSuffix,
                remainingSuffix.length,
                loadNode(branchNode.leftChild()),
                loadNode(branchNode.rightChild()),
                false);

    TrieNode currentNode;
    if (divergingBit == 0) {
      currentNode = new MemoryBranchNode(new byte[0], 0, continuation, TrieNode.empty(), false);
    } else {
      currentNode = new MemoryBranchNode(new byte[0], 0, TrieNode.empty(), continuation, false);
    }

    for (int i = commonPrefix.length - 1; i >= 0; i--) {
      if (commonPrefix[i] == 0) {
        currentNode = new MemoryBranchNode(new byte[0], 0, currentNode, TrieNode.empty(), false);
      } else {
        currentNode = new MemoryBranchNode(new byte[0], 0, TrieNode.empty(), currentNode, false);
      }
    }

    return processNode(currentNode, location, depth, updates, maybeCommitCache);
  }

  private int findDivergenceInPrefix(
      final List<UpdateEntry> updates,
      final int baseDepth,
      final byte[] prefixBits,
      final int prefixLen) {

    for (int i = 0; i < prefixLen; i++) {
      final int absolutePosition = baseDepth + i;
      final byte prefixBit = prefixBits[i];

      for (final UpdateEntry update : updates) {
        if (update.bitCount() <= absolutePosition) {
          return i;
        }
        if (update.getBit(absolutePosition) != prefixBit) {
          return i;
        }
      }
    }
    return prefixLen;
  }

  private TrieNode handleLeafNode(
      final MemoryLeafNode leaf,
      final Bytes location,
      final int depth,
      final List<UpdateEntry> updates,
      final Optional<CommitCache> maybeCommitCache) {
    if (updates.size() > 1 && countDistinctBitsAtDepth(updates, depth) > 1) {
      final MemoryBranchNode branch = buildBranchFromLeaf(leaf, depth);
      return handleBranchNode(branch, location, depth, updates, maybeCommitCache);
    }
    return applyUpdatesSequentially(leaf, location, depth, updates, maybeCommitCache);
  }

  private TrieNode handleEmptyNode(
      final Bytes location,
      final int depth,
      final List<UpdateEntry> updates,
      final Optional<CommitCache> maybeCommitCache) {
    if (updates.size() > 1 && countDistinctBitsAtDepth(updates, depth) > 1) {
      final MemoryBranchNode branch = buildEmptyBranch();
      return handleBranchNode(branch, location, depth, updates, maybeCommitCache);
    }
    return applyUpdatesSequentially(TrieNode.empty(), location, depth, updates, maybeCommitCache);
  }

  private MemoryBranchNode buildBranchFromLeaf(final MemoryLeafNode leaf, final int depth) {
    final byte[] bits = ByteTrieOps.expandKeyBitsCopy(leaf.keyBytes(), leaf.keyLength());
    final int keyBits = leaf.keyLength() * 8;
    if (depth >= keyBits) {
      return new MemoryBranchNode(new byte[0], 0, leaf, TrieNode.empty(), false);
    }
    if (bits[depth] == 0) {
      return new MemoryBranchNode(new byte[0], 0, leaf, TrieNode.empty(), false);
    }
    return new MemoryBranchNode(new byte[0], 0, TrieNode.empty(), leaf, false);
  }

  private MemoryBranchNode buildEmptyBranch() {
    return new MemoryBranchNode(new byte[0], 0, TrieNode.empty(), TrieNode.empty(), false);
  }

  private Map<Byte, List<UpdateEntry>> groupUpdatesByBit(
      final List<UpdateEntry> updates, final int depth) {
    return updates.stream().collect(Collectors.groupingBy(entry -> entry.getBit(depth)));
  }

  private int countDistinctBitsAtDepth(final List<UpdateEntry> updates, final int depth) {
    return (int) updates.stream().map(entry -> entry.getBit(depth)).distinct().count();
  }

  private void commitOrHashNode(
      final TrieNode node, final Bytes location, final Optional<CommitCache> maybeCommitCache) {
    if (maybeCommitCache.isPresent()) {
      node.accept(location, new ParallelCommitVisitor(maybeCommitCache.get()));
    } else {
      Objects.requireNonNull(node.merkleHashBytes());
    }
  }

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
              ? getDeferredPutVisitor(entry.merger())
              : (entry.value().isPresent()
                  ? getPutVisitor(entry.value().get())
                  : getRemoveVisitor());
      updatedNode = updatedNode.accept(visitor, entry.key(), entry.keyLen(), depth);
    }

    commitOrHashNode(updatedNode, location, maybeCommitCache);
    return updatedNode;
  }

  private void storeAndResetRoot(final NodeUpdater nodeUpdater) {
    final Bytes32 rootHash = getRootHash();
    nodeUpdater.store(Bytes.EMPTY, rootHash, root.encode());

    this.root =
        rootHash.equals(TrieConstants.EMPTY_TRIE_ROOT)
            ? TrieNode.empty()
            : nodeFactory.wrapStored(Bytes.EMPTY, rootHash);
  }

  private TrieNode loadNode(final TrieNode node) {
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
    private final byte[] key;
    private final int keyLen;
    private final Optional<byte[]> value;
    private final UnaryOperator<Optional<byte[]>> merger;
    private final byte[] expandedBits;

    UpdateEntry(
        final byte[] key,
        final int keyLen,
        final Optional<byte[]> value,
        final UnaryOperator<Optional<byte[]>> merger) {
      this.key = key;
      this.keyLen = keyLen;
      this.value = value;
      this.merger = merger;
      this.expandedBits = ByteTrieOps.expandKeyBitsCopy(key, keyLen);
    }

    byte[] key() {
      return key;
    }

    int keyLen() {
      return keyLen;
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
      return keyLen * 8;
    }

    byte getBit(final int index) {
      return index >= expandedBits.length ? 0 : expandedBits[index];
    }
  }

  private static final class BranchWrapper {
    private final MemoryBranchNode originalBranch;
    private TrieNode leftChild;
    private TrieNode rightChild;

    BranchWrapper(final MemoryBranchNode branch) {
      this.originalBranch = branch;
      this.leftChild = branch.leftChild();
      this.rightChild = branch.rightChild();
    }

    void loadChildren() {
      leftChild = loadStored(leftChild);
      rightChild = loadStored(rightChild);
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

    private static TrieNode loadStored(final TrieNode node) {
      if (node instanceof StoredTrieNode stored) {
        return stored.load();
      }
      return node;
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
