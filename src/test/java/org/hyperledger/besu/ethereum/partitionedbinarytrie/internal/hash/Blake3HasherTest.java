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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.internal.hash;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.internal.TrieConstants;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.bouncycastle.crypto.digests.Blake3Digest;
import org.junit.jupiter.api.Test;

/**
 * BLAKE3 hashing utilities used for trie node merkleization.
 *
 * <p>Layer: internal hash. Output is compared against BouncyCastle {@link Blake3Digest};
 * tag-prefixed and raw hash methods must return independent array copies.
 */
class Blake3HasherTest {

  private static Bytes32 referenceBlake3(final Bytes data) {
    final Blake3Digest digest = new Blake3Digest(256);
    digest.update(data.toArrayUnsafe(), 0, data.size());
    final byte[] out = new byte[32];
    digest.doFinal(out, 0);
    return Bytes32.wrap(out);
  }

  @Test
  void hashBytesMatchesBouncyCastle() {
    final Bytes data = Bytes.fromHexString("0x0102030405");
    assertThat(Blake3Hasher.hashBytes(data)).isEqualTo(referenceBlake3(data));
  }

  @Test
  void sequentialHashesReturnIndependentArrays() {
    final byte[] key = Bytes.fromHexString("0xaaaa").toArrayUnsafe();
    final byte[] value = Bytes32.repeat((byte) 1).toArrayUnsafe();
    final byte[] h1 =
        Blake3Hasher.hash(TrieConstants.LEAF_NODE_TAG, key, 0, key.length, value, 0, 32);
    final byte[] h2 =
        Blake3Hasher.hash(TrieConstants.LEAF_NODE_TAG, key, 0, key.length, value, 0, 32);
    assertThat(h1).isEqualTo(h2);
    assertThat(h1).isNotSameAs(h2);

    final byte[] branch =
        Blake3Hasher.hash(
            TrieConstants.BRANCH_NODE_TAG, new byte[] {0, 3}, 0, 2, h1, 0, 32, h2, 0, 32);
    assertThat(branch).isNotEqualTo(h1);
    assertThat(h1).isEqualTo(h2);
  }

  @Test
  void hashRawReturnsOwnedCopy() {
    final byte[] data = Bytes.fromHexString("0xdead").toArrayUnsafe();
    final byte[] h1 = Blake3Hasher.hashRaw(data, 0, data.length);
    final byte[] h2 = Blake3Hasher.hashRaw(data, 0, data.length);
    h1[0] = (byte) 0xFF;
    assertThat(h2[0]).isNotEqualTo((byte) 0xFF);
  }
}
