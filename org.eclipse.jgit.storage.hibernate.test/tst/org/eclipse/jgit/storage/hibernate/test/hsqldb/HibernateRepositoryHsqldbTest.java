/*
 * Copyright (C) 2026, Carsten Hammer and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.storage.hibernate.test.hsqldb;

import java.util.Properties;

import org.eclipse.jgit.storage.hibernate.test.base.AbstractHibernateRepositoryTest;

/**
 * Fast in-memory tests using HSQLDB. No Testcontainers or Docker required.
 * <p>
 * Runs in the default Maven build alongside H2 tests.
 */
public class HibernateRepositoryHsqldbTest
		extends AbstractHibernateRepositoryTest {

	@Override
	protected Properties createProperties() {
		Properties props = new Properties();
		props.put("hibernate.connection.url", //$NON-NLS-1$
				"jdbc:hsqldb:mem:" + testRepoName //$NON-NLS-1$
						+ ";shutdown=true"); //$NON-NLS-1$
		props.put("hibernate.connection.driver_class", //$NON-NLS-1$
				"org.hsqldb.jdbc.JDBCDriver"); //$NON-NLS-1$
		props.put("hibernate.dialect", //$NON-NLS-1$
				"org.hibernate.dialect.HSQLDialect"); //$NON-NLS-1$
		props.put("hibernate.hbm2ddl.auto", "create-drop"); //$NON-NLS-1$ //$NON-NLS-2$
		props.put("hibernate.show_sql", "false"); //$NON-NLS-1$ //$NON-NLS-2$
		props.put("hibernate.search.backend.directory.type", "local-heap"); //$NON-NLS-1$ //$NON-NLS-2$
		return props;
	}
}
