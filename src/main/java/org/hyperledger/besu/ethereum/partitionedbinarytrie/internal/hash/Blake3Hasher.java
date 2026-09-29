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

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.digests.Blake3Digest;

/**
 * Thread-local BLAKE3 hashing (32-byte output) for trie keys and nodes.
 *
 * <p>Inputs of at most one chunk, which is every PBT key and node, go through {@link
 * Blake3SingleChunk}; anything longer (a branch with a very long prefix) falls back to
 * BouncyCastle. The tagged overloads hash {@code tag} followed by the given slices. Not part of the
 * public API.
 */
public final class Blake3Hasher {

  private static final ThreadLocal<Blake3SingleChunk> SINGLE_CHUNK =
      ThreadLocal.withInitial(Blake3SingleChunk::new);

  private static final ThreadLocal<Blake3Digest> GENERAL =
      ThreadLocal.withInitial(() -> new Blake3Digest(256));

  private Blake3Hasher() {}

  /** Hash {@code data} with BLAKE3, returning an owned 32-byte digest. */
  public static Bytes32 hashBytes(final Bytes data) {
    final byte[] bytes = data.toArrayUnsafe();
    return Bytes32.wrap(hashRaw(bytes, 0, bytes.length));
  }

  /** Hash {@code data[off, off + len)}, returning an owned 32-byte digest. */
  public static byte[] hashRaw(final byte[] data, final int off, final int len) {
    final Digest digest = digestFor(len);
    digest.update(data, off, len);
    return finish(digest);
  }

  /** Hash {@code tag || a || b}. */
  public static byte[] hash(
      final byte tag,
      final byte[] a,
      final int aOff,
      final int aLen,
      final byte[] b,
      final int bOff,
      final int bLen) {
    final Digest digest = digestFor(1 + aLen + bLen);
    digest.update(tag);
    digest.update(a, aOff, aLen);
    digest.update(b, bOff, bLen);
    return finish(digest);
  }

  /** Hash {@code tag || a || b || c}. */
  public static byte[] hash(
      final byte tag,
      final byte[] a,
      final int aOff,
      final int aLen,
      final byte[] b,
      final int bOff,
      final int bLen,
      final byte[] c,
      final int cOff,
      final int cLen) {
    final Digest digest = digestFor(1 + aLen + bLen + cLen);
    digest.update(tag);
    digest.update(a, aOff, aLen);
    digest.update(b, bOff, bLen);
    digest.update(c, cOff, cLen);
    return finish(digest);
  }

  private static Digest digestFor(final int inputLength) {
    final Digest digest =
        inputLength <= Blake3SingleChunk.MAX_INPUT ? SINGLE_CHUNK.get() : GENERAL.get();
    digest.reset();
    return digest;
  }

  private static byte[] finish(final Digest digest) {
    final byte[] out = new byte[32];
    digest.doFinal(out, 0);
    return out;
  }
}
