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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

import org.bouncycastle.crypto.Digest;

/**
 * BLAKE3 (hash mode, 32-byte output) for messages of at most one chunk ({@link #MAX_INPUT} bytes).
 *
 * <p>Every PBT hash input fits in one chunk, which is also the root: the message is split into
 * 64-byte blocks compressed in sequence, the first flagged {@code CHUNK_START}, the last {@code
 * CHUNK_END | ROOT}, with chunk counter 0. That skips the chunk tree and the allocations of a
 * general implementation. The compression works on local variables, which the JIT keeps in
 * registers. Longer inputs are rejected; {@link Blake3Hasher} sends them to Commons Codec.
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

  /** Reads and writes 4 bytes at once, little-endian. */
  private static final VarHandle LE_INT =
      MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

  private final int[] cv = new int[8];
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
      LE_INT.set(out, outOff + 4 * i, cv[i]);
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
    firstBlock = false;
    blockLen = 0;
  }

  /** Compresses {@link #block} under {@link #cv}, then puts the first 8 output words in it. */
  private void compress(final int len, final int flags) {
    final byte[] b = block;
    int m0 = (int) LE_INT.get(b, 0);
    int m1 = (int) LE_INT.get(b, 4);
    int m2 = (int) LE_INT.get(b, 8);
    int m3 = (int) LE_INT.get(b, 12);
    int m4 = (int) LE_INT.get(b, 16);
    int m5 = (int) LE_INT.get(b, 20);
    int m6 = (int) LE_INT.get(b, 24);
    int m7 = (int) LE_INT.get(b, 28);
    int m8 = (int) LE_INT.get(b, 32);
    int m9 = (int) LE_INT.get(b, 36);
    int m10 = (int) LE_INT.get(b, 40);
    int m11 = (int) LE_INT.get(b, 44);
    int m12 = (int) LE_INT.get(b, 48);
    int m13 = (int) LE_INT.get(b, 52);
    int m14 = (int) LE_INT.get(b, 56);
    int m15 = (int) LE_INT.get(b, 60);

    int v0 = cv[0];
    int v1 = cv[1];
    int v2 = cv[2];
    int v3 = cv[3];
    int v4 = cv[4];
    int v5 = cv[5];
    int v6 = cv[6];
    int v7 = cv[7];
    int v8 = IV[0];
    int v9 = IV[1];
    int v10 = IV[2];
    int v11 = IV[3];
    int v12 = 0; // chunk counter, low word
    int v13 = 0; // chunk counter, high word
    int v14 = len;
    int v15 = flags;

    for (int round = 0; round < 7; round++) {
      // Columns: g(0, 4, 8, 12), g(1, 5, 9, 13), g(2, 6, 10, 14), g(3, 7, 11, 15).
      v0 += v4 + m0;
      v12 = Integer.rotateRight(v12 ^ v0, 16);
      v8 += v12;
      v4 = Integer.rotateRight(v4 ^ v8, 12);
      v0 += v4 + m1;
      v12 = Integer.rotateRight(v12 ^ v0, 8);
      v8 += v12;
      v4 = Integer.rotateRight(v4 ^ v8, 7);

      v1 += v5 + m2;
      v13 = Integer.rotateRight(v13 ^ v1, 16);
      v9 += v13;
      v5 = Integer.rotateRight(v5 ^ v9, 12);
      v1 += v5 + m3;
      v13 = Integer.rotateRight(v13 ^ v1, 8);
      v9 += v13;
      v5 = Integer.rotateRight(v5 ^ v9, 7);

      v2 += v6 + m4;
      v14 = Integer.rotateRight(v14 ^ v2, 16);
      v10 += v14;
      v6 = Integer.rotateRight(v6 ^ v10, 12);
      v2 += v6 + m5;
      v14 = Integer.rotateRight(v14 ^ v2, 8);
      v10 += v14;
      v6 = Integer.rotateRight(v6 ^ v10, 7);

      v3 += v7 + m6;
      v15 = Integer.rotateRight(v15 ^ v3, 16);
      v11 += v15;
      v7 = Integer.rotateRight(v7 ^ v11, 12);
      v3 += v7 + m7;
      v15 = Integer.rotateRight(v15 ^ v3, 8);
      v11 += v15;
      v7 = Integer.rotateRight(v7 ^ v11, 7);

      // Diagonals: g(0, 5, 10, 15), g(1, 6, 11, 12), g(2, 7, 8, 13), g(3, 4, 9, 14).
      v0 += v5 + m8;
      v15 = Integer.rotateRight(v15 ^ v0, 16);
      v10 += v15;
      v5 = Integer.rotateRight(v5 ^ v10, 12);
      v0 += v5 + m9;
      v15 = Integer.rotateRight(v15 ^ v0, 8);
      v10 += v15;
      v5 = Integer.rotateRight(v5 ^ v10, 7);

      v1 += v6 + m10;
      v12 = Integer.rotateRight(v12 ^ v1, 16);
      v11 += v12;
      v6 = Integer.rotateRight(v6 ^ v11, 12);
      v1 += v6 + m11;
      v12 = Integer.rotateRight(v12 ^ v1, 8);
      v11 += v12;
      v6 = Integer.rotateRight(v6 ^ v11, 7);

      v2 += v7 + m12;
      v13 = Integer.rotateRight(v13 ^ v2, 16);
      v8 += v13;
      v7 = Integer.rotateRight(v7 ^ v8, 12);
      v2 += v7 + m13;
      v13 = Integer.rotateRight(v13 ^ v2, 8);
      v8 += v13;
      v7 = Integer.rotateRight(v7 ^ v8, 7);

      v3 += v4 + m14;
      v14 = Integer.rotateRight(v14 ^ v3, 16);
      v9 += v14;
      v4 = Integer.rotateRight(v4 ^ v9, 12);
      v3 += v4 + m15;
      v14 = Integer.rotateRight(v14 ^ v3, 8);
      v9 += v14;
      v4 = Integer.rotateRight(v4 ^ v9, 7);

      // Message permutation {2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8}: two cycles.
      final int t0 = m0;
      m0 = m2;
      m2 = m3;
      m3 = m10;
      m10 = m12;
      m12 = m9;
      m9 = m11;
      m11 = m5;
      m5 = t0;
      final int t1 = m1;
      m1 = m6;
      m6 = m4;
      m4 = m7;
      m7 = m13;
      m13 = m14;
      m14 = m15;
      m15 = m8;
      m8 = t1;
    }

    cv[0] = v0 ^ v8;
    cv[1] = v1 ^ v9;
    cv[2] = v2 ^ v10;
    cv[3] = v3 ^ v11;
    cv[4] = v4 ^ v12;
    cv[5] = v5 ^ v13;
    cv[6] = v6 ^ v14;
    cv[7] = v7 ^ v15;
  }
}
