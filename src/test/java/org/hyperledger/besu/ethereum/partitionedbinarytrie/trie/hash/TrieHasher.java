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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.hash;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.internal.TrieConstants;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.BinaryNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.BranchNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.LeafNode;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.bouncycastle.crypto.digests.Blake3Digest;

/** BLAKE3 hashing utilities for the partitioned binary trie. */
public final class TrieHasher {

  private TrieHasher() {}

  /** Hash {@code data} with BLAKE3, returning a 32-byte digest. */
  public static Bytes32 blake3Hash(final Bytes data) {
    final Blake3Digest digest = new Blake3Digest(256);
    digest.update(data.toArrayUnsafe(), 0, data.size());
    final byte[] output = new byte[32];
    digest.doFinal(output, 0);
    return Bytes32.wrap(output);
  }

  /** Compute the hash committing to {@code node} and everything below it. */
  public static Bytes32 merkleize(final BinaryNode node) {
    return switch (node) {
      case final LeafNode leaf -> blake3Hash(
          Bytes.concatenate(Bytes.of(TrieConstants.LEAF_NODE_TAG), leaf.key(), leaf.value()));
      case final BranchNode branch -> blake3Hash(
          Bytes.concatenate(
              Bytes.of(TrieConstants.BRANCH_NODE_TAG),
              PrefixEncoder.encodeBitPrefix(branch.prefix()),
              merkleize(branch.left()),
              merkleize(branch.right())));
    };
  }
}
