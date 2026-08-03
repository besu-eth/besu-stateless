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

import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * Packs account basic data (version, code size, nonce, balance) into a 32-byte leaf value per
 * EIP-8297.
 */
public final class BasicDataEncoder {

  private BasicDataEncoder() {}

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
    if (balance.compareTo(UInt256.valueOf(2).pow(128)) >= 0) {
      throw new IllegalArgumentException("Balance does not fit in 16 bytes");
    }
    if (codeSize < 0 || (codeSize >>> 32) != 0) {
      throw new IllegalArgumentException("Code size does not fit in 4 bytes");
    }
    if (nonce < 0) {
      throw new IllegalArgumentException("Nonce must be non-negative");
    }
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
}
