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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.codec;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.internal.bytes.ByteTrieOps;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieConstants;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieKey;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.NodeLoaderMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.NodeUpdaterMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.StoredTrieNodeFactory;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.BranchNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.EmptyTrieNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.LeafNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.TrieNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.visitor.GetVisitor;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.visitor.PutVisitor;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.visitor.RemoveVisitor;
import org.hyperledger.besu.ethereum.trie.NodeLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;

/**
 * Round-trip encoding and decoding of persisted trie nodes ({@link TrieNodeCodec}).
 *
 * <p>Layer: codec. Distinct from BLAKE3 hash preimages; validates leaf/branch wire format, child
 * locations, and prefix bit unpacking against {@link ByteTrieOps} merkle hashes.
 */
class TrieNodeCodecTest {

  @Test
  void leafEncodeDecodeRoundTrip() {
    final byte[] key = Bytes.fromHexString("0x01020304").toArrayUnsafe();
    final byte[] value = Bytes32.repeat((byte) 0x42).toArrayUnsafe();
    final Bytes encoded = TrieNodeCodec.encodeLeaf(key, 4, value);

    assertThat(encoded.get(0)).isEqualTo(TrieNodeCodec.LEAF_TAG);
    assertThat(encoded.size()).isEqualTo(1 + 5 + 4 + 32);

    final NodeUpdaterMock updater = new NodeUpdaterMock();
    final StoredTrieNodeFactory factory = new StoredTrieNodeFactory(new NodeLoaderMock(updater));
    final LeafNode leaf = new LeafNode(key, 4, value, false);
    leaf.commit(Bytes.EMPTY, updater);
    final TrieNode decoded = factory.retrieve(Bytes.EMPTY, Bytes32.wrap(leaf.merkleHashBytes()));
    assertThat(decoded.accept(new GetVisitor(), TrieKey.of(key, 4), 0).leafValue()).contains(value);
    assertThat(decoded.merkleHashBytes()).isEqualTo(ByteTrieOps.leafHash(key, 4, value));
  }

  @Test
  void branchEncodeDecodeRoundTrip() {
    final byte[] keyA = Bytes.fromHexString("0xaaaa").toArrayUnsafe();
    final byte[] keyB = Bytes.fromHexString("0xbbbb").toArrayUnsafe();
    final byte[] valueA = Bytes32.repeat((byte) 0x01).toArrayUnsafe();
    final byte[] valueB = Bytes32.repeat((byte) 0x02).toArrayUnsafe();

    final TrieNode root =
        new LeafNode(keyA, 2, valueA, false).accept(new PutVisitor(valueB), TrieKey.of(keyB, 2), 0);
    final byte[] rootHash = root.merkleHashBytes();
    final Bytes encoded = root.encode();

    assertThat(encoded.get(0)).isEqualTo(TrieNodeCodec.BRANCH_TAG);

    final NodeUpdaterMock updater = new NodeUpdaterMock();
    final StoredTrieNodeFactory factory = new StoredTrieNodeFactory(new NodeLoaderMock(updater));
    root.commit(Bytes.EMPTY, updater);
    final TrieNode decoded = factory.retrieve(Bytes.EMPTY, Bytes32.wrap(rootHash));
    assertThat(decoded.accept(new GetVisitor(), TrieKey.of(keyA, 2), 0).leafValue())
        .contains(valueA);
    assertThat(decoded.accept(new GetVisitor(), TrieKey.of(keyB, 2), 0).leafValue())
        .contains(valueB);
    assertThat(decoded.merkleHashBytes()).isEqualTo(rootHash);
  }

  @Test
  void emptyChildHashDecodesToEmptyTrieNodeAndIsNeverLoaded() {
    // Key 0x00 has bit0=0 → left child. Right child stays empty.
    final byte[] key = Bytes.fromHexString("0x00").toArrayUnsafe();
    final byte[] value = Bytes32.repeat((byte) 0x11).toArrayUnsafe();
    final LeafNode leaf = new LeafNode(key, key.length, value, false);
    final BranchNode branch = new BranchNode(new byte[0], 0, leaf, TrieNode.empty(), false);

    final NodeUpdaterMock updater = new NodeUpdaterMock();
    final CountingNodeLoader loader = new CountingNodeLoader(new NodeLoaderMock(updater));
    final StoredTrieNodeFactory factory = new StoredTrieNodeFactory(loader);

    branch.commit(Bytes.EMPTY, updater);
    final Bytes32 rootHash = Bytes32.wrap(branch.merkleHashBytes());
    loader.loads.clear();

    final TrieNode decoded = factory.retrieve(Bytes.EMPTY, rootHash);
    assertThat(decoded).isInstanceOf(BranchNode.class);
    final BranchNode decodedBranch = (BranchNode) decoded;
    assertThat(decodedBranch.rightChild()).isSameAs(TrieNode.empty());
    assertThat(decodedBranch.leftChild()).isNotInstanceOf(EmptyTrieNode.class);

    final Bytes rightLoc = TrieNodeCodec.childLocation(Bytes.EMPTY, new byte[0], 0, 1);
    // Key 0x80 has bit0=1 → empty right side. Get must not load that location.
    decoded.accept(new GetVisitor(), TrieKey.of(Bytes.fromHexString("0x80").toArrayUnsafe(), 1), 0);
    // Remove on the empty side flattens (Besu replaceChild), but still must not load rightLoc.
    decoded.accept(
        new RemoveVisitor(), TrieKey.of(Bytes.fromHexString("0x80").toArrayUnsafe(), 1), 0);

    assertThat(loader.loads).doesNotContain(rightLoc);
  }

  @Test
  void wrapStoredEmptyHashReturnsEmptySingleton() {
    final NodeUpdaterMock updater = new NodeUpdaterMock();
    final StoredTrieNodeFactory factory = new StoredTrieNodeFactory(new NodeLoaderMock(updater));
    assertThat(factory.wrapStored(Bytes.EMPTY, TrieConstants.EMPTY_TRIE_ROOT))
        .isSameAs(TrieNode.empty());
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

  /** Records every storage location requested through {@link NodeLoader#getNode}. */
  private static final class CountingNodeLoader implements NodeLoader {
    private final NodeLoader delegate;
    private final List<Bytes> loads = new ArrayList<>();

    CountingNodeLoader(final NodeLoader delegate) {
      this.delegate = delegate;
    }

    @Override
    public Optional<Bytes> getNode(final Bytes location, final Bytes32 hash) {
      loads.add(location);
      return delegate.getNode(location, hash);
    }
  }
}
