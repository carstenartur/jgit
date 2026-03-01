/*
 * Copyright (C) 2026, carstenartur and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.storage.hibernate.test.category;

/**
 * JUnit category marker for slow database tests.
 * <p>
 * Tests in this category perform heavy operations (large blob streaming,
 * benchmarks) and are excluded from all normal test runs.
 */
public interface SlowDatabaseTest extends DatabaseTest {
	// marker interface
}
