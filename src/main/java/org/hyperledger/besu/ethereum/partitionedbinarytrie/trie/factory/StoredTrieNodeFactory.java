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
    final int tag = encoded.get(0) & 0xFF;
    if (tag == TrieNodeCodec.LEAF_TAG) {
      final int keyLen = encoded.getInt(1);
      final byte[] key = encoded.slice(5, keyLen).toArrayUnsafe();
      final byte[] value = encoded.slice(5 + keyLen, 32).toArrayUnsafe();
      return new LeafNode(key, keyLen, value, true);
    }
    if (tag == TrieNodeCodec.BRANCH_TAG) {
      final int prefixLen = encoded.getInt(1);
      final int packedLen = (prefixLen + 7) / 8;
      final int cursor = 5 + packedLen;
      final byte[] prefixBits = TrieNodeCodec.unpackPrefix(encoded.slice(5, packedLen), prefixLen);
      final Bytes32 leftHash = Bytes32.wrap(encoded.slice(cursor, 32).toArrayUnsafe());
      final Bytes32 rightHash = Bytes32.wrap(encoded.slice(cursor + 32, 32).toArrayUnsafe());
      final Bytes leftLoc = TrieNodeCodec.childLocation(location, prefixBits, prefixLen, 0);
      final Bytes rightLoc = TrieNodeCodec.childLocation(location, prefixBits, prefixLen, 1);
      return new BranchNode(
          prefixBits,
          prefixLen,
          new StoredTrieNode(this, leftLoc, leftHash),
          new StoredTrieNode(this, rightLoc, rightHash),
          true);
    }
    throw new IllegalArgumentException("Unknown node tag: " + tag);
  }
}
