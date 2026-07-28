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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.reference.BinaryTrie;
import org.hyperledger.besu.ethereum.partitionedbinarytrie.trie.reference.MutableBinaryTrie;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;

/**
 * Remove semantics agreement across production trie and reference oracles.
 *
 * <p>Layer: stored core ({@link PartitionedBinaryTrie}) cross-checked with {@link BinaryTrie} and
 * {@link MutableBinaryTrie}. Confirms root hash and key presence after two-key remove scenarios.
 */
class PartitionedBinaryTrieRemoveOracleTest {

  @Test
  void binaryAndMutableAgreeOnTwoKeyRemove() {
    final Bytes keyA = Bytes.fromHexString("0xaaaa");
    final Bytes keyB = Bytes.fromHexString("0xbbbb");
    final Bytes32 valueA = Bytes32.repeat((byte) 0x01);
    final Bytes32 valueB = Bytes32.repeat((byte) 0x02);

    final BinaryTrie binary = new BinaryTrie();
    final MutableBinaryTrie mutable = new MutableBinaryTrie();
    final PartitionedBinaryTrie stored = new PartitionedBinaryTrie();

    binary.put(keyA, valueA);
    mutable.put(keyA, valueA);
    stored.put(keyA.toArray(), keyA.size(), valueA.toArray());
    final Bytes32 rootA = binary.root();
    assertThat(mutable.root()).isEqualTo(rootA);
    assertThat(stored.getRootHash()).isEqualTo(rootA);

    binary.put(keyB, valueB);
    mutable.put(keyB, valueB);
    stored.put(keyB.toArray(), keyB.size(), valueB.toArray());
    assertThat(stored.get(keyB.toArray(), keyB.size())).as("stored get B after put").isPresent();
    assertThat(stored.getRootHash()).as("stored root after put B").isNotEqualTo(rootA);
    assertThat(mutable.root()).isEqualTo(binary.root());
    assertThat(stored.getRootHash()).isEqualTo(binary.root());

    binary.remove(keyB);
    mutable.remove(keyB);
    stored.remove(keyB.toArray(), keyB.size());

    assertThat(mutable.root()).isEqualTo(binary.root());
    assertThat(stored.getRootHash()).as("stored after remove").isEqualTo(binary.root());
    assertThat(stored.get(keyB.toArray(), keyB.size())).isEmpty();
  }
}
