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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Properties;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCache;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCacheConfig;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.server.JGitServerApplication;
import org.eclipse.jgit.storage.hibernate.repository.HibernateRepository;
import org.eclipse.jgit.storage.hibernate.service.CommitIndexer;
import org.eclipse.jgit.storage.hibernate.test.category.DatabaseTest;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runners.MethodSorters;
import org.testcontainers.containers.MSSQLServerContainer;

/**
 * End-to-end integration test using a MSSQL Testcontainer.
 * <p>
 * Requires Docker. Excluded from default Maven build; activate with
 * {@code -Pe2e-tests}.
 */
@Category(DatabaseTest.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class EndToEndMSSQLTest {

	/** MSSQL Testcontainer. */
	@ClassRule
	@SuppressWarnings("resource")
	public static MSSQLServerContainer<?> mssql = new MSSQLServerContainer<>(
			"mcr.microsoft.com/mssql/server:2022-latest") //$NON-NLS-1$
					.acceptLicense();

	private static JGitServerApplication server;

	private static String restBaseUrl;

	private static String gitBaseUrl;

	private static String jdbcUrl;

	private static String dbUser;

	private static String dbPassword;

	private static String pushedCommitSha;

	/**
	 * Start the JGit server backed by MSSQL Testcontainer.
	 *
	 * @throws Exception
	 *             on startup failure
	 */
	@BeforeClass
	public static void startServer() throws Exception {
		DfsBlockCache.reconfigure(new DfsBlockCacheConfig());

		jdbcUrl = mssql.getJdbcUrl();
		dbUser = mssql.getUsername();
		dbPassword = mssql.getPassword();

		Properties props = new Properties();
		props.put("hibernate.connection.url", jdbcUrl); //$NON-NLS-1$
		props.put("hibernate.connection.username", dbUser); //$NON-NLS-1$
		props.put("hibernate.connection.password", dbPassword); //$NON-NLS-1$
		props.put("hibernate.connection.driver_class", //$NON-NLS-1$
				"com.microsoft.sqlserver.jdbc.SQLServerDriver"); //$NON-NLS-1$
		props.put("hibernate.dialect", //$NON-NLS-1$
				"org.hibernate.dialect.SQLServerDialect"); //$NON-NLS-1$
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
				"{\"name\":\"e2e-mssql\",\"description\":\"E2E MSSQL Test\"}"); //$NON-NLS-1$
		assertEquals(201, conn.getResponseCode());
		String body = TestHelper.readBody(conn);
		assertTrue(body.contains("\"name\":\"e2e-mssql\"")); //$NON-NLS-1$

		// Enable receive-pack for anonymous push
		HibernateRepository repo = server.getRepositoryResolver()
				.getOrCreateRepository("e2e-mssql"); //$NON-NLS-1$
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
				"/api/repos/e2e-mssql"); //$NON-NLS-1$
		assertEquals(200, conn.getResponseCode());
		String body = TestHelper.readBody(conn);
		assertTrue(body.contains("\"name\":\"e2e-mssql\"")); //$NON-NLS-1$
	}

	/**
	 * Clone an empty repository via Git Smart HTTP.
	 *
	 * @throws Exception
	 *             on failure
	 */
	@Test
	public void test04_CloneEmptyRepo() throws Exception {
		File localDir = Files.createTempDirectory("jgit-e2e-mssql-clone") //$NON-NLS-1$
				.toFile();
		try (Git git = Git.cloneRepository()
				.setURI(gitBaseUrl + "/git/e2e-mssql.git") //$NON-NLS-1$
				.setDirectory(localDir).call()) {
			assertNotNull(git.getRepository());
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
		File localDir = Files.createTempDirectory("jgit-e2e-mssql-push") //$NON-NLS-1$
				.toFile();
		try (Git git = Git.cloneRepository()
				.setURI(gitBaseUrl + "/git/e2e-mssql.git") //$NON-NLS-1$
				.setDirectory(localDir).call()) {

			File testFile = new File(localDir, "README.md"); //$NON-NLS-1$
			Files.writeString(testFile.toPath(),
					"# MSSQL E2E\nPushed via Testcontainers!"); //$NON-NLS-1$
			git.add().addFilepattern("README.md").call(); //$NON-NLS-1$
			RevCommit commit = git.commit()
					.setMessage("MSSQL e2e commit: add README") //$NON-NLS-1$
					.setAuthor("MSSQL Test", "mssql@test.org") //$NON-NLS-1$ //$NON-NLS-2$
					.call();

			assertNotNull(commit);
			pushedCommitSha = commit.getName();

			Iterable<PushResult> results = git.push().call();
			for (PushResult pr : results) {
				for (RemoteRefUpdate ru : pr.getRemoteUpdates()) {
					assertEquals(RemoteRefUpdate.Status.OK,
							ru.getStatus());
				}
			}
		} finally {
			deleteRecursive(localDir);
		}
	}

	/**
	 * Verify the commit is in the MSSQL database via JDBC.
	 *
	 * @throws Exception
	 *             on failure
	 */
	@Test
	public void test06_VerifyCommitViaJDBC() throws Exception {
		assertNotNull(pushedCommitSha);

		// Index the commit
		CommitIndexer indexer = new CommitIndexer(
				server.getRepositoryResolver().getSessionFactoryProvider()
						.getSessionFactory(),
				"e2e-mssql"); //$NON-NLS-1$
		HibernateRepository repo = server.getRepositoryResolver()
				.getOrCreateRepository("e2e-mssql"); //$NON-NLS-1$
		ObjectId tipId = findHeadTip(repo);
		assertNotNull("Should have a HEAD ref", tipId); //$NON-NLS-1$
		indexer.indexCommit(repo, tipId);

		try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser,
				dbPassword)) {
			try (PreparedStatement ps = conn.prepareStatement(
					"SELECT commit_message, author_name, author_email " //$NON-NLS-1$
							+ "FROM git_commit_index " //$NON-NLS-1$
							+ "WHERE repository_name = ?")) { //$NON-NLS-1$
				ps.setString(1, "e2e-mssql"); //$NON-NLS-1$
				try (ResultSet rs = ps.executeQuery()) {
					assertTrue("Expected commit in MSSQL", rs.next()); //$NON-NLS-1$
					assertEquals("MSSQL e2e commit: add README", //$NON-NLS-1$
							rs.getString("commit_message")); //$NON-NLS-1$
					assertEquals("MSSQL Test", //$NON-NLS-1$
							rs.getString("author_name")); //$NON-NLS-1$
					assertEquals("mssql@test.org", //$NON-NLS-1$
							rs.getString("author_email")); //$NON-NLS-1$
				}
			}
		}
	}

	/**
	 * Search commits via REST API on MSSQL-backed repo.
	 *
	 * @throws Exception
	 *             on failure
	 */
	@Test
	public void test07_SearchCommitsViaRest() throws Exception {
		HttpURLConnection conn = TestHelper.openGet(restBaseUrl,
				"/api/search/commits?repo=e2e-mssql&q=README"); //$NON-NLS-1$
		assertEquals(200, conn.getResponseCode());
		String body = TestHelper.readBody(conn);
		assertFalse(body.isEmpty());
		assertTrue(body.contains("\"results\"")); //$NON-NLS-1$
	}

	/**
	 * Query author analytics via REST API on MSSQL-backed repo.
	 *
	 * @throws Exception
	 *             on failure
	 */
	@Test
	public void test08_AnalyticsAuthorsViaRest() throws Exception {
		HttpURLConnection conn = TestHelper.openGet(restBaseUrl,
				"/api/analytics/authors?repo=e2e-mssql"); //$NON-NLS-1$
		assertEquals(200, conn.getResponseCode());
		String body = TestHelper.readBody(conn);
		assertTrue(body.contains("\"authors\"")); //$NON-NLS-1$
	}

	/**
	 * Query object count analytics via REST API on MSSQL-backed repo.
	 *
	 * @throws Exception
	 *             on failure
	 */
	@Test
	public void test09_AnalyticsObjectsViaRest() throws Exception {
		HttpURLConnection conn = TestHelper.openGet(restBaseUrl,
				"/api/analytics/objects?repo=e2e-mssql"); //$NON-NLS-1$
		assertEquals(200, conn.getResponseCode());
		String body = TestHelper.readBody(conn);
		assertTrue(body.contains("\"objectCounts\"")); //$NON-NLS-1$
	}

	/**
	 * Query pack analytics via REST API on MSSQL-backed repo.
	 *
	 * @throws Exception
	 *             on failure
	 */
	@Test
	public void test10_AnalyticsPacksViaRest() throws Exception {
		HttpURLConnection conn = TestHelper.openGet(restBaseUrl,
				"/api/analytics/packs?repo=e2e-mssql"); //$NON-NLS-1$
		assertEquals(200, conn.getResponseCode());
		String body = TestHelper.readBody(conn);
		assertTrue(body.contains("\"packCount\"")); //$NON-NLS-1$
	}

	/**
	 * Verify JDBC object count query returns expected types.
	 *
	 * @throws Exception
	 *             on failure
	 */
	@Test
	public void test11_VerifyObjectCountsViaJDBC() throws Exception {
		try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser,
				dbPassword)) {
			try (PreparedStatement ps = conn.prepareStatement(
					"SELECT COUNT(*) FROM git_commit_index " //$NON-NLS-1$
							+ "WHERE repository_name = ?")) { //$NON-NLS-1$
				ps.setString(1, "e2e-mssql"); //$NON-NLS-1$
				try (ResultSet rs = ps.executeQuery()) {
					assertTrue(rs.next());
					assertTrue("Expected at least one indexed commit", //$NON-NLS-1$
							rs.getLong(1) >= 1);
				}
			}
		}
	}

	private static ObjectId findHeadTip(HibernateRepository repo)
			throws Exception {
		ObjectId tipId = null;
		if (repo.exactRef("refs/heads/master") != null) { //$NON-NLS-1$
			tipId = repo.exactRef("refs/heads/master").getObjectId(); //$NON-NLS-1$
		}
		if (tipId == null && repo.exactRef("refs/heads/main") != null) { //$NON-NLS-1$
			tipId = repo.exactRef("refs/heads/main").getObjectId(); //$NON-NLS-1$
		}
		return tipId;
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
