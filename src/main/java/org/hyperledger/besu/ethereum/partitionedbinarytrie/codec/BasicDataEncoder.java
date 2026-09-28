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

import org.hyperledger.besu.ethereum.partitionedbinarytrie.params.EmbeddingParameters;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * Packs account basic data (version, code size, nonce, balance) into a 32-byte leaf value per
 * EIP-8297.
 *
 * <p>Layout: {@code version (1) || reserved (3) || code_size (4) || nonce (8) || balance (16)}.
 */
public final class BasicDataEncoder {

  private BasicDataEncoder() {}

  /**
   * Decoded EIP-8297 basic-data leaf fields.
   *
   * @param version account basic-data version byte
   * @param reserved three reserved bytes following the version
   * @param codeSize contract bytecode length in bytes
   * @param nonce account transaction count
   * @param balance account balance (16-byte field as {@link UInt256})
   */
  public record BasicData(
      int version, Bytes reserved, long codeSize, long nonce, UInt256 balance) {}

  /**
   * Encodes account basic data for storage at the basic-data header leaf.
   *
   * @param codeSize contract bytecode length in bytes
   * @param nonce account transaction count
   * @param balance account balance (must fit in 16 bytes)
   * @return 32-byte encoded basic data leaf value
   */
  public static Bytes32 encodeBasicData(
      final long codeSize, final long nonce, final UInt256 balance) {
    final byte[] result = new byte[32];
    result[0] = (byte) EmbeddingParameters.BASIC_DATA_VERSION;
    // bytes 1-3 reserved
    result[4] = (byte) (codeSize >> 24);
    result[5] = (byte) (codeSize >> 16);
    result[6] = (byte) (codeSize >> 8);
    result[7] = (byte) codeSize;
    result[8] = (byte) (nonce >> 56);
    result[9] = (byte) (nonce >> 48);
    result[10] = (byte) (nonce >> 40);
    result[11] = (byte) (nonce >> 32);
    result[12] = (byte) (nonce >> 24);
    result[13] = (byte) (nonce >> 16);
    result[14] = (byte) (nonce >> 8);
    result[15] = (byte) nonce;
    final byte[] balanceBytes = balance.toArray();
    for (int i = 0; i < 16; i++) {
      final int balanceIndex = balanceBytes.length - 16 + i;
      result[16 + i] = balanceIndex >= 0 ? balanceBytes[balanceIndex] : 0;
    }
    return Bytes32.wrap(result);
  }

  /**
   * Decodes a 32-byte basic-data leaf value.
   *
   * <p>Rejects non-canonical encodings that do not round-trip through {@link #encodeBasicData}
   * (wrong version, non-zero reserved bytes, or truncated high bits).
   *
   * @param encoded 32-byte leaf value
   * @return decoded fields
   * @throws IllegalArgumentException if {@code encoded} is not a canonical basic-data leaf
   */
  public static BasicData decodeBasicData(final Bytes32 encoded) {
    final int version = encoded.get(0) & 0xFF;
    final Bytes reserved = encoded.slice(1, 3);
    final long codeSize =
        ((long) (encoded.get(4) & 0xFF) << 24)
            | ((long) (encoded.get(5) & 0xFF) << 16)
            | ((long) (encoded.get(6) & 0xFF) << 8)
            | ((long) (encoded.get(7) & 0xFF));
    long nonce = 0L;
    for (int i = 8; i < 16; i++) {
      nonce = (nonce << 8) | (encoded.get(i) & 0xFF);
    }
    final UInt256 balance = UInt256.fromBytes(encoded.slice(16, 16));
    final Bytes32 roundTrip = encodeBasicData(codeSize, nonce, balance);
    if (!roundTrip.equals(encoded)) {
      throw new IllegalArgumentException(
          "basic-data leaf does not match BasicDataEncoder layout");
    }
    return new BasicData(version, reserved, codeSize, nonce, balance);
  }
}
