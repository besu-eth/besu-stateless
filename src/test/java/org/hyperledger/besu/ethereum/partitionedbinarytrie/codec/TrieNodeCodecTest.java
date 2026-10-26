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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.internal.bytes.ByteTrieOps;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieConstants;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieKey;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.NodeLoaderMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.NodeUpdaterMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.StoredTrieNodeFactory;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.BranchNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.LeafNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.StoredTrieNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.TrieNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.visitor.GetVisitor;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.visitor.PutVisitor;
import org.hyperledger.besu.ethereum.trie.MerkleTrieException;
import org.hyperledger.besu.ethereum.trie.NodeLoader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;

/**
 * Persisted trie nodes: branch ({@code 0x10}) and stem ({@code 0x12}) entries, the leaf alone
 * ({@code 0x11}) carried by proofs, and node locations.
 */
class TrieNodeCodecTest {

  private static final byte[] LEFT = Bytes32.repeat((byte) 0x0A).toArrayUnsafe();
  private static final byte[] RIGHT = Bytes32.repeat((byte) 0x0B).toArrayUnsafe();

  private final NodeUpdaterMock updater = new NodeUpdaterMock();
  private final StoredTrieNodeFactory factory =
      new StoredTrieNodeFactory(new NodeLoaderMock(updater));

  @Test
  void leafAloneKeepsTheLayoutOfItsHashPreimage() {
    final byte[] key = Bytes.fromHexString("0x01020304").toArrayUnsafe();
    final byte[] value = Bytes32.repeat((byte) 0x42).toArrayUnsafe();

    assertThat(TrieNodeCodec.encodeLeaf(key, 4, value))
        .isEqualTo(Bytes.concatenate(Bytes.of(0x11), Bytes.wrap(key), Bytes.wrap(value)));
  }

  @Test
  void branchKeepsTheLayoutOfItsHashPreimage() {
    // Prefix 101 out of 0xBF: the bits after the third one are dropped.
    assertThat(TrieNodeCodec.encodeBranch(new byte[] {(byte) 0xBF}, 3, LEFT, RIGHT))
        .isEqualTo(
            Bytes.concatenate(
                Bytes.fromHexString("0x100003a0"), Bytes.wrap(LEFT), Bytes.wrap(RIGHT)));
  }

  @Test
  void stemHoldsTheStemOnceAndEachValueWithoutItsLeadingZeros() {
    final Bytes encoded =
        TrieNodeCodec.encodeStem(
            new byte[] {(byte) 0xAA, (byte) 0xBB},
            2,
            new byte[] {0x01, (byte) 0xFF},
            new byte[][] {
              Bytes32.ZERO.toArrayUnsafe(), Bytes32.fromHexStringLenient("0x0102").toArrayUnsafe()
            });

    // Tag, count - 1, then suffix, value length and value per leaf, then the stem.
    assertThat(encoded).isEqualTo(Bytes.fromHexString("0x12010100ff020102aabb"));
    assertThat(decodeStem(encoded))
        .containsExactly(
            Bytes.fromHexString("0xaabb01"),
            Bytes32.ZERO,
            Bytes.fromHexString("0xaabbff"),
            Bytes32.fromHexStringLenient("0x0102"));
  }

  @Test
  void stemOf256LeavesCountsThemInOneByte() {
    final byte[] suffixes = new byte[256];
    final byte[][] values = new byte[256][];
    for (int i = 0; i < 256; i++) {
      suffixes[i] = (byte) i;
      values[i] = Bytes32.leftPad(Bytes.of(i)).toArrayUnsafe();
    }
    final Bytes encoded = TrieNodeCodec.encodeStem(new byte[] {0x07}, 1, suffixes, values);

    assertThat(encoded.get(1)).isEqualTo((byte) 0xFF);
    final List<Bytes> decoded = decodeStem(encoded);
    assertThat(decoded).hasSize(512);
    for (int i = 0; i < 256; i++) {
      assertThat(decoded.get(2 * i)).isEqualTo(Bytes.of(0x07, i));
      assertThat(decoded.get(2 * i + 1)).isEqualTo(Bytes32.leftPad(Bytes.of(i)));
    }
  }

  @Test
  void longestBranchPrefixFitsItsTwoByteLength() {
    final byte[] prefix = new byte[8192];
    Arrays.fill(prefix, (byte) 0xFF);
    final byte[] encoded = TrieNodeCodec.encodeBranch(prefix, 65535, LEFT, RIGHT).toArrayUnsafe();

    assertThat(encoded).hasSize(3 + 8192 + 64);
    assertThat(TrieNodeCodec.branchPrefixLength(encoded)).isEqualTo(65535);
    final byte[] decoded = TrieNodeCodec.branchPrefix(encoded);
    assertThat(decoded).hasSize(8192);
    assertThat(decoded[8191]).isEqualTo((byte) 0xFE);
  }

  @Test
  void leafAloneIsStoredAsAStemOfOneLeaf() {
    final byte[] key = Bytes.fromHexString("0x01020304").toArrayUnsafe();
    final byte[] value = Bytes32.repeat((byte) 0x42).toArrayUnsafe();
    final LeafNode leaf = new LeafNode(key, 4, value, false);

    leaf.commit(Bytes.EMPTY, updater);

    assertThat(updater.storage.get(Bytes.EMPTY))
        .isEqualTo(
            Bytes.concatenate(
                Bytes.fromHexString("0x12000420"), Bytes.wrap(value), Bytes.of(1, 2, 3)));
    final TrieNode reloaded = factory.retrieve(Bytes.EMPTY, Bytes32.wrap(leaf.merkleHashBytes()));
    assertThat(reloaded.accept(new GetVisitor(), TrieKey.of(key, 4), 0).leafValue())
        .contains(value);
    assertThat(reloaded.merkleHashBytes()).isEqualTo(ByteTrieOps.leafHash(key, 4, value));
  }

  @Test
  void leafWithTheLongestKeyIsStoredAsAStemOfOneLeaf() {
    final byte[] key = new byte[TrieConstants.MAX_KEY_LENGTH];
    Arrays.fill(key, (byte) 0x5A);
    final byte[] value = Bytes32.repeat((byte) 0x42).toArrayUnsafe();
    final LeafNode leaf = new LeafNode(key, key.length, value, false);

    leaf.commit(Bytes.EMPTY, updater);

    assertThat(updater.storage.get(Bytes.EMPTY).size()).isEqualTo(2 + 2 + 32 + key.length - 1);
    final TrieNode reloaded = factory.retrieve(Bytes.EMPTY, Bytes32.wrap(leaf.merkleHashBytes()));
    assertThat(reloaded.accept(new GetVisitor(), TrieKey.of(key, key.length), 0).leafValue())
        .contains(value);
  }

  @Test
  void branchSplittingBeforeTheLastKeyByteIsStoredAsABranch() {
    // The keys differ at bit 311, before their last byte: the root branch, with a 311-bit prefix,
    // joins two stems and is stored on its own.
    final byte[] keyA = new byte[40];
    final byte[] keyB = new byte[40];
    keyB[38] = 1;
    final byte[] valueA = Bytes32.repeat((byte) 0x01).toArrayUnsafe();
    final byte[] valueB = Bytes32.repeat((byte) 0x02).toArrayUnsafe();
    final TrieNode root =
        new LeafNode(keyA, 40, valueA, false)
            .accept(new PutVisitor(valueB), TrieKey.of(keyB, 40), 0);
    final byte[] rootHash = root.merkleHashBytes();

    root.commit(Bytes.EMPTY, updater);

    final byte[] stored = updater.storage.get(Bytes.EMPTY).toArrayUnsafe();
    assertThat(stored[0]).isEqualTo(TrieNodeCodec.BRANCH_TAG);
    assertThat(TrieNodeCodec.branchPrefixLength(stored)).isEqualTo(311);
    final TrieNode reloaded = factory.retrieve(Bytes.EMPTY, Bytes32.wrap(rootHash));
    assertThat(reloaded.merkleHashBytes()).isEqualTo(rootHash);
    assertThat(reloaded.accept(new GetVisitor(), TrieKey.of(keyA, 40), 0).leafValue())
        .contains(valueA);
    assertThat(reloaded.accept(new GetVisitor(), TrieKey.of(keyB, 40), 0).leafValue())
        .contains(valueB);
  }

  @Test
  void stemRebuiltFromStorageHasTheStoredHash() {
    // Three leaves of one stem below a branch joining it to another stem.
    final TrieNode root =
        put(put(put(put(TrieNode.empty(), "0xaa00", 1), "0xaa01", 2), "0xaa80", 3), "0xbb00", 4);
    final byte[] rootHash = root.merkleHashBytes();
    root.commit(Bytes.EMPTY, updater);

    final BranchNode reloaded = (BranchNode) factory.retrieve(Bytes.EMPTY, Bytes32.wrap(rootHash));
    final TrieNode stem = ((StoredTrieNode) reloaded.leftChild()).load();
    assertThat(stem.merkleHashBytes()).isEqualTo(reloaded.leftChild().merkleHashBytes());
    assertThat(reloaded.merkleHashBytes()).isEqualTo(rootHash);
  }

  @Test
  void emptyChildHashDecodesToEmptyTrieNodeAndIsNeverLoaded() {
    // A branch with an empty side is stored on its own, with the empty hash for that side.
    final byte[] key = Bytes.fromHexString("0x00").toArrayUnsafe();
    final byte[] value = Bytes32.repeat((byte) 0x11).toArrayUnsafe();
    final BranchNode branch =
        new BranchNode(new byte[0], 0, new LeafNode(key, 1, value, false), TrieNode.empty(), false);
    branch.commit(Bytes.EMPTY, updater);
    final CountingNodeLoader loader = new CountingNodeLoader(new NodeLoaderMock(updater));

    final TrieNode decoded =
        new StoredTrieNodeFactory(loader)
            .retrieve(Bytes.EMPTY, Bytes32.wrap(branch.merkleHashBytes()));

    assertThat(((BranchNode) decoded).rightChild()).isSameAs(TrieNode.empty());
    final byte[] absent = Bytes.fromHexString("0x80").toArrayUnsafe();
    assertThat(decoded.accept(new GetVisitor(), TrieKey.of(absent, 1), 0).leafValue()).isEmpty();
    assertThat(loader.loads).containsExactly(Bytes.EMPTY);
  }

  @Test
  void wrapStoredEmptyHashReturnsEmptySingleton() {
    assertThat(factory.wrapStored(Bytes.EMPTY, TrieConstants.EMPTY_TRIE_ROOT))
        .isSameAs(TrieNode.empty());
  }

  @Test
  void entryOfALeafAloneOrOfAnUnknownTagIsRejected() {
    updater.store(Bytes.EMPTY, null, TrieNodeCodec.encodeLeaf(new byte[1], 1, new byte[32]));
    assertThatThrownBy(factory::retrieveRoot).isInstanceOf(IllegalArgumentException.class);

    updater.store(Bytes.EMPTY, null, Bytes.of(0x7F));
    assertThatThrownBy(factory::retrieveRoot).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void missingNodeForARealHashFails() {
    assertThatThrownBy(
            () -> factory.retrieve(Bytes.fromHexString("0x0040"), Bytes32.repeat((byte) 0x01)))
        .isInstanceOf(MerkleTrieException.class);
  }

  @Test
  void childLocationExtendsParentPath() {
    // 0x00, the path bits, then a closing 1 bit.
    final byte[] prefix = {(byte) 0b1010_0000};
    final Bytes left = TrieNodeCodec.childLocation(Bytes.EMPTY, prefix, 3, 0);
    final Bytes right = TrieNodeCodec.childLocation(Bytes.EMPTY, prefix, 3, 1);
    assertThat(left).isEqualTo(Bytes.of((byte) 0, (byte) 0b1010_1000));
    assertThat(right).isEqualTo(Bytes.of((byte) 0, (byte) 0b1011_1000));
    assertThat(TrieNodeCodec.childLocation(left, new byte[0], 0, 1))
        .isEqualTo(Bytes.of((byte) 0, (byte) 0b1010_1100));

    final Bytes deep = TrieNodeCodec.childLocation(left, new byte[] {(byte) 0xFF}, 5, 0);
    assertThat(deep).isEqualTo(Bytes.of((byte) 0, (byte) 0b1010_1111, (byte) 0b1010_0000));
    assertThat(TrieNodeCodec.locationDepth(Bytes.EMPTY)).isZero();
    assertThat(TrieNodeCodec.locationDepth(left)).isEqualTo(4);
    assertThat(TrieNodeCodec.locationDepth(deep)).isEqualTo(10);
  }

  @Test
  void closingBitAtAByteBoundary() {
    // Depth 7 closes on the last bit of its byte; depth 8 needs a byte for the closing bit alone.
    final Bytes depth7 = TrieNodeCodec.childLocation(Bytes.EMPTY, new byte[] {(byte) 0xFF}, 6, 1);
    final Bytes depth8 = TrieNodeCodec.childLocation(Bytes.EMPTY, new byte[] {(byte) 0xFF}, 7, 1);
    final Bytes depth9 = TrieNodeCodec.childLocation(depth8, new byte[0], 0, 0);
    assertThat(depth7).isEqualTo(Bytes.fromHexString("0x00ff"));
    assertThat(depth8).isEqualTo(Bytes.fromHexString("0x00ff80"));
    assertThat(depth9).isEqualTo(Bytes.fromHexString("0x00ff40"));
    assertThat(TrieNodeCodec.locationDepth(depth7)).isEqualTo(7);
    assertThat(TrieNodeCodec.locationDepth(depth8)).isEqualTo(8);
    assertThat(TrieNodeCodec.locationDepth(depth9)).isEqualTo(9);
  }

  @Test
  void locationsOfAllShortPathsAreDistinctWhicheverWayTheyAreBuilt() {
    final Set<Bytes> seen = new HashSet<>();
    for (int length = 1; length <= 12; length++) {
      for (int bits = 0; bits < 1 << length; bits++) {
        // One bit per branch, as a commit walks down...
        Bytes chained = Bytes.EMPTY;
        for (int i = 0; i < length; i++) {
          chained = TrieNodeCodec.childLocation(chained, new byte[0], 0, bitOf(bits, length, i));
        }
        // ...or a key's first bits at once, as the bulk loader does, with junk after them.
        final byte[] path = {(byte) 0xFF, (byte) 0xFF};
        for (int i = 0; i < length; i++) {
          if (bitOf(bits, length, i) == 0) {
            path[i >>> 3] &= (byte) ~(0x80 >>> (i & 7));
          }
        }
        final Bytes direct =
            TrieNodeCodec.childLocation(
                Bytes.EMPTY, path, length - 1, bitOf(bits, length, length - 1));

        assertThat(direct).isEqualTo(chained);
        assertThat(chained.get(0)).isEqualTo(TrieNodeCodec.NODE_KEY_PREFIX);
        assertThat(TrieNodeCodec.locationDepth(chained)).isEqualTo(length);
        assertThat(seen.add(chained)).as("path %s of length %d", bits, length).isTrue();
      }
    }
  }

  private static int bitOf(final int bits, final int length, final int index) {
    return (bits >>> (length - 1 - index)) & 1;
  }

  private static TrieNode put(final TrieNode root, final String key, final int value) {
    final byte[] keyBytes = Bytes.fromHexString(key).toArrayUnsafe();
    return root.accept(
        new PutVisitor(Bytes32.repeat((byte) value).toArrayUnsafe()),
        TrieKey.of(keyBytes, keyBytes.length),
        0);
  }

  /** Keys and values of a stem, alternating. */
  private static List<Bytes> decodeStem(final Bytes encoded) {
    final List<Bytes> leaves = new ArrayList<>();
    TrieNodeCodec.decodeStem(
        encoded.toArrayUnsafe(),
        (key, value) -> {
          leaves.add(Bytes.wrap(key));
          leaves.add(Bytes.wrap(value));
        });
    return leaves;
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
