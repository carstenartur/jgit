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
import static org.junit.Assert.assertTrue;

import org.eclipse.jgit.storage.hibernate.search.BlobIndexData;
import org.eclipse.jgit.storage.hibernate.search.strategies.JavaFileStrategy;
import org.junit.Test;

/**
 * Unit tests for {@link JavaFileStrategy}.
 */
public class JavaFileStrategyTest {

	private final JavaFileStrategy strategy = new JavaFileStrategy();

	@Test
	public void testFileType() {
		assertEquals("java", strategy.fileType());
	}

	@Test
	public void testExtractsJavaMetadata() {
		String source = "package org.example;\n\n"
				+ "import java.util.List;\n\n"
				+ "public class Foo {\n"
				+ "    private int count;\n"
				+ "    public void doWork() {}\n"
				+ "}\n";

		BlobIndexData data = strategy.extract(source,
				"src/org/example/Foo.java");

		assertEquals("java", data.getFileType());
		assertEquals("org.example", data.getPackageOrNamespace());
		assertEquals("Foo", data.getDeclaredTypes());
		assertEquals("org.example.Foo",
				data.getFullyQualifiedNames());
		assertTrue(data.getDeclaredMethods().contains("doWork"));
		assertTrue(data.getDeclaredFields().contains("count"));
		assertNotNull(data.getImportStatements());
		assertTrue(
				data.getImportStatements().contains("java.util.List"));
		assertNotNull(data.getSourceSnippet());
	}

	@Test
	public void testSupportedExtensions() {
		assertTrue(strategy.supportedExtensions().contains(".java"));
	}

	@Test
	public void testSupportedFilenamesEmpty() {
		assertTrue(strategy.supportedFilenames().isEmpty());
	}
}
