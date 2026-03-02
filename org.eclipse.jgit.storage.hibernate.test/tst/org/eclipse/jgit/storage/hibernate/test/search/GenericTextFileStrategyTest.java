/*
 * Copyright (C) 2026, Carsten Hammer and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.storage.hibernate.test.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.eclipse.jgit.storage.hibernate.search.BlobIndexData;
import org.eclipse.jgit.storage.hibernate.search.strategies.GenericTextFileStrategy;
import org.junit.Test;

/**
 * Unit tests for {@link GenericTextFileStrategy}.
 */
public class GenericTextFileStrategyTest {

	private final GenericTextFileStrategy strategy = new GenericTextFileStrategy();

	@Test
	public void testFileType() {
		assertEquals("text", strategy.fileType());
	}

	@Test
	public void testExtractsFqnsFromText() {
		String source = "See org.eclipse.jgit.lib.Repository for more.\n"
				+ "Also check java.util.List usage.";

		BlobIndexData data = strategy.extract(source, "README.md");

		assertEquals("md", data.getFileType());
		assertNotNull(data.getSourceSnippet());
		assertNotNull(data.getFullyQualifiedNames());
		assertTrue(data.getFullyQualifiedNames()
				.contains("org.eclipse.jgit.lib.Repository"));
		assertTrue(data.getFullyQualifiedNames()
				.contains("java.util.List"));
	}

	@Test
	public void testPlainTextNoFqns() {
		String source = "Hello world, this is a plain text file.";

		BlobIndexData data = strategy.extract(source, "notes.txt");

		assertEquals("txt", data.getFileType());
		assertNotNull(data.getSourceSnippet());
		assertNull(data.getFullyQualifiedNames());
	}

	@Test
	public void testDetectsFileTypeFromExtension() {
		BlobIndexData mdData = strategy.extract("test", "doc.md");
		assertEquals("md", mdData.getFileType());

		BlobIndexData yamlData = strategy.extract("test",
				"config.yaml");
		assertEquals("yaml", yamlData.getFileType());

		BlobIndexData noExt = strategy.extract("test", "Makefile");
		assertEquals("text", noExt.getFileType());
	}

	@Test
	public void testSupportedExtensionsEmpty() {
		assertTrue(strategy.supportedExtensions().isEmpty());
	}

	@Test
	public void testSupportedFilenamesEmpty() {
		assertTrue(strategy.supportedFilenames().isEmpty());
	}
}
