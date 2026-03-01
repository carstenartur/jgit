/*
 * Copyright (C) 2026, Carsten Hammer and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.storage.hibernate.test.h2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jgit.internal.storage.dfs.DfsBlockCache;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCacheConfig;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.ReflogEntry;
import org.eclipse.jgit.lib.ReflogReader;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import org.eclipse.jgit.storage.hibernate.entity.GitCommitIndex;
import org.eclipse.jgit.storage.hibernate.entity.GitReflogEntity;
import org.eclipse.jgit.storage.hibernate.refs.HibernateReflogReader;
import org.eclipse.jgit.storage.hibernate.refs.HibernateReflogWriter;
import org.eclipse.jgit.storage.hibernate.repository.HibernateRepository;
import org.eclipse.jgit.storage.hibernate.repository.HibernateRepositoryBuilder;
import org.eclipse.jgit.storage.hibernate.service.CommitIndexer;
import org.eclipse.jgit.storage.hibernate.service.GitDatabaseQueryService;
import org.eclipse.jgit.storage.hibernate.service.GitDatabaseQueryService.AuthorStats;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Comprehensive tests for the Hibernate storage backend using H2
 * in-memory database. Covers Phases 1-4 of the implementation plan.
 */
public class HibernateRepositoryH2Test {

	private static final AtomicInteger TEST_COUNTER = new AtomicInteger();

	private HibernateSessionFactoryProvider provider;

	private HibernateRepository repo;

	private String testRepoName;

	@Before
	public void setUp() throws Exception {
		// Reset the DFS block cache to avoid stale data between tests
		DfsBlockCache.reconfigure(new DfsBlockCacheConfig());

		testRepoName = "test-repo-" + TEST_COUNTER.incrementAndGet();

		Properties props = new Properties();
		props.put("hibernate.connection.url",
				"jdbc:h2:mem:" + testRepoName + ";DB_CLOSE_DELAY=-1");
		props.put("hibernate.connection.driver_class", "org.h2.Driver");
		props.put("hibernate.dialect",
				"org.hibernate.dialect.H2Dialect");
		props.put("hibernate.hbm2ddl.auto", "create-drop");
		props.put("hibernate.show_sql", "false");

		provider = new HibernateSessionFactoryProvider(props);

		repo = new HibernateRepositoryBuilder()
				.setSessionFactoryProvider(provider)
				.setRepositoryName(testRepoName)
				.setRepositoryDescription(
						new DfsRepositoryDescription(testRepoName))
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

	// ===== Phase 1: Foundation Tests =====

	@Test
	public void testRepositoryCreation() {
		assertNotNull(repo);
		assertNotNull(repo.getObjectDatabase());
		assertNotNull(repo.getRefDatabase());
		assertEquals(testRepoName, repo.getRepositoryName());
	}

	@Test
	public void testInsertBlob() throws Exception {
		byte[] content = "Hello, JGit Hibernate!"
				.getBytes(StandardCharsets.UTF_8);
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
			TreeFormatter tree = new TreeFormatter();
			ObjectId treeId = inserter.insert(tree);

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
		ObjectId commitId = createCommit("Test commit");

		RefUpdate ru = repo.updateRef("refs/heads/main");
		ru.setNewObjectId(commitId);
		ru.setExpectedOldObjectId(ObjectId.zeroId());
		RefUpdate.Result result = ru.update();
		assertTrue("Expected NEW or FAST_FORWARD but got " + result,
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
	public void testApproximateObjectCount() throws Exception {
		assertEquals(0, repo.getObjectDatabase().getApproximateObjectCount());
	}

	@Test
	public void testGitwebDescription() {
		assertNull(repo.getGitwebDescription());
		repo.setGitwebDescription("My test repo");
		assertEquals("My test repo", repo.getGitwebDescription());
	}

	// ===== Phase 2: Extended Testing =====

	@Test
	public void testReadBlobContent() throws Exception {
		byte[] content = "Hello, Database Storage!"
				.getBytes(StandardCharsets.UTF_8);
		ObjectId blobId;
		try (ObjectInserter inserter = repo.newObjectInserter()) {
			blobId = inserter.insert(Constants.OBJ_BLOB, content);
			inserter.flush();
		}

		try (ObjectReader reader = repo.newObjectReader()) {
			ObjectLoader loader = reader.open(blobId);
			assertNotNull(loader);
			assertEquals(Constants.OBJ_BLOB, loader.getType());
			byte[] readBack = loader.getBytes();
			assertEquals(new String(content, StandardCharsets.UTF_8),
					new String(readBack, StandardCharsets.UTF_8));
		}
	}

	@Test
	public void testTwoPacksReadBack() throws Exception {
		ObjectId blob1;
		try (ObjectInserter inserter = repo.newObjectInserter()) {
			blob1 = inserter.insert(Constants.OBJ_BLOB,
					"pack1 content".getBytes(StandardCharsets.UTF_8));
			inserter.flush();
		}
		assertTrue(repo.getObjectDatabase().has(blob1));

		ObjectId blob2;
		try (ObjectInserter inserter = repo.newObjectInserter()) {
			blob2 = inserter.insert(Constants.OBJ_BLOB,
					"pack2 content".getBytes(StandardCharsets.UTF_8));
			inserter.flush();
		}
		assertTrue(repo.getObjectDatabase().has(blob2));
		assertTrue("blob1 should still exist after second pack",
				repo.getObjectDatabase().has(blob1));
	}

	@Test
	public void testMultipleBlobs() throws Exception {
		ObjectId id1, id2, id3;
		try (ObjectInserter inserter = repo.newObjectInserter()) {
			id1 = inserter.insert(Constants.OBJ_BLOB,
					"blob1".getBytes(StandardCharsets.UTF_8));
			id2 = inserter.insert(Constants.OBJ_BLOB,
					"blob2".getBytes(StandardCharsets.UTF_8));
			id3 = inserter.insert(Constants.OBJ_BLOB,
					"blob3".getBytes(StandardCharsets.UTF_8));
			inserter.flush();
		}

		assertTrue(repo.getObjectDatabase().has(id1));
		assertTrue(repo.getObjectDatabase().has(id2));
		assertTrue(repo.getObjectDatabase().has(id3));
	}

	@Test
	public void testTreeWithBlob() throws Exception {
		ObjectId treeId;
		try (ObjectInserter inserter = repo.newObjectInserter()) {
			ObjectId blobId = inserter.insert(Constants.OBJ_BLOB,
					"file content".getBytes(StandardCharsets.UTF_8));

			TreeFormatter tree = new TreeFormatter();
			tree.append("README.md", FileMode.REGULAR_FILE, blobId);
			treeId = inserter.insert(tree);
			inserter.flush();
		}

		assertTrue(repo.getObjectDatabase().has(treeId));
		try (ObjectReader reader = repo.newObjectReader()) {
			ObjectLoader loader = reader.open(treeId);
			assertEquals(Constants.OBJ_TREE, loader.getType());
		}
	}

	@Test
	public void testMultipleRefs() throws Exception {
		ObjectId c1 = createCommit("Commit on main");
		updateRef("refs/heads/main", c1);

		Ref main = repo.exactRef("refs/heads/main");
		assertNotNull("main ref should exist", main);
		assertEquals(c1, main.getObjectId());

		ObjectId c2 = createCommit("Commit on feature");
		updateRef("refs/heads/feature", c2);

		Ref feature = repo.exactRef("refs/heads/feature");
		assertNotNull("feature ref should exist", feature);
		assertEquals(c2, feature.getObjectId());
	}

	@Test
	public void testDeleteRef() throws Exception {
		ObjectId commitId = createCommit("Commit to delete");
		updateRef("refs/heads/tobedeleted", commitId);

		assertNotNull(repo.exactRef("refs/heads/tobedeleted"));

		RefUpdate ru = repo.updateRef("refs/heads/tobedeleted");
		ru.setForceUpdate(true);
		ru.delete();

		assertNull(repo.exactRef("refs/heads/tobedeleted"));
	}

	@Test
	public void testCommitWithParent() throws Exception {
		ObjectId parent = createCommit("Parent commit");
		updateRef("refs/heads/main", parent);

		ObjectId child;
		try (ObjectInserter inserter = repo.newObjectInserter()) {
			TreeFormatter tree = new TreeFormatter();
			ObjectId treeId = inserter.insert(tree);

			CommitBuilder commit = new CommitBuilder();
			commit.setTreeId(treeId);
			commit.setParentId(parent);
			commit.setAuthor(
					new PersonIdent("Test User", "test@example.com"));
			commit.setCommitter(
					new PersonIdent("Test User", "test@example.com"));
			commit.setMessage("Child commit");

			child = inserter.insert(commit);
			inserter.flush();
		}

		try (RevWalk rw = new RevWalk(repo)) {
			RevCommit parsed = rw.parseCommit(child);
			assertEquals(1, parsed.getParentCount());
			assertEquals(parent, parsed.getParent(0).getId());
		}
	}

	@Test
	public void testLargeBlob() throws Exception {
		// 1 MB blob to test streaming
		byte[] largeContent = new byte[1024 * 1024];
		for (int i = 0; i < largeContent.length; i++) {
			largeContent[i] = (byte) (i % 256);
		}

		ObjectId blobId;
		try (ObjectInserter inserter = repo.newObjectInserter()) {
			blobId = inserter.insert(Constants.OBJ_BLOB, largeContent);
			inserter.flush();
		}

		assertTrue(repo.getObjectDatabase().has(blobId));

		try (ObjectReader reader = repo.newObjectReader()) {
			ObjectLoader loader = reader.open(blobId);
			assertEquals(largeContent.length, loader.getSize());
			byte[] readBack = loader.getBytes();
			assertEquals(largeContent.length, readBack.length);
			// Verify first and last bytes
			assertEquals(largeContent[0], readBack[0]);
			assertEquals(largeContent[largeContent.length - 1],
					readBack[readBack.length - 1]);
		}
	}

	// ===== Phase 3: Extended Query Layer Tests =====

	@Test
	public void testRevWalkOnCommitWithFile() throws Exception {
		ObjectId commitId = createCommitWithFile("RevWalk test", "file.txt",
				"content");
		try (RevWalk rw = new RevWalk(repo)) {
			RevCommit c = rw.parseCommit(commitId);
			assertNotNull(c);
			assertEquals("RevWalk test", c.getFullMessage().trim());
		}
	}

	@Test
	public void testQueryServiceWithEmptyRepo() {
		GitDatabaseQueryService queryService = new GitDatabaseQueryService(
				provider.getSessionFactory());

		assertEquals(0, queryService.countObjectsByType(testRepoName,
				Constants.OBJ_BLOB));
		assertTrue(queryService.searchCommitMessages(testRepoName, "test")
				.isEmpty());
		assertTrue(queryService.findRepositoriesContainingObject(
				ObjectId.zeroId().name()).isEmpty());
	}

	@Test
	public void testCommitIndexer() throws Exception {
		ObjectId commitId = createCommitWithFile("Initial commit",
				"README.md", "Hello World");

		CommitIndexer indexer = new CommitIndexer(
				provider.getSessionFactory(), testRepoName);
		indexer.indexCommit(repo, commitId);

		GitDatabaseQueryService queryService = new GitDatabaseQueryService(
				provider.getSessionFactory());

		List<GitCommitIndex> results = queryService
				.searchCommitMessages(testRepoName, "Initial");
		assertEquals(1, results.size());
		assertEquals(commitId.name(), results.get(0).getObjectId());
		assertEquals("Test User", results.get(0).getAuthorName());
		assertEquals("test@example.com", results.get(0).getAuthorEmail());
		assertTrue(results.get(0).getChangedPaths().contains("README.md"));
	}

	@Test
	public void testCommitIndexerMultipleCommits() throws Exception {
		ObjectId c1 = createCommitWithFile("First commit", "a.txt",
				"content A");
		ObjectId c2 = createCommitWithFile("Second commit", "b.txt",
				"content B");

		CommitIndexer indexer = new CommitIndexer(
				provider.getSessionFactory(), testRepoName);
		indexer.indexCommit(repo, c1);
		indexer.indexCommit(repo, c2);

		GitDatabaseQueryService queryService = new GitDatabaseQueryService(
				provider.getSessionFactory());

		assertEquals(1,
				queryService.searchCommitMessages(testRepoName, "First")
						.size());
		assertEquals(1,
				queryService.searchCommitMessages(testRepoName, "Second")
						.size());
		assertEquals(2,
				queryService.searchCommitMessages(testRepoName, "commit")
						.size());
	}

	@Test
	public void testSearchByChangedPath() throws Exception {
		ObjectId c1 = createCommitWithFile("Add readme", "docs/README.md",
				"readme");
		ObjectId c2 = createCommitWithFile("Add license", "LICENSE", "MIT");

		CommitIndexer indexer = new CommitIndexer(
				provider.getSessionFactory(), testRepoName);
		indexer.indexCommit(repo, c1);
		indexer.indexCommit(repo, c2);

		GitDatabaseQueryService queryService = new GitDatabaseQueryService(
				provider.getSessionFactory());

		List<GitCommitIndex> docsResults = queryService
				.searchByChangedPath(testRepoName, "docs/");
		assertEquals(1, docsResults.size());

		List<GitCommitIndex> licenseResults = queryService
				.searchByChangedPath(testRepoName, "LICENSE");
		assertEquals(1, licenseResults.size());
	}

	@Test
	public void testAuthorStatistics() throws Exception {
		ObjectId c1 = createCommitWithFile("Commit 1", "a.txt", "a");
		ObjectId c2 = createCommitWithFile("Commit 2", "b.txt", "b");

		CommitIndexer indexer = new CommitIndexer(
				provider.getSessionFactory(), testRepoName);
		indexer.indexCommit(repo, c1);
		indexer.indexCommit(repo, c2);

		GitDatabaseQueryService queryService = new GitDatabaseQueryService(
				provider.getSessionFactory());

		List<AuthorStats> stats = queryService
				.getAuthorStatistics(testRepoName);
		assertFalse(stats.isEmpty());
		assertEquals("Test User", stats.get(0).getAuthorName());
		assertEquals(2, stats.get(0).getCommitCount());
	}

	@Test
	public void testCommitsBetweenTimestamps() throws Exception {
		ObjectId commitId = createCommitWithFile("Timed commit", "a.txt",
				"a");

		CommitIndexer indexer = new CommitIndexer(
				provider.getSessionFactory(), testRepoName);
		indexer.indexCommit(repo, commitId);

		GitDatabaseQueryService queryService = new GitDatabaseQueryService(
				provider.getSessionFactory());

		Instant past = Instant.now().minusSeconds(3600);
		Instant future = Instant.now().plusSeconds(3600);

		List<GitCommitIndex> results = queryService
				.getCommitsBetween(testRepoName, past, future);
		assertFalse(results.isEmpty());
	}

	@Test
	public void testIndexCommitsFromWalk() throws Exception {
		ObjectId c1 = createCommit("Walk commit 1");
		updateRef("refs/heads/main", c1);

		CommitIndexer indexer = new CommitIndexer(
				provider.getSessionFactory(), testRepoName);
		int count = indexer.indexCommitsFrom(repo, c1, -1);
		assertEquals(1, count);

		// Indexing again should skip already-indexed commits
		int count2 = indexer.indexCommitsFrom(repo, c1, -1);
		assertEquals(0, count2);
	}

	// ===== Phase 4: Reflog Tests =====

	@Test
	public void testReflogWriter() throws Exception {
		HibernateReflogWriter writer = repo.getReflogWriter();
		assertNotNull(writer);

		ObjectId oldId = ObjectId.zeroId();
		ObjectId newId = createCommit("Commit for reflog");

		PersonIdent who = new PersonIdent("Test User", "test@example.com");
		writer.log("refs/heads/main", oldId, newId, who,
				"branch: Created from HEAD");

		// Read it back
		HibernateReflogReader reader = new HibernateReflogReader(
				provider.getSessionFactory(), testRepoName,
				"refs/heads/main");

		ReflogEntry entry = reader.getLastEntry();
		assertNotNull(entry);
		assertEquals(oldId, entry.getOldId());
		assertEquals(newId, entry.getNewId());
		assertEquals("branch: Created from HEAD", entry.getComment());
		assertEquals("Test User", entry.getWho().getName());
	}

	@Test
	public void testReflogReaderMultipleEntries() throws Exception {
		HibernateReflogWriter writer = repo.getReflogWriter();

		ObjectId id1 = createCommit("Commit 1");
		ObjectId id2 = createCommit("Commit 2");
		ObjectId id3 = createCommit("Commit 3");

		PersonIdent who = new PersonIdent("Test User", "test@example.com");
		writer.log("refs/heads/main", ObjectId.zeroId(), id1, who,
				"commit: first");
		writer.log("refs/heads/main", id1, id2, who, "commit: second");
		writer.log("refs/heads/main", id2, id3, who, "commit: third");

		HibernateReflogReader reader = new HibernateReflogReader(
				provider.getSessionFactory(), testRepoName,
				"refs/heads/main");

		// getReverseEntries returns newest first
		List<ReflogEntry> entries = reader.getReverseEntries();
		assertEquals(3, entries.size());
		assertEquals("commit: third", entries.get(0).getComment());
		assertEquals("commit: second", entries.get(1).getComment());
		assertEquals("commit: first", entries.get(2).getComment());

		// getReverseEntries with max
		assertEquals(2, reader.getReverseEntries(2).size());

		// getReverseEntry by index
		ReflogEntry secondEntry = reader.getReverseEntry(1);
		assertNotNull(secondEntry);
		assertEquals("commit: second", secondEntry.getComment());
	}

	@Test
	public void testReflogReaderEmptyRef() throws Exception {
		HibernateReflogReader reader = new HibernateReflogReader(
				provider.getSessionFactory(), testRepoName,
				"refs/heads/nonexistent");

		assertNull(reader.getLastEntry());
		assertTrue(reader.getReverseEntries().isEmpty());
	}

	@Test
	public void testRepositoryGetReflogReader() throws Exception {
		HibernateReflogWriter writer = repo.getReflogWriter();
		ObjectId commitId = createCommit("Reflog test commit");
		PersonIdent who = new PersonIdent("Test", "test@test.com");
		writer.log("refs/heads/main", ObjectId.zeroId(), commitId, who,
				"test message");

		ReflogReader reader = repo.getReflogReader("refs/heads/main");
		assertNotNull(reader);
		ReflogEntry entry = reader.getLastEntry();
		assertNotNull(entry);
		assertEquals("test message", entry.getComment());
	}

	@Test
	public void testReflogPurge() throws Exception {
		HibernateReflogWriter writer = repo.getReflogWriter();
		ObjectId commitId = createCommit("Purge test");
		PersonIdent who = new PersonIdent("Test", "test@test.com");
		writer.log("refs/heads/main", ObjectId.zeroId(), commitId, who,
				"old entry");

		GitDatabaseQueryService queryService = new GitDatabaseQueryService(
				provider.getSessionFactory());

		// Purge entries older than far in the future = purges all
		int purged = queryService.purgeReflogEntries(testRepoName,
				Instant.now().plusSeconds(3600));
		assertEquals(1, purged);

		HibernateReflogReader reader = new HibernateReflogReader(
				provider.getSessionFactory(), testRepoName,
				"refs/heads/main");
		assertNull(reader.getLastEntry());
	}

	@Test
	public void testQueryServiceReflogEntries() throws Exception {
		HibernateReflogWriter writer = repo.getReflogWriter();
		ObjectId c1 = createCommit("Commit A");
		ObjectId c2 = createCommit("Commit B");
		PersonIdent who = new PersonIdent("Test", "test@test.com");
		writer.log("refs/heads/main", ObjectId.zeroId(), c1, who,
				"msg A");
		writer.log("refs/heads/main", c1, c2, who, "msg B");

		GitDatabaseQueryService queryService = new GitDatabaseQueryService(
				provider.getSessionFactory());
		List<GitReflogEntity> entries = queryService
				.getReflogEntries(testRepoName, "refs/heads/main", 10);
		assertEquals(2, entries.size());
		// Newest first
		assertEquals("msg B", entries.get(0).getMessage());
	}

	// ===== Helper methods =====

	private ObjectId createCommit(String message) throws Exception {
		try (ObjectInserter inserter = repo.newObjectInserter()) {
			TreeFormatter tree = new TreeFormatter();
			ObjectId treeId = inserter.insert(tree);

			CommitBuilder commit = new CommitBuilder();
			commit.setTreeId(treeId);
			commit.setAuthor(
					new PersonIdent("Test User", "test@example.com"));
			commit.setCommitter(
					new PersonIdent("Test User", "test@example.com"));
			commit.setMessage(message);

			ObjectId commitId = inserter.insert(commit);
			inserter.flush();
			return commitId;
		}
	}

	private ObjectId createCommitWithFile(String message, String fileName,
			String content) throws Exception {
		try (ObjectInserter inserter = repo.newObjectInserter()) {
			ObjectId blobId = inserter.insert(Constants.OBJ_BLOB,
					content.getBytes(StandardCharsets.UTF_8));

			TreeFormatter tree = new TreeFormatter();
			tree.append(fileName, FileMode.REGULAR_FILE, blobId);
			ObjectId treeId = inserter.insert(tree);

			CommitBuilder commit = new CommitBuilder();
			commit.setTreeId(treeId);
			commit.setAuthor(
					new PersonIdent("Test User", "test@example.com"));
			commit.setCommitter(
					new PersonIdent("Test User", "test@example.com"));
			commit.setMessage(message);

			ObjectId commitId = inserter.insert(commit);
			inserter.flush();
			return commitId;
		}
	}

	private void updateRef(String name, ObjectId id) throws Exception {
		RefUpdate ru = repo.updateRef(name);
		ru.setNewObjectId(id);
		ru.setForceUpdate(true);
		RefUpdate.Result result = ru.update();
		assertTrue("Ref update for " + name + " failed: " + result,
				result == RefUpdate.Result.NEW
						|| result == RefUpdate.Result.FORCED
						|| result == RefUpdate.Result.FAST_FORWARD);
	}
}
