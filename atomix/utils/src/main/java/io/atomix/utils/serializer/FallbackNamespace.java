/*
 * Copyright © 2020 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.utils.serializer;

import com.esotericsoftware.kryo.KryoException;
import java.nio.ByteBuffer;

public class FallbackNamespace implements Namespace {

  private final Namespace legacy;
  private final Namespace compatible;

  public FallbackNamespace(final Namespace legacy, final Namespace compatible) {
    this.legacy = legacy;
    this.compatible = compatible;
  }

  /**
   * Serializes given object to byte array using Kryo instance in pool.
   *
   * <p>Note: Serialized bytes must be smaller than {@link NamespaceImpl#MAX_BUFFER_SIZE}.
   *
   * @param obj Object to serialize
   * @return serialized bytes
   */
  public byte[] serialize(final Object obj) {
    return compatible.serialize(obj);
  }

  /**
   * Serializes given object to byte array using Kryo instance in pool.
   *
   * @param obj Object to serialize
   * @param bufferSize maximum size of serialized bytes
   * @return serialized bytes
   */
  public byte[] serialize(final Object obj, final int bufferSize) {
    return compatible.serialize(obj, bufferSize);
  }

  /**
   * Serializes given object to byte buffer using Kryo instance in pool.
   *
   * @param obj Object to serialize
   * @param buffer to write to
   */
  public void serialize(final Object obj, final ByteBuffer buffer) {
    compatible.serialize(obj, buffer);
  }

  /**
   * Deserializes given byte array to Object using Kryo instance in pool.
   *
   * @param bytes serialized bytes
   * @param <T> deserialized Object type
   * @return deserialized Object
   */
  public <T> T deserialize(final byte[] bytes) {
    try {
      return compatible.deserialize(bytes);
    } catch (final KryoException | StringIndexOutOfBoundsException e) {
      return legacy.deserialize(bytes);
    }
  }

  /**
   * Deserializes given byte buffer to Object using Kryo instance in pool.
   *
   * @param buffer input with serialized bytes
   * @param <T> deserialized Object type
   * @return deserialized Object
   */
  public <T> T deserialize(final ByteBuffer buffer) {
    final int position = buffer.position();
    final int limit = buffer.limit();

    try {
      return compatible.deserialize(buffer);
    } catch (final KryoException | StringIndexOutOfBoundsException e) {
      buffer.position(position).limit(limit);
      return legacy.deserialize(buffer);
    }
  }
}
