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

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.eclipse.jgit.storage.hibernate.search.FileTypeStrategy;
import org.eclipse.jgit.storage.hibernate.search.FileTypeStrategyRegistry;
import org.eclipse.jgit.storage.hibernate.search.strategies.GenericTextFileStrategy;
import org.eclipse.jgit.storage.hibernate.search.strategies.JavaFileStrategy;
import org.eclipse.jgit.storage.hibernate.search.strategies.PropertiesFileStrategy;
import org.junit.Test;

/**
 * Unit tests for {@link FileTypeStrategyRegistry}.
 */
public class FileTypeStrategyRegistryTest {

	private final FileTypeStrategyRegistry registry = new FileTypeStrategyRegistry();

	@Test
	public void testJavaFileResolution() {
		FileTypeStrategy strategy = registry
				.getStrategy("src/org/example/Foo.java");
		assertTrue(strategy instanceof JavaFileStrategy);
	}

	@Test
	public void testPropertiesFileResolution() {
		FileTypeStrategy strategy = registry
				.getStrategy("config/app.properties");
		assertTrue(strategy instanceof PropertiesFileStrategy);
	}

	@Test
	public void testFallbackForUnknownExtension() {
		FileTypeStrategy strategy = registry
				.getStrategy("README.md");
		assertTrue(strategy instanceof GenericTextFileStrategy);
	}

	@Test
	public void testFallbackForNoExtension() {
		FileTypeStrategy strategy = registry.getStrategy("Makefile");
		assertTrue(strategy instanceof GenericTextFileStrategy);
	}

	@Test
	public void testCaseInsensitiveExtension() {
		FileTypeStrategy strategy = registry
				.getStrategy("src/Main.JAVA");
		assertTrue(strategy instanceof JavaFileStrategy);
	}

	@Test
	public void testGetFallback() {
		FileTypeStrategy fallback = registry.getFallback();
		assertTrue(fallback instanceof GenericTextFileStrategy);
		assertSame(fallback, registry.getStrategy("unknown.xyz"));
	}
}
