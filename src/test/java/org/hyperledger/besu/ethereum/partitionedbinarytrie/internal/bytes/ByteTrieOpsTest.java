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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.internal.bytes;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.hash.BitUtils;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;

/**
 * Low-level byte trie operations: key bit expansion, equality, and domain-tagged hashes.
 *
 * <p>Layer: internal bytes. {@link BitUtils} is the oracle for bit expansion; leaf and branch hash
 * helpers must be stable and return owned copies.
 */
class ByteTrieOpsTest {

  @Test
  void bitAtMatchesBitUtils() {
    final Bytes key = Bytes.fromHexString("0xa5f0");
    final byte[] keyBytes = key.toArrayUnsafe();
    final Bytes specBits = BitUtils.bytesToBitList(key);
    for (int i = 0; i < specBits.size(); i++) {
      assertThat(ByteTrieOps.bitAt(keyBytes, i)).isEqualTo((byte) BitUtils.bitAt(specBits, i));
    }
  }

  @Test
  void expandBitsReturnsTheRequestedRange() {
    final byte[] key = Bytes.fromHexString("0xa5f0").toArrayUnsafe(); // 1010 0101 1111 0000
    assertThat(ByteTrieOps.expandBits(key, 2, 9)).containsExactly(1, 0, 0, 1, 0, 1, 1);
    assertThat(ByteTrieOps.expandBits(key, 5, 5)).isEmpty();
  }

  @Test
  void keysEqualComparesLengthsAndBytes() {
    final byte[] a = Bytes.fromHexString("0x0102").toArrayUnsafe();
    final byte[] b = Bytes.fromHexString("0x0102").toArrayUnsafe();
    final byte[] c = Bytes.fromHexString("0x0103").toArrayUnsafe();
    assertThat(ByteTrieOps.keysEqual(a, 2, b, 2)).isTrue();
    assertThat(ByteTrieOps.keysEqual(a, 2, c, 2)).isFalse();
    assertThat(ByteTrieOps.keysEqual(a, 1, b, 2)).isFalse();
  }

  @Test
  void leafAndBranchHashesAreStable() {
    final byte[] key = Bytes.fromHexString("0x42").toArrayUnsafe();
    final byte[] value = Bytes32.repeat((byte) 0x11).toArrayUnsafe();
    final byte[] h1 = ByteTrieOps.leafHash(key, 1, value);
    final byte[] h2 = ByteTrieOps.leafHash(key, 1, value);
    assertThat(h1).isEqualTo(h2);
    assertThat(h1).isNotSameAs(h2);

    final byte[] prefix = new byte[] {1, 0, 1};
    final byte[] bh1 = ByteTrieOps.branchHash(prefix, 3, h1, h2);
    final byte[] bh2 = ByteTrieOps.branchHash(prefix, 3, h1, h2);
    assertThat(bh1).isEqualTo(bh2);
    assertThat(bh1).isNotSameAs(h1);
  }
}
