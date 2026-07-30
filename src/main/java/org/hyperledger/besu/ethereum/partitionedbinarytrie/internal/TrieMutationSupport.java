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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.internal;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.internal.bytes.ByteTrieOps;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.node.BranchNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.node.LeafNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.node.TrieNode;

import java.util.Arrays;

/**
 * Shared helpers for partitioned-binary-trie mutation visitors.
 *
 * <p>The visitors own the high-level operation semantics (direct put vs deferred merge), while this
 * class owns the mechanical trie operations: finding divergence bits, preserving compressed
 * prefixes, and rejecting keys that would violate the trie prefix-free invariant.
 */
public final class TrieMutationSupport {

  public static final String PREFIX_FREE_VIOLATION = "Key is a prefix of another key";

  private TrieMutationSupport() {}

  /**
   * Splits an existing leaf into a branch containing that leaf and a new leaf.
   *
   * <p>The branch prefix is exactly the shared bit run from {@code depth} until the first
   * divergence. The bit at the divergence chooses the left/right child position, so the returned
   * subtree is canonical immediately.
   */
  public static TrieNode branchFromDivergingLeaves(
      final LeafNode existingLeaf,
      final byte[] key,
      final int keyLen,
      final byte[] value,
      final int depth) {
    final byte[] keyBits = ByteTrieOps.expandKeyBitsCopy(key, keyLen);
    final byte[] existingBits =
        ByteTrieOps.expandKeyBits(existingLeaf.keyBytes(), existingLeaf.keyLength());
    final int sharedPrefixLen =
        sharedPrefixLength(keyBits, keyLen * 8, existingBits, existingLeaf.keyLength() * 8, depth);
    final byte[] sharedPrefix = Arrays.copyOfRange(keyBits, depth, depth + sharedPrefixLen);
    final TrieNode newLeaf = new LeafNode(key, keyLen, value, false);
    final TrieNode oldLeaf =
        new LeafNode(
            existingLeaf.keyBytes(), existingLeaf.keyLength(), existingLeaf.valueBytes(), false);

    if (keyBits[depth + sharedPrefixLen] == 0) {
      return new BranchNode(sharedPrefix, sharedPrefixLen, newLeaf, oldLeaf, false);
    }
    return new BranchNode(sharedPrefix, sharedPrefixLen, oldLeaf, newLeaf, false);
  }

  /**
   * Splits an existing compressed branch because the inserted key diverges inside its prefix.
   *
   * <p>The old branch survives below the new split with its consumed prefix removed. The new leaf
   * is placed on the side selected by the inserted key's divergence bit.
   */
  public static TrieNode splitBranchWithLeaf(
      final BranchNode branchNode,
      final byte[] key,
      final int keyLen,
      final byte[] value,
      final byte[] keyBits,
      final int keyBitCount,
      final int depth,
      final int matchedPrefixLen) {
    ensureHasSplitBit(depth + matchedPrefixLen, keyBitCount);

    final byte[] prefixBits = branchNode.prefixBits();
    final int prefixLen = branchNode.prefixLength();
    final TrieNode survivor =
        new BranchNode(
            Arrays.copyOfRange(prefixBits, matchedPrefixLen + 1, prefixLen),
            prefixLen - matchedPrefixLen - 1,
            branchNode.leftChild(),
            branchNode.rightChild(),
            false);
    final TrieNode leaf = new LeafNode(key, keyLen, value, false);

    if (keyBits[depth + matchedPrefixLen] == 0) {
      return new BranchNode(
          Arrays.copyOf(prefixBits, matchedPrefixLen), matchedPrefixLen, leaf, survivor, false);
    }
    return new BranchNode(
        Arrays.copyOf(prefixBits, matchedPrefixLen), matchedPrefixLen, survivor, leaf, false);
  }

  /**
   * Counts how many bits of {@code prefixBits} match {@code keyBits} at {@code depth}.
   *
   * <p>The result may be smaller than {@code prefixLen} for an in-prefix divergence, or exactly
   * {@code prefixLen} when the caller can safely look at the following split bit.
   */
  public static int matchingPrefixLength(
      final byte[] keyBits,
      final int keyBitCount,
      final byte[] prefixBits,
      final int prefixLen,
      final int depth) {
    int matched = 0;
    while (matched < prefixLen
        && depth + matched < keyBitCount
        && keyBits[depth + matched] == prefixBits[matched]) {
      matched++;
    }
    return matched;
  }

  /**
   * Ensures a key has a bit available at the branch split depth.
   *
   * <p>If not, the key would terminate where an existing branch still needs to split, making it a
   * prefix of another key. This trie deliberately rejects such key sets.
   */
  public static void ensureHasSplitBit(final int splitDepth, final int keyBitCount) {
    if (splitDepth >= keyBitCount) {
      throw new IllegalArgumentException(PREFIX_FREE_VIOLATION);
    }
  }

  /** Validates the fixed 32-byte leaf value required by the spec. */
  public static void validateValue(final byte[] value) {
    if (value.length != TrieConstants.VALUE_LENGTH) {
      throw new IllegalArgumentException("Value must be 32 bytes");
    }
  }

  private static int sharedPrefixLength(
      final byte[] keyBits,
      final int keyBitCount,
      final byte[] otherBits,
      final int otherBitCount,
      final int depth) {
    int shared = 0;
    while (true) {
      final int position = depth + shared;
      if (position >= keyBitCount || position >= otherBitCount) {
        throw new IllegalArgumentException(PREFIX_FREE_VIOLATION);
      }
      if (keyBits[position] != otherBits[position]) {
        return shared;
      }
      shared++;
    }
  }
}
