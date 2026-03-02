/*
 * Copyright (C) 2026, Carsten Hammer and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.storage.hibernate.config;

import java.util.Properties;

import org.eclipse.jgit.storage.hibernate.entity.GitCommitIndex;
import org.eclipse.jgit.storage.hibernate.entity.GitObjectEntity;
import org.eclipse.jgit.storage.hibernate.entity.GitPackEntity;
import org.eclipse.jgit.storage.hibernate.entity.GitRefEntity;
import org.eclipse.jgit.storage.hibernate.entity.GitReflogEntity;
import org.eclipse.jgit.storage.hibernate.entity.JavaBlobIndex;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;

/**
 * Provides a Hibernate {@link SessionFactory} configured for JGit database
 * storage.
 */
public class HibernateSessionFactoryProvider {

	private final SessionFactory sessionFactory;

	/**
	 * Create a provider with the given configuration properties.
	 *
	 * @param properties
	 *            Hibernate configuration properties including connection URL,
	 *            driver, dialect, etc. Hibernate Search defaults to a
	 *            local-filesystem Lucene backend if not configured explicitly.
	 *            Set {@code hibernate.search.backend.directory.type} to
	 *            {@code local-heap} for in-memory indexes (suitable for
	 *            testing only).
	 */
	public HibernateSessionFactoryProvider(Properties properties) {
		Configuration cfg = new Configuration();
		cfg.addProperties(properties);
		// Default Hibernate Search to Lucene backend
		if (!properties.containsKey("hibernate.search.backend.type")) { //$NON-NLS-1$
			cfg.setProperty("hibernate.search.backend.type", "lucene"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (!properties
				.containsKey("hibernate.search.backend.directory.type")) { //$NON-NLS-1$
			cfg.setProperty("hibernate.search.backend.directory.type", //$NON-NLS-1$
					"local-filesystem"); //$NON-NLS-1$
		}
		if (!properties
				.containsKey("hibernate.search.backend.directory.root") //$NON-NLS-1$
				&& "local-filesystem".equals(cfg.getProperties().get( //$NON-NLS-1$
						"hibernate.search.backend.directory.type"))) { //$NON-NLS-1$
			String root = System.getenv("JGIT_SEARCH_INDEX_DIR"); //$NON-NLS-1$
			if (root == null || root.isEmpty()) {
				root = "jgit-search-index"; //$NON-NLS-1$
			}
			cfg.setProperty("hibernate.search.backend.directory.root", //$NON-NLS-1$
					root);
		}
		if (!properties
				.containsKey("hibernate.search.backend.analysis.configurer")) { //$NON-NLS-1$
			cfg.setProperty("hibernate.search.backend.analysis.configurer", //$NON-NLS-1$
					"class:org.eclipse.jgit.storage.hibernate.search.JavaSourceAnalysisConfigurer"); //$NON-NLS-1$
		}
		cfg.addAnnotatedClass(GitObjectEntity.class);
		cfg.addAnnotatedClass(GitRefEntity.class);
		cfg.addAnnotatedClass(GitPackEntity.class);
		cfg.addAnnotatedClass(GitReflogEntity.class);
		cfg.addAnnotatedClass(GitCommitIndex.class);
		cfg.addAnnotatedClass(JavaBlobIndex.class);
		this.sessionFactory = cfg.buildSessionFactory();
	}

	/**
	 * Create a provider with an existing session factory.
	 *
	 * @param sessionFactory
	 *            the session factory to use
	 */
	public HibernateSessionFactoryProvider(SessionFactory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}

	/**
	 * Get the session factory.
	 *
	 * @return the Hibernate session factory
	 */
	public SessionFactory getSessionFactory() {
		return sessionFactory;
	}

	/**
	 * Close the session factory and release resources.
	 */
	public void close() {
		if (sessionFactory != null && !sessionFactory.isClosed()) {
			sessionFactory.close();
		}
	}
}
