/*
 * Copyright (C) 2026, Carsten Hammer and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.server.e2e;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.util.List;
import java.util.Properties;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCache;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCacheConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.server.JGitServerApplication;
import org.eclipse.jgit.storage.hibernate.entity.GitCommitIndex;
import org.eclipse.jgit.storage.hibernate.service.GitDatabaseQueryService;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * End-to-end integration test for the JGit server webapp.
 * <p>
 * This test exercises the full flow:
 * <ol>
 * <li>REST API health check, repository CRUD</li>
 * <li>JGit client clone and push via Git Smart HTTP</li>
 * <li>JDBC verification of commit data in the database</li>
 * <li>REST API search and analytics queries</li>
 * </ol>
 * <p>
 * By default runs with H2 in-memory (no Docker required). When the
 * {@code -Pe2e-tests} Maven profile is active, a MSSQL Testcontainer variant
 * is also included.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class EndToEndH2Test {

	private static JGitServerApplication server;

	private static String restBaseUrl;

	private static String gitBaseUrl;

	private static String pushedCommitSha;

	/**
	 * Start the JGit server with H2 in-memory database.
	 *
	 * @throws Exception
	 *             on startup failure
	 */
	@BeforeClass
	public static void startServer() throws Exception {
		DfsBlockCache.reconfigure(new DfsBlockCacheConfig());

		Properties props = new Properties();
		String jdbcUrl = "jdbc:h2:mem:e2e-test;DB_CLOSE_DELAY=-1"; //$NON-NLS-1$

		props.put("hibernate.connection.url", jdbcUrl); //$NON-NLS-1$
		props.put("hibernate.connection.username", "sa"); //$NON-NLS-1$ //$NON-NLS-2$
		props.put("hibernate.connection.password", ""); //$NON-NLS-1$ //$NON-NLS-2$
		props.put("hibernate.connection.driver_class", //$NON-NLS-1$
				"org.h2.Driver"); //$NON-NLS-1$
		props.put("hibernate.dialect", //$NON-NLS-1$
				"org.hibernate.dialect.H2Dialect"); //$NON-NLS-1$
		props.put("hibernate.hbm2ddl.auto", "create"); //$NON-NLS-1$ //$NON-NLS-2$
		props.put("hibernate.show_sql", "false"); //$NON-NLS-1$ //$NON-NLS-2$

		server = new JGitServerApplication();
		server.start(props, 0, 0);

		restBaseUrl = "http://localhost:" + server.getRestPort(); //$NON-NLS-1$
		gitBaseUrl = "http://localhost:" + server.getGitPort(); //$NON-NLS-1$
	}

	/**
	 * Stop the JGit server.
	 *
	 * @throws Exception
	 *             on shutdown failure
	 */
	@AfterClass
	public static void stopServer() throws Exception {
		if (server != null) {
			server.stop();
		}
	}

	/**
	 * Verify health endpoint returns UP with database connected.
	 *
	 * @throws Exception
	 *             on failure
	 */
	@Test
	public void test01_HealthEndpoint() throws Exception {
		HttpURLConnection conn = TestHelper.openGet(restBaseUrl,
				"/api/health"); //$NON-NLS-1$
		assertEquals(200, conn.getResponseCode());
		String body = TestHelper.readBody(conn);
		assertTrue("Should contain UP status", //$NON-NLS-1$
				body.contains("\"status\":\"UP\"")); //$NON-NLS-1$
		assertTrue("Should indicate database connected", //$NON-NLS-1$
				body.contains("\"database\":\"connected\"")); //$NON-NLS-1$
	}

	/**
	 * Create a repository via REST API.
	 *
	 * @throws Exception
	 *             on failure
	 */
	@Test
	public void test02_CreateRepo() throws Exception {
		HttpURLConnection conn = TestHelper.openPost(restBaseUrl,
				"/api/repos", //$NON-NLS-1$
				"{\"name\":\"e2e-test\",\"description\":\"E2E Test Repo\"}"); //$NON-NLS-1$
		assertEquals(201, conn.getResponseCode());
		String body = TestHelper.readBody(conn);
		assertTrue("Should contain repo name", //$NON-NLS-1$
				body.contains("\"name\":\"e2e-test\"")); //$NON-NLS-1$

		// Enable receive-pack for anonymous push
		org.eclipse.jgit.storage.hibernate.repository.HibernateRepository repo = server
				.getRepositoryResolver()
				.getOrCreateRepository("e2e-test"); //$NON-NLS-1$
		org.eclipse.jgit.lib.StoredConfig cfg = repo.getConfig();
		cfg.setBoolean("http", null, "receivepack", true); //$NON-NLS-1$ //$NON-NLS-2$
		cfg.save();
	}

	/**
	 * Get a repository via REST API.
	 *
	 * @throws Exception
	 *             on failure
	 */
	@Test
	public void test03_GetRepo() throws Exception {
		HttpURLConnection conn = TestHelper.openGet(restBaseUrl,
				"/api/repos/e2e-test"); //$NON-NLS-1$
		assertEquals(200, conn.getResponseCode());
		String body = TestHelper.readBody(conn);
		assertTrue("Should contain repo name", //$NON-NLS-1$
				body.contains("\"name\":\"e2e-test\"")); //$NON-NLS-1$
	}

	/**
	 * Clone an empty repository via Git Smart HTTP.
	 *
	 * @throws Exception
	 *             on failure
	 */
	@Test
	public void test04_CloneEmptyRepo() throws Exception {
		File localDir = Files.createTempDirectory("jgit-e2e-clone") //$NON-NLS-1$
				.toFile();
		try (Git git = Git.cloneRepository()
				.setURI(gitBaseUrl + "/git/e2e-test.git") //$NON-NLS-1$
				.setDirectory(localDir).call()) {
			assertNotNull("Cloned repository should not be null", //$NON-NLS-1$
					git.getRepository());
		} finally {
			deleteRecursive(localDir);
		}
	}

	/**
	 * Commit a file and push it via Git Smart HTTP.
	 *
	 * @throws Exception
	 *             on failure
	 */
	@Test
	public void test05_CommitAndPush() throws Exception {
		File localDir = Files.createTempDirectory("jgit-e2e-push") //$NON-NLS-1$
				.toFile();
		try (Git git = Git.cloneRepository()
				.setURI(gitBaseUrl + "/git/e2e-test.git") //$NON-NLS-1$
				.setDirectory(localDir).call()) {

			// Create a file and commit
			File testFile = new File(localDir, "README.md"); //$NON-NLS-1$
			Files.writeString(testFile.toPath(),
					"# E2E Test\nHello from Testcontainers!"); //$NON-NLS-1$
			git.add().addFilepattern("README.md").call(); //$NON-NLS-1$
			RevCommit commit = git.commit()
					.setMessage("Initial e2e commit: add README") //$NON-NLS-1$
					.setAuthor("E2E Test", "e2e@test.org") //$NON-NLS-1$ //$NON-NLS-2$
					.call();

			assertNotNull("Commit should not be null", commit); //$NON-NLS-1$
			pushedCommitSha = commit.getName();

			// Push
			Iterable<PushResult> results = git.push().call();
			for (PushResult pr : results) {
				for (RemoteRefUpdate ru : pr.getRemoteUpdates()) {
					assertEquals(
							"Push should succeed", //$NON-NLS-1$
							RemoteRefUpdate.Status.OK, ru.getStatus());
				}
			}
		} finally {
			deleteRecursive(localDir);
		}
	}

	/**
	 * Verify the pushed commit is searchable via Hibernate Search.
	 *
	 * @throws Exception
	 *             on failure
	 */
	@Test
	public void test06_VerifyCommitInDatabase() throws Exception {
		assertNotNull("pushedCommitSha should be set by test05", //$NON-NLS-1$
				pushedCommitSha);
		// Index the commit (Hibernate Search auto-indexes on persist)
		org.eclipse.jgit.storage.hibernate.service.CommitIndexer indexer = new org.eclipse.jgit.storage.hibernate.service.CommitIndexer(
				server.getRepositoryResolver().getSessionFactoryProvider()
						.getSessionFactory(),
				"e2e-test"); //$NON-NLS-1$
		org.eclipse.jgit.storage.hibernate.repository.HibernateRepository repo = server
				.getRepositoryResolver().getOrCreateRepository("e2e-test"); //$NON-NLS-1$
		org.eclipse.jgit.lib.ObjectId tipId = repo
				.exactRef("refs/heads/master") != null //$NON-NLS-1$
						? repo.exactRef("refs/heads/master").getObjectId() //$NON-NLS-1$
						: null;
		if (tipId == null) {
			tipId = repo.exactRef("refs/heads/main") != null //$NON-NLS-1$
					? repo.exactRef("refs/heads/main").getObjectId() //$NON-NLS-1$
					: null;
		}
		assertNotNull("Should have a HEAD ref", tipId); //$NON-NLS-1$
		indexer.indexCommit(repo, tipId);

		// Verify via Hibernate Search through GitDatabaseQueryService
		GitDatabaseQueryService queryService = new GitDatabaseQueryService(
				server.getRepositoryResolver().getSessionFactoryProvider()
						.getSessionFactory());
		List<GitCommitIndex> results = queryService
				.searchCommitMessages("e2e-test", "README"); //$NON-NLS-1$ //$NON-NLS-2$
		assertFalse("Should find at least one commit", //$NON-NLS-1$
				results.isEmpty());
		assertEquals("Initial e2e commit: add README", //$NON-NLS-1$
				results.get(0).getCommitMessage());
		assertEquals("E2E Test", //$NON-NLS-1$
				results.get(0).getAuthorName());
		assertEquals("e2e@test.org", //$NON-NLS-1$
				results.get(0).getAuthorEmail());
	}

	/**
	 * Search commits via REST API.
	 *
	 * @throws Exception
	 *             on failure
	 */
	@Test
	public void test07_SearchCommitsViaRest() throws Exception {
		HttpURLConnection conn = TestHelper.openGet(restBaseUrl,
				"/api/search/commits?repo=e2e-test&q=README"); //$NON-NLS-1$
		assertEquals(200, conn.getResponseCode());
		String body = TestHelper.readBody(conn);
		assertFalse("Search body should not be empty", body.isEmpty()); //$NON-NLS-1$
		assertTrue("Should contain results array", //$NON-NLS-1$
				body.contains("\"results\"")); //$NON-NLS-1$
	}

	/**
	 * Search paths via REST API.
	 *
	 * @throws Exception
	 *             on failure
	 */
	@Test
	public void test08_SearchPathsViaRest() throws Exception {
		HttpURLConnection conn = TestHelper.openGet(restBaseUrl,
				"/api/search/paths?repo=e2e-test&q=README"); //$NON-NLS-1$
		assertEquals(200, conn.getResponseCode());
		String body = TestHelper.readBody(conn);
		assertFalse("Search paths body should not be empty", //$NON-NLS-1$
				body.isEmpty());
		assertTrue("Should contain results array", //$NON-NLS-1$
				body.contains("\"results\"")); //$NON-NLS-1$
	}

	/**
	 * Query author analytics via REST API.
	 *
	 * @throws Exception
	 *             on failure
	 */
	@Test
	public void test09_AnalyticsAuthorsViaRest() throws Exception {
		HttpURLConnection conn = TestHelper.openGet(restBaseUrl,
				"/api/analytics/authors?repo=e2e-test"); //$NON-NLS-1$
		assertEquals(200, conn.getResponseCode());
		String body = TestHelper.readBody(conn);
		assertTrue("Should contain authors key", //$NON-NLS-1$
				body.contains("\"authors\"")); //$NON-NLS-1$
	}

	/**
	 * Query object count analytics via REST API.
	 *
	 * @throws Exception
	 *             on failure
	 */
	@Test
	public void test10_AnalyticsObjectsViaRest() throws Exception {
		HttpURLConnection conn = TestHelper.openGet(restBaseUrl,
				"/api/analytics/objects?repo=e2e-test"); //$NON-NLS-1$
		assertEquals(200, conn.getResponseCode());
		String body = TestHelper.readBody(conn);
		assertTrue("Should contain objectCounts key", //$NON-NLS-1$
				body.contains("\"objectCounts\"")); //$NON-NLS-1$
	}

	/**
	 * Query pack analytics via REST API.
	 *
	 * @throws Exception
	 *             on failure
	 */
	@Test
	public void test11_AnalyticsPacksViaRest() throws Exception {
		HttpURLConnection conn = TestHelper.openGet(restBaseUrl,
				"/api/analytics/packs?repo=e2e-test"); //$NON-NLS-1$
		assertEquals(200, conn.getResponseCode());
		String body = TestHelper.readBody(conn);
		assertTrue("Should contain packCount key", //$NON-NLS-1$
				body.contains("\"packCount\"")); //$NON-NLS-1$
	}

	private static void deleteRecursive(File file) {
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children != null) {
				for (File child : children) {
					deleteRecursive(child);
				}
			}
		}
		file.delete();
	}
}
