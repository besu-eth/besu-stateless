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

import org.apache.commons.codec.digest.Blake3;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Thread-local BLAKE3 hashing (32-byte output) for trie keys and nodes, on Apache Commons Codec.
 *
 * <p>The tagged overloads hash {@code tag} followed by the given slices. Not part of the public
 * API.
 */
public final class Blake3Hasher {

  private static final ThreadLocal<Blake3> BLAKE3 = ThreadLocal.withInitial(Blake3::initHash);

  /** One-byte buffer for the domain tag (Commons Codec has no single-byte update). */
  private static final ThreadLocal<byte[]> TAG = ThreadLocal.withInitial(() -> new byte[1]);

  private Blake3Hasher() {}

  /** Hash {@code data} with BLAKE3, returning an owned 32-byte digest. */
  public static Bytes32 hashBytes(final Bytes data) {
    final byte[] bytes = data.toArrayUnsafe();
    return Bytes32.wrap(hashRaw(bytes, 0, bytes.length));
  }

  /** Hash {@code data[off, off + len)}, returning an owned 32-byte digest. */
  public static byte[] hashRaw(final byte[] data, final int off, final int len) {
    final Blake3 blake3 = start();
    blake3.update(data, off, len);
    return finish(blake3);
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
    final Blake3 blake3 = startTagged(tag);
    blake3.update(a, aOff, aLen);
    blake3.update(b, bOff, bLen);
    return finish(blake3);
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
    final Blake3 blake3 = startTagged(tag);
    blake3.update(a, aOff, aLen);
    blake3.update(b, bOff, bLen);
    blake3.update(c, cOff, cLen);
    return finish(blake3);
  }

  private static Blake3 start() {
    return BLAKE3.get().reset();
  }

  private static Blake3 startTagged(final byte tag) {
    final byte[] tagBuffer = TAG.get();
    tagBuffer[0] = tag;
    return start().update(tagBuffer);
  }

  private static byte[] finish(final Blake3 blake3) {
    final byte[] out = new byte[32];
    blake3.doFinalize(out);
    return out;
  }
}
