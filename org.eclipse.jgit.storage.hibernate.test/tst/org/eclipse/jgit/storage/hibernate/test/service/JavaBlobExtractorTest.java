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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.eclipse.jgit.storage.hibernate.entity.JavaBlobIndex;
import org.eclipse.jgit.storage.hibernate.service.JavaBlobExtractor;
import org.junit.Test;

/**
 * Unit tests for {@link JavaBlobExtractor}.
 */
public class JavaBlobExtractorTest {

	private final JavaBlobExtractor extractor = new JavaBlobExtractor();

	@Test
	public void testSimpleClass() {
		String source = "package org.example;\n" + "\n"
				+ "import java.util.List;\n" + "\n"
				+ "public class MyClass {\n" + "    private int count;\n"
				+ "    public void doWork() {}\n"
				+ "    public String getName() { return null; }\n" + "}\n";

		JavaBlobIndex idx = extractor.extract(source,
				"src/main/java/org/example/MyClass.java", "test-repo",
				"abc123", "def456");

		assertEquals("test-repo", idx.getRepositoryName());
		assertEquals("abc123", idx.getBlobObjectId());
		assertEquals("def456", idx.getCommitObjectId());
		assertEquals("src/main/java/org/example/MyClass.java",
				idx.getFilePath());
		assertEquals("org.example", idx.getPackageName());
		assertEquals("MyClass", idx.getDeclaredTypes());
		assertEquals("org.example.MyClass", idx.getFullyQualifiedNames());
		assertTrue(idx.getDeclaredMethods().contains("doWork"));
		assertTrue(idx.getDeclaredMethods().contains("getName"));
		assertTrue(idx.getDeclaredFields().contains("count"));
		assertNotNull(idx.getImportStatements());
		assertTrue(idx.getImportStatements().contains("java.util.List"));
		assertNotNull(idx.getSourceSnippet());
	}

	@Test
	public void testClassWithInheritance() {
		String source = "package org.example;\n" + "\n"
				+ "import java.util.ArrayList;\n"
				+ "import java.io.Serializable;\n" + "\n"
				+ "public class MyList extends ArrayList implements Serializable {\n"
				+ "}\n";

		JavaBlobIndex idx = extractor.extract(source,
				"src/main/java/org/example/MyList.java", "test-repo",
				"aaa111", "bbb222");

		assertEquals("org.example", idx.getPackageName());
		assertEquals("MyList", idx.getDeclaredTypes());
		assertEquals("org.example.MyList", idx.getFullyQualifiedNames());
		assertEquals("java.util.ArrayList", idx.getExtendsTypes());
		assertEquals("java.io.Serializable", idx.getImplementsTypes());
	}

	@Test
	public void testEnum() {
		String source = "package org.example;\n" + "\n"
				+ "public enum Color {\n" + "    RED, GREEN, BLUE;\n"
				+ "}\n";

		JavaBlobIndex idx = extractor.extract(source,
				"src/main/java/org/example/Color.java", "test-repo",
				"eee555", "fff666");

		assertEquals("Color", idx.getDeclaredTypes());
		assertEquals("org.example.Color", idx.getFullyQualifiedNames());
		assertTrue("Should contain enum constant RED",
				idx.getDeclaredFields().contains("RED"));
		assertTrue("Should contain enum constant GREEN",
				idx.getDeclaredFields().contains("GREEN"));
		assertTrue("Should contain enum constant BLUE",
				idx.getDeclaredFields().contains("BLUE"));
	}

	@Test
	public void testNonJavaFile() {
		String source = "# README\nThis is a readme file.";

		JavaBlobIndex idx = extractor.extract(source, "README.md",
				"test-repo", "ggg777", "hhh888");

		assertEquals("README.md", idx.getFilePath());
		assertNotNull(idx.getSourceSnippet());
		// Non-java files should have no structural metadata
		assertEquals(null, idx.getPackageName());
		assertEquals(null, idx.getDeclaredTypes());
	}

	@Test
	public void testSyntaxError() {
		String source = "package org.example;\n" + "\n"
				+ "public class Broken {\n" + "    int x = @#$;\n"
				+ "}\n";

		// Should not throw - graceful degradation
		JavaBlobIndex idx = extractor.extract(source,
				"src/main/java/org/example/Broken.java", "test-repo",
				"iii999", "jjj000");

		assertNotNull(idx);
		assertNotNull(idx.getSourceSnippet());
	}

	@Test
	public void testMultipleTypes() {
		String source = "package org.example;\n" + "\n"
				+ "public class Outer {\n"
				+ "    public class Inner {\n"
				+ "        private String value;\n"
				+ "    }\n" + "}\n";

		JavaBlobIndex idx = extractor.extract(source,
				"src/main/java/org/example/Outer.java", "test-repo",
				"kkk111", "lll222");

		assertTrue("Should contain Outer type",
				idx.getDeclaredTypes().contains("Outer"));
		assertTrue("Should contain Inner type",
				idx.getDeclaredTypes().contains("Inner"));
		assertTrue("Should contain Outer FQN",
				idx.getFullyQualifiedNames()
						.contains("org.example.Outer"));
		assertTrue("Should contain Inner FQN",
				idx.getFullyQualifiedNames()
						.contains("org.example.Inner"));
	}
}
