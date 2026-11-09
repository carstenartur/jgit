/*
 * Copyright (C) 2026, Carsten Hammer and contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.internal.storage.dfs;

import static org.eclipse.jgit.internal.storage.pack.PackExt.REFTABLE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.function.LongUnaryOperator;

import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.junit.Test;

public class DfsReftableCommitCompactionTest {
	private static final String FIRST = "refs/heads/first";
	private static final String SECOND = "refs/heads/second";

	@Test
	public void defaultCompactsTheSmallTopTable() throws Exception {
		assertStackSize(null, 1);
	}

	@Test
	public void zeroLeavesBothTables() throws Exception {
		assertStackSize(size -> 0, 2);
	}

	@Test
	public void limitAtPreviousTableSizeAllowsCompaction() throws Exception {
		assertStackSize(LongUnaryOperator.identity(), 1);
	}

	@Test
	public void limitBelowPreviousTableSizeLeavesBothTables() throws Exception {
		assertStackSize(size -> size - 1, 2);
	}

	private static void assertStackSize(LongUnaryOperator limit, int expected)
			throws Exception {
		try (TestRepository<InMemoryRepository> git = new TestRepository<>(
				new InMemoryRepository(new DfsRepositoryDescription("test")))) {
			InMemoryRepository repo = git.getRepository();
			ObjectId commit = git.commit().create();
			createRef(repo, FIRST, commit);
			DfsReftable[] seed = repo.getObjectDatabase().getReftables();
			assertEquals(1, seed.length);
			long oldSize = seed[0].getPackDescription().getFileSize(REFTABLE);
			assertTrue(oldSize > 1);
			if (limit != null) {
				repo.getConfig().setLong("reftable", null,
						"maxCommitCompactionBytes", limit.applyAsLong(oldSize));
			}

			createRef(repo, SECOND, commit);
			assertEquals(expected, repo.getObjectDatabase().getReftables().length);
			assertEquals(commit, repo.exactRef(FIRST).getObjectId());
			assertEquals(commit, repo.exactRef(SECOND).getObjectId());
		}
	}

	private static void createRef(InMemoryRepository repo, String name,
			ObjectId commit) throws Exception {
		RefUpdate update = repo.updateRef(name);
		update.setExpectedOldObjectId(ObjectId.zeroId());
		update.setNewObjectId(commit);
		update.disableRefLog();
		assertEquals(RefUpdate.Result.NEW, update.update());
	}
}
