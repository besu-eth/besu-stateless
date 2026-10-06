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

import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;

class CodeRefCountEncoderTest {

  @Test
  void chunkCountThenReferenceCountOnSixteenBytesEach() {
    final Bytes32 encoded = CodeRefCountEncoder.encode(3, 700);

    assertThat(encoded)
        .isEqualTo(Bytes32.fromHexString("0x" + "00".repeat(14) + "02bc" + "00".repeat(15) + "03"));
    assertThat(CodeRefCountEncoder.refCount(encoded)).isEqualTo(3);
    assertThat(CodeRefCountEncoder.chunkCount(encoded)).isEqualTo(700);
  }

  @Test
  void rejectsNegativeCounts() {
    assertThatThrownBy(() -> CodeRefCountEncoder.encode(-1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CodeRefCountEncoder.encode(1, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
