/*
 * Copyright (C) 2025, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.storage.hibernate.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jgit.storage.hibernate.entity.GitCommitIndex;
import org.eclipse.jgit.storage.hibernate.entity.GitObjectEntity;
import org.eclipse.jgit.storage.hibernate.entity.GitRefEntity;
import org.eclipse.jgit.storage.hibernate.entity.GitReflogEntity;
import org.hibernate.Session;
import org.hibernate.SessionFactory;

/**
 * Extended query service that leverages the relational database for operations
 * impossible with filesystem-based Git storage.
 * <p>
 * This service provides SQL-based queries over Git data, including full-text
 * search across commit messages, author statistics, cross-repository object
 * deduplication, and time-based queries.
 */
public class GitDatabaseQueryService {

	private final SessionFactory sessionFactory;

	/**
	 * Create a new query service.
	 *
	 * @param sessionFactory
	 *            the Hibernate session factory
	 */
	public GitDatabaseQueryService(SessionFactory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}

	/**
	 * Search commit messages using SQL LIKE.
	 *
	 * @param repoName
	 *            the repository name
	 * @param query
	 *            the search query
	 * @return matching commit index entries
	 */
	public List<GitCommitIndex> searchCommitMessages(String repoName,
			String query) {
		try (Session session = sessionFactory.openSession()) {
			return session.createQuery(
					"FROM GitCommitIndex c WHERE c.repositoryName = :repo AND c.commitMessage LIKE :q", //$NON-NLS-1$
					GitCommitIndex.class).setParameter("repo", repoName) //$NON-NLS-1$
					.setParameter("q", "%" + query + "%").getResultList(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
	}

	/**
	 * Find repositories containing a specific object.
	 *
	 * @param objectId
	 *            the SHA-1 hex string
	 * @return list of repository names containing this object
	 */
	public List<String> findRepositoriesContainingObject(String objectId) {
		try (Session session = sessionFactory.openSession()) {
			return session.createQuery(
					"SELECT DISTINCT o.repositoryName FROM GitObjectEntity o WHERE o.objectId = :oid", //$NON-NLS-1$
					String.class).setParameter("oid", objectId) //$NON-NLS-1$
					.getResultList();
		}
	}

	/**
	 * Get commits between two timestamps.
	 *
	 * @param repoName
	 *            the repository name
	 * @param start
	 *            start of the time range (inclusive)
	 * @param end
	 *            end of the time range (inclusive)
	 * @return matching commit index entries
	 */
	public List<GitCommitIndex> getCommitsBetween(String repoName,
			Instant start, Instant end) {
		try (Session session = sessionFactory.openSession()) {
			return session.createQuery(
					"FROM GitCommitIndex c WHERE c.repositoryName = :repo AND c.commitTime BETWEEN :start AND :end", //$NON-NLS-1$
					GitCommitIndex.class).setParameter("repo", repoName) //$NON-NLS-1$
					.setParameter("start", start).setParameter("end", end) //$NON-NLS-1$ //$NON-NLS-2$
					.getResultList();
		}
	}

	/**
	 * Get refs modified since a given timestamp.
	 *
	 * @param repoName
	 *            the repository name
	 * @param since
	 *            the cutoff timestamp
	 * @return refs modified after the given timestamp
	 */
	public List<GitRefEntity> getRefsModifiedSince(String repoName,
			Instant since) {
		try (Session session = sessionFactory.openSession()) {
			return session.createQuery(
					"FROM GitRefEntity r WHERE r.repositoryName = :repo AND r.updatedAt >= :since", //$NON-NLS-1$
					GitRefEntity.class).setParameter("repo", repoName) //$NON-NLS-1$
					.setParameter("since", since).getResultList(); //$NON-NLS-1$
		}
	}

	/**
	 * Count objects by type in a repository.
	 *
	 * @param repoName
	 *            the repository name
	 * @param objectType
	 *            the object type constant
	 * @return count of objects of the given type
	 */
	public long countObjectsByType(String repoName, int objectType) {
		try (Session session = sessionFactory.openSession()) {
			Long count = session.createQuery(
					"SELECT COUNT(o) FROM GitObjectEntity o WHERE o.repositoryName = :repo AND o.objectType = :type", //$NON-NLS-1$
					Long.class).setParameter("repo", repoName) //$NON-NLS-1$
					.setParameter("type", objectType).uniqueResult(); //$NON-NLS-1$
			return count != null ? count : 0;
		}
	}

	/**
	 * Get all objects in a repository.
	 *
	 * @param repoName
	 *            the repository name
	 * @return all object entities
	 */
	public List<GitObjectEntity> getAllObjects(String repoName) {
		try (Session session = sessionFactory.openSession()) {
			return session.createQuery(
					"FROM GitObjectEntity o WHERE o.repositoryName = :repo", //$NON-NLS-1$
					GitObjectEntity.class).setParameter("repo", repoName) //$NON-NLS-1$
					.getResultList();
		}
	}

	/**
	 * Search commits by changed path pattern.
	 *
	 * @param repoName
	 *            the repository name
	 * @param pathPattern
	 *            the path pattern to search for
	 * @return matching commit index entries
	 */
	public List<GitCommitIndex> searchByChangedPath(String repoName,
			String pathPattern) {
		try (Session session = sessionFactory.openSession()) {
			return session.createQuery(
					"FROM GitCommitIndex c WHERE c.repositoryName = :repo AND c.changedPaths LIKE :path", //$NON-NLS-1$
					GitCommitIndex.class).setParameter("repo", repoName) //$NON-NLS-1$
					.setParameter("path", "%" + pathPattern + "%") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					.getResultList();
		}
	}

	/**
	 * Get author statistics for a repository. Returns a list of
	 * [authorName, authorEmail, commitCount] arrays.
	 *
	 * @param repoName
	 *            the repository name
	 * @return list of author statistics as Object arrays
	 */
	public List<AuthorStats> getAuthorStatistics(String repoName) {
		try (Session session = sessionFactory.openSession()) {
			List<Object[]> rows = session.createQuery(
					"SELECT c.authorName, c.authorEmail, COUNT(c) FROM GitCommitIndex c WHERE c.repositoryName = :repo GROUP BY c.authorName, c.authorEmail ORDER BY COUNT(c) DESC", //$NON-NLS-1$
					Object[].class).setParameter("repo", repoName) //$NON-NLS-1$
					.getResultList();
			List<AuthorStats> result = new ArrayList<>(rows.size());
			for (Object[] row : rows) {
				result.add(new AuthorStats((String) row[0], (String) row[1],
						(Long) row[2]));
			}
			return result;
		}
	}

	/**
	 * Get reflog entries for a specific ref.
	 *
	 * @param repoName
	 *            the repository name
	 * @param refName
	 *            the reference name
	 * @param max
	 *            maximum number of entries to return
	 * @return reflog entities in reverse chronological order
	 */
	public List<GitReflogEntity> getReflogEntries(String repoName,
			String refName, int max) {
		try (Session session = sessionFactory.openSession()) {
			return session.createQuery(
					"FROM GitReflogEntity r WHERE r.repositoryName = :repo AND r.refName = :ref ORDER BY r.id DESC", //$NON-NLS-1$
					GitReflogEntity.class)
					.setParameter("repo", repoName) //$NON-NLS-1$
					.setParameter("ref", refName) //$NON-NLS-1$
					.setMaxResults(max).getResultList();
		}
	}

	/**
	 * Delete reflog entries older than a given timestamp.
	 *
	 * @param repoName
	 *            the repository name
	 * @param before
	 *            the cutoff timestamp
	 * @return number of entries deleted
	 */
	public int purgeReflogEntries(String repoName, Instant before) {
		try (Session session = sessionFactory.openSession()) {
			session.beginTransaction();
			int deleted = session.createMutationQuery(
					"DELETE FROM GitReflogEntity r WHERE r.repositoryName = :repo AND r.when < :before") //$NON-NLS-1$
					.setParameter("repo", repoName) //$NON-NLS-1$
					.setParameter("before", before) //$NON-NLS-1$
					.executeUpdate();
			session.getTransaction().commit();
			return deleted;
		}
	}

	/**
	 * Find pack names that are not referenced by any current pack description.
	 * <p>
	 * This can identify orphaned pack data left after failed operations.
	 *
	 * @param repoName
	 *            the repository name
	 * @return list of orphaned pack names
	 */
	public List<String> findOrphanedPacks(String repoName) {
		try (Session session = sessionFactory.openSession()) {
			return session.createQuery(
					"SELECT DISTINCT p.packName FROM GitPackEntity p " //$NON-NLS-1$
							+ "WHERE p.repositoryName = :repo " //$NON-NLS-1$
							+ "AND NOT EXISTS (" //$NON-NLS-1$
							+ "SELECT 1 FROM GitPackEntity p2 " //$NON-NLS-1$
							+ "WHERE p2.repositoryName = :repo " //$NON-NLS-1$
							+ "AND p2.packName = p.packName " //$NON-NLS-1$
							+ "AND p2.packExtension = 'pack')", //$NON-NLS-1$
					String.class)
					.setParameter("repo", repoName) //$NON-NLS-1$
					.getResultList();
		}
	}

	/**
	 * Get the total count of pack files in a repository.
	 *
	 * @param repoName
	 *            the repository name
	 * @return the number of distinct pack files
	 */
	public long countPacks(String repoName) {
		try (Session session = sessionFactory.openSession()) {
			Long count = session.createQuery(
					"SELECT COUNT(DISTINCT p.packName) FROM GitPackEntity p WHERE p.repositoryName = :repo", //$NON-NLS-1$
					Long.class).setParameter("repo", repoName) //$NON-NLS-1$
					.uniqueResult();
			return count != null ? count : 0;
		}
	}

	/**
	 * Get the total storage size (in bytes) of all packs in a repository.
	 *
	 * @param repoName
	 *            the repository name
	 * @return the total pack data size in bytes
	 */
	public long getTotalPackSize(String repoName) {
		try (Session session = sessionFactory.openSession()) {
			Long size = session.createQuery(
					"SELECT COALESCE(SUM(p.fileSize), 0) FROM GitPackEntity p WHERE p.repositoryName = :repo", //$NON-NLS-1$
					Long.class).setParameter("repo", repoName) //$NON-NLS-1$
					.uniqueResult();
			return size != null ? size : 0;
		}
	}

	/**
	 * Author statistics record.
	 */
	public static class AuthorStats {
		private final String authorName;

		private final String authorEmail;

		private final long commitCount;

		/**
		 * Create author statistics.
		 *
		 * @param authorName
		 *            the author name
		 * @param authorEmail
		 *            the author email
		 * @param commitCount
		 *            the number of commits
		 */
		public AuthorStats(String authorName, String authorEmail,
				long commitCount) {
			this.authorName = authorName;
			this.authorEmail = authorEmail;
			this.commitCount = commitCount;
		}

		/**
		 * Get the author name.
		 *
		 * @return the authorName
		 */
		public String getAuthorName() {
			return authorName;
		}

		/**
		 * Get the author email.
		 *
		 * @return the authorEmail
		 */
		public String getAuthorEmail() {
			return authorEmail;
		}

		/**
		 * Get the commit count.
		 *
		 * @return the commitCount
		 */
		public long getCommitCount() {
			return commitCount;
		}
	}
}
