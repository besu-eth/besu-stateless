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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.jmh.support;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory.PartitionedBinaryTrieFactory;
import org.hyperledger.besu.ethereum.trie.NodeLoader;
import org.hyperledger.besu.ethereum.trie.NodeUpdater;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/** In-memory {@link NodeLoader} / {@link NodeUpdater} pair for JMH benchmarks. */
public final class InMemoryTrieBackend {

  private final Map<Bytes, Bytes> storage = new HashMap<>();
  private final Map<Bytes32, Bytes> byHash = new HashMap<>();

  private final NodeUpdater updater =
      (location, hash, value) -> {
        if (value == null) {
          storage.remove(location);
          if (hash != null) {
            byHash.remove(hash);
          }
          return;
        }
        storage.put(location, value);
        if (hash != null) {
          byHash.put(hash, value);
        }
      };

  private final NodeLoader loader =
      (location, hash) -> {
        if (hash != null) {
          final Bytes byHashValue = byHash.get(hash);
          if (byHashValue != null) {
            return Optional.of(byHashValue);
          }
        }
        return Optional.ofNullable(storage.get(location));
      };

  public NodeUpdater updater() {
    return updater;
  }

  public NodeLoader loader() {
    return loader;
  }

  public PartitionedBinaryTrieFactory factory() {
    return new PartitionedBinaryTrieFactory(loader);
  }
}
