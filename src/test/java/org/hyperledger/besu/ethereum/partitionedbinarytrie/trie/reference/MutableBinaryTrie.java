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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.reference;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.internal.TrieConstants;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.hash.BitUtils;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.hash.TrieHasher;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.BinaryNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.BranchNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.LeafNode;

import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Incremental compressed binary radix trie with insertion-based updates.
 *
 * <p>Produces the same canonical structure and root hash as {@link BinaryTrie} but maintains the
 * node tree across operations for efficient client use. Used in conformance tests today; kept as
 * the spec-reference incremental API alongside {@link BinaryTrie}.
 */
public final class MutableBinaryTrie {

  private BinaryNode root;

  /** Creates an empty trie. */
  public MutableBinaryTrie() {
    this.root = null;
  }

  /**
   * Looks up a value by key.
   *
   * @param key variable-length key
   * @return the 32-byte value, or empty if absent
   */
  public Optional<Bytes32> get(final Bytes key) {
    if (root == null) {
      return Optional.empty();
    }
    return getNode(root, key, 0);
  }

  /**
   * Inserts or replaces a key-value pair.
   *
   * @param key variable-length key (1–{@link TrieConstants#MAX_KEY_LENGTH} bytes)
   * @param value 32-byte value
   */
  public void put(final Bytes key, final Bytes32 value) {
    if (key.isEmpty()) {
      throw new IllegalArgumentException("Key must not be empty");
    }
    if (key.size() > TrieConstants.MAX_KEY_LENGTH) {
      throw new IllegalArgumentException("Key exceeds maximum length");
    }
    if (value.size() != TrieConstants.VALUE_LENGTH) {
      throw new IllegalArgumentException("Value must be 32 bytes");
    }
    if (root == null) {
      root = new LeafNode(key, value);
      return;
    }
    root = insert(root, key, value, 0);
  }

  /**
   * Removes a key if present.
   *
   * <p>Absent keys produce no trie nodes (non-sparse trie). This is distinct from storing {@link
   * Bytes32#ZERO}.
   *
   * @param key key to remove
   */
  public void remove(final Bytes key) {
    if (root == null) {
      return;
    }
    root = removeNode(root, key, 0);
    if (root == null) {
      return;
    }
  }

  /**
   * Returns the BLAKE3 merkle root of the current trie.
   *
   * @return root hash, or {@link TrieConstants#EMPTY_TRIE_ROOT} when empty
   */
  public Bytes32 root() {
    if (root == null) {
      return TrieConstants.EMPTY_TRIE_ROOT;
    }
    return TrieHasher.merkleize(root);
  }

  /** Returns {@code true} if the trie contains no entries. */
  public boolean isEmpty() {
    return root == null;
  }

  private static Optional<Bytes32> getNode(
      final BinaryNode node, final Bytes key, final int depth) {
    return switch (node) {
      case final LeafNode leaf -> leaf.key().equals(key)
          ? Optional.of(leaf.value())
          : Optional.empty();
      case final BranchNode branch -> {
        final Bytes bits = BitUtils.bytesToBitList(key);
        final int prefixSize = branch.prefix().size();
        for (int i = 0; i < prefixSize; i++) {
          if (depth + i >= bits.size()
              || BitUtils.bitAt(bits, depth + i) != BitUtils.bitAt(branch.prefix(), i)) {
            yield Optional.empty();
          }
        }
        final int split = depth + prefixSize;
        if (split >= bits.size()) {
          yield Optional.empty();
        }
        yield BitUtils.bitAt(bits, split) == 0
            ? getNode(branch.left(), key, split + 1)
            : getNode(branch.right(), key, split + 1);
      }
    };
  }

  private static BinaryNode insert(
      final BinaryNode node, final Bytes key, final Bytes32 value, final int depth) {
    return switch (node) {
      case final LeafNode leaf -> {
        if (leaf.key().equals(key)) {
          yield new LeafNode(key, value);
        }
        final Bytes bits = BitUtils.bytesToBitList(key);
        final Bytes otherBits = BitUtils.bytesToBitList(leaf.key());
        // Find the longest shared bit prefix, then branch on the first differing bit.
        int run = 0;
        while (true) {
          final int position = depth + run;
          if (position >= bits.size() || position >= otherBits.size()) {
            throw new IllegalArgumentException("Key is a prefix of another key");
          }
          if (BitUtils.bitAt(bits, position) != BitUtils.bitAt(otherBits, position)) {
            break;
          }
          run++;
        }
        final Bytes prefix = BitUtils.sliceBits(bits, depth, depth + run);
        final LeafNode newLeaf = new LeafNode(key, value);
        if (BitUtils.bitAt(bits, depth + run) == 0) {
          yield new BranchNode(prefix, newLeaf, leaf);
        }
        yield new BranchNode(prefix, leaf, newLeaf);
      }
      case final BranchNode branch -> {
        final Bytes bits = BitUtils.bytesToBitList(key);
        int matched = 0;
        while (matched < branch.prefix().size()) {
          final int position = depth + matched;
          if (position >= bits.size()
              || BitUtils.bitAt(bits, position) != BitUtils.bitAt(branch.prefix(), matched)) {
            break;
          }
          matched++;
        }
        if (matched == branch.prefix().size()) {
          final int split = depth + matched;
          if (split >= bits.size()) {
            throw new IllegalArgumentException("Key is a prefix of another key");
          }
          if (BitUtils.bitAt(bits, split) == 0) {
            yield new BranchNode(
                branch.prefix(), insert(branch.left(), key, value, split + 1), branch.right());
          }
          yield new BranchNode(
              branch.prefix(), branch.left(), insert(branch.right(), key, value, split + 1));
        }
        // Prefix mismatch: split this branch and insert the new leaf alongside a survivor subtree.
        final BranchNode survivor =
            new BranchNode(
                BitUtils.sliceBits(branch.prefix(), matched + 1, branch.prefix().size()),
                branch.left(),
                branch.right());
        final LeafNode newLeaf = new LeafNode(key, value);
        if (BitUtils.bitAt(bits, depth + matched) == 0) {
          yield new BranchNode(BitUtils.sliceBits(branch.prefix(), 0, matched), newLeaf, survivor);
        }
        yield new BranchNode(BitUtils.sliceBits(branch.prefix(), 0, matched), survivor, newLeaf);
      }
    };
  }

  private static BinaryNode removeNode(final BinaryNode node, final Bytes key, final int depth) {
    return switch (node) {
      case final LeafNode leaf -> leaf.key().equals(key) ? null : leaf;
      case final BranchNode branch -> {
        final Bytes bits = BitUtils.bytesToBitList(key);
        for (int i = 0; i < branch.prefix().size(); i++) {
          if (depth + i >= bits.size()
              || BitUtils.bitAt(bits, depth + i) != BitUtils.bitAt(branch.prefix(), i)) {
            yield branch;
          }
        }
        final int split = depth + branch.prefix().size();
        if (split >= bits.size()) {
          yield branch;
        }
        if (BitUtils.bitAt(bits, split) == 0) {
          final BinaryNode updatedLeft = removeNode(branch.left(), key, split + 1);
          if (updatedLeft == null) {
            yield branch.right();
          }
          yield new BranchNode(branch.prefix(), updatedLeft, branch.right());
        }
        final BinaryNode updatedRight = removeNode(branch.right(), key, split + 1);
        if (updatedRight == null) {
          yield branch.left();
        }
        yield new BranchNode(branch.prefix(), branch.left(), updatedRight);
      }
    };
  }
}
