/*
 * Copyright (C) 2026, Carsten Hammer and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.storage.hibernate.test.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.eclipse.jgit.storage.hibernate.service.BlobIndexer;
import org.junit.Test;

/**
 * Unit tests for {@link BlobIndexer#isBinaryContent(byte[])}.
 */
public class BlobIndexerBinaryDetectionTest {

	@Test
	public void testTextContentNotBinary() {
		byte[] text = "Hello world\nThis is text".getBytes();
		assertFalse(BlobIndexer.isBinaryContent(text));
	}

	@Test
	public void testBinaryContentWithNullByte() {
		byte[] binary = new byte[] { 0x50, 0x4B, 0x03, 0x04, 0x00,
				0x01 };
		assertTrue(BlobIndexer.isBinaryContent(binary));
	}

	@Test
	public void testEmptyContentNotBinary() {
		byte[] empty = new byte[0];
		assertFalse(BlobIndexer.isBinaryContent(empty));
	}

	@Test
	public void testJavaSourceNotBinary() {
		String source = "package org.example;\n\n"
				+ "public class Foo {\n" + "}\n";
		assertFalse(BlobIndexer.isBinaryContent(source.getBytes()));
	}

	@Test
	public void testNullByteAtStartIsBinary() {
		byte[] data = new byte[] { 0x00, 0x48, 0x65, 0x6C, 0x6C };
		assertTrue(BlobIndexer.isBinaryContent(data));
	}
}
