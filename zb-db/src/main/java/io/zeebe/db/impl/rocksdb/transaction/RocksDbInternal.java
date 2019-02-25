/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
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
package io.zeebe.db.impl.rocksdb.transaction;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksIterator;
import org.rocksdb.RocksObject;
import org.rocksdb.Transaction;

public class RocksDbInternal {

  static Field nativeHandle;

  static Method putWithHandle;
  static Method getWithHandle;
  static Method removeWithHandle;

  static Method seekMethod;

  static {
    RocksDB.loadLibrary();

    try {
      resolveInternalMethods();
    } catch (final Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  private static void resolveInternalMethods() throws NoSuchFieldException, NoSuchMethodException {
    nativeHandles();

    putWithHandle();
    getWithHandle();
    removeWithHandle();

    seekWithHandle();
  }

  private static void nativeHandles() throws NoSuchFieldException {
    nativeHandle = RocksObject.class.getDeclaredField("nativeHandle_");
    nativeHandle.setAccessible(true);
  }

  //    private native void put(final long handle, final byte[] key,
  //      final int keyLength, final byte[] value, final int valueLength,
  //      final long columnFamilyHandle)

  private static void putWithHandle() throws NoSuchMethodException {
    putWithHandle =
        Transaction.class.getDeclaredMethod(
            "put", Long.TYPE, byte[].class, Integer.TYPE, byte[].class, Integer.TYPE, Long.TYPE);
    putWithHandle.setAccessible(true);
  }

  //    private native byte[] get(final long handle, final long readOptionsHandle,
  //      final byte key[], final int keyLength, final long columnFamilyHandle)

  private static void getWithHandle() throws NoSuchMethodException {
    getWithHandle =
        Transaction.class.getDeclaredMethod(
            "get", Long.TYPE, Long.TYPE, byte[].class, Integer.TYPE, Long.TYPE);
    getWithHandle.setAccessible(true);
  }

  //    private native void delete(final long handle, final byte[] key,
  //      final int keyLength, final long columnFamilyHandle)

  private static void removeWithHandle() throws NoSuchMethodException {
    removeWithHandle =
        Transaction.class.getDeclaredMethod(
            "delete", Long.TYPE, byte[].class, Integer.TYPE, Long.TYPE);
    removeWithHandle.setAccessible(true);
  }

  private static void seekWithHandle() throws NoSuchMethodException {
    seekMethod =
        RocksIterator.class.getDeclaredMethod("seek0", long.class, byte[].class, int.class);
    seekMethod.setAccessible(true);
  }

  public static void seek(
      RocksIterator iterator, long nativeHandle, byte[] target, int targetLength) {
    try {
      seekMethod.invoke(iterator, nativeHandle, target, targetLength);
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new RuntimeException("Unexpected error occurred trying to seek with RocksIterator", e);
    }
  }
}
