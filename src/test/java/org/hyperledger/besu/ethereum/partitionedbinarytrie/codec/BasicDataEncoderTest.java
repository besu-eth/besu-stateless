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
  void decodeRoundTripsCanonicalEncoding() {
    final long codeSize = 0x11223344L;
    final long nonce = 0x5566778899aabbccl;
    final UInt256 balance = UInt256.fromHexString("0123456789abcdef0123456789abcdef");

    final Bytes32 encoded = BasicDataEncoder.encodeBasicData(codeSize, nonce, balance);
    final BasicDataEncoder.BasicData decoded = BasicDataEncoder.decodeBasicData(encoded);

    assertThat(decoded.version()).isEqualTo(EmbeddingParameters.BASIC_DATA_VERSION);
    assertThat(decoded.reserved()).isEqualTo(Bytes.repeat((byte) 0, 3));
    assertThat(decoded.codeSize()).isEqualTo(codeSize);
    assertThat(decoded.nonce()).isEqualTo(nonce);
    assertThat(decoded.balance()).isEqualTo(balance);
    assertThat(
            BasicDataEncoder.encodeBasicData(
                decoded.codeSize(), decoded.nonce(), decoded.balance()))
        .isEqualTo(encoded);
  }

  @Test
  void decodeEmptyAccount() {
    final Bytes32 encoded = BasicDataEncoder.encodeBasicData(0, 0, UInt256.ZERO);
    final BasicDataEncoder.BasicData decoded = BasicDataEncoder.decodeBasicData(encoded);

    assertThat(decoded.version()).isEqualTo(EmbeddingParameters.BASIC_DATA_VERSION);
    assertThat(decoded.reserved()).isEqualTo(Bytes.repeat((byte) 0, 3));
    assertThat(decoded.codeSize()).isZero();
    assertThat(decoded.nonce()).isZero();
    assertThat(decoded.balance()).isEqualTo(UInt256.ZERO);
  }

  @Test
  void decodeRejectsNonZeroReserved() {
    final MutableBytes32 mangled =
        MutableBytes32.wrap(BasicDataEncoder.encodeBasicData(1, 2, UInt256.ONE).mutableCopy());
    mangled.set(2, (byte) 0x01);

    assertThatThrownBy(() -> BasicDataEncoder.decodeBasicData(mangled))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("basic-data leaf does not match BasicDataEncoder layout");
  }

  @Test
  void decodeRejectsUnexpectedVersion() {
    final MutableBytes32 mangled =
        MutableBytes32.wrap(BasicDataEncoder.encodeBasicData(1, 2, UInt256.ONE).mutableCopy());
    mangled.set(0, (byte) 0x01);

    assertThatThrownBy(() -> BasicDataEncoder.decodeBasicData(mangled))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("basic-data leaf does not match BasicDataEncoder layout");
  }
}
