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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.embedding.keys;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.embedding.params.Eip8297EmbeddingParameters;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.internal.hash.Blake3Hasher;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * Key derivation for the EIP-8297 state embedding.
 *
 * <p>Maps Ethereum addresses, storage slots, and code chunks to variable-length trie keys using
 * zone prefixes and BLAKE3 tree positions.
 */
public final class Eip8297TreeKeyDerivation {

  /** Keccak-256 hash of empty bytecode, used as the code hash for accounts without code. */
  public static final Bytes32 EMPTY_CODE_HASH =
      Bytes32.fromHexString("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470");

  private Eip8297TreeKeyDerivation() {}

  /**
   * Left-pads a 20-byte address to 32 bytes.
   *
   * @param address 20-byte Ethereum address
   * @return 32-byte padded address
   */
  public static Bytes32 address20ToAddress32(final Bytes address) {
    if (address.size() != 20) {
      throw new IllegalArgumentException("Address must be 20 bytes");
    }
    return Bytes32.leftPad(address);
  }

  /**
   * Computes a BLAKE3 hash used as a tree position component.
   *
   * @param data input bytes
   * @return 32-byte hash
   */
  public static Bytes32 keyHash(final Bytes data) {
    return Blake3Hasher.hashBytes(data);
  }

  /**
   * Builds a raw tree key: {@code zone (1) | treePosition | subIndex (1)}.
   *
   * @param zone zone identifier ({@link Eip8297EmbeddingParameters#ACCOUNT_ZONE}, etc.)
   * @param treePosition hashed tree position bytes
   * @param subIndex leaf sub-index within the stem (0–255)
   * @return concatenated key bytes
   */
  public static Bytes getTreeKey(final int zone, final Bytes treePosition, final int subIndex) {
    if (subIndex < 0 || subIndex > 255) {
      throw new IllegalArgumentException("Sub-index must fit in one byte");
    }
    return Bytes.concatenate(Bytes.of((byte) zone), treePosition, Bytes.of((byte) subIndex));
  }

  /**
   * Builds an account-header tree key for {@code address} at {@code subIndex}.
   *
   * @param address 32-byte account address
   * @param subIndex header leaf sub-index
   * @return 34-byte account key
   */
  public static Bytes getTreeKeyForHeader(final Bytes32 address, final int subIndex) {
    final Bytes key =
        getTreeKey(Eip8297EmbeddingParameters.ACCOUNT_ZONE, keyHash(address), subIndex);
    if (key.size() != Eip8297EmbeddingParameters.ACCOUNT_KEY_LENGTH) {
      throw new IllegalStateException("Unexpected account key length: " + key.size());
    }
    return key;
  }

  /**
   * Returns the tree key for an account's basic-data leaf.
   *
   * @param address 32-byte account address
   * @return basic-data tree key
   */
  public static Bytes getTreeKeyForBasicData(final Bytes32 address) {
    return getTreeKeyForHeader(address, Eip8297EmbeddingParameters.BASIC_DATA_LEAF_KEY);
  }

  /**
   * Returns the tree key for an account's code-hash leaf.
   *
   * @param address 32-byte account address
   * @return code-hash tree key
   */
  public static Bytes getTreeKeyForCodeHash(final Bytes32 address) {
    return getTreeKeyForHeader(address, Eip8297EmbeddingParameters.CODE_HASH_LEAF_KEY);
  }

  /**
   * Computes the storage zone tree position for a large storage index.
   *
   * @param address 32-byte account address
   * @param treeIndex storage stem index
   * @return 64-byte tree position
   */
  public static Bytes storageTreePosition(final Bytes32 address, final UInt256 treeIndex) {
    final Bytes prefix = keyHash(address);
    final Bytes suffix = keyHash(Bytes.concatenate(address, Bytes32.leftPad(treeIndex)));
    return Bytes.concatenate(prefix, suffix);
  }

  /**
   * Returns the tree key for a storage slot.
   *
   * <p>Slots below {@link Eip8297EmbeddingParameters#CODE_OFFSET} map into the account header stem;
   * larger slots use the storage zone with stem grouping.
   *
   * @param address 32-byte account address
   * @param storageKey storage slot index
   * @return storage tree key (34 or 66 bytes depending on zone)
   */
  public static Bytes getTreeKeyForStorageSlot(final Bytes32 address, final UInt256 storageKey) {
    if (storageKey.compareTo(
            UInt256.valueOf(
                Eip8297EmbeddingParameters.CODE_OFFSET
                    - Eip8297EmbeddingParameters.HEADER_STORAGE_OFFSET))
        < 0) {
      return getTreeKeyForHeader(
          address, Eip8297EmbeddingParameters.HEADER_STORAGE_OFFSET + storageKey.intValue());
    }
    final UInt256 treeIndex =
        storageKey.divide(UInt256.valueOf(Eip8297EmbeddingParameters.STEM_SUBTREE_WIDTH));
    final int subIndex =
        storageKey.mod(UInt256.valueOf(Eip8297EmbeddingParameters.STEM_SUBTREE_WIDTH)).intValue();
    final Bytes key =
        getTreeKey(
            Eip8297EmbeddingParameters.STORAGE_ZONE,
            storageTreePosition(address, treeIndex),
            subIndex);
    if (key.size() != Eip8297EmbeddingParameters.STORAGE_KEY_LENGTH) {
      throw new IllegalStateException("Unexpected storage key length: " + key.size());
    }
    return key;
  }

  /**
   * Returns the tree key for a contract code chunk.
   *
   * <p>Early chunks live in the account header stem; overflow chunks use the code zone.
   *
   * @param address 32-byte account address
   * @param codeHash 32-byte code hash
   * @param chunkId zero-based chunk index
   * @return code chunk tree key
   */
  public static Bytes getTreeKeyForCodeChunk(
      final Bytes32 address, final Bytes32 codeHash, final int chunkId) {
    if (chunkId
        < Eip8297EmbeddingParameters.STEM_SUBTREE_WIDTH - Eip8297EmbeddingParameters.CODE_OFFSET) {
      return getTreeKeyForHeader(address, Eip8297EmbeddingParameters.CODE_OFFSET + chunkId);
    }
    final int overflow =
        chunkId
            - (Eip8297EmbeddingParameters.STEM_SUBTREE_WIDTH
                - Eip8297EmbeddingParameters.CODE_OFFSET);
    final int treeIndex = overflow / Eip8297EmbeddingParameters.STEM_SUBTREE_WIDTH;
    final int subIndex = overflow % Eip8297EmbeddingParameters.STEM_SUBTREE_WIDTH;
    final Bytes key =
        getTreeKey(
            Eip8297EmbeddingParameters.CODE_ZONE,
            keyHash(Bytes.concatenate(codeHash, Bytes32.leftPad(UInt256.valueOf(treeIndex)))),
            subIndex);
    if (key.size() != Eip8297EmbeddingParameters.CODE_KEY_LENGTH) {
      throw new IllegalStateException("Unexpected code key length: " + key.size());
    }
    return key;
  }
}
