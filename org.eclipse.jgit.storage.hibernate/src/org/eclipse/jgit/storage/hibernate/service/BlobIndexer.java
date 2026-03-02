/*
 * Copyright (C) 2026, Carsten Hammer and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.storage.hibernate.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.hibernate.entity.JavaBlobIndex;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.hibernate.Session;
import org.hibernate.SessionFactory;

/**
 * Indexes Java source blobs from Git commits into {@link JavaBlobIndex}
 * entities.
 * <p>
 * Complements {@link CommitIndexer} by walking each commit's tree and
 * extracting structural metadata from {@code .java} files using
 * {@link JavaBlobExtractor}. Blobs larger than 1 MB are skipped.
 * </p>
 */
public class BlobIndexer {

	private static final int MAX_BLOB_SIZE = 1024 * 1024;

	private final SessionFactory sessionFactory;

	private final String repositoryName;

	private final JavaBlobExtractor extractor;

	/**
	 * Create a new blob indexer.
	 *
	 * @param sessionFactory
	 *            the Hibernate session factory
	 * @param repositoryName
	 *            the repository name for partitioning
	 */
	public BlobIndexer(SessionFactory sessionFactory,
			String repositoryName) {
		this.sessionFactory = sessionFactory;
		this.repositoryName = repositoryName;
		this.extractor = new JavaBlobExtractor();
	}

	/**
	 * Index all Java blobs in a commit's tree.
	 *
	 * @param repo
	 *            the repository to read objects from
	 * @param commitId
	 *            the commit object ID whose tree will be walked
	 * @return the number of blobs indexed
	 * @throws IOException
	 *             if an error occurs reading objects
	 */
	public int indexCommitBlobs(Repository repo, ObjectId commitId)
			throws IOException {
		int count = 0;
		try (RevWalk rw = new RevWalk(repo)) {
			RevCommit commit = rw.parseCommit(commitId);
			try (ObjectReader reader = repo.newObjectReader();
					TreeWalk tw = new TreeWalk(reader)) {
				tw.addTree(commit.getTree());
				tw.setRecursive(true);
				while (tw.next()) {
					String path = tw.getPathString();
					if (!path.endsWith(".java")) { //$NON-NLS-1$
						continue;
					}
					ObjectLoader loader = reader.open(tw.getObjectId(0));
					if (loader.getSize() > MAX_BLOB_SIZE) {
						continue;
					}
					String blobOid = tw.getObjectId(0).name();
					if (isAlreadyIndexed(blobOid)) {
						continue;
					}
					String source = new String(loader.getBytes(),
							StandardCharsets.UTF_8);
					JavaBlobIndex idx = extractor.extract(source, path,
							repositoryName, blobOid, commitId.name());
					persist(idx);
					count++;
				}
			}
		}
		return count;
	}

	private void persist(JavaBlobIndex idx) {
		try (Session session = sessionFactory.openSession()) {
			session.beginTransaction();
			session.persist(idx);
			session.getTransaction().commit();
		}
	}

	private boolean isAlreadyIndexed(String blobObjectId) {
		try (Session session = sessionFactory.openSession()) {
			Long count = session.createQuery(
					"SELECT COUNT(j) FROM JavaBlobIndex j WHERE j.repositoryName = :repo AND j.blobObjectId = :oid", //$NON-NLS-1$
					Long.class)
					.setParameter("repo", repositoryName) //$NON-NLS-1$
					.setParameter("oid", blobObjectId) //$NON-NLS-1$
					.uniqueResult();
			return count != null && count > 0;
		}
	}
}
