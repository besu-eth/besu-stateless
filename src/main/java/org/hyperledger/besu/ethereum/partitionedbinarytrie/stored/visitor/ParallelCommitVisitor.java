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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.visitor;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.codec.StoredNodeCodec;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.node.BranchNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.node.EmptyTrieNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.node.LeafNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.node.StoredNode;
import org.hyperledger.besu.ethereum.trie.NodeUpdater;

import java.util.concurrent.Callable;
import java.util.concurrent.ForkJoinTask;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Location visitor that persists dirty trie nodes, committing left and right children in parallel.
 *
 * <p>Mirrors {@link CommitVisitor} with parallel child fan-out for binary branch nodes.
 */
@SuppressWarnings("ThreadPriorityCheck")
public class ParallelCommitVisitor implements LocationNodeVisitor {

  private final NodeUpdater nodeUpdater;

  public ParallelCommitVisitor(final NodeUpdater nodeUpdater) {
    this.nodeUpdater = nodeUpdater;
  }

  @Override
  public void visit(final Bytes location, final EmptyTrieNode emptyNode) {}

  @Override
  public void visit(final Bytes location, final LeafNode leafNode) {
    if (leafNode.isClean()) {
      return;
    }
    nodeUpdater.store(
        location,
        Bytes32.wrap(leafNode.merkleHashBytes()),
        StoredNodeCodec.encodeLeaf(
            leafNode.keyBytes(), leafNode.keyLength(), leafNode.valueBytes()));
    leafNode.markClean();
  }

  @Override
  public void visit(final Bytes location, final BranchNode branchNode) {
    if (branchNode.isClean()) {
      return;
    }
    final Bytes leftLoc =
        StoredNodeCodec.childLocation(
            location, branchNode.prefixBits(), branchNode.prefixLength(), 0);
    final Bytes rightLoc =
        StoredNodeCodec.childLocation(
            location, branchNode.prefixBits(), branchNode.prefixLength(), 1);

    final ForkJoinTask<Void> leftTask =
        ForkJoinTask.adapt(
            (Callable<Void>)
                () -> {
                  branchNode.leftChild().accept(leftLoc, ParallelCommitVisitor.this);
                  return null;
                });
    final ForkJoinTask<Void> rightTask =
        ForkJoinTask.adapt(
            (Callable<Void>)
                () -> {
                  branchNode.rightChild().accept(rightLoc, ParallelCommitVisitor.this);
                  return null;
                });
    leftTask.fork();
    rightTask.fork();
    leftTask.join();
    rightTask.join();

    nodeUpdater.store(
        location,
        Bytes32.wrap(branchNode.merkleHashBytes()),
        StoredNodeCodec.encodeBranch(
            branchNode.prefixBits(),
            branchNode.prefixLength(),
            branchNode.leftChild().merkleHashBytes(),
            branchNode.rightChild().merkleHashBytes()));
    branchNode.markClean();
  }

  @Override
  public void visit(final Bytes location, final StoredNode storedNode) {
    storedNode.load().accept(storedNode.storageLocation(), this);
    storedNode.reloadAfterCommit();
  }
}
