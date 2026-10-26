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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;

import org.bouncycastle.crypto.digests.Blake3Digest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link Blake3SingleChunk} against the official BLAKE3 vectors and BouncyCastle {@link
 * Blake3Digest}, fed whole, byte by byte and in parts. One instance is reused for every hash of a
 * test, so each hash also checks the reset done by the previous one.
 */
class Blake3SingleChunkTest {

  private static final int MAX = Blake3SingleChunk.MAX_INPUT;
  private static final int FEEDINGS = 5;

  private final Blake3SingleChunk digest = new Blake3SingleChunk();

  /** Official vectors (BLAKE3 repository, test_vectors.json) up to one chunk. */
  @ParameterizedTest
  @CsvSource({
    "0, af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262",
    "1, 2d3adedff11b61f14c886e35afa036736dcd87a74d27b5c1510225d0f592e213",
    "2, 7b7015bb92cf0b318037702a6cdd81dee41224f734684c2c122cd6359cb1ee63",
    "3, e1be4d7a8ab5560aa4199eea339849ba8e293d55ca0a81006726d184519e647f",
    "4, f30f5ab28fe047904037f77b6da4fea1e27241c5d132638d8bedce9d40494f32",
    "5, b40b44dfd97e7a84a996a91af8b85188c66c126940ba7aad2e7ae6b385402aa2",
    "6, 06c4e8ffb6872fad96f9aaca5eee1553eb62aed0ad7198cef42e87f6a616c844",
    "7, 3f8770f387faad08faa9d8414e9f449ac68e6ff0417f673f602a646a891419fe",
    "8, 2351207d04fc16ade43ccab08600939c7c1fa70a5c0aaca76063d04c3228eaeb",
    "63, e9bc37a594daad83be9470df7f7b3798297c3d834ce80ba85d6e207627b7db7b",
    "64, 4eed7141ea4a5cd4b788606bd23f46e212af9cacebacdc7d1f4c6dc7f2511b98",
    "65, de1e5fa0be70df6d2be8fffd0e99ceaa8eb6e8c93a63f2d8d1c30ecb6b263dee",
    "127, d81293fda863f008c09e92fc382a81f5a0b4a1251cba1634016a0f86a6bd640d",
    "128, f17e570564b26578c33bb7f44643f539624b05df1a76c81f30acd548c44b45ef",
    "129, 683aaae9f3c5ba37eaaf072aed0f9e30bac0865137bae68b1fde4ca2aebdcb12",
    "1023, 10108970eeda3eb932baac1428c7a2163b0e924c9a9e25b35bba72b28f70bd11",
    "1024, 42214739f095a406f3fc83deb889744ac00df831c10daa55189b5d121c855af7"
  })
  void matchesTheOfficialVectors(final int length, final String expected) {
    // The vector inputs repeat 0, 1, ..., 250.
    final byte[] input = new byte[length];
    for (int i = 0; i < length; i++) {
      input[i] = (byte) (i % 251);
    }
    final Random random = new Random(length);
    for (int feeding = 0; feeding < FEEDINGS; feeding++) {
      assertThat(hash(input, feeding, random))
          .as("feeding %d", feeding)
          .isEqualTo(HexFormat.of().parseHex(expected));
    }
  }

  @Test
  void matchesBouncyCastleForEveryLengthAndFeeding() {
    final Random random = new Random(1);
    for (int length = 0; length <= MAX; length++) {
      for (int sample = 0; sample < 3; sample++) {
        final byte[] input = randomBytes(random, length);
        final byte[] expected = reference(input);
        for (int feeding = 0; feeding < FEEDINGS; feeding++) {
          assertThat(hash(input, feeding, random))
              .as("length %d, feeding %d", length, feeding)
              .isEqualTo(expected);
        }
      }
    }
  }

  @Test
  void matchesBouncyCastleWhenCutAtEveryPointAroundBlockBoundaries() {
    final Random random = new Random(2);
    for (int boundary = 0; boundary <= MAX; boundary += 64) {
      for (int length = Math.max(0, boundary - 1);
          length <= Math.min(MAX, boundary + 1);
          length++) {
        final byte[] input = randomBytes(random, length);
        final byte[] expected = reference(input);
        for (int cut = 0; cut <= length; cut++) {
          digest.update(input, 0, cut);
          digest.update(input, cut, length - cut);
          assertThat(finish()).as("length %d, cut %d", length, cut).isEqualTo(expected);

          for (int i = 0; i < cut; i++) {
            digest.update(input[i]);
          }
          digest.update(input, cut, length - cut);
          assertThat(finish())
              .as("length %d, bytes then array at %d", length, cut)
              .isEqualTo(expected);
        }
      }
    }
  }

  @Test
  void writesExactlyThe32BytesAtTheOffset() {
    final byte[] input = randomBytes(new Random(3), 99);
    for (final int offset : new int[] {0, 1, 3, 17, 48}) {
      final byte[] out = new byte[80];
      Arrays.fill(out, (byte) 0x5A);
      digest.update(input, 0, input.length);

      assertThat(digest.doFinal(out, offset)).isEqualTo(32);
      assertThat(Arrays.copyOfRange(out, offset, offset + 32)).isEqualTo(reference(input));
      for (int i = 0; i < out.length; i++) {
        if (i < offset || i >= offset + 32) {
          assertThat(out[i]).as("byte %d, offset %d", i, offset).isEqualTo((byte) 0x5A);
        }
      }
    }
  }

  @Test
  void refusesMoreThanOneChunkAndWorksAgainAfterReset() {
    final byte[] input = randomBytes(new Random(4), MAX + 1);
    final byte[] abc = {'a', 'b', 'c'};

    assertThatThrownBy(() -> digest.update(input, 0, MAX + 1))
        .isInstanceOf(IllegalStateException.class);
    digest.reset();
    digest.update(input, 0, MAX);
    assertThatThrownBy(() -> digest.update(input[MAX])).isInstanceOf(IllegalStateException.class);
    digest.reset();
    digest.update(input, 0, 512);
    assertThatThrownBy(() -> digest.update(input, 512, 513))
        .isInstanceOf(IllegalStateException.class);
    digest.reset();

    digest.update(abc, 0, abc.length);
    assertThat(finish()).isEqualTo(reference(abc));
    // Exactly one chunk, then an empty update, is within the limit.
    digest.update(input, 0, MAX);
    digest.update(input, 0, 0);
    assertThat(finish()).isEqualTo(reference(Arrays.copyOf(input, MAX)));
  }

  /** Hashes {@code input} with {@link #digest}, fed in one of {@link #FEEDINGS} ways. */
  private byte[] hash(final byte[] input, final int feeding, final Random random) {
    switch (feeding) {
      case 0 -> digest.update(input, 0, input.length);
      case 1 -> {
        for (final byte b : input) {
          digest.update(b);
        }
      }
      case 2 -> {
        // Random parts, empty ones included, single bytes sometimes through update(byte).
        int off = 0;
        while (off < input.length) {
          final int n = random.nextInt(Math.min(input.length - off, 130) + 1);
          if (n == 1 && random.nextBoolean()) {
            digest.update(input[off]);
          } else {
            digest.update(input, off, n);
          }
          off += n;
        }
      }
      case 3 -> {
        final int step = random.nextBoolean() ? 64 : 32;
        for (int off = 0; off < input.length; off += step) {
          digest.update(input, off, Math.min(step, input.length - off));
        }
      }
      case 4 -> {
        // From a larger buffer, at an offset.
        final int shift = 1 + random.nextInt(17);
        final byte[] buffer = new byte[input.length + shift + 7];
        System.arraycopy(input, 0, buffer, shift, input.length);
        int off = 0;
        while (off < input.length) {
          final int n = 1 + random.nextInt(input.length - off);
          digest.update(buffer, shift + off, n);
          off += n;
        }
      }
      default -> throw new IllegalArgumentException("feeding " + feeding);
    }
    return finish();
  }

  private byte[] finish() {
    final byte[] out = new byte[32];
    digest.doFinal(out, 0);
    return out;
  }

  private static byte[] reference(final byte[] input) {
    final Blake3Digest reference = new Blake3Digest(256);
    reference.update(input, 0, input.length);
    final byte[] out = new byte[32];
    reference.doFinal(out, 0);
    return out;
  }

  private static byte[] randomBytes(final Random random, final int length) {
    final byte[] bytes = new byte[length];
    random.nextBytes(bytes);
    return bytes;
  }
}
