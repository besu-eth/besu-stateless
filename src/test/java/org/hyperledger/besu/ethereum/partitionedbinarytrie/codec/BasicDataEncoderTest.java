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

import org.hyperledger.besu.ethereum.partitionedbinarytrie.params.EmbeddingParameters;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.Test;

class BasicDataEncoderTest {

  @Test
  void encodesEachFieldAtItsOffset() {
    final long codeSize = 0x11223344L;
    final long nonce = 0x5566778899aabbccL;
    final UInt256 balance = UInt256.fromHexString("0123456789abcdef0123456789abcdef");

    final Bytes32 encoded = BasicDataEncoder.encodeBasicData(codeSize, nonce, balance);

    // Version, three reserved bytes, code size (4), nonce (8), balance (16).
    assertThat(encoded)
        .isEqualTo(
            Bytes32.fromHexString(
                "0x00000000112233445566778899aabbcc0123456789abcdef0123456789abcdef"));
    final BasicDataEncoder.BasicData decoded = BasicDataEncoder.decodeBasicData(encoded);
    assertThat(decoded.version()).isEqualTo(EmbeddingParameters.BASIC_DATA_VERSION);
    assertThat(decoded.reserved()).isEqualTo(Bytes.repeat((byte) 0, 3));
    assertThat(decoded.codeSize()).isEqualTo(codeSize);
    assertThat(decoded.nonce()).isEqualTo(nonce);
    assertThat(decoded.balance()).isEqualTo(balance);
  }

  @Test
  void largestFieldsRoundTrip() {
    // Every byte has its top bit set, which decoding must read as unsigned.
    final UInt256 balance = UInt256.ONE.shiftLeft(128).subtract(UInt256.ONE);
    final Bytes32 encoded = BasicDataEncoder.encodeBasicData(0xFFFFFFFFL, -1L, balance);

    assertThat(encoded).isEqualTo(Bytes32.fromHexString("0x00000000" + "ff".repeat(28)));
    final BasicDataEncoder.BasicData decoded = BasicDataEncoder.decodeBasicData(encoded);
    assertThat(decoded.codeSize()).isEqualTo(0xFFFFFFFFL);
    assertThat(decoded.nonce()).isEqualTo(-1L);
    assertThat(decoded.balance()).isEqualTo(balance);
  }

  @Test
  void emptyAccountIsAllZero() {
    final Bytes32 encoded = BasicDataEncoder.encodeBasicData(0, 0, UInt256.ZERO);

    assertThat(encoded).isEqualTo(Bytes32.ZERO);
    final BasicDataEncoder.BasicData decoded = BasicDataEncoder.decodeBasicData(encoded);
    assertThat(decoded.codeSize()).isZero();
    assertThat(decoded.nonce()).isZero();
    assertThat(decoded.balance()).isEqualTo(UInt256.ZERO);
  }

  @Test
  void decodeRejectsNonZeroReservedOrUnexpectedVersion() {
    for (final int index : new int[] {0, 2}) {
      final MutableBytes32 mangled =
          BasicDataEncoder.encodeBasicData(1, 2, UInt256.ONE).mutableCopy();
      mangled.set(index, (byte) 0x01);

      assertThatThrownBy(() -> BasicDataEncoder.decodeBasicData(mangled))
          .as("byte %d", index)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("basic-data leaf does not match BasicDataEncoder layout");
    }
  }
}
