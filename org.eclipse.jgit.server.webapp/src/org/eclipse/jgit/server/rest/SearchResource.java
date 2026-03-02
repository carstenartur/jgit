/*
 * Copyright (C) 2026, Carsten Hammer and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.server.rest;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import org.eclipse.jgit.storage.hibernate.entity.GitCommitIndex;
import org.eclipse.jgit.storage.hibernate.entity.JavaBlobIndex;
import org.eclipse.jgit.storage.hibernate.service.GitDatabaseQueryService;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * REST endpoint for full-text search over Git commit data and Java source.
 * <ul>
 * <li>{@code GET /api/search/commits?repo=...&amp;q=...} — search commit
 * messages</li>
 * <li>{@code GET /api/search/paths?repo=...&amp;q=...} — search changed
 * paths</li>
 * <li>{@code GET /api/search/types?repo=...&amp;q=...} — search Java
 * types by name or FQN</li>
 * <li>{@code GET /api/search/symbols?repo=...&amp;q=...} — search methods
 * and fields</li>
 * <li>{@code GET /api/search/hierarchy?repo=...&amp;q=...} — find types
 * by supertype</li>
 * <li>{@code GET /api/search/source?repo=...&amp;q=...} — full-text
 * search across source snippets</li>
 * </ul>
 */
public class SearchResource extends HttpServlet {

	private static final long serialVersionUID = 1L;

	private static final Logger LOG = Logger
			.getLogger(SearchResource.class.getName());

	private final HibernateSessionFactoryProvider provider;

	private final Gson gson = new Gson();

	/**
	 * Create a search endpoint.
	 *
	 * @param provider
	 *            the session factory provider
	 */
	public SearchResource(HibernateSessionFactoryProvider provider) {
		this.provider = provider;
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp)
			throws IOException {
		resp.setContentType("application/json"); //$NON-NLS-1$
		resp.setCharacterEncoding("UTF-8"); //$NON-NLS-1$

		String pathInfo = req.getPathInfo();
		if (pathInfo == null) {
			pathInfo = "/"; //$NON-NLS-1$
		}

		String repo = req.getParameter("repo"); //$NON-NLS-1$
		String query = req.getParameter("q"); //$NON-NLS-1$

		if (repo == null || repo.isEmpty() || query == null
				|| query.isEmpty()) {
			resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			try (PrintWriter w = resp.getWriter()) {
				w.write("{\"error\":\"Parameters 'repo' and 'q' are required\"}"); //$NON-NLS-1$
			}
			return;
		}

		try {
			GitDatabaseQueryService queryService = new GitDatabaseQueryService(
					provider.getSessionFactory());

			if (pathInfo.startsWith("/commits")) { //$NON-NLS-1$
				handleCommitSearch(queryService, repo, query, resp);
			} else if (pathInfo.startsWith("/paths")) { //$NON-NLS-1$
				handlePathSearch(queryService, repo, query, resp);
			} else if (pathInfo.startsWith("/types")) { //$NON-NLS-1$
				handleTypeSearch(queryService, repo, query, resp);
			} else if (pathInfo.startsWith("/symbols")) { //$NON-NLS-1$
				handleSymbolSearch(queryService, repo, query, resp);
			} else if (pathInfo.startsWith("/hierarchy")) { //$NON-NLS-1$
				handleHierarchySearch(queryService, repo, query, resp);
			} else if (pathInfo.startsWith("/source")) { //$NON-NLS-1$
				handleSourceSearch(queryService, repo, query, resp);
			} else {
				// Default: search commits
				handleCommitSearch(queryService, repo, query, resp);
			}
		} catch (Exception e) {
			LOG.log(Level.WARNING, "Search error", e); //$NON-NLS-1$
			resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			try (PrintWriter w = resp.getWriter()) {
				w.write("{\"error\":\"Search failed\"}"); //$NON-NLS-1$
			}
		}
	}

	private void handleCommitSearch(GitDatabaseQueryService queryService,
			String repo, String query, HttpServletResponse resp)
			throws IOException {
		List<GitCommitIndex> results = queryService
				.searchCommitMessages(repo, query);

		JsonObject response = new JsonObject();
		response.addProperty("query", query); //$NON-NLS-1$
		response.addProperty("repository", repo); //$NON-NLS-1$
		response.addProperty("totalResults", results.size()); //$NON-NLS-1$

		JsonArray items = new JsonArray();
		for (GitCommitIndex ci : results) {
			JsonObject item = new JsonObject();
			item.addProperty("objectId", ci.getObjectId()); //$NON-NLS-1$
			item.addProperty("message", ci.getCommitMessage()); //$NON-NLS-1$
			item.addProperty("author", ci.getAuthorName()); //$NON-NLS-1$
			item.addProperty("authorEmail", ci.getAuthorEmail()); //$NON-NLS-1$
			items.add(item);
		}
		response.add("results", items); //$NON-NLS-1$

		resp.setStatus(HttpServletResponse.SC_OK);
		try (PrintWriter w = resp.getWriter()) {
			w.write(gson.toJson(response));
		}
	}

	private void handlePathSearch(GitDatabaseQueryService queryService,
			String repo, String query, HttpServletResponse resp)
			throws IOException {
		List<GitCommitIndex> results = queryService
				.searchByChangedPath(repo, query);

		JsonObject response = new JsonObject();
		response.addProperty("query", query); //$NON-NLS-1$
		response.addProperty("repository", repo); //$NON-NLS-1$
		response.addProperty("totalResults", results.size()); //$NON-NLS-1$

		JsonArray items = new JsonArray();
		for (GitCommitIndex ci : results) {
			JsonObject item = new JsonObject();
			item.addProperty("objectId", ci.getObjectId()); //$NON-NLS-1$
			item.addProperty("message", ci.getCommitMessage()); //$NON-NLS-1$
			item.addProperty("changedPaths", ci.getChangedPaths()); //$NON-NLS-1$
			items.add(item);
		}
		response.add("results", items); //$NON-NLS-1$

		resp.setStatus(HttpServletResponse.SC_OK);
		try (PrintWriter w = resp.getWriter()) {
			w.write(gson.toJson(response));
		}
	}

	private void handleTypeSearch(GitDatabaseQueryService queryService,
			String repo, String query, HttpServletResponse resp)
			throws IOException {
		List<JavaBlobIndex> results = queryService.searchByType(repo, query);
		writeJavaBlobResponse(results, repo, query, resp);
	}

	private void handleSymbolSearch(GitDatabaseQueryService queryService,
			String repo, String query, HttpServletResponse resp)
			throws IOException {
		List<JavaBlobIndex> results = queryService.searchBySymbol(repo,
				query);
		writeJavaBlobResponse(results, repo, query, resp);
	}

	private void handleHierarchySearch(GitDatabaseQueryService queryService,
			String repo, String query, HttpServletResponse resp)
			throws IOException {
		List<JavaBlobIndex> results = queryService.searchByHierarchy(repo,
				query);
		writeJavaBlobResponse(results, repo, query, resp);
	}

	private void handleSourceSearch(GitDatabaseQueryService queryService,
			String repo, String query, HttpServletResponse resp)
			throws IOException {
		List<JavaBlobIndex> results = queryService
				.searchSourceContent(repo, query);
		writeJavaBlobResponse(results, repo, query, resp);
	}

	private void writeJavaBlobResponse(List<JavaBlobIndex> results,
			String repo, String query, HttpServletResponse resp)
			throws IOException {
		JsonObject response = new JsonObject();
		response.addProperty("query", query); //$NON-NLS-1$
		response.addProperty("repository", repo); //$NON-NLS-1$
		response.addProperty("totalResults", results.size()); //$NON-NLS-1$

		JsonArray items = new JsonArray();
		for (JavaBlobIndex jbi : results) {
			JsonObject item = new JsonObject();
			item.addProperty("blobObjectId", jbi.getBlobObjectId()); //$NON-NLS-1$
			item.addProperty("commitObjectId", //$NON-NLS-1$
					jbi.getCommitObjectId());
			item.addProperty("filePath", jbi.getFilePath()); //$NON-NLS-1$
			item.addProperty("packageName", jbi.getPackageName()); //$NON-NLS-1$
			item.addProperty("declaredTypes", //$NON-NLS-1$
					jbi.getDeclaredTypes());
			item.addProperty("fullyQualifiedNames", //$NON-NLS-1$
					jbi.getFullyQualifiedNames());
			item.addProperty("declaredMethods", //$NON-NLS-1$
					jbi.getDeclaredMethods());
			item.addProperty("declaredFields", //$NON-NLS-1$
					jbi.getDeclaredFields());
			item.addProperty("extendsTypes", //$NON-NLS-1$
					jbi.getExtendsTypes());
			item.addProperty("implementsTypes", //$NON-NLS-1$
					jbi.getImplementsTypes());
			items.add(item);
		}
		response.add("results", items); //$NON-NLS-1$

		resp.setStatus(HttpServletResponse.SC_OK);
		try (PrintWriter w = resp.getWriter()) {
			w.write(gson.toJson(response));
		}
	}
}
