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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.proof;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.codec.TrieNodeCodec;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.internal.bytes.ByteTrieOps;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieConstants;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/** Verifies partitioned binary trie proofs by following the key from the root hash. */
public final class TrieNodeProofVerifier {

  private TrieNodeProofVerifier() {}

  /**
   * Follows {@code key} from {@code root} through {@code proofNodes}, each of which must hash to
   * what its parent holds.
   *
   * @param root trie root hash
   * @param key lookup key
   * @param proofNodes encoded proof nodes
   * @return the value of the key, or empty when the proof shows it absent
   * @throws IllegalArgumentException when the proof lacks a node of the path
   */
  public static Optional<Bytes32> verify(
      final Bytes32 root, final Bytes key, final List<Bytes> proofNodes) {
    final Map<Bytes32, byte[]> nodesByHash = new HashMap<>();
    for (final Bytes node : proofNodes) {
      nodesByHash.put(hash(node.toArrayUnsafe()), node.toArrayUnsafe());
    }
    final byte[] keyBytes = key.toArrayUnsafe();
    final int keyBits = keyBytes.length * Byte.SIZE;
    Bytes32 hash = root;
    int depth = 0;
    while (!hash.equals(TrieConstants.EMPTY_TRIE_ROOT)) {
      final byte[] node = nodesByHash.get(hash);
      if (node == null) {
        throw new IllegalArgumentException("proof lacks node " + hash);
      }
      if (node[0] == TrieNodeCodec.LEAF_TAG) {
        final int valueOffset = node.length - TrieConstants.VALUE_LENGTH;
        return Arrays.equals(node, 1, valueOffset, keyBytes, 0, keyBytes.length)
            ? Optional.of(Bytes32.wrap(node, valueOffset))
            : Optional.empty();
      }
      final byte[] prefix = TrieNodeCodec.branchPrefix(node);
      final int split = depth + TrieNodeCodec.branchPrefixLength(node);
      if (split >= keyBits) {
        return Optional.empty();
      }
      for (int i = depth; i < split; i++) {
        if (ByteTrieOps.bitAt(keyBytes, i) != ByteTrieOps.bitAt(prefix, i - depth)) {
          return Optional.empty();
        }
      }
      final int child = node.length - (2 - ByteTrieOps.bitAt(keyBytes, split)) * Bytes32.SIZE;
      hash = Bytes32.wrap(node, child);
      depth = split + 1;
    }
    return Optional.empty();
  }

  private static Bytes32 hash(final byte[] node) {
    if (node[0] == TrieNodeCodec.LEAF_TAG) {
      final int valueOffset = node.length - TrieConstants.VALUE_LENGTH;
      return Bytes32.wrap(
          ByteTrieOps.leafHash(
              Arrays.copyOfRange(node, 1, valueOffset),
              valueOffset - 1,
              Arrays.copyOfRange(node, valueOffset, node.length)));
    }
    if (node[0] != TrieNodeCodec.BRANCH_TAG) {
      throw new IllegalArgumentException("unknown node tag " + node[0]);
    }
    return Bytes32.wrap(
        ByteTrieOps.branchHash(
            TrieNodeCodec.branchPrefix(node),
            TrieNodeCodec.branchPrefixLength(node),
            Arrays.copyOfRange(node, node.length - 2 * Bytes32.SIZE, node.length - Bytes32.SIZE),
            Arrays.copyOfRange(node, node.length - Bytes32.SIZE, node.length)));
  }
}
