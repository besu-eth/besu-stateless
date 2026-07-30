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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.proof;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.internal.TrieConstants;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.codec.StoredNodeCodec;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.node.BranchNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.node.LeafNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.node.TrieNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Verifies partitioned binary trie proofs against an expected root hash.
 *
 * <p>Proof nodes are canonical encoded trie nodes ({@link StoredNodeCodec}) indexed by BLAKE3
 * merkle hash, matching the proof format produced by {@link
 * org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.visitor.ProofVisitor}.
 */
public final class TrieNodeProofVerifier {

  private TrieNodeProofVerifier() {}

  /**
   * Verifies that proof nodes reconstruct to {@code expectedRoot}.
   *
   * @param expectedRoot trie root hash to validate
   * @param proofNodes ordered encoded proof nodes
   * @return {@code true} when the proof nodes hash to the expected root
   */
  public static boolean verifyRoot(final Bytes32 expectedRoot, final List<Bytes> proofNodes) {
    if (expectedRoot.equals(TrieConstants.EMPTY_TRIE_ROOT)) {
      return proofNodes.isEmpty();
    }
    final Map<Bytes32, Bytes> nodesByHash = indexProofNodes(proofNodes);
    final Bytes rootEncoded = nodesByHash.get(expectedRoot);
    if (rootEncoded == null) {
      return false;
    }
    final TrieNode root = decodeFromProof(Bytes.EMPTY, rootEncoded, nodesByHash);
    return Bytes32.wrap(root.merkleHashBytes()).equals(expectedRoot);
  }

  /**
   * Verifies a proof and returns the value at {@code key} when the root matches.
   *
   * @param expectedRoot trie root hash to validate
   * @param key lookup key bytes
   * @param keyLen valid key length
   * @param proofNodes ordered encoded proof nodes
   * @return outer empty when the proof is invalid; otherwise inner optional for the key value
   */
  public static Optional<Optional<byte[]>> verifyAndGetValue(
      final Bytes32 expectedRoot,
      final byte[] key,
      final int keyLen,
      final List<Bytes> proofNodes) {
    if (!verifyRoot(expectedRoot, proofNodes)) {
      return Optional.empty();
    }
    if (expectedRoot.equals(TrieConstants.EMPTY_TRIE_ROOT)) {
      return Optional.of(Optional.empty());
    }
    final Map<Bytes32, Bytes> nodesByHash = indexProofNodes(proofNodes);
    final TrieNode root = decodeFromProof(Bytes.EMPTY, nodesByHash.get(expectedRoot), nodesByHash);
    return Optional.of(root.get(key, keyLen, 0));
  }

  private static Map<Bytes32, Bytes> indexProofNodes(final List<Bytes> proofNodes) {
    final Map<Bytes32, Bytes> nodesByHash = new HashMap<>();
    for (final Bytes encoded : proofNodes) {
      if (encoded.isEmpty()) {
        continue;
      }
      final TrieNode node = decodeLeafOrBranchOnly(encoded);
      nodesByHash.put(Bytes32.wrap(node.merkleHashBytes()), encoded);
    }
    return nodesByHash;
  }

  private static TrieNode decodeFromProof(
      final Bytes location, final Bytes encoded, final Map<Bytes32, Bytes> nodesByHash) {
    if (encoded.isEmpty()) {
      return TrieNode.empty();
    }
    final int tag = encoded.get(0) & 0xFF;
    if (tag == StoredNodeCodec.LEAF_TAG) {
      return decodeLeafOrBranchOnly(encoded);
    }
    if (tag == StoredNodeCodec.BRANCH_TAG) {
      final int prefixLen = encoded.getInt(1);
      final int packedLen = (prefixLen + 7) / 8;
      final int cursor = 5 + packedLen;
      final byte[] prefixBits =
          StoredNodeCodec.unpackPrefix(encoded.slice(5, packedLen), prefixLen);
      final Bytes32 leftHash = Bytes32.wrap(encoded.slice(cursor, 32).toArrayUnsafe());
      final Bytes32 rightHash = Bytes32.wrap(encoded.slice(cursor + 32, 32).toArrayUnsafe());
      final Bytes leftLoc = StoredNodeCodec.childLocation(location, prefixBits, prefixLen, 0);
      final Bytes rightLoc = StoredNodeCodec.childLocation(location, prefixBits, prefixLen, 1);
      return new BranchNode(
          prefixBits,
          prefixLen,
          resolveChild(leftLoc, leftHash, nodesByHash),
          resolveChild(rightLoc, rightHash, nodesByHash),
          true);
    }
    throw new IllegalArgumentException("Unknown node tag: " + tag);
  }

  private static TrieNode resolveChild(
      final Bytes location, final Bytes32 hash, final Map<Bytes32, Bytes> nodesByHash) {
    if (hash.equals(TrieConstants.EMPTY_TRIE_ROOT)) {
      return TrieNode.empty();
    }
    final Bytes encoded = nodesByHash.get(hash);
    if (encoded == null) {
      return new ProofReferenceNode(hash);
    }
    return decodeFromProof(location, encoded, nodesByHash);
  }

  private static TrieNode decodeLeafOrBranchOnly(final Bytes encoded) {
    final int tag = encoded.get(0) & 0xFF;
    if (tag == StoredNodeCodec.LEAF_TAG) {
      final int keyLen = encoded.getInt(1);
      final byte[] key = encoded.slice(5, keyLen).toArrayUnsafe();
      final byte[] value = encoded.slice(5 + keyLen, 32).toArrayUnsafe();
      return new LeafNode(key, keyLen, value, true);
    }
    if (tag == StoredNodeCodec.BRANCH_TAG) {
      final int prefixLen = encoded.getInt(1);
      final int packedLen = (prefixLen + 7) / 8;
      final int cursor = 5 + packedLen;
      final byte[] prefixBits =
          StoredNodeCodec.unpackPrefix(encoded.slice(5, packedLen), prefixLen);
      final Bytes32 leftHash = Bytes32.wrap(encoded.slice(cursor, 32).toArrayUnsafe());
      final Bytes32 rightHash = Bytes32.wrap(encoded.slice(cursor + 32, 32).toArrayUnsafe());
      return new BranchNode(
          prefixBits,
          prefixLen,
          new ProofReferenceNode(leftHash),
          new ProofReferenceNode(rightHash),
          true);
    }
    throw new IllegalArgumentException("Unknown node tag: " + tag);
  }

  /** Placeholder child that exposes only the merkle hash from a proof reference. */
  private static final class ProofReferenceNode extends TrieNode {

    private final Bytes32 hash;

    private ProofReferenceNode(final Bytes32 hash) {
      super(true);
      this.hash = hash;
    }

    @Override
    public byte[] merkleHashBytes() {
      return hash.toArrayUnsafe();
    }

    @Override
    public Bytes encode() {
      throw new UnsupportedOperationException("Proof reference node is not encodable");
    }

    @Override
    public TrieNode accept(
        final org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.visitor.PathNodeVisitor
            visitor,
        final byte[] key,
        final int keyLen,
        final int depth) {
      throw new UnsupportedOperationException("Proof reference node cannot be traversed");
    }

    @Override
    public void accept(
        final Bytes location,
        final org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.visitor.LocationNodeVisitor
            visitor) {
      throw new UnsupportedOperationException("Proof reference node cannot be traversed");
    }
  }
}
