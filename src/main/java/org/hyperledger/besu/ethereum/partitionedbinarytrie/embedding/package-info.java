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
 * EIP-8297 state embedding: maps Ethereum accounts, storage slots, and contract bytecode to trie
 * keys.
 *
 * <p>Zone-based key derivation ({@link
 * org.hyperledger.besu.ethereum.partitionedbinarytrie.embedding.keys.TrieKeyDerivation}), account
 * header encoding ({@link
 * org.hyperledger.besu.ethereum.partitionedbinarytrie.embedding.codec.BasicDataEncoder}), and
 * bytecode chunking ({@link
 * org.hyperledger.besu.ethereum.partitionedbinarytrie.embedding.codec.CodeChunkifier}) follow the
 * execution-specs binary trie embedding layer.
 */
package org.hyperledger.besu.ethereum.partitionedbinarytrie.embedding;
