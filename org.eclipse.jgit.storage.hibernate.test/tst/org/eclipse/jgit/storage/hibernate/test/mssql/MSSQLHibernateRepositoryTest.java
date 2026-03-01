/*
 * Copyright (C) 2026, carstenartur and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.storage.hibernate.test.mssql;

import java.util.Properties;

import org.eclipse.jgit.storage.hibernate.test.base.AbstractHibernateRepositoryTest;
import org.eclipse.jgit.storage.hibernate.test.category.DatabaseTest;
import org.junit.ClassRule;
import org.junit.experimental.categories.Category;
import org.testcontainers.containers.MSSQLServerContainer;

/**
 * Microsoft SQL Server integration tests using Testcontainers.
 * <p>
 * Requires Docker. The MSSQL container image requires acceptance of
 * Microsoft's EULA. Run with:
 * <pre>
 * mvn test -pl org.eclipse.jgit.storage.hibernate.test -Pdb-tests
 * </pre>
 */
@Category(DatabaseTest.class)
public class MSSQLHibernateRepositoryTest
		extends AbstractHibernateRepositoryTest {

	@ClassRule
	@SuppressWarnings("resource")
	public static MSSQLServerContainer<?> mssql = new MSSQLServerContainer<>(
			"mcr.microsoft.com/mssql/server:2022-latest") //$NON-NLS-1$
					.acceptLicense();

	@Override
	protected Properties createProperties() {
		Properties props = new Properties();
		props.put("hibernate.connection.url", mssql.getJdbcUrl()); //$NON-NLS-1$
		props.put("hibernate.connection.username", mssql.getUsername()); //$NON-NLS-1$
		props.put("hibernate.connection.password", mssql.getPassword()); //$NON-NLS-1$
		props.put("hibernate.connection.driver_class", //$NON-NLS-1$
				"com.microsoft.sqlserver.jdbc.SQLServerDriver"); //$NON-NLS-1$
		props.put("hibernate.dialect", //$NON-NLS-1$
				"org.hibernate.dialect.SQLServerDialect"); //$NON-NLS-1$
		props.put("hibernate.hbm2ddl.auto", "create-drop"); //$NON-NLS-1$ //$NON-NLS-2$
		props.put("hibernate.show_sql", "false"); //$NON-NLS-1$ //$NON-NLS-2$
		return props;
	}
}
