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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.trie;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.codec.CodeChunkifier;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.codec.CodeRefCountEncoder;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.keys.TrieKeyDerivation;

import java.util.List;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Insert and delete content-addressed code, sharing chunks between accounts with the same {@code
 * codeHash}.
 *
 * <p>Counts are held outside the trie, keyed by code hash, so only the {@code CODE_ZONE} chunks
 * contribute to the state root.
 */
final class CodeReferenceCounter {

  private CodeReferenceCounter() {}

  /**
   * Writes chunks for a hash seen for the first time; otherwise only bumps the count.
   *
   * @param trie trie holding the code chunks
   * @param codeHash hash identifying the bytecode
   * @param code bytecode, chunked on first insert
   */
  static void insertCode(
      final PartitionedBinaryTrie trie, final Bytes32 codeHash, final Bytes code) {
    final Optional<Bytes> existing = trie.getCodeRefCount(codeHash);
    final long count = existing.map(CodeRefCountEncoder::refCount).orElse(0L);

    final int chunkCount;
    if (count == 0) {
      final List<Bytes32> chunks = CodeChunkifier.chunkifyCode(code);
      for (int i = 0; i < chunks.size(); i++) {
        final Bytes32 chunk = chunks.get(i);
        if (!Bytes32.ZERO.equals(chunk)) {
          trie.put(TrieKeyDerivation.getTreeKeyForCodeChunk(codeHash, i), chunk);
        }
      }
      chunkCount = chunks.size();
    } else {
      chunkCount = existing.map(CodeRefCountEncoder::chunkCount).orElseThrow();
    }
    trie.putCodeRefCount(codeHash, CodeRefCountEncoder.encode(count + 1, chunkCount));
  }

  /**
   * Drops the chunks once the last reference is released; otherwise only lowers the count.
   *
   * @param trie trie holding the code chunks
   * @param codeHash hash whose reference is released
   */
  static void deleteCode(final PartitionedBinaryTrie trie, final Bytes32 codeHash) {
    final Optional<Bytes> existing = trie.getCodeRefCount(codeHash);
    if (existing.isEmpty()) {
      return;
    }
    final long count = CodeRefCountEncoder.refCount(existing.get());
    final int chunkCount = CodeRefCountEncoder.chunkCount(existing.get());
    if (count > 1) {
      trie.putCodeRefCount(codeHash, CodeRefCountEncoder.encode(count - 1, chunkCount));
      return;
    }
    trie.removeCodeRefCount(codeHash);
    for (int i = 0; i < chunkCount; i++) {
      trie.remove(TrieKeyDerivation.getTreeKeyForCodeChunk(codeHash, i));
    }
  }
}
