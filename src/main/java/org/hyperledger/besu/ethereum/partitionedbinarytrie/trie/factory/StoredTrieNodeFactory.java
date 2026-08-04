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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.codec.TrieNodeCodec;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.BranchNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.LeafNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.StoredTrieNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.TrieNode;
import org.hyperledger.besu.ethereum.trie.NodeLoader;

import java.util.Arrays;

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

  public TrieNode retrieve(final Bytes location) {
    return retrieve(location, null);
  }

  public TrieNode retrieve(final Bytes location, final Bytes32 hash) {
    return nodeLoader
        .getNode(location, hash)
        .map(bytes -> decode(location, bytes))
        .orElseGet(TrieNode::empty);
  }

  public TrieNode wrapStored(final Bytes location, final Bytes32 hash) {
    return new StoredTrieNode(this, location, hash);
  }

  TrieNode decode(final Bytes location, final Bytes encoded) {
    if (encoded.isEmpty()) {
      return TrieNode.empty();
    }
    final byte[] raw = encoded.toArrayUnsafe();
    final int tag = raw[0] & 0xFF;
    if (tag == TrieNodeCodec.LEAF_TAG) {
      // Wire layout: [tag | keyLen (4) | key (keyLen) | value (32)]
      final int keyLen = readInt(raw, 1);
      final byte[] key = Arrays.copyOfRange(raw, 5, 5 + keyLen);
      final byte[] value = Arrays.copyOfRange(raw, 5 + keyLen, 5 + keyLen + 32);
      return new LeafNode(key, keyLen, value, true);
    }
    if (tag == TrieNodeCodec.BRANCH_TAG) {
      // Wire layout (inverse of TrieNodeCodec.encodeBranch):
      // [tag | prefixLen (4) | packed prefix | leftHash (32) | rightHash (32)]
      //  0       1..4           5..cursor-1      cursor       cursor+32
      // prefixLen: prefix length in bits (shared path before the left/right split).
      final int prefixLen = readInt(raw, 1);
      // packedLen: on-disk size of those bits (ceil(prefixLen / 8) bytes, MSB-first).
      final int packedLen = (prefixLen + 7) / 8;
      final byte[] prefixBits = TrieNodeCodec.unpackPrefix(raw, 5, prefixLen);

      final int cursor = 5 + packedLen;
      final Bytes32 leftHash = Bytes32.wrap(Arrays.copyOfRange(raw, cursor, cursor + 32));
      final Bytes32 rightHash = Bytes32.wrap(Arrays.copyOfRange(raw, cursor + 32, cursor + 64));
      // Child paths: current location + prefix bits + split bit (0=left, 1=right).
      final Bytes leftLoc = TrieNodeCodec.childLocation(location, prefixBits, prefixLen, 0);
      final Bytes rightLoc = TrieNodeCodec.childLocation(location, prefixBits, prefixLen, 1);
      // StoredTrieNode stubs: hash + location only; child body loaded on demand.
      return new BranchNode(
          prefixBits,
          prefixLen,
          new StoredTrieNode(this, leftLoc, leftHash),
          new StoredTrieNode(this, rightLoc, rightHash),
          true);
    }
    throw new IllegalArgumentException("Unknown node tag: " + tag);
  }

  private static int readInt(final byte[] raw, final int offset) {
    return ((raw[offset] & 0xFF) << 24)
        | ((raw[offset + 1] & 0xFF) << 16)
        | ((raw[offset + 2] & 0xFF) << 8)
        | (raw[offset + 3] & 0xFF);
  }
}
