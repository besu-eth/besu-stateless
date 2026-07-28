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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.factory;

import org.hyperledger.besu.ethereum.trie.NodeUpdater;

import java.util.HashMap;
import java.util.Map;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

public final class NodeUpdaterMock implements NodeUpdater {

  /** Latest node bytes at each trie path (current root lives at {@link Bytes#EMPTY}). */
  public final Map<Bytes, Bytes> storage = new HashMap<>();

  /** Content-addressed node bytes for historical root reload (Besu rollback simulation). */
  private final Map<Bytes32, Bytes> byHash = new HashMap<>();

  @Override
  public void store(final Bytes location, final Bytes32 hash, final Bytes value) {
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
  }

  /**
   * Returns bytes stored for {@code hash}, falling back to {@code location} when hash is absent.
   */
  public Bytes lookup(final Bytes location, final Bytes32 hash) {
    if (hash != null) {
      final Bytes byHashValue = byHash.get(hash);
      if (byHashValue != null) {
        return byHashValue;
      }
    }
    return storage.get(location);
  }
}
