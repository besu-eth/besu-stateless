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

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Encodes EIP-7702 delegation indicator values for the account header stem per EIP-8297.
 *
 * <p>Value layout: {@code 0xef0100 || target (20) || 0x00 * 9}.
 */
public final class DelegationEncoder {

  /** EIP-7702 delegation designator prefix ({@code 0xef0100}). */
  public static final Bytes DESIGNATOR = Bytes.fromHexString("ef0100");

  private DelegationEncoder() {}

  /**
   * Packs a 20-byte delegation target into the 32-byte header leaf value.
   *
   * @param target20 20-byte delegation target address
   * @return 32-byte leaf value ({@code designator || target || 9 zero bytes})
   */
  public static Bytes32 encodeDelegation(final Bytes target20) {
    if (target20.size() != 20) {
      throw new IllegalArgumentException("Delegation target must be 20 bytes");
    }
    return Bytes32.wrap(Bytes.concatenate(DESIGNATOR, target20, Bytes.repeat((byte) 0, 9)));
  }
}
