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

import java.util.ArrayList;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Splits contract bytecode into 31-byte payload chunks with a leading push-data metadata byte per
 * EIP-8297 code embedding rules.
 */
public final class CodeChunkifier {

  private CodeChunkifier() {}

  /**
   * Chunks bytecode for storage in the trie.
   *
   * @param code contract bytecode (may be empty)
   * @return list of 32-byte chunk values; empty input yields an empty list
   */
  public static List<Bytes32> chunkifyCode(final Bytes code) {
    if (code.isEmpty()) {
      return List.of();
    }

    Bytes padded = code;
    if (code.size() % 31 != 0) {
      final int padAmount = 31 - (code.size() % 31);
      padded = Bytes.concatenate(code, Bytes.wrap(new byte[padAmount]));
    }

    // Track remaining PUSH immediate bytes at each position for the chunk header byte.
    final int[] remainingPushData = new int[padded.size() + 32];
    int position = 0;
    while (position < padded.size()) {
      final int opcode = padded.get(position) & 0xFF;
      final int pushDataBytes;
      if (opcode >= EmbeddingParameters.PUSH1 && opcode <= EmbeddingParameters.PUSH32) {
        pushDataBytes = opcode - EmbeddingParameters.PUSH_OFFSET;
      } else {
        pushDataBytes = 0;
      }
      position++;
      for (int offset = 0; offset < pushDataBytes; offset++) {
        remainingPushData[position + offset] = pushDataBytes - offset;
      }
      position += pushDataBytes;
    }

    final List<Bytes32> chunks = new ArrayList<>();
    for (int start = 0; start < padded.size(); start += 31) {
      final byte pushCount = (byte) Math.min(remainingPushData[start], 31);
      final byte[] chunk = new byte[32];
      chunk[0] = pushCount;
      final int copyLength = Math.min(31, padded.size() - start);
      for (int i = 0; i < copyLength; i++) {
        chunk[1 + i] = padded.get(start + i);
      }
      chunks.add(Bytes32.wrap(chunk));
    }
    return chunks;
  }
}
