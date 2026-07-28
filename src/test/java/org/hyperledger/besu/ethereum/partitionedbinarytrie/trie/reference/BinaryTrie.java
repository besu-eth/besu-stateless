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

import org.hyperledger.besu.ethereum.partitionedbinarytrie.internal.TrieConstants;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.hash.TrieHasher;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.node.Binarizer;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Mapping of variable-length keys to 32-byte values with a single root hash.
 *
 * <p>This implementation stores key/value pairs and rebuilds the canonical node structure on every
 * root computation, matching the EIP-8297 reference specification. For incremental updates, use
 * {@link MutableBinaryTrie}.
 */
public final class BinaryTrie {

  private final Map<Bytes, Bytes32> data;

  /** Creates an empty trie. */
  public BinaryTrie() {
    this(new HashMap<>());
  }

  private BinaryTrie(final Map<Bytes, Bytes32> data) {
    this.data = data;
  }

  /**
   * Returns a shallow copy of {@code trie} with an independent entry map.
   *
   * @param trie trie to copy
   * @return a new trie containing the same entries
   */
  public static BinaryTrie copyOf(final BinaryTrie trie) {
    return new BinaryTrie(new HashMap<>(trie.data));
  }

  /**
   * Looks up a value by key.
   *
   * @param key variable-length key (1–{@link TrieConstants#MAX_KEY_LENGTH} bytes)
   * @return the 32-byte value, or empty if absent
   */
  public Optional<Bytes32> get(final Bytes key) {
    return Optional.ofNullable(data.get(key));
  }

  /**
   * Inserts or replaces a key-value pair.
   *
   * @param key variable-length key (1–{@link TrieConstants#MAX_KEY_LENGTH} bytes)
   * @param value 32-byte value
   */
  public void put(final Bytes key, final Bytes32 value) {
    validateKey(key);
    validateValue(value);
    data.put(key, value);
  }

  /**
   * Removes a key from the trie.
   *
   * <p>Per PBT semantics, absence means the key is not present in the trie — not a zero-valued
   * leaf. See {@code zeroValueIsNotAbsence} in {@link BinaryTrieConformanceTest}.
   *
   * @param key key to remove
   */
  public void remove(final Bytes key) {
    validateKey(key);
    data.remove(key);
  }

  /**
   * Computes the BLAKE3 merkle root over the canonical trie structure for the current entries.
   *
   * @return root hash, or {@link TrieConstants#EMPTY_TRIE_ROOT} when empty
   */
  public Bytes32 root() {
    if (data.isEmpty()) {
      return TrieConstants.EMPTY_TRIE_ROOT;
    }
    return TrieHasher.merkleize(Binarizer.binarize(data, 0));
  }

  /** Returns {@code true} if the trie contains no entries. */
  public boolean isEmpty() {
    return data.isEmpty();
  }

  /** Returns the number of key-value pairs stored. */
  public int size() {
    return data.size();
  }

  private static void validateKey(final Bytes key) {
    if (key.isEmpty()) {
      throw new IllegalArgumentException("Key must not be empty");
    }
    if (key.size() > TrieConstants.MAX_KEY_LENGTH) {
      throw new IllegalArgumentException("Key exceeds maximum length");
    }
  }

  private static void validateValue(final Bytes32 value) {
    if (value.size() != TrieConstants.VALUE_LENGTH) {
      throw new IllegalArgumentException("Value must be 32 bytes");
    }
  }
}
