/*
 * Copyright (C) 2026, Carsten Hammer and contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.reftable;

import static org.eclipse.jgit.internal.storage.reftable.ReftableConstants.INDEX_BLOCK_TYPE;
import static org.eclipse.jgit.internal.storage.reftable.ReftableConstants.LOG_BLOCK_TYPE;
import static org.eclipse.jgit.internal.storage.reftable.ReftableConstants.REF_BLOCK_TYPE;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.zip.InflaterInputStream;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.Ref;
import org.junit.Test;

public class ReftableOutputStreamTest {

	private static final int ONE_MIB = 1 << 20;

	@Test
	public void smallTablesDoNotAllocateWholeLogicalBlocks() throws IOException {
		java.lang.management.ThreadMXBean platform = ManagementFactory
				.getThreadMXBean();
		assumeTrue(platform instanceof com.sun.management.ThreadMXBean);
		com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) platform;
		assumeTrue(bean.isThreadAllocatedMemorySupported());
		boolean enabled = bean.isThreadAllocatedMemoryEnabled();
		try {
			bean.setThreadAllocatedMemoryEnabled(true);
			ReftableConfig config = new ReftableConfig();
			config.setRefBlockSize(ONE_MIB);
			Ref ref = new ObjectIdRef.PeeledNonTag(Ref.Storage.PACKED,
					"refs/heads/main", ObjectId.fromString(
							"0000000000000000000000000000000000000001"));
			for (int i = 0; i < 5; i++) {
				writeSmallTable(config, ref);
			}
			long thread = Thread.currentThread().getId();
			long before = bean.getThreadAllocatedBytes(thread);
			for (int i = 0; i < 32; i++) {
				writeSmallTable(config, ref);
			}
			long allocated = bean.getThreadAllocatedBytes(thread) - before;
			assertTrue("Small tables allocated " + allocated + " bytes",
					allocated < 32L * 64 * 1024);
		} finally {
			bean.setThreadAllocatedMemoryEnabled(enabled);
		}
	}

	@Test
	public void growsWithoutChangingLogicalBlockSize() throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		ReftableOutputStream out = new ReftableOutputStream(bytes, ONE_MIB, true);
		byte[] payload = new byte[8193];
		Arrays.fill(payload, (byte) 0x5a);
		out.beginBlock(REF_BLOCK_TYPE);
		out.write(payload);
		assertEquals(ONE_MIB - 8197, out.bytesAvailableInBlock());
		out.flushBlock();
		byte[] actual = bytes.toByteArray();
		assertArrayEquals(new byte[] { 'r', 0, 0x20, 5 }, Arrays.copyOf(actual, 4));
		assertArrayEquals(payload, Arrays.copyOfRange(actual, 4, actual.length));
	}

	@Test
	public void retainsPhysicalBlockPadding() throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		ReftableOutputStream out = new ReftableOutputStream(bytes, ONE_MIB, true);
		out.beginBlock(REF_BLOCK_TYPE);
		out.write(0x11);
		out.flushBlock();
		out.padBetweenBlocksToNextBlock();
		out.beginBlock(REF_BLOCK_TYPE);
		out.write(0x22);
		out.flushBlock();
		byte[] expected = new byte[ONE_MIB + 5];
		expected[0] = expected[ONE_MIB] = 'r';
		expected[3] = expected[ONE_MIB + 3] = 5;
		expected[4] = 0x11;
		expected[ONE_MIB + 4] = 0x22;
		assertArrayEquals(expected, bytes.toByteArray());
		assertEquals(ONE_MIB - 5, out.paddingUsed());
	}

	@Test
	public void reservesBlockHeaderAcrossBufferBoundary() throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		ReftableOutputStream out = new ReftableOutputStream(bytes, ONE_MIB, true);
		out.write(new byte[4096]);
		out.beginBlock(INDEX_BLOCK_TYPE);
		out.flushBlock();
		byte[] expected = new byte[4100];
		expected[4096] = 'i';
		expected[4098] = 0x10;
		expected[4099] = 4;
		assertArrayEquals(expected, bytes.toByteArray());
	}

	@Test
	public void compressesLogBlockAfterBufferGrowth() throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		ReftableOutputStream out = new ReftableOutputStream(bytes, ONE_MIB, false);
		byte[] payload = new byte[8193];
		Arrays.fill(payload, (byte) 0x5a);
		out.beginBlock(LOG_BLOCK_TYPE);
		out.write(payload);
		out.flushBlock();
		out.finishFile();
		byte[] actual = bytes.toByteArray();
		assertArrayEquals(new byte[] { 'g', 0, 0x20, 5 }, Arrays.copyOf(actual, 4));
		try (InflaterInputStream input = new InflaterInputStream(
				new ByteArrayInputStream(actual, 4, actual.length - 4))) {
			assertArrayEquals(payload, input.readAllBytes());
		}
	}

	private static void writeSmallTable(ReftableConfig config, Ref ref)
			throws IOException {
		ReftableWriter writer = new ReftableWriter(config,
				OutputStream.nullOutputStream());
		writer.begin();
		writer.writeRef(ref);
		writer.finish();
	}
}
