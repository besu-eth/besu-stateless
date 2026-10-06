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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.internal.hash;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;

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
  void matchesTheOfficialVectorForEmptyInput() {
    assertThat(Bytes.wrap(Blake3Hasher.hashRaw(new byte[0], 0, 0)))
        .isEqualTo(
            Bytes.fromHexString(
                "0xaf1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262"));
  }

  @Test
  void matchesBouncyCastleForEveryLengthAcrossChunks() {
    final Random random = new Random(3);
    for (int len = 0; len <= 2048; len++) {
      final byte[] data = randomBytes(random, len);
      assertThat(Bytes32.wrap(Blake3Hasher.hashRaw(data, 0, len)))
          .as("length %d", len)
          .isEqualTo(referenceBlake3(Bytes.wrap(data)));
      assertThat(Blake3Hasher.hashBytes(Bytes.wrap(data)))
          .isEqualTo(referenceBlake3(Bytes.wrap(data)));
    }
  }

  @Test
  void matchesBouncyCastleForLongInputs() {
    final Random random = new Random(5);
    for (final int len : new int[] {4096, 10_000}) {
      final byte[] data = randomBytes(random, len);
      assertThat(Bytes32.wrap(Blake3Hasher.hashRaw(data, 0, len)))
          .as("length %d", len)
          .isEqualTo(referenceBlake3(Bytes.wrap(data)));
    }
  }

  @Test
  void taggedOverloadsHashTheTagThenEachSlice() {
    final Random random = new Random(7);
    // Distinct slices taken at an offset, the last one past a chunk boundary.
    final byte[] a = randomBytes(random, 40);
    final byte[] b = randomBytes(random, 40);
    final byte[] c = randomBytes(random, 1100);

    assertThat(Bytes32.wrap(Blake3Hasher.hash((byte) 7, a, 3, 34, b, 5, 32)))
        .isEqualTo(
            referenceBlake3(
                Bytes.concatenate(Bytes.of(7), Bytes.wrap(a, 3, 34), Bytes.wrap(b, 5, 32))));
    assertThat(Bytes32.wrap(Blake3Hasher.hash((byte) 9, a, 1, 2, b, 0, 32, c, 50, 1050)))
        .isEqualTo(
            referenceBlake3(
                Bytes.concatenate(
                    Bytes.of(9),
                    Bytes.wrap(a, 1, 2),
                    Bytes.wrap(b, 0, 32),
                    Bytes.wrap(c, 50, 1050))));
  }

  @Test
  void hashesAreNewArrays() {
    final byte[] data = Bytes.fromHexString("0xdead").toArrayUnsafe();
    final byte[] h1 = Blake3Hasher.hashRaw(data, 0, data.length);
    final byte[] h2 = Blake3Hasher.hashRaw(data, 0, data.length);
    h1[0] ^= (byte) 0xFF;
    assertThat(h2).isEqualTo(Blake3Hasher.hashRaw(data, 0, data.length));
    assertThat(Blake3Hasher.hash((byte) 0, data, 0, 2, data, 0, 2))
        .isNotSameAs(Blake3Hasher.hash((byte) 0, data, 0, 2, data, 0, 2));
  }

  private static byte[] randomBytes(final Random random, final int length) {
    final byte[] bytes = new byte[length];
    random.nextBytes(bytes);
    return bytes;
  }
}
