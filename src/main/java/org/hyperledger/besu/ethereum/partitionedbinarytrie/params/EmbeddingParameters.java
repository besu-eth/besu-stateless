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
package org.hyperledger.besu.ethereum.partitionedbinarytrie.params;

/** EIP-8297 embedding constants for zone layout, key lengths, and header offsets. */
public final class EmbeddingParameters {

  /** Sub-index for the basic-data leaf in the account header stem. */
  public static final int BASIC_DATA_LEAF_KEY = 0;

  /** Version byte written into encoded basic data. */
  public static final int BASIC_DATA_VERSION = 0;

  /** Sub-index for the code-hash leaf in the account header stem. */
  public static final int CODE_HASH_LEAF_KEY = 1;

  /** First storage slot index stored in the account header stem (slots 0–63). */
  public static final int HEADER_STORAGE_OFFSET = 64;

  /** First code chunk index stored in the account header stem. */
  public static final int CODE_OFFSET = 128;

  /** Width of a stem subtree (256 children per stem group). */
  public static final int STEM_SUBTREE_WIDTH = 256;

  /** Zone identifier for account header keys. */
  public static final int ACCOUNT_ZONE = 0;

  /** Zone identifier for contract code chunk keys. */
  public static final int CODE_ZONE = 1;

  /** Zone identifier for storage slot keys beyond the header stem. */
  public static final int STORAGE_ZONE = 255;

  /** Expected byte length of an account tree key. */
  public static final int ACCOUNT_KEY_LENGTH = 34;

  /** Expected byte length of a code tree key. */
  public static final int CODE_KEY_LENGTH = 34;

  /** Expected byte length of a storage tree key. */
  public static final int STORAGE_KEY_LENGTH = 66;

  /** Opcode value of {@code PUSH1} (used by {@link CodeChunkifier}). */
  public static final int PUSH_OFFSET = 95;

  /** Opcode value of {@code PUSH1}. */
  public static final int PUSH1 = PUSH_OFFSET + 1;

  /** Opcode value of {@code PUSH32}. */
  public static final int PUSH32 = PUSH_OFFSET + 32;

  private EmbeddingParameters() {}
}
