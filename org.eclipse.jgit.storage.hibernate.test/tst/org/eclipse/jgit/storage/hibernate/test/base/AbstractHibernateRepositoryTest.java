/*
 * Copyright (C) 2025, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.storage.hibernate.test.base;

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
 * Abstract base test that exercises the complete Hibernate storage backend.
 * <p>
 * Subclasses provide the database-specific connection properties via
 * {@link #createProperties()}. This enables running the full test suite
 * against H2, HSQLDB, PostgreSQL, MySQL, MariaDB, etc.
 */
public abstract class AbstractHibernateRepositoryTest {

	private static final AtomicInteger TEST_COUNTER = new AtomicInteger();

	/** The Hibernate session factory provider. */
	protected HibernateSessionFactoryProvider provider;

	/** The repository under test. */
	protected HibernateRepository repo;

	/** Unique repository name for this test run. */
	protected String testRepoName;

	/**
	 * Create the database-specific connection properties.
	 *
	 * @return a Properties object with JDBC URL, driver, dialect, etc.
	 */
	protected abstract Properties createProperties();

	@Before
	public void setUp() throws Exception {
		DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
		testRepoName = "test-repo-" + TEST_COUNTER.incrementAndGet(); //$NON-NLS-1$

		Properties props = createProperties();
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

	// ===== Phase 1: Foundation =====

	@Test
	public void testRepositoryCreation() {
		assertNotNull(repo);
		assertNotNull(repo.getObjectDatabase());
		assertNotNull(repo.getRefDatabase());
		assertEquals(testRepoName, repo.getRepositoryName());
	}

	@Test
	public void testInsertAndReadBlob() throws Exception {
		byte[] content = "Hello, JGit Hibernate!" //$NON-NLS-1$
				.getBytes(StandardCharsets.UTF_8);
		ObjectId blobId;
		try (ObjectInserter inserter = repo.newObjectInserter()) {
			blobId = inserter.insert(Constants.OBJ_BLOB, content);
			inserter.flush();
		}
		assertNotNull(blobId);
		assertTrue(repo.getObjectDatabase().has(blobId));

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
	public void testInsertAndReadCommit() throws Exception {
		ObjectId commitId = createCommit("Initial commit"); //$NON-NLS-1$
		assertNotNull(commitId);
		assertTrue(repo.getObjectDatabase().has(commitId));
	}

	@Test
	public void testRefCreateAndRead() throws Exception {
		ObjectId commitId = createCommit("Test commit"); //$NON-NLS-1$
		updateRef("refs/heads/main", commitId); //$NON-NLS-1$

		Ref ref = repo.exactRef("refs/heads/main"); //$NON-NLS-1$
		assertNotNull(ref);
		assertEquals(commitId, ref.getObjectId());
	}

	@Test
	public void testRefDelete() throws Exception {
		ObjectId commitId = createCommit("Commit to delete"); //$NON-NLS-1$
		updateRef("refs/heads/tobedeleted", commitId); //$NON-NLS-1$
		assertNotNull(repo.exactRef("refs/heads/tobedeleted")); //$NON-NLS-1$

		RefUpdate ru = repo.updateRef("refs/heads/tobedeleted"); //$NON-NLS-1$
		ru.setForceUpdate(true);
		ru.delete();
		assertNull(repo.exactRef("refs/heads/tobedeleted")); //$NON-NLS-1$
	}

	@Test
	public void testAtomicTransactions() {
		assertTrue(repo.getRefDatabase().performsAtomicTransactions());
	}

	@Test
	public void testMultipleRefs() throws Exception {
		ObjectId c1 = createCommit("main commit"); //$NON-NLS-1$
		updateRef("refs/heads/main", c1); //$NON-NLS-1$
		Ref main = repo.exactRef("refs/heads/main"); //$NON-NLS-1$
		assertNotNull(main);

		ObjectId c2 = createCommit("feature commit"); //$NON-NLS-1$
		updateRef("refs/heads/feature", c2); //$NON-NLS-1$
		Ref feature = repo.exactRef("refs/heads/feature"); //$NON-NLS-1$
		assertNotNull(feature);
		assertEquals(c2, feature.getObjectId());
	}

	// ===== Phase 2: Extended testing =====

	@Test
	public void testTreeWithBlob() throws Exception {
		ObjectId treeId;
		try (ObjectInserter inserter = repo.newObjectInserter()) {
			ObjectId blobId = inserter.insert(Constants.OBJ_BLOB,
					"file content".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
			TreeFormatter tree = new TreeFormatter();
			tree.append("README.md", FileMode.REGULAR_FILE, blobId); //$NON-NLS-1$
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
	public void testCommitWithParent() throws Exception {
		ObjectId parent = createCommit("Parent commit"); //$NON-NLS-1$
		updateRef("refs/heads/main", parent); //$NON-NLS-1$

		ObjectId child;
		try (ObjectInserter inserter = repo.newObjectInserter()) {
			TreeFormatter tree = new TreeFormatter();
			ObjectId treeId = inserter.insert(tree);
			CommitBuilder cb = new CommitBuilder();
			cb.setTreeId(treeId);
			cb.setParentId(parent);
			cb.setAuthor(new PersonIdent("Test", "test@example.com")); //$NON-NLS-1$ //$NON-NLS-2$
			cb.setCommitter(new PersonIdent("Test", "test@example.com")); //$NON-NLS-1$ //$NON-NLS-2$
			cb.setMessage("Child commit"); //$NON-NLS-1$
			child = inserter.insert(cb);
			inserter.flush();
		}

		try (RevWalk rw = new RevWalk(repo)) {
			RevCommit parsed = rw.parseCommit(child);
			assertEquals(1, parsed.getParentCount());
			assertEquals(parent, parsed.getParent(0).getId());
		}
	}

	@Test
	public void testTwoPacksReadBack() throws Exception {
		ObjectId blob1;
		try (ObjectInserter inserter = repo.newObjectInserter()) {
			blob1 = inserter.insert(Constants.OBJ_BLOB,
					"pack1".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
			inserter.flush();
		}
		assertTrue(repo.getObjectDatabase().has(blob1));

		ObjectId blob2;
		try (ObjectInserter inserter = repo.newObjectInserter()) {
			blob2 = inserter.insert(Constants.OBJ_BLOB,
					"pack2".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
			inserter.flush();
		}
		assertTrue(repo.getObjectDatabase().has(blob2));
		assertTrue(repo.getObjectDatabase().has(blob1));
	}

	@Test
	public void testLargeBlob() throws Exception {
		byte[] largeContent = new byte[1024 * 1024]; // 1 MB
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
		}
	}

	// ===== Phase 3: Query layer =====

	@Test
	public void testCommitIndexer() throws Exception {
		ObjectId commitId = createCommitWithFile("Initial commit", //$NON-NLS-1$
				"README.md", "Hello"); //$NON-NLS-1$ //$NON-NLS-2$

		CommitIndexer indexer = new CommitIndexer(
				provider.getSessionFactory(), testRepoName);
		indexer.indexCommit(repo, commitId);

		GitDatabaseQueryService qs = new GitDatabaseQueryService(
				provider.getSessionFactory());
		List<GitCommitIndex> results = qs
				.searchCommitMessages(testRepoName, "Initial"); //$NON-NLS-1$
		assertEquals(1, results.size());
		assertEquals(commitId.name(), results.get(0).getObjectId());
		assertTrue(results.get(0).getChangedPaths().contains("README.md")); //$NON-NLS-1$
	}

	@Test
	public void testSearchByChangedPath() throws Exception {
		ObjectId c1 = createCommitWithFile("Add docs", "docs/README.md", //$NON-NLS-1$ //$NON-NLS-2$
				"readme"); //$NON-NLS-1$
		ObjectId c2 = createCommitWithFile("Add license", "LICENSE", //$NON-NLS-1$ //$NON-NLS-2$
				"MIT"); //$NON-NLS-1$

		CommitIndexer indexer = new CommitIndexer(
				provider.getSessionFactory(), testRepoName);
		indexer.indexCommit(repo, c1);
		indexer.indexCommit(repo, c2);

		GitDatabaseQueryService qs = new GitDatabaseQueryService(
				provider.getSessionFactory());
		assertEquals(1,
				qs.searchByChangedPath(testRepoName, "docs/").size()); //$NON-NLS-1$
		assertEquals(1,
				qs.searchByChangedPath(testRepoName, "LICENSE").size()); //$NON-NLS-1$
	}

	@Test
	public void testAuthorStatistics() throws Exception {
		ObjectId c1 = createCommitWithFile("C1", "a.txt", "a"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		ObjectId c2 = createCommitWithFile("C2", "b.txt", "b"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

		CommitIndexer indexer = new CommitIndexer(
				provider.getSessionFactory(), testRepoName);
		indexer.indexCommit(repo, c1);
		indexer.indexCommit(repo, c2);

		GitDatabaseQueryService qs = new GitDatabaseQueryService(
				provider.getSessionFactory());
		List<AuthorStats> stats = qs.getAuthorStatistics(testRepoName);
		assertFalse(stats.isEmpty());
		assertEquals("Test User", stats.get(0).getAuthorName()); //$NON-NLS-1$
		assertEquals(2, stats.get(0).getCommitCount());
	}

	@Test
	public void testCommitsBetweenTimestamps() throws Exception {
		ObjectId commitId = createCommitWithFile("Timed", "t.txt", "t"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

		CommitIndexer indexer = new CommitIndexer(
				provider.getSessionFactory(), testRepoName);
		indexer.indexCommit(repo, commitId);

		GitDatabaseQueryService qs = new GitDatabaseQueryService(
				provider.getSessionFactory());
		Instant past = Instant.now().minusSeconds(3600);
		Instant future = Instant.now().plusSeconds(3600);
		List<GitCommitIndex> results = qs
				.getCommitsBetween(testRepoName, past, future);
		assertFalse(results.isEmpty());
	}

	// ===== Phase 4: Reflog =====

	@Test
	public void testReflogWriteAndRead() throws Exception {
		HibernateReflogWriter writer = repo.getReflogWriter();
		ObjectId id1 = createCommit("Reflog commit"); //$NON-NLS-1$
		PersonIdent who = new PersonIdent("Test", "test@example.com"); //$NON-NLS-1$ //$NON-NLS-2$
		writer.log("refs/heads/main", ObjectId.zeroId(), id1, who, //$NON-NLS-1$
				"branch: Created"); //$NON-NLS-1$

		ReflogReader reader = repo.getReflogReader("refs/heads/main"); //$NON-NLS-1$
		ReflogEntry entry = reader.getLastEntry();
		assertNotNull(entry);
		assertEquals(id1, entry.getNewId());
		assertEquals("branch: Created", entry.getComment()); //$NON-NLS-1$
	}

	@Test
	public void testReflogMultipleEntries() throws Exception {
		HibernateReflogWriter writer = repo.getReflogWriter();
		ObjectId id1 = createCommit("C1"); //$NON-NLS-1$
		ObjectId id2 = createCommit("C2"); //$NON-NLS-1$
		PersonIdent who = new PersonIdent("Test", "test@example.com"); //$NON-NLS-1$ //$NON-NLS-2$
		writer.log("refs/heads/main", ObjectId.zeroId(), id1, who, "first"); //$NON-NLS-1$ //$NON-NLS-2$
		writer.log("refs/heads/main", id1, id2, who, "second"); //$NON-NLS-1$ //$NON-NLS-2$

		HibernateReflogReader reader = new HibernateReflogReader(
				provider.getSessionFactory(), testRepoName,
				"refs/heads/main"); //$NON-NLS-1$
		List<ReflogEntry> entries = reader.getReverseEntries();
		assertEquals(2, entries.size());
		assertEquals("second", entries.get(0).getComment()); //$NON-NLS-1$
		assertEquals("first", entries.get(1).getComment()); //$NON-NLS-1$
	}

	@Test
	public void testReflogPurge() throws Exception {
		HibernateReflogWriter writer = repo.getReflogWriter();
		ObjectId id1 = createCommit("Purge test"); //$NON-NLS-1$
		PersonIdent who = new PersonIdent("Test", "test@example.com"); //$NON-NLS-1$ //$NON-NLS-2$
		writer.log("refs/heads/main", ObjectId.zeroId(), id1, who, "old"); //$NON-NLS-1$ //$NON-NLS-2$

		GitDatabaseQueryService qs = new GitDatabaseQueryService(
				provider.getSessionFactory());
		int purged = qs.purgeReflogEntries(testRepoName,
				Instant.now().plusSeconds(3600));
		assertEquals(1, purged);

		HibernateReflogReader reader = new HibernateReflogReader(
				provider.getSessionFactory(), testRepoName,
				"refs/heads/main"); //$NON-NLS-1$
		assertNull(reader.getLastEntry());
	}

	// ===== Cross-repository tests (DB-only capability) =====

	@Test
	public void testMultipleRepositoriesInSameDatabase() throws Exception {
		String repoName2 = testRepoName + "-second"; //$NON-NLS-1$
		HibernateRepository repo2 = new HibernateRepositoryBuilder()
				.setSessionFactoryProvider(provider)
				.setRepositoryName(repoName2)
				.setRepositoryDescription(
						new DfsRepositoryDescription(repoName2))
				.build();
		repo2.create(true);

		try {
			// Insert blob in first repo
			ObjectId blobId;
			try (ObjectInserter inserter = repo.newObjectInserter()) {
				blobId = inserter.insert(Constants.OBJ_BLOB,
						"shared content".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
				inserter.flush();
			}
			assertTrue(repo.getObjectDatabase().has(blobId));

			// Insert different blob in second repo
			ObjectId blobId2;
			try (ObjectInserter ins2 = repo2.newObjectInserter()) {
				blobId2 = ins2.insert(Constants.OBJ_BLOB,
						"repo2 content".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
				ins2.flush();
			}
			assertTrue(repo2.getObjectDatabase().has(blobId2));

			// Refs in each repo are independent
			ObjectId c1 = createCommit("repo1 commit"); //$NON-NLS-1$
			updateRef("refs/heads/main", c1); //$NON-NLS-1$

			ObjectId c2;
			try (ObjectInserter ins2 = repo2.newObjectInserter()) {
				TreeFormatter tree = new TreeFormatter();
				ObjectId treeId = ins2.insert(tree);
				CommitBuilder cb = new CommitBuilder();
				cb.setTreeId(treeId);
				cb.setAuthor(
						new PersonIdent("User2", "u2@example.com")); //$NON-NLS-1$ //$NON-NLS-2$
				cb.setCommitter(
						new PersonIdent("User2", "u2@example.com")); //$NON-NLS-1$ //$NON-NLS-2$
				cb.setMessage("repo2 commit"); //$NON-NLS-1$
				c2 = ins2.insert(cb);
				ins2.flush();
			}
			RefUpdate ru2 = repo2.updateRef("refs/heads/main"); //$NON-NLS-1$
			ru2.setNewObjectId(c2);
			ru2.setForceUpdate(true);
			ru2.update();

			// Each repo's main points to its own commit
			Ref r1 = repo.exactRef("refs/heads/main"); //$NON-NLS-1$
			Ref r2 = repo2.exactRef("refs/heads/main"); //$NON-NLS-1$
			assertNotNull(r1);
			assertNotNull(r2);
			assertEquals(c1, r1.getObjectId());
			assertEquals(c2, r2.getObjectId());
			assertFalse(c1.equals(c2));
		} finally {
			repo2.close();
		}
	}

	@Test
	public void testCrossRepositoryCommitSearch() throws Exception {
		String repoName2 = testRepoName + "-search"; //$NON-NLS-1$
		HibernateRepository repo2 = new HibernateRepositoryBuilder()
				.setSessionFactoryProvider(provider)
				.setRepositoryName(repoName2)
				.setRepositoryDescription(
						new DfsRepositoryDescription(repoName2))
				.build();
		repo2.create(true);

		try {
			ObjectId c1 = createCommitWithFile("Fix bug #123", "fix.txt", //$NON-NLS-1$ //$NON-NLS-2$
					"fixed"); //$NON-NLS-1$
			CommitIndexer idx1 = new CommitIndexer(
					provider.getSessionFactory(), testRepoName);
			idx1.indexCommit(repo, c1);

			ObjectId c2;
			try (ObjectInserter ins = repo2.newObjectInserter()) {
				ObjectId blobId = ins.insert(Constants.OBJ_BLOB,
						"other fix".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
				TreeFormatter tree = new TreeFormatter();
				tree.append("other.txt", FileMode.REGULAR_FILE, blobId); //$NON-NLS-1$
				ObjectId treeId = ins.insert(tree);
				CommitBuilder cb = new CommitBuilder();
				cb.setTreeId(treeId);
				cb.setAuthor(
						new PersonIdent("User2", "u2@example.com")); //$NON-NLS-1$ //$NON-NLS-2$
				cb.setCommitter(
						new PersonIdent("User2", "u2@example.com")); //$NON-NLS-1$ //$NON-NLS-2$
				cb.setMessage("Fix bug #456"); //$NON-NLS-1$
				c2 = ins.insert(cb);
				ins.flush();
			}
			CommitIndexer idx2 = new CommitIndexer(
					provider.getSessionFactory(), repoName2);
			idx2.indexCommit(repo2, c2);

			// Search in repo1 only finds repo1 commits
			GitDatabaseQueryService qs = new GitDatabaseQueryService(
					provider.getSessionFactory());
			List<GitCommitIndex> repo1Results = qs
					.searchCommitMessages(testRepoName, "Fix bug"); //$NON-NLS-1$
			assertEquals(1, repo1Results.size());
			assertTrue(repo1Results.get(0).getCommitMessage()
					.contains("#123")); //$NON-NLS-1$

			// Search in repo2 only finds repo2 commits
			List<GitCommitIndex> repo2Results = qs
					.searchCommitMessages(repoName2, "Fix bug"); //$NON-NLS-1$
			assertEquals(1, repo2Results.size());
			assertTrue(repo2Results.get(0).getCommitMessage()
					.contains("#456")); //$NON-NLS-1$
		} finally {
			repo2.close();
		}
	}

	// ===== GC tests =====

	@Test
	public void testGarbageCollectionPurgeReflog() throws Exception {
		// Phase 4: GC can purge old reflog entries
		HibernateReflogWriter writer = repo.getReflogWriter();
		ObjectId id1 = createCommit("GC test"); //$NON-NLS-1$
		PersonIdent who = new PersonIdent("Test", "test@example.com"); //$NON-NLS-1$ //$NON-NLS-2$
		writer.log("refs/heads/main", ObjectId.zeroId(), id1, who, //$NON-NLS-1$
				"gc test entry"); //$NON-NLS-1$

		GitDatabaseQueryService qs = new GitDatabaseQueryService(
				provider.getSessionFactory());
		List<GitReflogEntity> entries = qs
				.getReflogEntries(testRepoName, "refs/heads/main", 10); //$NON-NLS-1$
		assertEquals(1, entries.size());

		int purged = qs.purgeReflogEntries(testRepoName,
				Instant.now().plusSeconds(3600));
		assertEquals(1, purged);

		entries = qs.getReflogEntries(testRepoName, "refs/heads/main", //$NON-NLS-1$
				10);
		assertEquals(0, entries.size());
	}

	// ===== Helpers =====

	/**
	 * Create a commit with an empty tree.
	 *
	 * @param message
	 *            the commit message
	 * @return the new commit's ObjectId
	 * @throws Exception
	 *             on error
	 */
	protected ObjectId createCommit(String message) throws Exception {
		try (ObjectInserter inserter = repo.newObjectInserter()) {
			TreeFormatter tree = new TreeFormatter();
			ObjectId treeId = inserter.insert(tree);
			CommitBuilder cb = new CommitBuilder();
			cb.setTreeId(treeId);
			cb.setAuthor(
					new PersonIdent("Test User", "test@example.com")); //$NON-NLS-1$ //$NON-NLS-2$
			cb.setCommitter(
					new PersonIdent("Test User", "test@example.com")); //$NON-NLS-1$ //$NON-NLS-2$
			cb.setMessage(message);
			ObjectId commitId = inserter.insert(cb);
			inserter.flush();
			return commitId;
		}
	}

	/**
	 * Create a commit containing a single file.
	 *
	 * @param message
	 *            the commit message
	 * @param fileName
	 *            the file name
	 * @param content
	 *            the file content
	 * @return the new commit's ObjectId
	 * @throws Exception
	 *             on error
	 */
	protected ObjectId createCommitWithFile(String message, String fileName,
			String content) throws Exception {
		try (ObjectInserter inserter = repo.newObjectInserter()) {
			ObjectId blobId = inserter.insert(Constants.OBJ_BLOB,
					content.getBytes(StandardCharsets.UTF_8));
			TreeFormatter tree = new TreeFormatter();
			tree.append(fileName, FileMode.REGULAR_FILE, blobId);
			ObjectId treeId = inserter.insert(tree);
			CommitBuilder cb = new CommitBuilder();
			cb.setTreeId(treeId);
			cb.setAuthor(
					new PersonIdent("Test User", "test@example.com")); //$NON-NLS-1$ //$NON-NLS-2$
			cb.setCommitter(
					new PersonIdent("Test User", "test@example.com")); //$NON-NLS-1$ //$NON-NLS-2$
			cb.setMessage(message);
			ObjectId commitId = inserter.insert(cb);
			inserter.flush();
			return commitId;
		}
	}

	/**
	 * Update a ref to point to the given object.
	 *
	 * @param name
	 *            the ref name
	 * @param id
	 *            the target ObjectId
	 * @throws Exception
	 *             on error
	 */
	protected void updateRef(String name, ObjectId id) throws Exception {
		RefUpdate ru = repo.updateRef(name);
		ru.setNewObjectId(id);
		ru.setForceUpdate(true);
		RefUpdate.Result result = ru.update();
		assertTrue("Ref update for " + name + " failed: " + result, //$NON-NLS-1$ //$NON-NLS-2$
				result == RefUpdate.Result.NEW
						|| result == RefUpdate.Result.FORCED
						|| result == RefUpdate.Result.FAST_FORWARD);
	}
}
