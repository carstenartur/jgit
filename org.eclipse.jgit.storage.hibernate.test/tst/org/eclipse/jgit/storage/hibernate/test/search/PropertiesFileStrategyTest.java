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
import org.eclipse.jgit.storage.hibernate.search.strategies.PropertiesFileStrategy;
import org.junit.Test;

/**
 * Unit tests for {@link PropertiesFileStrategy}.
 */
public class PropertiesFileStrategyTest {

	private final PropertiesFileStrategy strategy = new PropertiesFileStrategy();

	@Test
	public void testFileType() {
		assertEquals("properties", strategy.fileType());
	}

	@Test
	public void testExtractsKeysAndFqns() {
		String source = "# Main config\n"
				+ "app.name=MyApp\n"
				+ "main.class=org.example.Main\n"
				+ "service.impl=com.acme.ServiceImpl\n";

		BlobIndexData data = strategy.extract(source, "app.properties");

		assertEquals("properties", data.getFileType());
		assertNotNull(data.getSourceSnippet());
		assertNotNull(data.getDeclaredFields());
		assertTrue(data.getDeclaredFields().contains("app.name"));
		assertTrue(data.getDeclaredFields().contains("main.class"));
		assertTrue(data.getDeclaredFields()
				.contains("service.impl"));

		assertNotNull(data.getFullyQualifiedNames());
		assertTrue(data.getFullyQualifiedNames()
				.contains("org.example.Main"));
		assertTrue(data.getFullyQualifiedNames()
				.contains("com.acme.ServiceImpl"));
	}

	@Test
	public void testSimplePropertiesNoFqns() {
		String source = "color=red\nsize=10\n";

		BlobIndexData data = strategy.extract(source, "simple.properties");

		assertNotNull(data.getDeclaredFields());
		assertTrue(data.getDeclaredFields().contains("color"));
		assertTrue(data.getDeclaredFields().contains("size"));
		assertNull(data.getFullyQualifiedNames());
	}

	@Test
	public void testSupportedExtensions() {
		assertTrue(strategy.supportedExtensions().contains(".properties"));
	}

	@Test
	public void testSupportedFilenamesEmpty() {
		assertTrue(strategy.supportedFilenames().isEmpty());
	}
}
