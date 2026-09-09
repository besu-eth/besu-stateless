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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.keys;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.internal.bytes.ByteTrieOps;

/**
 * A trie lookup key in both byte and expanded-bit form.
 *
 * <p>The partitioned binary trie uses two views of the same key:
 *
 * <ul>
 *   <li>{@link #bytes()} / {@link #length()} — raw key bytes for leaf comparison, storage, and
 *       hashing
 *   <li>{@link #pathBits()} / {@link #bitCount()} — MSB-first navigation bits ({@code 0} or {@code
 *       1} per element) for branch traversal
 * </ul>
 *
 * <p>Create once per get/put/remove via {@link #of(byte[], int)} and pass through path visitors.
 */
public final class TrieKey {

  private final byte[] bytes;
  private final int length;
  private final byte[] pathBits;

  private TrieKey(final byte[] bytes, final int length, final byte[] pathBits) {
    this.bytes = bytes;
    this.length = length;
    this.pathBits = pathBits;
  }

  /**
   * Builds a key with both representations; expands path bits once via {@link
   * ByteTrieOps#expandKeyBitsCopy}.
   *
   * @param key key byte buffer
   * @param keyLen number of valid key bytes in {@code key}
   */
  public static TrieKey of(final byte[] key, final int keyLen) {
    return new TrieKey(key, keyLen, ByteTrieOps.expandKeyBitsCopy(key, keyLen));
  }

  /** Raw key bytes (may be longer than {@link #length()}). */
  public byte[] bytes() {
    return bytes;
  }

  /** Number of valid key bytes. */
  public int length() {
    return length;
  }

  /** Expanded navigation bits ({@code 0} or {@code 1} per element), length {@link #bitCount()}. */
  public byte[] pathBits() {
    return pathBits;
  }

  /** Number of navigation bits ({@code length() * 8}). */
  public int bitCount() {
    return length * 8;
  }

  /** Returns the navigation bit at {@code index} ({@code 0} or {@code 1}). */
  public byte bitAt(final int index) {
    return pathBits[index];
  }
}
