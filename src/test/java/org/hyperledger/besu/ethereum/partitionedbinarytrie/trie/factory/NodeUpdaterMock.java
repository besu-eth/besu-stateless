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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.factory;

import org.hyperledger.besu.ethereum.trie.NodeUpdater;

import java.util.HashMap;
import java.util.Map;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Location-keyed trie node store, like Besu Bonsai {@code BonsaiWorldStateKeyValueStorage}, which
 * resolves nodes by location only. It also keeps the hash written with each entry so that {@link
 * NodeLoaderMock} can catch a read of a stale entry.
 */
public final class NodeUpdaterMock implements NodeUpdater {

  /** Node bytes at each trie path (current root lives at {@link Bytes#EMPTY}). */
  public final Map<Bytes, Bytes> storage = new HashMap<>();

  /** Hash written with each entry, when there is one. */
  final Map<Bytes, Bytes32> hashes = new HashMap<>();

  @Override
  public void store(final Bytes location, final Bytes32 hash, final Bytes value) {
    if (value == null) {
      storage.remove(location);
      hashes.remove(location);
      return;
    }
    storage.put(location, value);
    if (hash == null) {
      hashes.remove(location);
    } else {
      hashes.put(location, hash);
    }
  }
}
