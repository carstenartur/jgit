/*
 * Copyright (C) 2025, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.storage.hibernate.test.h2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Properties;

import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import org.eclipse.jgit.storage.hibernate.repository.HibernateRepository;
import org.eclipse.jgit.storage.hibernate.repository.HibernateRepositoryBuilder;
import org.eclipse.jgit.storage.hibernate.service.GitDatabaseQueryService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the Hibernate storage backend using H2 in-memory database.
 */
public class HibernateRepositoryH2Test {

	private HibernateSessionFactoryProvider provider;

	private HibernateRepository repo;

	@Before
	public void setUp() throws Exception {
		Properties props = new Properties();
		props.put("hibernate.connection.url",
				"jdbc:h2:mem:test;DB_CLOSE_DELAY=-1");
		props.put("hibernate.connection.driver_class", "org.h2.Driver");
		props.put("hibernate.dialect",
				"org.hibernate.dialect.H2Dialect");
		props.put("hibernate.hbm2ddl.auto", "create-drop");
		props.put("hibernate.show_sql", "false");

		provider = new HibernateSessionFactoryProvider(props);

		repo = new HibernateRepositoryBuilder()
				.setSessionFactoryProvider(provider)
				.setRepositoryName("test-repo")
				.setRepositoryDescription(
						new DfsRepositoryDescription("test-repo"))
				.build();
		repo.create(true);
	}

	@After
	public void tearDown() {
		if (repo != null) {
			repo.close();
		}
		if (provider != null) {
			provider.close();
		}
	}

	@Test
	public void testRepositoryCreation() {
		assertNotNull(repo);
		assertNotNull(repo.getObjectDatabase());
		assertNotNull(repo.getRefDatabase());
		assertEquals("test-repo", repo.getRepositoryName());
	}

	@Test
	public void testInsertBlob() throws Exception {
		byte[] content = "Hello, JGit Hibernate!".getBytes(Constants.CHARSET);
		ObjectId blobId;
		try (ObjectInserter inserter = repo.newObjectInserter()) {
			blobId = inserter.insert(Constants.OBJ_BLOB, content);
			inserter.flush();
		}
		assertNotNull(blobId);
		assertTrue(repo.getObjectDatabase().has(blobId));
	}

	@Test
	public void testInsertAndReadCommit() throws Exception {
		ObjectId commitId;
		try (ObjectInserter inserter = repo.newObjectInserter()) {
			// Create an empty tree
			TreeFormatter tree = new TreeFormatter();
			ObjectId treeId = inserter.insert(tree);

			// Create a commit
			CommitBuilder commit = new CommitBuilder();
			commit.setTreeId(treeId);
			commit.setAuthor(
					new PersonIdent("Test User", "test@example.com"));
			commit.setCommitter(
					new PersonIdent("Test User", "test@example.com"));
			commit.setMessage("Initial commit");

			commitId = inserter.insert(commit);
			inserter.flush();
		}

		assertNotNull(commitId);
		assertTrue(repo.getObjectDatabase().has(commitId));
	}

	@Test
	public void testRefUpdate() throws Exception {
		ObjectId commitId;
		try (ObjectInserter inserter = repo.newObjectInserter()) {
			TreeFormatter tree = new TreeFormatter();
			ObjectId treeId = inserter.insert(tree);

			CommitBuilder commit = new CommitBuilder();
			commit.setTreeId(treeId);
			commit.setAuthor(
					new PersonIdent("Test User", "test@example.com"));
			commit.setCommitter(
					new PersonIdent("Test User", "test@example.com"));
			commit.setMessage("Test commit");

			commitId = inserter.insert(commit);
			inserter.flush();
		}

		RefUpdate ru = repo.updateRef("refs/heads/main");
		ru.setNewObjectId(commitId);
		ru.setExpectedOldObjectId(ObjectId.zeroId());
		RefUpdate.Result result = ru.update();
		assertTrue(
				"Expected NEW or FAST_FORWARD but got " + result,
				result == RefUpdate.Result.NEW
						|| result == RefUpdate.Result.FAST_FORWARD);

		Ref ref = repo.exactRef("refs/heads/main");
		assertNotNull(ref);
		assertEquals(commitId, ref.getObjectId());
	}

	@Test
	public void testAtomicTransactions() {
		assertTrue(repo.getRefDatabase().performsAtomicTransactions());
	}

	@Test
	public void testQueryServiceWithEmptyRepo() {
		GitDatabaseQueryService queryService = new GitDatabaseQueryService(
				provider.getSessionFactory());

		assertEquals(0,
				queryService.countObjectsByType("test-repo",
						Constants.OBJ_BLOB));
		assertTrue(queryService.searchCommitMessages("test-repo", "test")
				.isEmpty());
		assertTrue(queryService
				.findRepositoriesContainingObject(
						ObjectId.zeroId().name())
				.isEmpty());
	}

	@Test
	public void testApproximateObjectCount() throws Exception {
		assertEquals(0, repo.getObjectDatabase().getApproximateObjectCount());
	}
}
