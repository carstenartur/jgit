/*
 * Copyright (C) 2026, Carsten Hammer and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.storage.hibernate.test.category;

/**
 * JUnit category marker for database tests that require Testcontainers.
 * <p>
 * Tests annotated with this category require Docker to be available and may
 * take longer to run due to container startup. They are excluded from the
 * default Maven build and can be run with:
 * <pre>
 * mvn test -pl org.eclipse.jgit.storage.hibernate.test -Pdb-tests
 * </pre>
 */
public interface DatabaseTest {
	// marker interface
}
