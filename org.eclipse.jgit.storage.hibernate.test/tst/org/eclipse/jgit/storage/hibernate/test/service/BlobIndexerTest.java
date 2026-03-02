/*
 * Copyright (C) 2026, Carsten Hammer and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.storage.hibernate.test.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import java.nio.charset.StandardCharsets;
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
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import org.eclipse.jgit.storage.hibernate.entity.JavaBlobIndex;
import org.eclipse.jgit.storage.hibernate.repository.HibernateRepository;
import org.eclipse.jgit.storage.hibernate.repository.HibernateRepositoryBuilder;
import org.eclipse.jgit.storage.hibernate.service.BlobIndexer;
import org.eclipse.jgit.storage.hibernate.service.GitDatabaseQueryService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration tests for {@link BlobIndexer} with H2 in-memory database.
 */
public class BlobIndexerTest {

	private static final AtomicInteger TEST_COUNTER = new AtomicInteger();

	private HibernateSessionFactoryProvider provider;

	private HibernateRepository repo;

	private String testRepoName;

	@Before
	public void setUp() throws Exception {
		DfsBlockCache.reconfigure(new DfsBlockCacheConfig());

		testRepoName = "blob-idx-test-" + TEST_COUNTER.incrementAndGet();

		Properties props = new Properties();
		props.put("hibernate.connection.url",
				"jdbc:h2:mem:" + testRepoName + ";DB_CLOSE_DELAY=-1");
		props.put("hibernate.connection.driver_class", "org.h2.Driver");
		props.put("hibernate.dialect",
				"org.hibernate.dialect.H2Dialect");
		props.put("hibernate.hbm2ddl.auto", "create-drop");
		props.put("hibernate.show_sql", "false");
		props.put("hibernate.search.backend.directory.type", "local-heap");

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

	@Test
	public void testIndexJavaBlob() throws Exception {
		String javaSource = "package org.example;\n\n"
				+ "public class HelloWorld {\n"
				+ "    public static void main(String[] args) {\n"
				+ "        System.out.println(\"Hello\");\n"
				+ "    }\n" + "}\n";

		ObjectId commitId = createCommitWithFile(
				"Add HelloWorld.java",
				"src/main/java/org/example/HelloWorld.java",
				javaSource);

		BlobIndexer indexer = new BlobIndexer(provider.getSessionFactory(),
				testRepoName);
		int count = indexer.indexCommitBlobs(repo, commitId);
		assertEquals("Should index 1 Java blob", 1, count);

		// Verify searchable via query service
		GitDatabaseQueryService queryService = new GitDatabaseQueryService(
				provider.getSessionFactory());
		List<JavaBlobIndex> typeResults = queryService
				.searchByType(testRepoName, "HelloWorld");
		assertFalse("Should find HelloWorld by type search",
				typeResults.isEmpty());
		assertEquals("org.example",
				typeResults.get(0).getPackageName());
	}

	@Test
	public void testIndexSkipsNonJavaFiles() throws Exception {
		ObjectId commitId = createCommitWithFile("Add readme",
				"README.md", "# Hello World");

		BlobIndexer indexer = new BlobIndexer(provider.getSessionFactory(),
				testRepoName);
		int count = indexer.indexCommitBlobs(repo, commitId);
		assertEquals("Should skip non-Java files", 0, count);
	}

	@Test
	public void testIndexSkipsAlreadyIndexed() throws Exception {
		String javaSource = "package org.example;\n"
				+ "public class Foo {}\n";

		ObjectId commitId = createCommitWithFile("Add Foo.java",
				"src/Foo.java", javaSource);

		BlobIndexer indexer = new BlobIndexer(provider.getSessionFactory(),
				testRepoName);
		int count1 = indexer.indexCommitBlobs(repo, commitId);
		assertEquals(1, count1);

		// Re-indexing the same commit should skip
		int count2 = indexer.indexCommitBlobs(repo, commitId);
		assertEquals(0, count2);
	}

	@Test
	public void testSearchBySymbol() throws Exception {
		String javaSource = "package org.example;\n\n"
				+ "public class Calculator {\n"
				+ "    private int result;\n"
				+ "    public int add(int a, int b) { return a + b; }\n"
				+ "    public void reset() { result = 0; }\n"
				+ "}\n";

		ObjectId commitId = createCommitWithFile("Add Calculator",
				"src/org/example/Calculator.java", javaSource);

		BlobIndexer indexer = new BlobIndexer(provider.getSessionFactory(),
				testRepoName);
		indexer.indexCommitBlobs(repo, commitId);

		GitDatabaseQueryService queryService = new GitDatabaseQueryService(
				provider.getSessionFactory());

		List<JavaBlobIndex> symbolResults = queryService
				.searchBySymbol(testRepoName, "add");
		assertFalse("Should find 'add' method",
				symbolResults.isEmpty());

		List<JavaBlobIndex> fieldResults = queryService
				.searchBySymbol(testRepoName, "result");
		assertFalse("Should find 'result' field",
				fieldResults.isEmpty());
	}

	@Test
	public void testSearchByHierarchy() throws Exception {
		String javaSource = "package org.example;\n\n"
				+ "import java.util.ArrayList;\n"
				+ "import java.io.Serializable;\n\n"
				+ "public class MyList extends ArrayList implements Serializable {\n"
				+ "}\n";

		ObjectId commitId = createCommitWithFile("Add MyList",
				"src/org/example/MyList.java", javaSource);

		BlobIndexer indexer = new BlobIndexer(provider.getSessionFactory(),
				testRepoName);
		indexer.indexCommitBlobs(repo, commitId);

		GitDatabaseQueryService queryService = new GitDatabaseQueryService(
				provider.getSessionFactory());

		List<JavaBlobIndex> hierResults = queryService
				.searchByHierarchy(testRepoName, "java.util.ArrayList");
		assertFalse("Should find MyList as subtype of ArrayList",
				hierResults.isEmpty());
	}

	private ObjectId createCommitWithFile(String message, String filePath,
			String content) throws Exception {
		try (ObjectInserter inserter = repo.newObjectInserter()) {
			ObjectId blobId = inserter.insert(Constants.OBJ_BLOB,
					content.getBytes(StandardCharsets.UTF_8));

			ObjectId treeId = buildTree(inserter, filePath, blobId);

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

	private static ObjectId buildTree(ObjectInserter inserter,
			String filePath, ObjectId blobId) throws Exception {
		int slash = filePath.indexOf('/');
		if (slash < 0) {
			TreeFormatter tree = new TreeFormatter();
			tree.append(filePath, FileMode.REGULAR_FILE, blobId);
			return inserter.insert(tree);
		}
		String dir = filePath.substring(0, slash);
		String rest = filePath.substring(slash + 1);
		ObjectId childTreeId = buildTree(inserter, rest, blobId);
		TreeFormatter tree = new TreeFormatter();
		tree.append(dir, FileMode.TREE, childTreeId);
		return inserter.insert(tree);
	}
}
