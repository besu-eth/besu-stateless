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

import java.util.Arrays;

import org.bouncycastle.crypto.Digest;

/**
 * BLAKE3 (hash mode, 32-byte output) for messages of at most one chunk ({@link #MAX_INPUT} bytes).
 *
 * <p>Every PBT hash input fits in one chunk, which is also the root: the message is split into
 * 64-byte blocks compressed in sequence, the first flagged {@code CHUNK_START}, the last {@code
 * CHUNK_END | ROOT}, with chunk counter 0. That skips the chunk tree and the allocations of a
 * general implementation. Longer inputs are rejected; {@link Blake3Hasher} sends them to
 * BouncyCastle.
 *
 * <p>Not thread-safe; {@link Blake3Hasher} keeps one instance per thread.
 */
final class Blake3SingleChunk implements Digest {

  static final int MAX_INPUT = 1024;

  private static final int BLOCK_LEN = 64;
  private static final int CHUNK_START = 1;
  private static final int CHUNK_END = 2;
  private static final int ROOT = 8;

  private static final int[] IV = {
    0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A, 0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19
  };

  /** Message word order for each of the 7 rounds: round r applies the permutation r times. */
  private static final int[][] SCHEDULE = new int[7][16];

  static {
    final int[] permutation = {2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8};
    for (int i = 0; i < 16; i++) {
      SCHEDULE[0][i] = i;
    }
    for (int r = 1; r < 7; r++) {
      for (int i = 0; i < 16; i++) {
        SCHEDULE[r][i] = SCHEDULE[r - 1][permutation[i]];
      }
    }
  }

  private final int[] cv = new int[8];
  private final int[] m = new int[16];
  private final int[] v = new int[16];
  private final byte[] block = new byte[BLOCK_LEN];
  private int blockLen;
  private int total;
  private boolean firstBlock;

  Blake3SingleChunk() {
    reset();
  }

  @Override
  public String getAlgorithmName() {
    return "BLAKE3-single-chunk";
  }

  @Override
  public int getDigestSize() {
    return 32;
  }

  @Override
  public void reset() {
    System.arraycopy(IV, 0, cv, 0, 8);
    blockLen = 0;
    total = 0;
    firstBlock = true;
  }

  @Override
  public void update(final byte in) {
    reserve(1);
    if (blockLen == BLOCK_LEN) {
      compressFullBlock();
    }
    block[blockLen++] = in;
  }

  @Override
  public void update(final byte[] in, final int inOff, final int len) {
    reserve(len);
    int off = inOff;
    int remaining = len;
    while (remaining > 0) {
      // A full block is compressed only once more input arrives: the last block needs CHUNK_END.
      if (blockLen == BLOCK_LEN) {
        compressFullBlock();
      }
      final int n = Math.min(BLOCK_LEN - blockLen, remaining);
      System.arraycopy(in, off, block, blockLen, n);
      blockLen += n;
      off += n;
      remaining -= n;
    }
  }

  @Override
  public int doFinal(final byte[] out, final int outOff) {
    Arrays.fill(block, blockLen, BLOCK_LEN, (byte) 0);
    compress(blockLen, (firstBlock ? CHUNK_START : 0) | CHUNK_END | ROOT);
    for (int i = 0; i < 8; i++) {
      final int word = v[i] ^ v[i + 8];
      out[outOff + 4 * i] = (byte) word;
      out[outOff + 4 * i + 1] = (byte) (word >>> 8);
      out[outOff + 4 * i + 2] = (byte) (word >>> 16);
      out[outOff + 4 * i + 3] = (byte) (word >>> 24);
    }
    reset();
    return 32;
  }

  private void reserve(final int len) {
    total += len;
    if (total > MAX_INPUT) {
      throw new IllegalStateException("input exceeds one BLAKE3 chunk (" + MAX_INPUT + " bytes)");
    }
  }

  private void compressFullBlock() {
    compress(BLOCK_LEN, firstBlock ? CHUNK_START : 0);
    for (int i = 0; i < 8; i++) {
      cv[i] = v[i] ^ v[i + 8];
    }
    firstBlock = false;
    blockLen = 0;
  }

  /** BLAKE3 compression of {@link #block} under {@link #cv}; result left in {@link #v}. */
  private void compress(final int len, final int flags) {
    for (int i = 0; i < 16; i++) {
      m[i] =
          (block[4 * i] & 0xFF)
              | (block[4 * i + 1] & 0xFF) << 8
              | (block[4 * i + 2] & 0xFF) << 16
              | (block[4 * i + 3] & 0xFF) << 24;
    }
    System.arraycopy(cv, 0, v, 0, 8);
    System.arraycopy(IV, 0, v, 8, 4);
    v[12] = 0; // chunk counter, low word
    v[13] = 0; // chunk counter, high word
    v[14] = len;
    v[15] = flags;
    for (final int[] s : SCHEDULE) {
      g(0, 4, 8, 12, m[s[0]], m[s[1]]);
      g(1, 5, 9, 13, m[s[2]], m[s[3]]);
      g(2, 6, 10, 14, m[s[4]], m[s[5]]);
      g(3, 7, 11, 15, m[s[6]], m[s[7]]);
      g(0, 5, 10, 15, m[s[8]], m[s[9]]);
      g(1, 6, 11, 12, m[s[10]], m[s[11]]);
      g(2, 7, 8, 13, m[s[12]], m[s[13]]);
      g(3, 4, 9, 14, m[s[14]], m[s[15]]);
    }
  }

  private void g(final int a, final int b, final int c, final int d, final int x, final int y) {
    v[a] += v[b] + x;
    v[d] = Integer.rotateRight(v[d] ^ v[a], 16);
    v[c] += v[d];
    v[b] = Integer.rotateRight(v[b] ^ v[c], 12);
    v[a] += v[b] + y;
    v[d] = Integer.rotateRight(v[d] ^ v[a], 8);
    v[c] += v[d];
    v[b] = Integer.rotateRight(v[b] ^ v[c], 7);
  }
}
