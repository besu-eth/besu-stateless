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
import org.bouncycastle.crypto.digests.Blake3Digest;

/**
 * Thread-local BLAKE3 hasher to avoid per-hash digest allocation.
 *
 * <p>Each overload concatenates a domain-separating {@code tag} byte with the provided byte slices
 * before hashing. Not part of the public API.
 */
public final class Blake3Hasher {

  private static final ThreadLocal<Blake3Digest> DIGEST =
      ThreadLocal.withInitial(() -> new Blake3Digest(256));

  private static final ThreadLocal<byte[]> OUTPUT = ThreadLocal.withInitial(() -> new byte[32]);

  private Blake3Hasher() {}

  /** Hash {@code data} with BLAKE3, returning an owned 32-byte digest. */
  public static Bytes32 hashBytes(final Bytes data) {
    final byte[] bytes = data.toArrayUnsafe();
    return Bytes32.wrap(hashRaw(bytes, 0, bytes.length));
  }

  public static byte[] hashRaw(final byte[] data, final int off, final int len) {
    final Blake3Digest digest = DIGEST.get();
    digest.reset();
    digest.update(data, off, len);
    final byte[] out = OUTPUT.get();
    digest.doFinal(out, 0);
    return out.clone();
  }

  public static byte[] hash(final byte tag, final byte[] a, final int aOff, final int aLen) {
    final Blake3Digest digest = DIGEST.get();
    digest.reset();
    digest.update(tag);
    digest.update(a, aOff, aLen);
    final byte[] out = OUTPUT.get();
    digest.doFinal(out, 0);
    return out.clone();
  }

  public static byte[] hash(
      final byte tag,
      final byte[] a,
      final int aOff,
      final int aLen,
      final byte[] b,
      final int bOff,
      final int bLen) {
    final Blake3Digest digest = DIGEST.get();
    digest.reset();
    digest.update(tag);
    digest.update(a, aOff, aLen);
    digest.update(b, bOff, bLen);
    final byte[] out = OUTPUT.get();
    digest.doFinal(out, 0);
    return out.clone();
  }

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
    final Blake3Digest digest = DIGEST.get();
    digest.reset();
    digest.update(tag);
    digest.update(a, aOff, aLen);
    digest.update(b, bOff, bLen);
    digest.update(c, cOff, cLen);
    final byte[] out = OUTPUT.get();
    digest.doFinal(out, 0);
    return out.clone();
  }

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
      final int cLen,
      final byte[] d,
      final int dOff,
      final int dLen) {
    final Blake3Digest digest = DIGEST.get();
    digest.reset();
    digest.update(tag);
    digest.update(a, aOff, aLen);
    digest.update(b, bOff, bLen);
    digest.update(c, cOff, cLen);
    digest.update(d, dOff, dLen);
    final byte[] out = OUTPUT.get();
    digest.doFinal(out, 0);
    return out.clone();
  }
}
