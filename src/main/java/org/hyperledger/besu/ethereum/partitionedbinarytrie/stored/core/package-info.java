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
 * Trie engine: in-memory and stored implementations operating on primitive {@code byte[]} keys and
 * values.
 *
 * <ul>
 *   <li>{@link org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.core.PartitionedBinaryTrie}
 *       — in-memory trie graph
 *   <li>{@link
 *       org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.core.StoredPartitionedBinaryTrie}
 *       — lazy-loaded trie backed by {@code NodeLoader}
 *   <li>{@link
 *       org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.core.ParallelStoredPartitionedBinaryTrie}
 *       — parallel commit variant
 * </ul>
 */
package org.hyperledger.besu.ethereum.partitionedbinarytrie.stored.core;
