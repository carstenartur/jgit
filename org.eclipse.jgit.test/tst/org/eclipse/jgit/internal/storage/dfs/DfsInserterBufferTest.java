/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.dfs;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import org.eclipse.jgit.internal.storage.file.PackIndex;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.junit.After;
import org.junit.Test;

public class DfsInserterBufferTest {

	private static final int ONE_MIB = 1 << 20;

	@After
	public void restoreBlockCache() {
		DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
	}

	@Test
	public void smallPacksDoNotAllocateWholeBlocks() throws IOException {
		java.lang.management.ThreadMXBean platform = ManagementFactory
				.getThreadMXBean();
		assumeTrue(platform instanceof com.sun.management.ThreadMXBean);
		com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) platform;
		assumeTrue(bean.isThreadAllocatedMemorySupported());
		boolean enabled = bean.isThreadAllocatedMemoryEnabled();
		try (InMemoryRepository repo = repository();
				AlignedDatabase db = new AlignedDatabase(repo, ONE_MIB)) {
			bean.setThreadAllocatedMemoryEnabled(true);
			byte[] data = Constants.encode("small blob");
			for (int i = 0; i < 5; i++) {
				insertWithoutPublishing(db, data);
			}
			long thread = Thread.currentThread().getId();
			long before = bean.getThreadAllocatedBytes(thread);
			for (int i = 0; i < 32; i++) {
				insertWithoutPublishing(db, data);
			}
			long allocated = bean.getThreadAllocatedBytes(thread) - before;
			assertTrue("Small packs allocated " + allocated + " bytes",
					allocated < 32L * 256 * 1024);
		} finally {
			bean.setThreadAllocatedMemoryEnabled(enabled);
		}
	}

	@Test
	public void growsFirstBlockWithoutPublishingPartialBlocks() throws Exception {
		try (InMemoryRepository repo = repository();
				AlignedDatabase db = new AlignedDatabase(repo, ONE_MIB);
				DfsInserter inserter = new DfsInserter(db)) {
			byte[] data = randomBytes(256 * 1024 + 257);
			ObjectId id = inserter.insert(Constants.OBJ_BLOB, data);
			assertTrue(db.packWrites.isEmpty());
			assertEquals(0, db.listPacks().size());
			assertBufferedAndStreamingReads(inserter, db, id, data);
			assertTrue(db.packWrites.isEmpty());
			inserter.flush();
			assertEquals(1, db.packWrites.size());
			assertTrue(db.packWrites.get(0).intValue() < ONE_MIB);
			assertEquals(ONE_MIB,
					db.listPacks().get(0).getBlockSize(PackExt.PACK));
		}
	}

	@Test
	public void readsGrowingAndFlushedBlocksBeforeAndAfterPublication()
			throws Exception {
		for (int blockSize : new int[] { ONE_MIB, ONE_MIB + 37 }) {
			DfsBlockCache.reconfigure(new DfsBlockCacheConfig()
					.setBlockLimit(2 * ONE_MIB));
			try (InMemoryRepository repo = repository();
					AlignedDatabase db = new AlignedDatabase(repo, blockSize)) {
				byte[][] data = { new byte[0], randomBytes(256 * 1024 + 257),
						randomBytes(3 * ONE_MIB + 257) };
				ObjectId[] ids = new ObjectId[data.length];
				try (DfsInserter inserter = new DfsInserter(db)) {
					for (int i = 0; i < data.length; i++) {
						ids[i] = inserter.insert(Constants.OBJ_BLOB, data[i]);
						assertBufferedAndStreamingReads(inserter, db, ids[i],
								data[i]);
					}
					assertTrue(db.packWrites.size() >= 3);
					for (int length : db.packWrites) {
						assertEquals(blockSize, length);
					}
					assertBufferedAndStreamingReads(inserter, db, ids[0],
							data[0]);
					assertTrue(db.readBacks > 0);
					inserter.flush();
				}
				DfsPackDescription pack = db.listPacks().get(0);
				assertEquals(blockSize, pack.getBlockSize(PackExt.PACK));
				assertPackIntegrity(readFile(db, pack, PackExt.PACK),
						readFile(db, pack, PackExt.INDEX), ids);

				DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
				db.clearCache();
				db.getReaderOptions().setStreamFileThreshold(Integer.MAX_VALUE);
				try (ObjectReader reader = db.newReader()) {
					for (int i = 0; i < ids.length; i++) {
						assertArrayEquals(data[i],
								reader.open(ids[i]).getBytes());
					}
				}
			}
		}
	}

	@Test
	public void footerCanGrowTheBufferAndCrossTheLogicalBlock() throws Exception {
		for (int[] boundary : new int[][] { { ONE_MIB, 64 * 1024 },
				{ ONE_MIB, ONE_MIB }, { ONE_MIB + 37, ONE_MIB + 37 } }) {
			FooterFixture fixture = footerFixture(boundary[0], boundary[1]);
			long expectedLength = fixture.bodyLength()
					+ Constants.OBJECT_ID_LENGTH;
			try (InMemoryRepository repo = repository();
					AlignedDatabase db = new AlignedDatabase(repo, boundary[0]);
					DfsInserter inserter = new DfsInserter(db)) {
				inserter.setCompressionLevel(Deflater.NO_COMPRESSION);
				byte[] data = new byte[fixture.payloadLength()];
				ObjectId id = inserter.insert(Constants.OBJ_BLOB, data);
				assertTrue(db.packWrites.isEmpty());
				inserter.flush();
				DfsPackDescription pack = db.listPacks().get(0);
				assertEquals(expectedLength, pack.getFileSize(PackExt.PACK));
				assertEquals(expectedLength <= boundary[0] ? 1 : 2,
						db.packWrites.size());
				if (db.packWrites.size() == 2) {
					assertEquals(boundary[0], db.packWrites.get(0).intValue());
					assertEquals(expectedLength - boundary[0],
							db.packWrites.get(1).intValue());
				}
				assertPackIntegrity(readFile(db, pack, PackExt.PACK),
						readFile(db, pack, PackExt.INDEX), new ObjectId[] { id });
			}
		}
	}

	private static FooterFixture footerFixture(int blockSize, int boundary)
			throws IOException {
		// Deflater's stored-block segmentation can vary between runtimes.
		// Position the body less than one footer before the requested boundary.
		int low = 0;
		int high = boundary;
		for (int attempt = 0; attempt < 32 && low <= high; attempt++) {
			int length = low + (high - low) / 2;
			long bodyLength;
			try (InMemoryRepository repo = repository();
					AlignedDatabase db = new AlignedDatabase(repo, blockSize);
					DfsInserter inserter = new DfsInserter(db)) {
				inserter.setCompressionLevel(Deflater.NO_COMPRESSION);
				inserter.insert(Constants.OBJ_BLOB, new byte[length]);
				inserter.flush();
				bodyLength = db.listPacks().get(0).getFileSize(PackExt.PACK)
						- Constants.OBJECT_ID_LENGTH;
			}
			if (bodyLength < boundary - Constants.OBJECT_ID_LENGTH + 1) {
				low = length + 1;
			} else if (bodyLength >= boundary) {
				high = length - 1;
			} else {
				return new FooterFixture(length, bodyLength);
			}
		}
		throw new AssertionError("Could not position pack body before "
				+ boundary + " within 32 probes");
	}

	private record FooterFixture(int payloadLength, long bodyLength) {
	}

	@Test
	public void packAndIndexBytesDoNotDependOnOutputBlockSize() throws Exception {
		for (byte[][] data : new byte[][][] {
				{ new byte[0] },
				{ Constants.encode("small blob") },
				{ randomBytes(256 * 1024 + 257) },
				{ randomBytes(3 * ONE_MIB + 257), randomBytes(97) } }) {
			byte[][] expected = writePack(64 * 1024, data);
			for (int blockSize : new int[] { ONE_MIB, ONE_MIB + 37 }) {
				byte[][] actual = writePack(blockSize, data);
				assertArrayEquals(expected[0], actual[0]);
				assertArrayEquals(expected[1], actual[1]);
			}
		}
	}

	private static void insertWithoutPublishing(AlignedDatabase db, byte[] data)
			throws IOException {
		try (DfsInserter inserter = new DfsInserter(db)) {
			inserter.insert(Constants.OBJ_BLOB, data);
		}
	}

	private static void assertBufferedAndStreamingReads(DfsInserter inserter,
			AlignedDatabase db, ObjectId id, byte[] expected) throws IOException {
		db.getReaderOptions().setStreamFileThreshold(Integer.MAX_VALUE);
		try (ObjectReader reader = inserter.newReader()) {
			assertArrayEquals(expected, reader.open(id).getBytes());
		}
		db.getReaderOptions().setStreamFileThreshold(1);
		try (ObjectReader reader = inserter.newReader();
				var stream = reader.open(id).openStream()) {
			assertArrayEquals(expected, stream.readAllBytes());
		}
	}

	private static byte[][] writePack(int blockSize, byte[][] data)
			throws IOException {
		try (InMemoryRepository repo = repository();
				AlignedDatabase db = new AlignedDatabase(repo, blockSize);
				DfsInserter inserter = new DfsInserter(db)) {
			for (byte[] object : data) {
				inserter.insert(Constants.OBJ_BLOB, object);
			}
			inserter.flush();
			DfsPackDescription pack = db.listPacks().get(0);
			return new byte[][] { readFile(db, pack, PackExt.PACK),
					readFile(db, pack, PackExt.INDEX) };
		}
	}

	private static void assertPackIntegrity(byte[] pack, byte[] index,
			ObjectId[] ids) throws IOException {
		int footer = pack.length - Constants.OBJECT_ID_LENGTH;
		MessageDigest digest = Constants.newMessageDigest();
		digest.update(pack, 0, footer);
		byte[] checksum = digest.digest();
		assertArrayEquals(checksum, Arrays.copyOfRange(pack, footer, pack.length));
		PackIndex parsed = PackIndex.read(new ByteArrayInputStream(index));
		assertArrayEquals(checksum, parsed.getChecksum());
		digest.update(index, 0, index.length - Constants.OBJECT_ID_LENGTH);
		assertArrayEquals(digest.digest(), Arrays.copyOfRange(index,
				index.length - Constants.OBJECT_ID_LENGTH, index.length));
		assertEquals(ids.length, parsed.getObjectCount());
		for (int i = 0; i < ids.length; i++) {
			int start = (int) parsed.findOffset(ids[i]);
			int end = i + 1 < ids.length ? (int) parsed.findOffset(ids[i + 1])
					: footer;
			CRC32 crc = new CRC32();
			crc.update(pack, start, end - start);
			assertEquals(crc.getValue(), parsed.findCRC32(ids[i]));
		}
	}

	private static byte[] readFile(AlignedDatabase db, DfsPackDescription pack,
			PackExt extension) throws IOException {
		ByteBuffer buffer = ByteBuffer
				.allocate((int) pack.getFileSize(extension));
		try (ReadableChannel channel = db.openFile(pack, extension)) {
			while (buffer.hasRemaining()) {
				assertTrue(channel.read(buffer) > 0);
			}
		}
		return buffer.array();
	}

	private static byte[] randomBytes(int length) {
		byte[] data = new byte[length];
		new Random(0x5041434bL).nextBytes(data);
		return data;
	}

	private static InMemoryRepository repository() {
		return new InMemoryRepository(new DfsRepositoryDescription("buffer-test"));
	}

	private static class AlignedDatabase
			extends InMemoryRepository.MemObjDatabase {
		private final int blockSize;
		final List<Integer> packWrites = new ArrayList<>();
		int readBacks;

		AlignedDatabase(DfsRepository repo, int blockSize) {
			super(repo);
			this.blockSize = blockSize;
			setReadableChannelBlockSizeForTest(blockSize);
		}

		@Override
		protected DfsOutputStream writeFile(DfsPackDescription pack,
				PackExt extension) throws IOException {
			DfsOutputStream delegate = super.writeFile(pack, extension);
			return new DfsOutputStream() {
				@Override
				public int blockSize() {
					return blockSize;
				}

				@Override
				public void write(byte[] data, int offset, int length)
						throws IOException {
					if (extension == PackExt.PACK) {
						packWrites.add(Integer.valueOf(length));
					}
					delegate.write(data, offset, length);
				}

				@Override
				public int read(long position, ByteBuffer buffer)
						throws IOException {
					readBacks++;
					return delegate.read(position, buffer);
				}

				@Override
				public void close() throws IOException {
					delegate.close();
				}
			};
		}
	}
}
