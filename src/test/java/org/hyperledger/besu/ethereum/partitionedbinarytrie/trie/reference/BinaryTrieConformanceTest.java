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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.reference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.internal.TrieConstants;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.hash.BitUtils;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.hash.PrefixEncoder;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.hash.TrieHasher;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.Binarizer;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.BinaryNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.BranchNode;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.LeafNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.bouncycastle.crypto.digests.Blake3Digest;
import org.junit.jupiter.api.Test;

/**
 * Conformance suite for the in-memory {@link BinaryTrie} reference implementation.
 *
 * <p>Layer: reference oracle ({@code trie.reference}, {@code trie.hash}, {@code trie.node}).
 * Validates bit encoding, merkleization, CRUD, and structural invariants against hand-computed
 * BLAKE3 hashes and {@link MutableBinaryTrie} cross-checks.
 */
class BinaryTrieConformanceTest {

  private static Bytes32 blake3(final Bytes data) {
    final Blake3Digest digest = new Blake3Digest(256);
    digest.update(data.toArrayUnsafe(), 0, data.size());
    final byte[] output = new byte[32];
    digest.doFinal(output, 0);
    return Bytes32.wrap(output);
  }

  private static List<Integer> bits(final byte[] data) {
    final List<Integer> result = new ArrayList<>();
    for (final byte b : data) {
      for (int i = 0; i < 8; i++) {
        result.add((b >> (7 - i)) & 1);
      }
    }
    return result;
  }

  private static byte[] packPadded(final List<Integer> bitList) {
    final byte[] packed = new byte[(bitList.size() + 7) / 8];
    for (int i = 0; i < bitList.size(); i++) {
      packed[i / 8] |= (byte) (bitList.get(i) << (7 - i % 8));
    }
    return packed;
  }

  private static Bytes32 leafHash(final Bytes key, final Bytes32 value) {
    return blake3(Bytes.concatenate(Bytes.of((byte) 0), key, value));
  }

  private static Bytes32 branchHash(
      final List<Integer> prefix, final Bytes32 left, final Bytes32 right) {
    final byte[] count = new byte[] {(byte) (prefix.size() >> 8), (byte) (prefix.size() & 0xFF)};
    return blake3(
        Bytes.concatenate(
            Bytes.of((byte) 1), Bytes.wrap(count), Bytes.wrap(packPadded(prefix)), left, right));
  }

  @Test
  void bytesToBitListIsMsbFirst() {
    assertThat(BitUtils.bytesToBitList(Bytes.of((byte) 0x80)))
        .isEqualTo(Bytes.wrap(new byte[] {1, 0, 0, 0, 0, 0, 0, 0}));
    assertThat(BitUtils.bytesToBitList(Bytes.of((byte) 0x01)))
        .isEqualTo(Bytes.wrap(new byte[] {0, 0, 0, 0, 0, 0, 0, 1}));
    assertThat(BitUtils.bytesToBitList(Bytes.of((byte) 0xA5)))
        .isEqualTo(Bytes.wrap(new byte[] {1, 0, 1, 0, 0, 1, 0, 1}));
  }

  @Test
  void encodeBitPrefixLayout() {
    assertThat(PrefixEncoder.encodeBitPrefix(Bytes.EMPTY)).isEqualTo(Bytes.wrap(new byte[] {0, 0}));
    assertThat(PrefixEncoder.encodeBitPrefix(Bytes.wrap(new byte[] {1, 0, 1})))
        .isEqualTo(Bytes.fromHexString("0003a0"));
    assertThat(PrefixEncoder.encodeBitPrefix(Bytes.wrap(new byte[] {1, 1, 1, 1, 1, 1, 1, 1, 1})))
        .isEqualTo(Bytes.fromHexString("0009ff80"));
  }

  @Test
  void encodeBitPrefixRejectsUnrepresentableCounts() {
    assertThatThrownBy(() -> PrefixEncoder.encodeBitPrefix(Bytes.wrap(new byte[1 << 16])))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void encodeBitPrefixCountsTrailingZeroBits() {
    final Bytes shorter = Bytes.wrap(new byte[] {0, 1, 1, 0});
    final Bytes longer = Bytes.wrap(new byte[] {0, 1, 1, 0, 0});
    assertThat(PrefixEncoder.encodeBitPrefix(shorter).slice(2))
        .isEqualTo(PrefixEncoder.encodeBitPrefix(longer).slice(2));
    assertThat(PrefixEncoder.encodeBitPrefix(shorter))
        .isNotEqualTo(PrefixEncoder.encodeBitPrefix(longer));
  }

  @Test
  void emptyTrieRootIsAllZeros() {
    final BinaryTrie trie = new BinaryTrie();
    assertThat(trie.root()).isEqualTo(TrieConstants.EMPTY_TRIE_ROOT);
    assertThat(trie.root().toArray()).containsOnly(new byte[32]);
  }

  @Test
  void triePutAndGet() {
    final BinaryTrie trie = new BinaryTrie();
    final Bytes key = Bytes.repeat((byte) 0x01, 32);
    final Bytes32 value = Bytes32.repeat((byte) 0x02);

    assertThat(trie.get(key)).isEmpty();
    trie.put(key, value);
    assertThat(trie.get(key)).contains(value);

    final Bytes32 replacement = Bytes32.repeat((byte) 0x03);
    trie.put(key, replacement);
    assertThat(trie.get(key)).contains(replacement);
  }

  @Test
  void triePutRejectsMalformedInputs() {
    final BinaryTrie trie = new BinaryTrie();
    assertThatThrownBy(() -> trie.put(Bytes.EMPTY, Bytes32.repeat((byte) 1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> trie.put(Bytes.wrap(new byte[8193]), Bytes32.repeat((byte) 1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> trie.put(Bytes.of((byte) 1), Bytes32.wrap(new byte[31])))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void copyTrieIsIndependent() {
    final Bytes key = Bytes32.repeat((byte) 0x01);
    final Bytes otherKey = Bytes32.repeat((byte) 0x02);
    final Bytes32 value = Bytes32.repeat((byte) 0x03);

    final BinaryTrie original = new BinaryTrie();
    original.put(key, value);

    final BinaryTrie duplicate = BinaryTrie.copyOf(original);
    assertThat(duplicate.get(key)).contains(value);

    duplicate.put(otherKey, value);
    assertThat(original.get(otherKey)).isEmpty();
    assertThat(duplicate.root()).isNotEqualTo(original.root());
  }

  @Test
  void singleKeyIsALeafAtTheRoot() {
    final Bytes key =
        Bytes.concatenate(Bytes.of((byte) 0), Bytes.repeat((byte) 0x42, 32), Bytes.of((byte) 0x07));
    final Bytes32 value = Bytes32.repeat((byte) 0x11);

    final BinaryTrie trie = new BinaryTrie();
    trie.put(key, value);

    assertThat(trie.root()).isEqualTo(leafHash(key, value));
  }

  @Test
  void keysSharingAStemSplitUnderOneBranch() {
    final byte[] stem = new byte[33];
    stem[0] = 0;
    for (int i = 1; i < 33; i++) {
      stem[i] = 0x42;
    }
    final Bytes lowKey = Bytes.concatenate(Bytes.wrap(stem), Bytes.of((byte) 0));
    final Bytes highKey = Bytes.concatenate(Bytes.wrap(stem), Bytes.of((byte) 0xFF));
    final Bytes32 lowValue = Bytes32.repeat((byte) 0x01);
    final Bytes32 highValue = Bytes32.repeat((byte) 0x02);

    final BinaryTrie trie = new BinaryTrie();
    trie.put(lowKey, lowValue);
    trie.put(highKey, highValue);

    assertThat(trie.root())
        .isEqualTo(
            branchHash(bits(stem), leafHash(lowKey, lowValue), leafHash(highKey, highValue)));
  }

  @Test
  void firstBitDivergenceHasEmptyPrefix() {
    final Bytes zeroKey = Bytes.repeat((byte) 0, 34);
    final Bytes oneKey = Bytes.repeat((byte) 0xFF, 66);
    final Bytes32 value = Bytes32.repeat((byte) 0x33);

    final BinaryTrie trie = new BinaryTrie();
    trie.put(zeroKey, value);
    trie.put(oneKey, value);

    assertThat(trie.root())
        .isEqualTo(branchHash(List.of(), leafHash(zeroKey, value), leafHash(oneKey, value)));
  }

  @Test
  void canonicalFormExample() {
    final byte[] stem = new byte[33];
    stem[0] = (byte) 0xFF;
    for (int i = 1; i < 33; i++) {
      stem[i] = (byte) 0xAB;
    }
    final Bytes key0 = Bytes.concatenate(Bytes.wrap(stem), Bytes.of((byte) 0));
    final Bytes key1 = Bytes.concatenate(Bytes.wrap(stem), Bytes.of((byte) 1));
    final Bytes key128 = Bytes.concatenate(Bytes.wrap(stem), Bytes.of((byte) 0x80));
    final Bytes32 value = Bytes32.repeat((byte) 0x44);

    final BinaryTrie trie = new BinaryTrie();
    trie.put(key0, value);
    trie.put(key1, value);
    trie.put(key128, value);

    final Bytes32 lowSide =
        branchHash(List.of(0, 0, 0, 0, 0, 0), leafHash(key0, value), leafHash(key1, value));
    assertThat(trie.root()).isEqualTo(branchHash(bits(stem), lowSide, leafHash(key128, value)));
  }

  @Test
  void binarizeBuildsRelativePrefixesAndFullKeyLeaves() {
    final byte[] stem = new byte[33];
    stem[0] = (byte) 0xFF;
    for (int i = 1; i < 33; i++) {
      stem[i] = (byte) 0xAB;
    }
    final Bytes key0 = Bytes.concatenate(Bytes.wrap(stem), Bytes.of((byte) 0));
    final Bytes key1 = Bytes.concatenate(Bytes.wrap(stem), Bytes.of((byte) 1));
    final Bytes key128 = Bytes.concatenate(Bytes.wrap(stem), Bytes.of((byte) 0x80));
    final Bytes32 value = Bytes32.repeat((byte) 0x44);

    final Map<Bytes, Bytes32> entries = Map.of(key0, value, key1, value, key128, value);
    final BinaryNode top = Binarizer.binarize(entries, 0);

    assertThat(top).isInstanceOf(BranchNode.class);
    final BranchNode topBranch = (BranchNode) top;
    assertThat(topBranch.prefix()).isEqualTo(BitUtils.bytesToBitList(Bytes.wrap(stem)));

    assertThat(topBranch.left()).isInstanceOf(BranchNode.class);
    final BranchNode low = (BranchNode) topBranch.left();
    assertThat(low.prefix()).isEqualTo(Bytes.wrap(new byte[6]));
    assertThat(low.left()).isInstanceOf(LeafNode.class);
    assertThat(low.right()).isInstanceOf(LeafNode.class);
    assertThat(((LeafNode) low.left()).key()).isEqualTo(key0);
    assertThat(((LeafNode) low.right()).key()).isEqualTo(key1);

    assertThat(topBranch.right()).isInstanceOf(LeafNode.class);
    assertThat(((LeafNode) topBranch.right()).key()).isEqualTo(key128);
  }

  @Test
  void zeroValueIsNotAbsence() {
    final Bytes key = Bytes.concatenate(Bytes.repeat((byte) 0x07, 31), Bytes.of((byte) 0));
    final BinaryTrie trie = new BinaryTrie();
    trie.put(key, Bytes32.ZERO);
    assertThat(trie.root()).isNotEqualTo(TrieConstants.EMPTY_TRIE_ROOT);
  }

  @Test
  void prefixKeyViolationIsRejected() {
    final BinaryTrie trie = new BinaryTrie();
    trie.put(Bytes.repeat((byte) 0xAA, 34), Bytes32.repeat((byte) 0x01));
    trie.put(
        Bytes.concatenate(Bytes.repeat((byte) 0xAA, 34), Bytes.repeat((byte) 0xBB, 32)),
        Bytes32.repeat((byte) 0x02));

    assertThatThrownBy(trie::root).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rootMatchesReferenceImplementation() {
    final Random rng = new Random(8297);
    for (int trial = 0; trial < 20; trial++) {
      final Map<Bytes, Bytes32> entries = randomEntries(rng);

      final ReferenceRadixTree reference = new ReferenceRadixTree();
      final BinaryTrie trie = new BinaryTrie();
      for (final Map.Entry<Bytes, Bytes32> entry : entries.entrySet()) {
        reference.insert(entry.getKey(), entry.getValue());
        trie.put(entry.getKey(), entry.getValue());
      }

      assertThat(trie.root()).as("trial %d", trial).isEqualTo(reference.merkelize());
    }
  }

  @Test
  void rootMatchesReferenceWithVariableLengthKeys() {
    final Random rng = new Random(11832);
    for (int trial = 0; trial < 10; trial++) {
      final Map<Bytes, Bytes32> entries = new HashMap<>();
      for (int i = 0; i < rng.nextInt(29) + 1; i++) {
        final Bytes prefix;
        if (rng.nextDouble() < 0.5) {
          prefix =
              Bytes.concatenate(
                  Bytes.of((byte) (rng.nextBoolean() ? 0 : 1)), Bytes.wrap(randomBytes(rng, 32)));
        } else {
          prefix = Bytes.concatenate(Bytes.of((byte) 0xFF), Bytes.wrap(randomBytes(rng, 64)));
        }
        for (int j = 0; j < rng.nextInt(3) + 1; j++) {
          entries.put(
              Bytes.concatenate(prefix, Bytes.wrap(randomBytes(rng, 1))),
              Bytes32.wrap(randomBytes(rng, 32)));
        }
      }

      final ReferenceRadixTree reference = new ReferenceRadixTree();
      final BinaryTrie trie = new BinaryTrie();
      for (final Map.Entry<Bytes, Bytes32> entry : entries.entrySet()) {
        reference.insert(entry.getKey(), entry.getValue());
        trie.put(entry.getKey(), entry.getValue());
      }

      assertThat(trie.root()).as("trial %d", trial).isEqualTo(reference.merkelize());
    }
  }

  @Test
  void rootIsInsertionOrderIndependent() {
    final Random rng = new Random(1234);
    final List<Map.Entry<Bytes, Bytes32>> entries = new ArrayList<>();
    for (int i = 0; i < 16; i++) {
      entries.add(
          Map.entry(Bytes32.wrap(randomBytes(rng, 32)), Bytes32.wrap(randomBytes(rng, 32))));
    }

    final BinaryTrie forward = new BinaryTrie();
    for (final Map.Entry<Bytes, Bytes32> entry : entries) {
      forward.put(entry.getKey(), entry.getValue());
    }

    final BinaryTrie backward = new BinaryTrie();
    for (int i = entries.size() - 1; i >= 0; i--) {
      backward.put(entries.get(i).getKey(), entries.get(i).getValue());
    }

    assertThat(forward.root()).isEqualTo(backward.root());
  }

  @Test
  void mutableTrieMatchesBinaryTrie() {
    final Random rng = new Random(4567);
    for (int trial = 0; trial < 20; trial++) {
      final Map<Bytes, Bytes32> entries = randomEntries(rng);
      final BinaryTrie specTrie = new BinaryTrie();
      final MutableBinaryTrie mutableTrie = new MutableBinaryTrie();
      for (final Map.Entry<Bytes, Bytes32> entry : entries.entrySet()) {
        specTrie.put(entry.getKey(), entry.getValue());
        mutableTrie.put(entry.getKey(), entry.getValue());
      }
      assertThat(mutableTrie.root()).as("trial %d", trial).isEqualTo(specTrie.root());
    }
  }

  @Test
  void mutableTrieMergesBranchPrefixesAfterRemove() {
    final byte[] stem = new byte[33];
    stem[0] = (byte) 0xFF;
    for (int i = 1; i < 33; i++) {
      stem[i] = (byte) 0xAB;
    }
    final Bytes key0 = Bytes.concatenate(Bytes.wrap(stem), Bytes.of((byte) 0));
    final Bytes key1 = Bytes.concatenate(Bytes.wrap(stem), Bytes.of((byte) 1));
    final Bytes key128 = Bytes.concatenate(Bytes.wrap(stem), Bytes.of((byte) 0x80));
    final Bytes32 value = Bytes32.repeat((byte) 0x44);

    final BinaryTrie rebuilt = new BinaryTrie();
    final MutableBinaryTrie mutable = new MutableBinaryTrie();
    for (final Bytes key : List.of(key0, key1, key128)) {
      rebuilt.put(key, value);
      mutable.put(key, value);
    }

    rebuilt.remove(key128);
    mutable.remove(key128);

    assertThat(mutable.root()).isEqualTo(rebuilt.root());
  }

  @Test
  void overwritingAValueRecommitsToTheFinalValue() {
    final byte[] stem = new byte[33];
    stem[0] = 0;
    for (int i = 1; i < 33; i++) {
      stem[i] = 0x42;
    }
    final Bytes key = Bytes.concatenate(Bytes.wrap(stem), Bytes.of((byte) 0x07));
    final Bytes neighbour = Bytes.concatenate(Bytes.wrap(stem), Bytes.of((byte) 0x08));
    final Bytes32 first = Bytes32.repeat((byte) 0x01);
    final Bytes32 second = Bytes32.repeat((byte) 0x02);

    final BinaryTrie overwritten = new BinaryTrie();
    overwritten.put(key, first);
    overwritten.put(neighbour, first);
    final Bytes32 oldRoot = overwritten.root();
    overwritten.put(key, second);

    final BinaryTrie fresh = new BinaryTrie();
    fresh.put(key, second);
    fresh.put(neighbour, first);

    final ReferenceRadixTree reference = new ReferenceRadixTree();
    reference.insert(key, first);
    reference.insert(neighbour, first);
    reference.insert(key, second);

    assertThat(overwritten.root()).isNotEqualTo(oldRoot);
    assertThat(overwritten.root()).isEqualTo(fresh.root());
    assertThat(reference.merkelize()).isEqualTo(fresh.root());
  }

  private static Map<Bytes, Bytes32> randomEntries(final Random rng) {
    final Map<Bytes, Bytes32> entries = new HashMap<>();
    for (int i = 0; i < rng.nextInt(39) + 1; i++) {
      final Bytes key = Bytes.wrap(randomBytes(rng, 32));
      entries.put(key, Bytes32.wrap(randomBytes(rng, 32)));

      for (int j = 0; j < rng.nextInt(3); j++) {
        entries.put(
            Bytes.concatenate(key.slice(0, 31), Bytes.wrap(randomBytes(rng, 1))),
            Bytes32.wrap(randomBytes(rng, 32)));
      }

      for (final int prefixLength : List.of(1, 7, 30)) {
        if (rng.nextDouble() < 0.2) {
          entries.put(
              Bytes.concatenate(
                  key.slice(0, prefixLength),
                  Bytes.wrap(randomBytes(rng, 31 - prefixLength)),
                  Bytes.wrap(randomBytes(rng, 1))),
              Bytes32.wrap(randomBytes(rng, 32)));
        }
      }
    }
    return entries;
  }

  private static byte[] randomBytes(final Random rng, final int length) {
    final byte[] bytes = new byte[length];
    rng.nextBytes(bytes);
    return bytes;
  }

  /** Insertion-based reference implementation from the EIP-8297 test suite. */
  private static final class ReferenceRadixTree {
    private BinaryNode root;

    void insert(final Bytes key, final Bytes32 value) {
      if (root == null) {
        root = new LeafNode(key, value);
        return;
      }
      root = insertNode(root, bits(key.toArrayUnsafe()), key, value, 0);
    }

    Bytes32 merkelize() {
      if (root == null) {
        return TrieConstants.EMPTY_TRIE_ROOT;
      }
      return TrieHasher.merkleize(root);
    }

    private static BinaryNode insertNode(
        final BinaryNode node,
        final List<Integer> bitList,
        final Bytes key,
        final Bytes32 value,
        final int depth) {
      if (node instanceof final LeafNode leaf) {
        if (leaf.key().equals(key)) {
          return new LeafNode(key, value);
        }
        final List<Integer> otherBits = bits(leaf.key().toArrayUnsafe());
        int run = 0;
        while (true) {
          final int position = depth + run;
          if (position >= bitList.size() || position >= otherBits.size()) {
            throw new IllegalArgumentException("Key is a prefix of another key");
          }
          if (!bitList.get(position).equals(otherBits.get(position))) {
            break;
          }
          run++;
        }
        final Bytes prefix = toBitBytes(bitList.subList(depth, depth + run));
        final LeafNode newLeaf = new LeafNode(key, value);
        if (bitList.get(depth + run) == 0) {
          return new BranchNode(prefix, newLeaf, leaf);
        }
        return new BranchNode(prefix, leaf, newLeaf);
      }

      final BranchNode branch = (BranchNode) node;
      int matched = 0;
      while (matched < branch.prefix().size()) {
        final int position = depth + matched;
        if (position >= bitList.size()
            || bitList.get(position) != BitUtils.bitAt(branch.prefix(), matched)) {
          break;
        }
        matched++;
      }
      if (matched == branch.prefix().size()) {
        final int split = depth + matched;
        if (bitList.get(split) == 0) {
          return new BranchNode(
              branch.prefix(),
              insertNode(branch.left(), bitList, key, value, split + 1),
              branch.right());
        }
        return new BranchNode(
            branch.prefix(),
            branch.left(),
            insertNode(branch.right(), bitList, key, value, split + 1));
      }
      final BranchNode survivor =
          new BranchNode(branch.prefix().slice(matched + 1), branch.left(), branch.right());
      final LeafNode newLeaf = new LeafNode(key, value);
      if (bitList.get(depth + matched) == 0) {
        return new BranchNode(branch.prefix().slice(0, matched), newLeaf, survivor);
      }
      return new BranchNode(branch.prefix().slice(0, matched), survivor, newLeaf);
    }

    private static Bytes toBitBytes(final List<Integer> bitList) {
      final byte[] bytes = new byte[bitList.size()];
      for (int i = 0; i < bitList.size(); i++) {
        bytes[i] = bitList.get(i).byteValue();
      }
      return Bytes.wrap(bytes);
    }
  }
}
