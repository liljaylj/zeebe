/*
 * Copyright 2017-present Open Networking Foundation
 * Copyright © 2020 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.storage.journal;

import io.atomix.storage.StorageException;
import io.atomix.storage.journal.index.JournalIndex;
import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;
import java.util.zip.Checksum;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Segment writer.
 *
 * <p>The format of an entry in the log is as follows:
 *
 * <ul>
 *   <li>64-bit index
 *   <li>8-bit boolean indicating whether a term change is contained in the entry
 *   <li>64-bit optional term
 *   <li>32-bit signed entry length, including the entry type ID
 *   <li>8-bit signed entry type ID
 *   <li>n-bit entry bytes
 * </ul>
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
class FileChannelJournalSegmentWriter implements JournalWriter {

  private final FileChannel channel;
  private final JournalSegment segment;
  private final int maxEntrySize;
  private final JournalIndex journalIndex;
  private final JournalSerde serde;
  private final ByteBuffer memory;
  private final MutableDirectBuffer writeBuffer;
  private final long firstIndex;
  private final Checksum checksum = new CRC32();
  private Indexed<RaftLogEntry> lastEntry;

  FileChannelJournalSegmentWriter(
      final JournalSegmentFile file,
      final JournalSegment segment,
      final int maxEntrySize,
      final JournalIndex journalIndex,
      final JournalSerde serde) {
    this.segment = segment;
    this.maxEntrySize = maxEntrySize;
    this.journalIndex = journalIndex;
    this.serde = serde;
    firstIndex = segment.index();
    channel =
        file.openChannel(
            StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
    memory =
        ByteBuffer.allocateDirect(maxEntrySize + Integer.BYTES + Integer.BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);
    writeBuffer = new UnsafeBuffer(memory);
    memory.limit(0);
    reset(0);
  }

  @Override
  public long getLastIndex() {
    return lastEntry != null ? lastEntry.index() : segment.index() - 1;
  }

  @Override
  public Indexed<RaftLogEntry> getLastEntry() {
    return lastEntry;
  }

  @Override
  public long getNextIndex() {
    if (lastEntry != null) {
      return lastEntry.index() + 1;
    } else {
      return firstIndex;
    }
  }

  @Override
  public Indexed<RaftLogEntry> append(final RaftLogEntry entry) {
    // Store the entry index.
    final long index = getNextIndex();
    final int entryOffset = Integer.BYTES + Integer.BYTES;
    final int length = serde.computeSerializedLength(entry);

    // If the entry length exceeds the maximum entry size then throw an exception.
    if (length > maxEntrySize) {
      throw new StorageException.TooLarge(
          "Entry size " + length + " exceeds maximum allowed bytes (" + maxEntrySize + ")");
    }

    if (length <= 0) {
      throw new StorageException("Entry size should be at least 1, but was " + length);
    }

    memory.clear();

    // write the length of the entry so we can read it back
    writeBuffer.putInt(0, length, ByteOrder.LITTLE_ENDIAN);

    try {
      // ensure there's enough space left in the buffer to store the entry.
      final long position = channel.position();
      if (segment.descriptor().maxSegmentSize() - position < length + entryOffset) {
        throw new BufferOverflowException();
      }

      // the serialized length should be the same as we computed
      final int serializedLength = serde.serializeRaftLogEntry(writeBuffer, entryOffset, entry);
      assert length == serializedLength
          : "Expected length " + length + " to be equal to serializedLength " + serializedLength;

      // store entry checksum along with the entry to verify integrity on reads
      checksum.reset();
      checksum.update(memory.asReadOnlyBuffer().position(entryOffset).limit(entryOffset + length));
      final int entryChecksum = (int) (checksum.getValue() & 0xFFFFFFFFL);
      writeBuffer.putInt(Integer.BYTES, entryChecksum, ByteOrder.LITTLE_ENDIAN);

      // write the entry to file
      channel.write(memory.asReadOnlyBuffer().limit(entryOffset + length));

      // Update the last entry with the correct index/term/length.
      final Indexed<RaftLogEntry> indexedEntry = new Indexed<>(index, entry, length);
      lastEntry = indexedEntry;
      journalIndex.index(lastEntry, (int) position);
      return indexedEntry;
    } catch (final IOException e) {
      throw new StorageException(e);
    }
  }

  @Override
  public void append(final Indexed<RaftLogEntry> entry) {
    final long nextIndex = getNextIndex();

    // If the entry's index is greater than the next index in the segment, skip some entries.
    if (entry.index() > nextIndex) {
      throw new IndexOutOfBoundsException("Entry index is not sequential");
    }

    // If the entry's index is less than the next index, truncate the segment.
    if (entry.index() < nextIndex) {
      truncate(entry.index() - 1);
    }
    append(entry.entry());
  }

  @Override
  public void commit(final long index) {}

  @Override
  public void reset(final long index) {
    long nextIndex = firstIndex;

    // Clear the buffer indexes.
    try {
      channel.position(JournalSegmentDescriptor.BYTES);
      memory.clear().flip();

      // Record the current buffer position.
      long position = channel.position();

      // Read more bytes from the segment if necessary.
      if (memory.remaining() < maxEntrySize) {
        memory.clear();
        channel.read(memory);
        channel.position(position);
        memory.flip();
      }

      // Read the entry length.
      memory.mark();
      int length = memory.getInt();

      // If the length is non-zero, read the entry.
      while (0 < length && length <= maxEntrySize && (index == 0 || nextIndex <= index)) {
        // Read the checksum of the entry.
        final long entryChecksum = memory.getInt() & 0xFFFFFFFFL;

        // Compute the checksum for the entry bytes
        checksum.reset();
        checksum.update(memory.asReadOnlyBuffer().limit(memory.position() + length));

        // If the stored checksum equals the computed checksum, return the entry.
        if (entryChecksum == checksum.getValue()) {
          final RaftLogEntry entry = serde.deserializeRaftLogEntry(writeBuffer, memory.position());
          memory.position(memory.position() + length);
          lastEntry = new Indexed<>(nextIndex, entry, length);
          journalIndex.index(lastEntry, (int) position);
          nextIndex++;
        } else {
          break;
        }

        // Update the current position for indexing.
        position = channel.position() + memory.position();

        // Read more bytes from the segment if necessary.
        if (memory.remaining() < maxEntrySize) {
          channel.position(position);
          memory.clear();
          channel.read(memory);
          channel.position(position);
          memory.flip();
        }

        memory.mark();
        length = memory.getInt();
      }

      // Reset the buffer to the previous mark.
      channel.position(channel.position() + memory.reset().position());
    } catch (final BufferUnderflowException e) {
      try {
        channel.position(channel.position() + memory.reset().position());
      } catch (final IOException e2) {
        throw new StorageException(e2);
      }
    } catch (final IOException e) {
      throw new StorageException(e);
    }
  }

  @Override
  public void truncate(final long index) {
    // If the index is greater than or equal to the last index, skip the truncate.
    if (index >= getLastIndex()) {
      return;
    }

    // Reset the last entry.
    lastEntry = null;

    try {
      // Truncate the index.
      journalIndex.truncate(index);

      if (index < segment.index()) {
        channel.position(JournalSegmentDescriptor.BYTES);
        channel.write(zero());
        channel.position(JournalSegmentDescriptor.BYTES);
      } else {
        // Reset the writer to the given index.
        reset(index);

        // Zero entries after the given index.
        final long position = channel.position();
        channel.write(zero());
        channel.position(position);
      }
    } catch (final IOException e) {
      throw new StorageException(e);
    }
  }

  @Override
  public void flush() {
    try {
      if (channel.isOpen()) {
        channel.force(true);
      }
    } catch (final IOException e) {
      throw new StorageException(e);
    }
  }

  @Override
  public void close() {
    flush();
    try {
      channel.close();
    } catch (final IOException e) {
      throw new StorageException(e);
    }
  }

  /**
   * Returns the size of the underlying buffer.
   *
   * @return The size of the underlying buffer.
   */
  public long size() {
    try {
      return channel.position();
    } catch (final IOException e) {
      throw new StorageException(e);
    }
  }

  /**
   * Returns a boolean indicating whether the segment is empty.
   *
   * @return Indicates whether the segment is empty.
   */
  public boolean isEmpty() {
    return lastEntry == null;
  }

  /**
   * Returns a boolean indicating whether the segment is full.
   *
   * @return Indicates whether the segment is full.
   */
  public boolean isFull() {
    return size() >= segment.descriptor().maxSegmentSize()
        || getNextIndex() - firstIndex >= segment.descriptor().maxEntries();
  }

  /** Returns a zeroed out byte buffer. */
  private ByteBuffer zero() {
    memory.clear();
    for (int i = 0; i < memory.limit(); i++) {
      memory.put(i, (byte) 0);
    }
    return memory;
  }
}
