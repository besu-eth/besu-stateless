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
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.visitor.LocationNodeVisitor;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.visitor.PathNodeVisitor;

import org.apache.tuweni.bytes.Bytes;

/** Singleton empty trie node with a 32 zero-byte root hash. */
public final class EmptyTrieNode extends TrieNode {

  private static final EmptyTrieNode INSTANCE = new EmptyTrieNode();

  public static EmptyTrieNode instance() {
    return INSTANCE;
  }

  private EmptyTrieNode() {
    super(true);
  }

  @Override
  public byte[] merkleHashBytes() {
    return new byte[32];
  }

  @Override
  public Bytes encode() {
    return Bytes.EMPTY;
  }

  @Override
  public TrieNode accept(final PathNodeVisitor visitor, final TrieKey key, final int depth) {
    return visitor.visit(this, key, depth);
  }

  @Override
  public void accept(final Bytes location, final LocationNodeVisitor visitor) {
    visitor.visit(location, this);
  }
}
