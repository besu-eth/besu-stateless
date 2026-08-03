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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.keys;

import org.apache.tuweni.bytes.Bytes32;

/** Constants for the partitioned binary trie per EIP-8297. */
public final class TrieConstants {

  /** Root hash of an empty binary tree: 32 zero bytes. */
  public static final Bytes32 EMPTY_TRIE_ROOT = Bytes32.ZERO;

  /** Longest key the tree accepts, in bytes. */
  public static final int MAX_KEY_LENGTH = 8192;

  /** First byte of every leaf node hash preimage. */
  public static final byte LEAF_NODE_TAG = 0x00;

  /** First byte of every branch node hash preimage. */
  public static final byte BRANCH_NODE_TAG = 0x01;

  /** Length of every stored value, in bytes. */
  public static final int VALUE_LENGTH = 32;

  private TrieConstants() {}
}
