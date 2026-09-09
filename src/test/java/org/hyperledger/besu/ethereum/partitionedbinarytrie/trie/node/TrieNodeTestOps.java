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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieKey;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.visitor.GetVisitor;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.visitor.PathNodeVisitor;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.visitor.PutVisitor;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.visitor.RemoveVisitor;

import java.util.Optional;

/** Test helpers that drive trie nodes through path visitors (Besu-style). */
final class TrieNodeTestOps {

  private TrieNodeTestOps() {}

  static Optional<byte[]> get(final TrieNode node, final byte[] key, final int keyLen) {
    return node.accept(new GetVisitor(), TrieKey.of(key, keyLen), 0).leafValue();
  }

  static TrieNode put(final TrieNode node, final byte[] key, final int keyLen, final byte[] value) {
    return node.accept(new PutVisitor(value), TrieKey.of(key, keyLen), 0);
  }

  static TrieNode remove(final TrieNode node, final byte[] key, final int keyLen) {
    return node.accept(new RemoveVisitor(), TrieKey.of(key, keyLen), 0);
  }

  static TrieNode accept(
      final TrieNode node,
      final PathNodeVisitor visitor,
      final byte[] key,
      final int keyLen,
      final int depth) {
    return node.accept(visitor, TrieKey.of(key, keyLen), depth);
  }
}
