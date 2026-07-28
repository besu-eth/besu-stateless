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

/**
 * Incremental partitioned binary trie with lazy node loading and Besu {@code NodeLoader} / {@code
 * NodeUpdater} persistence.
 *
 * <p>Subpackages:
 *
 * <ul>
 *   <li>{@code core} — trie engine ({@code PartitionedBinaryTrie}, {@code
 *       StoredPartitionedBinaryTrie})
 *   <li>{@code node} — {@code TrieNode} graph (empty, memory, stored)
 *   <li>{@code visitor} — get/put/remove/commit visitors
 *   <li>{@code factory} — node loading and trie construction
 *   <li>{@code codec} — binary node serialization
 *   <li>{@code proof} — merkle proof generation and verification
 * </ul>
 */
package org.hyperledger.besu.ethereum.partitionedbinarytrie.stored;
