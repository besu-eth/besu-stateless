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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.node;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.internal.bytes.ByteTrieOps;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.codec.StoredNodeCodec;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.visitor.LocationNodeVisitor;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.visitor.PathNodeVisitor;

import java.util.Arrays;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;

/** In-memory leaf node holding a single key-value pair. */
public final class LeafNode extends TrieNode {

  private final byte[] key;
  private final int keyLen;
  private final byte[] value;
  private byte[] hash;

  public LeafNode(final byte[] key, final int keyLen, final byte[] value, final boolean clean) {
    super(clean);
    this.key = Arrays.copyOf(key, keyLen);
    this.keyLen = keyLen;
    this.value = Arrays.copyOf(value, 32);
  }

  @Override
  public Optional<byte[]> leafValue() {
    return Optional.of(value);
  }

  @Override
  public byte[] merkleHashBytes() {
    if (hash == null) {
      hash = ByteTrieOps.leafHash(key, keyLen, value);
    }
    return hash;
  }

  @Override
  public Bytes encode() {
    return StoredNodeCodec.encodeLeaf(key, keyLen, value);
  }

  @Override
  public TrieNode accept(
      final PathNodeVisitor visitor, final byte[] lookupKey, final int lookupLen, final int depth) {
    return visitor.visit(this, lookupKey, lookupLen, depth);
  }

  @Override
  public void accept(final Bytes location, final LocationNodeVisitor visitor) {
    visitor.visit(location, this);
  }

  public byte[] keyBytes() {
    return key;
  }

  public int keyLength() {
    return keyLen;
  }

  public byte[] valueBytes() {
    return value;
  }
}
