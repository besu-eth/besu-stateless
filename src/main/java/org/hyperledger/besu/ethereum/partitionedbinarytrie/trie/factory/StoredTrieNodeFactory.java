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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.codec.TrieNodeCodec;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.internal.bytes.ByteTrieOps;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieConstants;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.BranchNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.LeafNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.StoredTrieNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.TrieNode;
import org.hyperledger.besu.ethereum.trie.MerkleTrieException;
import org.hyperledger.besu.ethereum.trie.NodeLoader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/** Node codec and loader backed by a Besu {@link NodeLoader}. */
public final class StoredTrieNodeFactory {

  private final NodeLoader nodeLoader;

  public StoredTrieNodeFactory(final NodeLoader nodeLoader) {
    this.nodeLoader = nodeLoader;
  }

  public TrieNode retrieveRoot() {
    return retrieve(Bytes.EMPTY);
  }

  /**
   * Reads raw stored bytes, for keys that do not hold an encoded trie node.
   *
   * @param key storage key
   * @return stored bytes, or empty when absent
   */
  public Optional<Bytes> retrieveRaw(final Bytes key) {
    return nodeLoader.getNode(key, null);
  }

  public TrieNode retrieve(final Bytes location) {
    return retrieve(location, null);
  }

  public TrieNode retrieve(final Bytes location, final Bytes32 hash) {
    final Optional<Bytes> encoded = nodeLoader.getNode(location, hash);
    if (encoded.isEmpty()) {
      // Empty-root / absent empty-hash lookups are NullNode. A real hash miss must not become a
      // silent empty child (Besu StoredNode.load throws MerkleTrieException).
      if (hash == null || hash.equals(TrieConstants.EMPTY_TRIE_ROOT)) {
        return TrieNode.empty();
      }
      throw new MerkleTrieException(
          "Unable to load trie node value for hash " + hash + " location " + location,
          hash,
          location);
    }
    return decode(location, encoded.get());
  }

  public TrieNode wrapStored(final Bytes location, final Bytes32 hash) {
    return wrapStored(Suppliers.ofInstance(location), hash);
  }

  /** Same as {@link #wrapStored(Bytes, Bytes32)} with a location computed only if asked for. */
  public TrieNode wrapStored(final Supplier<Bytes> location, final Bytes32 hash) {
    // Missing child / empty root is the EmptyTrieNode singleton, never a StoredTrieNode stub.
    if (hash.equals(TrieConstants.EMPTY_TRIE_ROOT)) {
      return TrieNode.empty();
    }
    return new StoredTrieNode(this, location, hash);
  }

  TrieNode decode(final Bytes location, final Bytes encoded) {
    if (encoded.isEmpty()) {
      return TrieNode.empty();
    }
    final byte[] raw = encoded.toArrayUnsafe();
    final int tag = raw[0] & 0xFF;
    if (tag == TrieNodeCodec.STEM_TAG) {
      // Rebuild the stem's leaves and the branches between them.
      final List<LeafNode> leaves = new ArrayList<>();
      TrieNodeCodec.decodeStem(
          raw, (key, value) -> leaves.add(new LeafNode(key, key.length, value, true)));
      return stemSubtree(leaves, 0, leaves.size(), location.size());
    }
    if (tag == TrieNodeCodec.BRANCH_TAG) {
      // Wire layout (inverse of TrieNodeCodec.encodeBranch):
      // [tag | prefixLen (2) | packed prefix | leftHash (32) | rightHash (32)]
      // prefixLen: prefix length in bits (shared path before the left/right split).
      final int prefixLen = TrieNodeCodec.branchPrefixLength(raw);
      final byte[] prefixBits =
          TrieNodeCodec.unpackPrefix(raw, TrieNodeCodec.BRANCH_PREFIX_OFFSET, prefixLen);

      // The two child hashes close the encoding.
      final int rightOffset = raw.length - Bytes32.SIZE;
      final int leftOffset = rightOffset - Bytes32.SIZE;
      final Bytes32 leftHash = Bytes32.wrap(Arrays.copyOfRange(raw, leftOffset, rightOffset));
      final Bytes32 rightHash = Bytes32.wrap(Arrays.copyOfRange(raw, rightOffset, raw.length));
      // Child paths: current location + prefix bits + split bit (0=left, 1=right).
      final Bytes leftLoc = TrieNodeCodec.childLocation(location, prefixBits, prefixLen, 0);
      final Bytes rightLoc = TrieNodeCodec.childLocation(location, prefixBits, prefixLen, 1);
      // Empty hash → NullNode singleton (never a StoredTrieNode that would load from disk).
      return new BranchNode(
          prefixBits,
          prefixLen,
          wrapStored(leftLoc, leftHash),
          wrapStored(rightLoc, rightHash),
          true);
    }
    throw new IllegalArgumentException("Unknown node tag: " + tag);
  }

  /** Rebuilds the subtree of {@code leaves[from, to)}, sorted, attached at bit {@code depth}. */
  private static TrieNode stemSubtree(
      final List<LeafNode> leaves, final int from, final int to, final int depth) {
    if (to - from == 1) {
      return leaves.get(from);
    }
    // The range splits where its first and last keys diverge.
    final byte[] first = leaves.get(from).keyBytes();
    final byte[] last = leaves.get(to - 1).keyBytes();
    int split = depth;
    while (ByteTrieOps.bitAt(first, split) == ByteTrieOps.bitAt(last, split)) {
      split++;
    }
    int middle = from + 1;
    while (ByteTrieOps.bitAt(leaves.get(middle).keyBytes(), split) == 0) {
      middle++;
    }
    return new BranchNode(
        ByteTrieOps.expandBits(first, depth, split),
        split - depth,
        stemSubtree(leaves, from, middle, split + 1),
        stemSubtree(leaves, middle, to, split + 1),
        true);
  }
}
