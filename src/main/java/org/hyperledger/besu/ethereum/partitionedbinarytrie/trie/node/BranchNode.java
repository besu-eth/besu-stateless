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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.codec.TrieNodeCodec;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.internal.bytes.ByteTrieOps;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieKey;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.visitor.LocationNodeVisitor;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.visitor.PathNodeVisitor;

import org.apache.tuweni.bytes.Bytes;

/**
 * Internal branch node in the partitioned binary trie.
 *
 * <p>Structure:
 *
 * <pre>
 *   prefix[0 .. prefixLen)   shared key bits before the split, packed MSB-first
 *          |
 *          +-- bit 0 --> left child
 *          +-- bit 1 --> right child
 * </pre>
 *
 * <p>Callers pass a {@link TrieKey} whose bits ({@link TrieKey#bitAt}) are matched starting at
 * {@code depth}, then descend using {@link TrieKey#bitAt(int)} at {@code depth + prefixLen}.
 *
 * <p>On disk ({@link TrieNodeCodec#encodeBranch}), only {@code prefixLen} (in bits) and the packed
 * prefix are stored with left/right child hashes, and only above the stems. Children may be {@link
 * StoredTrieNode} stubs loaded lazily from storage.
 *
 * <p>Merkle hash: BLAKE3 branch tag over the packed prefix and both child hashes (EIP-8297).
 */
public final class BranchNode extends TrieNode {

  /** Prefix bits packed MSB-first, zero-padded to whole bytes. */
  private final byte[] prefix;

  /** Number of prefix bits (not bytes); see {@link #prefixLength()}. */
  private final int prefixLen;

  private TrieNode left;
  private TrieNode right;

  /**
   * Cached merkle hash, valid until a child changes. Independent of {@link #isClean()}, which only
   * tracks persistence: a dirty node keeps its hash across calls until it is mutated again.
   */
  private byte[] hash;

  /**
   * @param prefix prefix bits packed MSB-first, zero-padded to whole bytes
   * @param prefixLen number of prefix bits
   * @param left child reached when the split bit is {@code 0}
   * @param right child reached when the split bit is {@code 1}
   * @param clean {@code true} if loaded from storage and not yet modified
   */
  public BranchNode(
      final byte[] prefix,
      final int prefixLen,
      final TrieNode left,
      final TrieNode right,
      final boolean clean) {
    super(clean);
    this.prefix = prefix;
    this.prefixLen = prefixLen;
    this.left = left;
    this.right = right;
  }

  /**
   * Replaces a child after remove and optionally collapses when that child becomes empty.
   *
   * <p>Mirrors Besu {@link org.hyperledger.besu.ethereum.trie.patricia.BranchNode#replaceChild}.
   */
  public TrieNode replaceChild(
      final boolean goRight, final TrieNode updatedChild, final boolean allowFlatten) {
    if (goRight) {
      right = updatedChild;
    } else {
      left = updatedChild;
    }
    hash = null;

    if (updatedChild == TrieNode.empty() && allowFlatten) {
      final TrieNode survivor = goRight ? left : right;
      if (survivor == TrieNode.empty()) {
        // Both children are empty, so the branch itself disappears.
        return TrieNode.empty();
      }
      // One child remains. Merge this branch's prefix, the split bit, and the survivor branch's
      // prefix when possible so the trie stays canonical.
      return maybeFlatten(survivor, goRight ? (byte) 0 : (byte) 1);
    }

    markDirty();
    return this;
  }

  TrieNode maybeFlatten(final TrieNode survivor, final byte splitBit) {
    final TrieNode loaded =
        survivor instanceof StoredTrieNode ? ((StoredTrieNode) survivor).load() : survivor;
    if (loaded instanceof BranchNode branch) {
      // Branch below branch with no sibling payload: concatenate compressed prefixes through the
      // split bit and reuse the survivor's two children.
      final int mergedLen = prefixLen + 1 + branch.prefixLen;
      final byte[] merged =
          ByteTrieOps.concatBits(prefix, prefixLen, splitBit, branch.prefix, branch.prefixLen);
      return new BranchNode(merged, mergedLen, branch.leftChild(), branch.rightChild(), false);
    }
    // Leaf (or empty) survivor: it is hoisted to a shallower location without changing its own
    // content. Storage is location-keyed, so it must still be marked dirty here or the commit
    // walk will skip re-persisting it at its new (shorter) location and leave the stale node
    // from its old location reachable after reload.
    loaded.markDirty();
    return loaded;
  }

  @Override
  public void markDirty() {
    super.markDirty();
    hash = null;
  }

  @Override
  public byte[] merkleHashBytes() {
    if (hash == null) {
      hash =
          ByteTrieOps.branchHash(
              prefix, prefixLen, left.merkleHashBytes(), right.merkleHashBytes());
    }
    return hash;
  }

  @Override
  public Bytes encode() {
    return TrieNodeCodec.encodeBranch(
        prefix, prefixLen, left.merkleHashBytes(), right.merkleHashBytes());
  }

  @Override
  public TrieNode accept(final PathNodeVisitor visitor, final TrieKey key, final int depth) {
    return visitor.visit(this, key, depth);
  }

  @Override
  public void accept(final Bytes location, final LocationNodeVisitor visitor) {
    visitor.visit(location, this);
  }

  /** Prefix bits packed MSB-first, zero-padded to whole bytes. */
  public byte[] prefix() {
    return prefix;
  }

  /** Bit {@code index} of the prefix ({@code 0} or {@code 1}). */
  public byte prefixBit(final int index) {
    return ByteTrieOps.bitAt(prefix, index);
  }

  public int prefixLength() {
    return prefixLen;
  }

  public TrieNode leftChild() {
    return left;
  }

  public TrieNode rightChild() {
    return right;
  }

  public void setLeftChild(final TrieNode left) {
    this.left = left;
    hash = null;
  }

  public void setRightChild(final TrieNode right) {
    this.right = right;
    hash = null;
  }
}
