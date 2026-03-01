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
import java.util.List;

import org.eclipse.jgit.storage.hibernate.entity.GitCommitIndex;
import org.eclipse.jgit.storage.hibernate.entity.GitObjectEntity;
import org.eclipse.jgit.storage.hibernate.entity.GitRefEntity;
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
}
