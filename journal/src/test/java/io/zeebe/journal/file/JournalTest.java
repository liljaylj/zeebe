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
package io.zeebe.journal.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.zeebe.journal.Journal;
import io.zeebe.journal.JournalException.InvalidChecksum;
import io.zeebe.journal.JournalException.InvalidIndex;
import io.zeebe.journal.JournalReader;
import io.zeebe.journal.JournalRecord;
import io.zeebe.journal.file.record.JournalRecordReaderUtil;
import io.zeebe.journal.file.record.PersistedJournalRecord;
import io.zeebe.journal.file.record.RecordData;
import io.zeebe.journal.file.record.RecordMetadata;
import io.zeebe.journal.file.record.SBESerializer;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalTest {

  @TempDir Path directory;
  @TempDir Path temp;

  private byte[] entry;
  private final DirectBuffer data = new UnsafeBuffer();
  private final DirectBuffer dataOther = new UnsafeBuffer();
  private Journal journal;

  @BeforeEach
  void setup() {
    entry = "TestData".getBytes();
    data.wrap(entry);

    final var entryOther = "TestData".getBytes();
    dataOther.wrap(entryOther);

    journal = openJournal();
  }

  @Test
  void shouldBeEmpty() {
    // when-then
    assertThat(journal.isEmpty()).isTrue();
  }

  @Test
  void shouldNotBeEmpty() {
    // given
    journal.append(1, data);

    // when-then
    assertThat(journal.isEmpty()).isFalse();
  }

  @Test
  void shouldAppendData() {
    // when
    final var recordAppended = journal.append(1, data);

    // then
    assertThat(recordAppended.index()).isEqualTo(1);
    assertThat(recordAppended.asqn()).isEqualTo(1);
  }

  @Test
  void shouldReadRecord() {
    // given
    final var recordAppended = journal.append(1, data);

    // when
    final var reader = journal.openReader();
    final var recordRead = reader.next();

    // then
    assertThat(recordRead).isEqualTo(recordAppended);
  }

  @Test
  void shouldAppendMultipleData() {
    // when
    final var firstRecord = journal.append(10, data);
    final var secondRecord = journal.append(20, dataOther);

    // then
    assertThat(firstRecord.index()).isEqualTo(1);
    assertThat(firstRecord.asqn()).isEqualTo(10);

    assertThat(secondRecord.index()).isEqualTo(2);
    assertThat(secondRecord.asqn()).isEqualTo(20);
  }

  @Test
  public void shouldReadMultipleRecord() {
    // given
    final var firstRecord = journal.append(1, data);
    final var secondRecord = journal.append(20, dataOther);

    // when
    final var reader = journal.openReader();
    final var firstRecordRead = reader.next();
    final var secondRecordRead = reader.next();

    // then
    assertThat(firstRecordRead).isEqualTo(firstRecord);
    assertThat(secondRecordRead).isEqualTo(secondRecord);
  }

  @Test
  public void shouldAppendAndReadMultipleRecordsInOrder() {
    // when
    for (int i = 0; i < 10; i++) {
      final var recordAppended = journal.append(i + 10, data);
      assertThat(recordAppended.index()).isEqualTo(i + 1);
    }

    // then
    final var reader = journal.openReader();
    for (int i = 0; i < 10; i++) {
      assertThat(reader.hasNext()).isTrue();
      final var recordRead = reader.next();
      assertThat(recordRead.index()).isEqualTo(i + 1);
      final byte[] data = new byte[recordRead.data().capacity()];
      recordRead.data().getBytes(0, data);
      assertThat(recordRead.asqn()).isEqualTo(i + 10);
      assertThat(data).containsExactly(entry);
    }
  }

  @Test
  void shouldAppendAndReadMultipleRecords() {
    final var reader = journal.openReader();
    for (int i = 0; i < 10; i++) {
      // given
      entry = ("TestData" + i).getBytes();
      data.wrap(entry);

      // when
      final var recordAppended = journal.append(i + 10, data);
      assertThat(recordAppended.index()).isEqualTo(i + 1);

      // then
      assertThat(reader.hasNext()).isTrue();
      final var recordRead = reader.next();
      assertThat(recordRead.index()).isEqualTo(i + 1);
      final byte[] data = new byte[recordRead.data().capacity()];
      recordRead.data().getBytes(0, data);
      assertThat(recordRead.asqn()).isEqualTo(i + 10);
      assertThat(data).containsExactly(entry);
    }
  }

  @Test
  void shouldReset() {
    // given
    long asqn = 1;
    assertThat(journal.getLastIndex()).isEqualTo(0);
    journal.append(asqn++, data);
    journal.append(asqn++, data);

    // when
    journal.reset(2);

    // then
    assertThat(journal.isEmpty()).isTrue();
    assertThat(journal.getLastIndex()).isEqualTo(1);
    final var record = journal.append(asqn++, data);
    assertThat(record.index()).isEqualTo(2);
  }

  @Test
  void shouldResetWhileReading() {
    // given
    final var reader = journal.openReader();
    long asqn = 1;
    assertThat(journal.getLastIndex()).isEqualTo(0);
    journal.append(asqn++, data);
    journal.append(asqn++, data);
    final var record1 = reader.next();
    assertThat(record1.index()).isEqualTo(1);

    // when
    journal.reset(2);

    // then
    assertThat(journal.getLastIndex()).isEqualTo(1);
    final var record = journal.append(asqn++, data);
    assertThat(record.index()).isEqualTo(2);

    // then
    assertThat(reader.hasNext()).isTrue();
    final var record2 = reader.next();
    assertThat(record2.index()).isEqualTo(2);
    assertThat(record2.asqn()).isEqualTo(record.asqn());
  }

  @Test
  void shouldWriteToTruncatedIndex() {
    // given
    final var reader = journal.openReader();
    assertThat(journal.getLastIndex()).isEqualTo(0);
    journal.append(1, data);
    journal.append(2, data);
    journal.append(3, data);
    final var record1 = reader.next();
    assertThat(record1.index()).isEqualTo(1);

    // when
    journal.deleteAfter(1);

    // then
    assertThat(journal.getLastIndex()).isEqualTo(1);
    final var record = journal.append(4, data);
    assertThat(record.index()).isEqualTo(2);
    assertThat(record.asqn()).isEqualTo(4);
    assertThat(reader.hasNext()).isTrue();

    final var newRecord = reader.next();
    assertThat(newRecord).isEqualTo(record);
  }

  @Test
  void shouldTruncate() {
    // given
    final var reader = journal.openReader();
    assertThat(journal.getLastIndex()).isEqualTo(0);
    journal.append(1, data);
    journal.append(2, data);
    journal.append(3, data);
    final var record1 = reader.next();
    assertThat(record1.index()).isEqualTo(1);

    // when
    journal.deleteAfter(1);

    // then
    assertThat(journal.getLastIndex()).isEqualTo(1);
    assertThat(reader.hasNext()).isFalse();
  }

  @Test
  void shouldNotReadTruncatedEntries() {
    // given
    final int totalWrites = 10;
    final int truncateIndex = 5;
    int asqn = 1;
    final Map<Integer, JournalRecord> written = new HashMap<>();

    final var reader = journal.openReader();

    int writerIndex;
    for (writerIndex = 1; writerIndex <= totalWrites; writerIndex++) {
      final var record = journal.append(asqn++, data);
      assertThat(record.index()).isEqualTo(writerIndex);
      written.put(writerIndex, record);
    }

    int readerIndex;
    for (readerIndex = 1; readerIndex <= truncateIndex; readerIndex++) {
      assertThat(reader.hasNext()).isTrue();
      final var record = reader.next();
      assertThat(record).isEqualTo(written.get(readerIndex));
    }

    // when
    journal.deleteAfter(truncateIndex);

    for (writerIndex = truncateIndex + 1; writerIndex <= totalWrites; writerIndex++) {
      final var record = journal.append(asqn++, data);
      assertThat(record.index()).isEqualTo(writerIndex);
      written.put(writerIndex, record);
    }

    // then
    for (; readerIndex <= totalWrites; readerIndex++) {
      assertThat(reader.hasNext()).isTrue();
      final var record = reader.next();
      assertThat(record).isEqualTo(written.get(readerIndex));
    }
  }

  @Test
  void shouldAppendJournalRecord() {
    // given
    final var receiverJournal =
        SegmentedJournal.builder()
            .withDirectory(directory.resolve("data-2").toFile())
            .withJournalIndexDensity(5)
            .build();
    final var expected = journal.append(10, data);

    // when
    receiverJournal.append(expected);

    // then
    final var reader = receiverJournal.openReader();
    assertThat(reader.hasNext()).isTrue();
    final var actual = reader.next();
    assertThat(expected).isEqualTo(actual);
  }

  @Test
  void shouldNotAppendRecordWithAlreadyAppendedIndex() {
    // given
    final var record = journal.append(1, data);
    journal.append(data);

    // when/then
    assertThatThrownBy(() -> journal.append(record)).isInstanceOf(InvalidIndex.class);
  }

  @Test
  void shouldNotAppendRecordWithGapInIndex() {
    // given
    final var receiverJournal =
        SegmentedJournal.builder()
            .withDirectory(directory.resolve("data-2").toFile())
            .withJournalIndexDensity(5)
            .build();
    journal.append(1, data);
    final var record = journal.append(1, data);

    // when/then
    assertThatThrownBy(() -> receiverJournal.append(record)).isInstanceOf(InvalidIndex.class);
  }

  @Test
  void shouldNotAppendLastRecord() {
    // given
    final var record = journal.append(1, data);

    // when/then
    assertThatThrownBy(() -> journal.append(record)).isInstanceOf(InvalidIndex.class);
  }

  @Test
  void shouldNotAppendRecordWithInvalidChecksum() {
    // given
    final var receiverJournal =
        SegmentedJournal.builder()
            .withDirectory(directory.resolve("data-2").toFile())
            .withJournalIndexDensity(5)
            .build();
    final var record = journal.append(1, data);

    // when
    final var invalidChecksumRecord =
        new TestJournalRecord(record.index(), record.asqn(), -1, record.data());

    // then
    assertThatThrownBy(() -> receiverJournal.append(invalidChecksumRecord))
        .isInstanceOf(InvalidChecksum.class);
  }

  @Test
  void shouldReturnFirstIndex() {
    // when
    final long firstIndex = journal.append(data).index();
    journal.append(data);

    // then
    assertThat(journal.getFirstIndex()).isEqualTo(firstIndex);
  }

  @Test
  void shouldReturnLastIndex() {
    // when
    journal.append(data);
    final long lastIndex = journal.append(data).index();

    // then
    assertThat(journal.getLastIndex()).isEqualTo(lastIndex);
  }

  @Test
  void shouldOpenAndClose() throws Exception {
    // when/then
    assertThat(journal.isOpen()).isTrue();
    journal.close();
    assertThat(journal.isOpen()).isFalse();
  }

  @Test
  void shouldReopenJournalWithExistingRecords() throws Exception {
    // given
    journal.append(data);
    journal.append(data);
    final long lastIndexBeforeClose = journal.getLastIndex();
    assertThat(lastIndexBeforeClose).isEqualTo(2);
    journal.close();

    // when
    journal = openJournal();

    // then
    assertThat(journal.isOpen()).isTrue();
    assertThat(journal.getLastIndex()).isEqualTo(lastIndexBeforeClose);
  }

  @Test
  void shouldReadReopenedJournal() throws Exception {
    // given
    final var appendedRecord = copyRecord(journal.append(data));
    journal.close();

    // when
    journal = openJournal();
    final JournalReader reader = journal.openReader();

    // then
    assertThat(journal.isOpen()).isTrue();
    assertThat(reader.hasNext()).isTrue();
    assertThat(reader.next()).isEqualTo(appendedRecord);
  }

  @Test
  void shouldWriteToReopenedJournalAtNextIndex() throws Exception {
    // given
    final var firstRecord = copyRecord(journal.append(data));
    journal.close();

    // when
    journal = openJournal();
    final var secondRecord = journal.append(data);

    // then
    assertThat(secondRecord.index()).isEqualTo(2);

    final JournalReader reader = journal.openReader();

    assertThat(reader.hasNext()).isTrue();
    assertThat(reader.next()).isEqualTo(firstRecord);

    assertThat(reader.hasNext()).isTrue();
    assertThat(reader.next()).isEqualTo(secondRecord);
  }

  @Test
  void shouldNotReadDeletedEntries() {
    // given
    final var firstRecord = journal.append(data);
    journal.append(data);
    journal.append(data);

    // when
    journal.deleteAfter(firstRecord.index());
    final var newSecondRecord = journal.append(data);

    // then
    final JournalReader reader = journal.openReader();
    assertThat(newSecondRecord.index()).isEqualTo(2);

    assertThat(reader.hasNext()).isTrue();
    assertThat(reader.next()).isEqualTo(firstRecord);

    assertThat(reader.hasNext()).isTrue();
    assertThat(reader.next()).isEqualTo(newSecondRecord);

    assertThat(reader.hasNext()).isFalse();
  }

  @Test
  public void shouldInvalidateAllEntries() throws Exception {
    // given
    data.wrap("000".getBytes(StandardCharsets.UTF_8));
    final var firstRecord = copyRecord(journal.append(data));

    journal.append(data);
    journal.append(data);

    // when
    journal.deleteAfter(firstRecord.index());
    data.wrap("111".getBytes(StandardCharsets.UTF_8));
    final var secondRecord = copyRecord(journal.append(data));

    journal.close();
    journal = openJournal();

    // then
    final var reader = journal.openReader();
    assertThat(reader.next()).isEqualTo(firstRecord);
    assertThat(reader.next()).isEqualTo(secondRecord);
    assertThat(reader.hasNext()).isFalse();
  }

  @Test
  public void shouldTruncateCorruptedEntriesOnStartup() throws Exception {
    // given
    data.wrap("000".getBytes(StandardCharsets.UTF_8));
    final var firstRecord = copyRecord(journal.append(data));
    final var secondRecord = copyRecord(journal.append(data));
    assertThat(directory.toFile().listFiles()).hasSize(1);
    assertThat(directory.toFile().listFiles()[0].listFiles()).hasSize(1);
    final File log = directory.toFile().listFiles()[0].listFiles()[0];

    final File tempFile = new File(temp.toFile(), "rand");
    assertThat(tempFile.createNewFile()).isTrue();

    // when
    journal.close();
    assertThat(corruptFile(tempFile, log, secondRecord.index())).isTrue();
    journal = openJournal();
    final var reader = journal.openReader();

    // then
    assertThat(journal.getLastIndex()).isEqualTo(firstRecord.index());
    assertThat(reader.seekToLast()).isEqualTo(firstRecord.index());
    assertThat(reader.next()).isEqualTo(firstRecord);
  }

  @Test
  public void shouldWriteEntriesAfterTruncatedCorruptedEntries() throws Exception {
    // given
    data.wrap("000".getBytes(StandardCharsets.UTF_8));
    final var firstRecord = copyRecord(journal.append(data));
    final var secondRecord = copyRecord(journal.append(data));
    assertThat(directory.toFile().listFiles()).hasSize(1);
    assertThat(directory.toFile().listFiles()[0].listFiles()).hasSize(1);
    final File log = directory.toFile().listFiles()[0].listFiles()[0];

    final File tempFile = new File(temp.toFile(), "rand");
    assertThat(tempFile.createNewFile()).isTrue();

    // when
    journal.close();
    assertThat(corruptFile(tempFile, log, secondRecord.index())).isTrue();
    journal = openJournal();
    data.wrap("111".getBytes(StandardCharsets.UTF_8));
    final var lastRecord = journal.append(data);
    final var reader = journal.openReader();

    // then
    assertThat(reader.next()).isEqualTo(firstRecord);
    assertThat(reader.next()).isEqualTo(lastRecord);
  }

  static boolean corruptFile(final File temp, final File file, final long index)
      throws IOException {
    final byte[] bytes = new byte[1024];
    int read = 0;

    try (final BufferedInputStream in = new BufferedInputStream(new FileInputStream(file))) {
      while (in.available() > 0 && read < 1024) {
        read += in.read(bytes, read, Math.min(1024, in.available()) - read);
      }
    }

    if (!corruptRecord(bytes, index)) {
      return false;
    }

    try (final BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(temp))) {
      out.write(bytes, 0, read);
    }

    return temp.renameTo(file);
  }

  private static boolean corruptRecord(final byte[] bytes, final long targetIndex) {
    final JournalRecordReaderUtil reader = new JournalRecordReaderUtil(new SBESerializer());
    final ByteBuffer buffer = ByteBuffer.wrap(bytes);
    buffer.position(JournalSegmentDescriptor.BYTES);

    Optional<Integer> version = FrameUtil.readVersion(buffer);
    for (long index = 1; version.isPresent() && version.get() == 1; index++) {
      final var record = reader.read(buffer, index);

      if (record == null || record.index() > targetIndex) {
        break;
      } else if (record.index() == targetIndex) {
        final int lastPos = buffer.position() - 1;
        buffer.put(lastPos, (byte) ~buffer.get(lastPos));
        return true;
      }

      version = FrameUtil.readVersion(buffer);
    }

    return false;
  }

  private PersistedJournalRecord copyRecord(final JournalRecord record) {
    final DirectBuffer data = record.data();
    final byte[] buffer = new byte[data.capacity()];
    data.getBytes(0, buffer);

    final UnsafeBuffer copiedData = new UnsafeBuffer(buffer);
    final RecordData copiedRecord = new RecordData(record.index(), record.asqn(), copiedData);

    return new PersistedJournalRecord(
        new RecordMetadata(record.checksum(), copiedRecord.data().capacity()), copiedRecord);
  }

  private SegmentedJournal openJournal() {
    return SegmentedJournal.builder()
        .withDirectory(directory.resolve("data").toFile())
        .withJournalIndexDensity(5)
        .build();
  }
}
