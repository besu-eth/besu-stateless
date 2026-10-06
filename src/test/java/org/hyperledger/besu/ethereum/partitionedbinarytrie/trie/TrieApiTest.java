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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.trie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.codec.TrieNodeCodec;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieConstants;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.PartitionedBinaryTrie.LeafHandler;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.PartitionedBinaryTrie.TrieNodeView;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.NodeLoaderMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.NodeUpdaterMock;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.PartitionedBinaryTrieFactory;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.reference.BinaryTrie;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;

/** Public API of {@link StoredPartitionedBinaryTrie}: arguments, prefix-freedom and traversals. */
class TrieApiTest {

  private static final Bytes32 VALUE_1 = Bytes32.repeat((byte) 1);
  private static final Bytes32 VALUE_2 = Bytes32.repeat((byte) 2);

  private final NodeUpdaterMock nodeUpdater = new NodeUpdaterMock();
  private final PartitionedBinaryTrieFactory factory =
      new PartitionedBinaryTrieFactory(new NodeLoaderMock(nodeUpdater));

  @Test
  void byteArrayKeyIsItsFirstKeyLenBytesAndIsCopied() {
    final byte[] buffer = Bytes.fromHexString("0x0a0b0c0d").toArray();
    final StoredPartitionedBinaryTrie trie = factory.create();
    trie.put(buffer, 3, VALUE_1.toArray());
    buffer[0] = 0x7f;
    trie.commit(nodeUpdater);

    final StoredPartitionedBinaryTrie reloaded = factory.create();
    assertThat(reloaded.get(Bytes.fromHexString("0x0a0b0c"))).contains(VALUE_1);
    assertThat(reloaded.get(Bytes.fromHexString("0x0a0b0cff").toArray(), 3))
        .contains(VALUE_1.toArray());
  }

  @Test
  void malformedKeysAndValuesAreRejected() {
    final StoredPartitionedBinaryTrie trie = factory.create();
    for (final Bytes key :
        List.of(Bytes.EMPTY, Bytes.wrap(new byte[TrieConstants.MAX_KEY_LENGTH + 1]))) {
      assertThatThrownBy(() -> trie.put(key, VALUE_1)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> trie.get(key)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> trie.remove(key)).isInstanceOf(IllegalArgumentException.class);
    }
    // A key length beyond the buffer.
    final byte[] key = {1};
    assertThatThrownBy(() -> trie.put(key, 2, VALUE_1.toArray()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> trie.get(key, 2)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> trie.remove(key, 2)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> trie.put(Bytes.wrap(key), Bytes.wrap(new byte[31])))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(trie.isEmpty()).isTrue();
  }

  @Test
  void keysOfTheMaximumLengthRoundTrip() {
    // Keys of 8192 bytes differing in their last bit: a branch prefix of 65535 bits.
    final Bytes first = Bytes.wrap(new byte[TrieConstants.MAX_KEY_LENGTH]);
    final Bytes second = Bytes.concatenate(first.slice(0, first.size() - 1), Bytes.of(1));
    final StoredPartitionedBinaryTrie trie = factory.create();
    trie.put(first, VALUE_1);
    trie.put(second, VALUE_2);
    trie.commit(nodeUpdater);

    final BinaryTrie spec = new BinaryTrie();
    spec.put(first, VALUE_1);
    spec.put(second, VALUE_2);
    final StoredPartitionedBinaryTrie reloaded = factory.create();
    assertThat(reloaded.getRootHash()).isEqualTo(spec.root());
    assertThat(reloaded.get(first)).contains(VALUE_1);
    assertThat(reloaded.get(second)).contains(VALUE_2);
  }

  @Test
  void keyThatIsAPrefixOfAnotherIsRejected() {
    // The shorter key is a leaf, ends at a branch split, or ends inside a branch prefix.
    assertPrefixViolation(List.of("0xaa"), "0xaabb");
    assertPrefixViolation(List.of("0xaa00", "0xaa80"), "0xaa");
    assertPrefixViolation(List.of("0xaa00", "0xaa01"), "0xaa");
  }

  @Test
  void cachedRootHashFollowsMutationsBeforeCommit() {
    final Bytes a = Bytes.fromHexString("0x00" + "11".repeat(32) + "00");
    final Bytes b = Bytes.fromHexString("0x00" + "11".repeat(32) + "01");
    final Bytes c = Bytes.fromHexString("0x00" + "22".repeat(32) + "00");
    final StoredPartitionedBinaryTrie trie = factory.create();
    trie.put(a, VALUE_1);
    trie.put(c, VALUE_1);
    final Bytes32 before = trie.getRootHash();

    trie.put(b, VALUE_2);
    final StoredPartitionedBinaryTrie fresh = factory.create();
    fresh.put(a, VALUE_1);
    fresh.put(b, VALUE_2);
    fresh.put(c, VALUE_1);
    assertThat(trie.getRootHash()).isNotEqualTo(before).isEqualTo(fresh.getRootHash());

    trie.remove(b);
    assertThat(trie.getRootHash()).isEqualTo(before);
  }

  @Test
  void putDeferredPassesTheCurrentValue() {
    final Bytes key = Bytes.fromHexString("0xabcd");
    final List<Optional<Bytes>> seen = new ArrayList<>();
    final StoredPartitionedBinaryTrie trie = factory.create();
    trie.putDeferred(
        key,
        existing -> {
          seen.add(existing);
          return Optional.of(VALUE_1);
        });
    trie.commit(nodeUpdater);
    trie.putDeferred(
        key,
        existing -> {
          seen.add(existing);
          return existing.map(value -> VALUE_2);
        });

    assertThat(seen).containsExactly(Optional.empty(), Optional.of(VALUE_1));
    assertThat(trie.get(key)).contains(VALUE_2);
  }

  @Test
  void visitLeafsWalksTheReloadedTrieInKeyOrder() {
    final List<Bytes> keys =
        List.of("0x0102", "0x0103", "0x10", "0x20ff00", "0x20ff01").stream()
            .map(Bytes::fromHexString)
            .toList();
    final StoredPartitionedBinaryTrie trie = factory.create();
    for (int i = keys.size() - 1; i >= 0; i--) {
      trie.put(keys.get(i), Bytes32.repeat((byte) i));
    }
    trie.commit(nodeUpdater);
    final StoredPartitionedBinaryTrie reloaded = factory.create();

    final List<Bytes> visited = new ArrayList<>();
    reloaded.visitLeafs(
        (key, value) -> {
          assertThat(value).isEqualTo(Bytes32.repeat((byte) keys.indexOf(key)));
          visited.add(key);
          return LeafHandler.State.CONTINUE;
        });
    assertThat(visited).isEqualTo(keys);

    visited.clear();
    reloaded.visitLeafs(
        (key, value) -> {
          visited.add(key);
          return visited.size() == 2 ? LeafHandler.State.STOP : LeafHandler.State.CONTINUE;
        });
    assertThat(visited).isEqualTo(keys.subList(0, 2));
  }

  @Test
  void visitAllShowsEveryNodeOfTheReloadedTrie() throws Exception {
    // In pre-order: the root over stems 0x0a and 0x0b, the branch of stem 0x0a and its two leaves,
    // then the leaf of stem 0x0b.
    final List<Bytes> keys =
        List.of("0x0a01", "0x0a02", "0x0b01").stream().map(Bytes::fromHexString).toList();
    final StoredPartitionedBinaryTrie trie = factory.create();
    keys.forEach(key -> trie.put(key, VALUE_1));
    trie.commit(nodeUpdater);
    final StoredPartitionedBinaryTrie reloaded = factory.create();

    final List<TrieNodeView> views = new ArrayList<>();
    reloaded.visitAll(views::add);

    assertThat(views)
        .extracting(TrieNodeView::isBranch)
        .containsExactly(true, true, false, false, false);
    assertThat(views).noneMatch(TrieNodeView::isEmpty);
    assertThat(views.get(0).getHash()).isEqualTo(reloaded.getRootHash());
    assertThat(views.get(0).getKey()).isEmpty();
    assertThat(views.get(0).getEncoded())
        .isEqualTo(
            TrieNodeCodec.encodeBranch(
                new byte[] {0x0a},
                7,
                views.get(1).getHash().toArrayUnsafe(),
                views.get(4).getHash().toArrayUnsafe()));
    assertThat(views.stream().filter(TrieNodeView::isLeaf).map(view -> view.getKey().orElseThrow()))
        .isEqualTo(keys);
    assertThat(views.get(2).getValue()).contains(VALUE_1);
    assertThat(views.get(2).getEncoded())
        .isEqualTo(
            TrieNodeCodec.encodeLeaf(keys.get(0).toArrayUnsafe(), 2, VALUE_1.toArrayUnsafe()));

    final Set<Bytes32> hashes = ConcurrentHashMap.newKeySet();
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      factory.create().visitAll(view -> hashes.add(view.getHash()), executor).get();
    }
    assertThat(hashes)
        .containsExactlyInAnyOrderElementsOf(views.stream().map(TrieNodeView::getHash).toList());
  }

  @Test
  void visitAllOfAnEmptyTrieShowsTheEmptyNode() {
    final List<TrieNodeView> views = new ArrayList<>();
    factory.create().visitAll(views::add);

    assertThat(views).singleElement().matches(TrieNodeView::isEmpty);
    assertThat(views.get(0).getHash()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);
  }

  private void assertPrefixViolation(final List<String> present, final String inserted) {
    final StoredPartitionedBinaryTrie trie = factory.create();
    present.forEach(key -> trie.put(Bytes.fromHexString(key), VALUE_1));
    final Bytes32 root = trie.getRootHash();
    final Bytes key = Bytes.fromHexString(inserted);

    assertThatThrownBy(() -> trie.put(key, VALUE_2))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("prefix-freedom");
    assertThatThrownBy(() -> trie.putDeferred(key, existing -> Optional.of(VALUE_2)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("prefix-freedom");
    // Such a key is absent: reading, proving or removing it finds nothing.
    assertThat(trie.get(key)).isEmpty();
    assertThat(trie.getValueWithProof(key).getValue()).isEmpty();
    trie.remove(key);
    assertThat(trie.getRootHash()).isEqualTo(root);
  }
}
