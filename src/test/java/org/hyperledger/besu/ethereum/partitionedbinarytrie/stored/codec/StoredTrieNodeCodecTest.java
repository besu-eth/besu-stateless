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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.codec;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.internal.bytes.ByteTrieOps;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.factory.NodeLoaderMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.factory.NodeUpdaterMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.factory.StoredTrieNodeFactory;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.node.MemoryLeafNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.node.TrieNode;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;

/**
 * Round-trip encoding and decoding of persisted trie nodes ({@link TrieNodeCodec}).
 *
 * <p>Layer: stored codec. Distinct from BLAKE3 hash preimages; validates leaf/branch wire format,
 * child locations, and prefix bit unpacking against {@link ByteTrieOps} merkle hashes.
 */
class StoredTrieNodeCodecTest {

  @Test
  void leafEncodeDecodeRoundTrip() {
    final byte[] key = Bytes.fromHexString("0x01020304").toArrayUnsafe();
    final byte[] value = Bytes32.repeat((byte) 0x42).toArrayUnsafe();
    final Bytes encoded = TrieNodeCodec.encodeLeaf(key, 4, value);

    assertThat(encoded.get(0)).isEqualTo(TrieNodeCodec.LEAF_TAG);
    assertThat(encoded.size()).isEqualTo(1 + 5 + 4 + 32);

    final NodeUpdaterMock updater = new NodeUpdaterMock();
    final StoredTrieNodeFactory factory = new StoredTrieNodeFactory(new NodeLoaderMock(updater));
    final MemoryLeafNode leaf = new MemoryLeafNode(key, 4, value, false);
    leaf.commit(Bytes.EMPTY, updater);
    final TrieNode decoded = factory.retrieve(Bytes.EMPTY, Bytes32.wrap(leaf.merkleHashBytes()));
    assertThat(decoded.get(key, 4, 0)).contains(value);
    assertThat(decoded.merkleHashBytes()).isEqualTo(ByteTrieOps.leafHash(key, 4, value));
  }

  @Test
  void branchEncodeDecodeRoundTrip() {
    final byte[] keyA = Bytes.fromHexString("0xaaaa").toArrayUnsafe();
    final byte[] keyB = Bytes.fromHexString("0xbbbb").toArrayUnsafe();
    final byte[] valueA = Bytes32.repeat((byte) 0x01).toArrayUnsafe();
    final byte[] valueB = Bytes32.repeat((byte) 0x02).toArrayUnsafe();

    final TrieNode root = new MemoryLeafNode(keyA, 2, valueA, false).put(keyB, 2, valueB, 0);
    final byte[] rootHash = root.merkleHashBytes();
    final Bytes encoded = root.encode();

    assertThat(encoded.get(0)).isEqualTo(TrieNodeCodec.BRANCH_TAG);

    final NodeUpdaterMock updater = new NodeUpdaterMock();
    final StoredTrieNodeFactory factory = new StoredTrieNodeFactory(new NodeLoaderMock(updater));
    root.commit(Bytes.EMPTY, updater);
    final TrieNode decoded = factory.retrieve(Bytes.EMPTY, Bytes32.wrap(rootHash));
    assertThat(decoded.get(keyA, 2, 0)).contains(valueA);
    assertThat(decoded.get(keyB, 2, 0)).contains(valueB);
    assertThat(decoded.merkleHashBytes()).isEqualTo(rootHash);
  }

  @Test
  void childLocationExtendsParentPath() {
    final byte[] prefix = new byte[] {1, 0, 1};
    final Bytes left = TrieNodeCodec.childLocation(Bytes.EMPTY, prefix, 3, 0);
    final Bytes right = TrieNodeCodec.childLocation(Bytes.EMPTY, prefix, 3, 1);
    assertThat(left).isEqualTo(Bytes.of((byte) 1, (byte) 0, (byte) 1, (byte) 0));
    assertThat(right).isEqualTo(Bytes.of((byte) 1, (byte) 0, (byte) 1, (byte) 1));
  }

  @Test
  void unpackPrefixReversesPackedBits() {
    final byte[] prefixBits = new byte[] {1, 0, 1, 1, 0, 0, 0, 1, 1};
    final int prefixLen = 9;
    final byte[] packed = new byte[2];
    for (int i = 0; i < prefixLen; i++) {
      if (prefixBits[i] == 1) {
        packed[i / 8] |= (byte) (1 << (7 - i % 8));
      }
    }
    assertThat(TrieNodeCodec.unpackPrefix(Bytes.wrap(packed), prefixLen)).isEqualTo(prefixBits);
  }
}
