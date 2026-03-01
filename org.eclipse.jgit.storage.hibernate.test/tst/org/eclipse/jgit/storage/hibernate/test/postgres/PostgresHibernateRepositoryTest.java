/*
 * Copyright (C) 2026, Carsten Hammer and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.storage.hibernate.test.postgres;

import java.util.Properties;

import org.eclipse.jgit.storage.hibernate.test.base.AbstractHibernateRepositoryTest;
import org.eclipse.jgit.storage.hibernate.test.category.DatabaseTest;
import org.junit.ClassRule;
import org.junit.experimental.categories.Category;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * PostgreSQL integration tests using Testcontainers.
 * <p>
 * Requires Docker. Run with:
 * <pre>
 * mvn test -pl org.eclipse.jgit.storage.hibernate.test -Pdb-tests
 * </pre>
 */
@Category(DatabaseTest.class)
public class PostgresHibernateRepositoryTest
		extends AbstractHibernateRepositoryTest {

	@ClassRule
	@SuppressWarnings("resource")
	public static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
			"postgres:16-alpine") //$NON-NLS-1$
					.withDatabaseName("jgit_test") //$NON-NLS-1$
					.withUsername("jgit") //$NON-NLS-1$
					.withPassword("jgit"); //$NON-NLS-1$

	@Override
	protected Properties createProperties() {
		Properties props = new Properties();
		props.put("hibernate.connection.url", postgres.getJdbcUrl()); //$NON-NLS-1$
		props.put("hibernate.connection.username", //$NON-NLS-1$
				postgres.getUsername());
		props.put("hibernate.connection.password", //$NON-NLS-1$
				postgres.getPassword());
		props.put("hibernate.connection.driver_class", //$NON-NLS-1$
				"org.postgresql.Driver"); //$NON-NLS-1$
		props.put("hibernate.dialect", //$NON-NLS-1$
				"org.hibernate.dialect.PostgreSQLDialect"); //$NON-NLS-1$
		props.put("hibernate.hbm2ddl.auto", "create-drop"); //$NON-NLS-1$ //$NON-NLS-2$
		props.put("hibernate.show_sql", "false"); //$NON-NLS-1$ //$NON-NLS-2$
		return props;
	}
}
