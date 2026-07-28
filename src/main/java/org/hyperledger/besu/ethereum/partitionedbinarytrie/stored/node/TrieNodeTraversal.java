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

import static java.util.stream.Collectors.toUnmodifiableSet;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/** Ordered traversal helpers for {@link TrieNode} graphs. */
public final class TrieNodeTraversal {

  /** Callback for in-order leaf visits. */
  public interface LeafHandler {

    /** Continue or stop leaf iteration. */
    enum State {
      CONTINUE,
      STOP
    }

    /**
     * Invoked for each leaf in lexicographic key order.
     *
     * @param key leaf key bytes
     * @param keyLen valid key length in bytes
     * @param value 32-byte leaf value
     * @return {@link State#STOP} to end iteration early
     */
    State onLeaf(byte[] key, int keyLen, byte[] value);
  }

  private TrieNodeTraversal() {}

  /**
   * Visits every node in the subtree (pre-order).
   *
   * @param root subtree root
   * @param nodeConsumer invoked for each node
   */
  public static void visitAll(final TrieNode root, final Consumer<TrieNode> nodeConsumer) {
    if (root instanceof StoredTrieNode stored) {
      visitAll(stored.load(), nodeConsumer);
      return;
    }
    nodeConsumer.accept(root);
    if (root instanceof MemoryBranchNode branch) {
      visitAll(branch.leftChild(), nodeConsumer);
      visitAll(branch.rightChild(), nodeConsumer);
    }
  }

  /**
   * Visits every node in the subtree, fanning out direct children of branch nodes in parallel.
   *
   * @param root subtree root
   * @param nodeConsumer invoked for each node
   * @param executorService executor for child subtrees
   * @return future completing when all visits finish
   */
  public static CompletableFuture<Void> visitAllParallel(
      final TrieNode root,
      final Consumer<TrieNode> nodeConsumer,
      final ExecutorService executorService) {
    if (root instanceof StoredTrieNode stored) {
      return visitAllParallel(stored.load(), nodeConsumer, executorService);
    }
    final Stream<CompletableFuture<Void>> childFutures;
    if (root instanceof MemoryBranchNode branch) {
      childFutures =
          Stream.of(
              visitAllParallel(branch.leftChild(), nodeConsumer, executorService),
              visitAllParallel(branch.rightChild(), nodeConsumer, executorService));
    } else {
      childFutures = Stream.empty();
    }
    return CompletableFuture.allOf(
        Stream.concat(
                Stream.of(
                    CompletableFuture.runAsync(() -> nodeConsumer.accept(root), executorService)),
                childFutures)
            .collect(toUnmodifiableSet())
            .toArray(CompletableFuture[]::new));
  }

  /**
   * Visits leaves in lexicographic key order.
   *
   * @param root subtree root
   * @param handler leaf callback
   */
  public static void visitLeaves(final TrieNode root, final LeafHandler handler) {
    final boolean[] stopped = {false};
    visitLeavesWithStop(root, handler, stopped);
  }

  /**
   * Collects up to {@code limit} entries with keys greater than or equal to {@code startKey}.
   *
   * @param root subtree root
   * @param startKey first key to include (lexicographic)
   * @param startKeyLen valid length of {@code startKey}
   * @param limit maximum number of entries
   * @return map of right-padded key hash to value bytes
   */
  public static Map<Bytes32, byte[]> entriesFrom(
      final TrieNode root, final byte[] startKey, final int startKeyLen, final int limit) {
    final Bytes32 startKeyPadded = Bytes32.rightPad(Bytes.wrap(startKey, 0, startKeyLen));
    final Map<Bytes32, byte[]> values = new TreeMap<>();
    visitLeaves(
        root,
        (key, keyLen, value) -> {
          final Bytes32 keyPadded = Bytes32.rightPad(Bytes.wrap(key, 0, keyLen));
          if (keyPadded.compareTo(startKeyPadded) >= 0) {
            values.put(keyPadded, value);
            if (values.size() >= limit) {
              return LeafHandler.State.STOP;
            }
          }
          return LeafHandler.State.CONTINUE;
        });
    return values;
  }

  private static void visitLeavesWithStop(
      final TrieNode root, final LeafHandler handler, final boolean[] stopped) {
    if (stopped[0] || root instanceof EmptyTrieNode) {
      return;
    }
    if (root instanceof MemoryLeafNode leaf) {
      if (handler.onLeaf(leaf.keyBytes(), leaf.keyLength(), leaf.valueBytes())
          == LeafHandler.State.STOP) {
        stopped[0] = true;
      }
      return;
    }
    if (root instanceof MemoryBranchNode branch) {
      visitLeavesWithStop(branch.leftChild(), handler, stopped);
      if (!stopped[0]) {
        visitLeavesWithStop(branch.rightChild(), handler, stopped);
      }
      return;
    }
    if (root instanceof StoredTrieNode stored) {
      visitLeavesWithStop(stored.load(), handler, stopped);
    }
  }
}
