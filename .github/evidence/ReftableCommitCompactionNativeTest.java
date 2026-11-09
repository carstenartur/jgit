/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static org.eclipse.jgit.internal.storage.pack.PackExt.REFTABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationKind;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCache;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCacheConfig;
import org.eclipse.jgit.internal.storage.dfs.DfsReftableBatchRefUpdate;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

class ReftableCommitCompactionNativeTest {
  private static final int SEED_REFS = 8192;
  private static final int REF_UPDATES = 32;
  private static final int ROUNDS = 6;
  private static final int PROXY_PORT = 8666;
  private static final long CAP = 64 * 1024L;
  private static final long DEFAULT_MAX = 3 * 1024 * 1024L;
  private static final String PREFIX = "refs/heads/";
  private static final String KEY = "maxCommitCompactionBytes";

  private enum Mode {
    DEFAULT,
    CAP_64_KIB,
    APPEND_ONLY
  }

  @Test
  void comparesRefCommitAndColdLookupOnPostgresql() throws Exception {
    URI codeSource = DfsReftableBatchRefUpdate.class.getProtectionDomain()
        .getCodeSource().getLocation().toURI();
    Path expectedJar = Path.of(System.getProperty("benchmark.refCompaction.jgitJar")).toRealPath();
    assertEquals(expectedJar, Path.of(codeSource).toRealPath());
    int latency = Integer.getInteger("benchmark.refCompaction.rttMillis", 0);
    assertTrue(latency == 0 || latency == 20);
    Path output = Path.of(System.getProperty("benchmark.refCompaction.output")).toAbsolutePath();
    Files.createDirectories(output.getParent());
    Files.writeString(output, """
        round,position,mode,requested_rtt_ms,observed_rtt_ns,seed_table_bytes,first_ref_commit_ns,elapsed_ns,p95_ref_commit_ns,ref_lock_held_ns,db_payload_read_bytes,db_payload_written_bytes,table_count_before,table_count_after,cold_verify_ns
        """, StandardOpenOption.CREATE_NEW);
    BenchmarkConnectionProperties.suppressConnectionMetadataLogging();
    try (Network network = Network.newNetwork();
        PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.10-alpine")
            .withDatabaseName("ref_budget")
            .withUsername("benchmark")
            .withPassword("benchmark")
            .withNetwork(network)
            .withNetworkAliases("ref-postgres")
            .withCommand("postgres", "-c", "fsync=on", "-c", "synchronous_commit=on");
        ToxiproxyContainer toxiproxy = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.12.0")
            .withNetwork(network)) {
      postgres.start();
      toxiproxy.start();
      var client = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());
      var proxy = client.createProxy("refs", "0.0.0.0:" + PROXY_PORT, "ref-postgres:5432");
      if (latency > 0) {
        proxy.toxics().latency("up", ToxicDirection.UPSTREAM, latency / 2);
        proxy.toxics().latency("down", ToxicDirection.DOWNSTREAM, latency / 2);
      }
      String url = "jdbc:postgresql://" + toxiproxy.getHost() + ":"
          + toxiproxy.getMappedPort(PROXY_PORT) + "/" + postgres.getDatabaseName();
      long rttNanos = calibrate(url, postgres);
      assertTrue(rttNanos >= latency * 1_000_000L && rttNanos <= (latency + 30) * 1_000_000L);
      try (HibernateSessionFactoryProvider provider =
          new HibernateSessionFactoryProvider(properties(url, postgres))) {
        for (int round = 0; round <= ROUNDS; round++) {
          for (int position = 0; position < Mode.values().length; position++) {
            Mode mode = Mode.values()[(round + position) % Mode.values().length];
            String evidence = measure(provider, round, position, mode, latency, rttNanos);
            if (round > 0) {
              Files.writeString(output, evidence, StandardOpenOption.APPEND);
            }
          }
        }
      }
    }
  }

  private static Properties properties(String url, PostgreSQLContainer postgres) {
    Properties props = new Properties();
    props.setProperty("hibernate.connection.url", url);
    props.setProperty("hibernate.connection.username", postgres.getUsername());
    props.setProperty("hibernate.connection.password", postgres.getPassword());
    props.setProperty("hibernate.connection.driver_class", "org.postgresql.Driver");
    props.setProperty("hibernate.hikari.minimumIdle", "1");
    props.setProperty("hibernate.hikari.maximumPoolSize", "4");
    props.setProperty("hibernate.hbm2ddl.auto", "create-drop");
    props.setProperty("hibernate.search.enabled", "false");
    props.setProperty(HibernateTransactionContext.METRICS_ENABLED_PROPERTY, "true");
    return props;
  }

  private static long calibrate(String url, PostgreSQLContainer postgres) throws Exception {
    long[] nanos = new long[15];
    try (var connection = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
        var statement = connection.createStatement()) {
      try (var row = statement.executeQuery(
          "select current_setting('fsync'), current_setting('synchronous_commit')")) {
        assertTrue(row.next());
        assertEquals("on", row.getString(1));
        assertEquals("on", row.getString(2));
      }
      for (int i = -5; i < nanos.length; i++) {
        long started = System.nanoTime();
        try (var row = statement.executeQuery("select 1")) {
          assertTrue(row.next());
          assertEquals(1, row.getInt(1));
        }
        if (i >= 0) {
          nanos[i] = System.nanoTime() - started;
        }
      }
    }
    Arrays.sort(nanos);
    return nanos[nanos.length / 2];
  }

  private static String measure(HibernateSessionFactoryProvider provider, int round, int position,
      Mode mode, int latency, long rttNanos) throws Exception {
    String name = "ref-budget-" + round + "-" + position;
    Set<String> expected = new HashSet<>();
    long[] durations = new long[REF_UPDATES];
    long seedBytes;
    long elapsed;
    long heldNanos;
    long readBytes;
    long writtenBytes;
    int initialTables;
    int finalTables;
    ObjectId commit;
    try (HibernateRepository repo = HibernateRepository.create(provider.getSessionFactory(), name)) {
      repo.create(true);
      commit = createCommit(repo);
      seed(repo, commit, expected);
      var tables = repo.getObjectDatabase().getReftables();
      initialTables = tables.length;
      seedBytes = tables[initialTables - 1].getPackDescription().getFileSize(REFTABLE);
      assertTrue(seedBytes > CAP && seedBytes <= DEFAULT_MAX, "seed must straddle both budgets");
      if (mode != Mode.DEFAULT) {
        repo.getConfig().setLong("reftable", null, KEY, mode == Mode.CAP_64_KIB ? CAP : 0);
      }
      var before = repo.getStorageOperationBreakdown().metrics(StorageOperationKind.REF_PUBLICATION);
      var beforeBytes = repo.getStorageByteMetrics();
      long started = System.nanoTime();
      int firstTables = 0;
      for (int i = 0; i < REF_UPDATES; i++) {
        String ref = PREFIX + "update/" + i;
        long refStarted = System.nanoTime();
        RefUpdate update = repo.updateRef(ref);
        update.setExpectedOldObjectId(ObjectId.zeroId());
        update.setNewObjectId(commit);
        update.disableRefLog();
        assertEquals(RefUpdate.Result.NEW, update.update(), ref);
        durations[i] = System.nanoTime() - refStarted;
        expected.add(ref);
        if (i == 0) {
          firstTables = repo.getObjectDatabase().getReftables().length;
        }
      }
      elapsed = System.nanoTime() - started;
      var operations = repo.getStorageOperationBreakdown().metrics(StorageOperationKind.REF_PUBLICATION)
          .minus(before);
      var bytes = repo.getStorageByteMetrics().minus(beforeBytes);
      heldNanos = operations.repositoryLockHeldNanos();
      readBytes = bytes.databasePayloadBytesRead();
      writtenBytes = bytes.databasePayloadBytesWritten();
      finalTables = repo.getObjectDatabase().getReftables().length;
      assertEquals(mode == Mode.DEFAULT ? initialTables : initialTables + 1, firstTables,
          "first update must take the intended compaction decision");
      if (mode == Mode.APPEND_ONLY) {
        assertEquals(initialTables + REF_UPDATES, finalTables);
      }
      assertEquals(REF_UPDATES, operations.transactionsCommitted());
      assertEquals(0, operations.transactionsRolledBack());
      assertTrue(heldNanos > 0 && writtenBytes > 0);
    }
    DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
    long lookupStarted = System.nanoTime();
    try (HibernateRepository reader = HibernateRepository.create(provider.getSessionFactory(), name)) {
      var refs = reader.getRefDatabase().getRefsByPrefix(PREFIX);
      Set<String> actual = new HashSet<>();
      for (var ref : refs) {
        actual.add(ref.getName());
        assertEquals(commit, ref.getObjectId(), ref.getName());
      }
      assertEquals(expected, actual);
    }
    long coldVerifyNanos = System.nanoTime() - lookupStarted;
    long first = durations[0];
    Arrays.sort(durations);
    long p95 = durations[(int) Math.ceil(0.95 * durations.length) - 1];
    return String.format(Locale.ROOT, "%d,%d,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
        round, position, mode, latency, rttNanos, seedBytes, first, elapsed, p95, heldNanos,
        readBytes, writtenBytes, initialTables, finalTables, coldVerifyNanos);
  }

  private static ObjectId createCommit(HibernateRepository repo) throws IOException {
    try (var inserter = repo.newObjectInserter()) {
      var commit = new CommitBuilder();
      commit.setTreeId(inserter.insert(new TreeFormatter()));
      var who = new PersonIdent("Benchmark", "bench@example.invalid", Instant.EPOCH, ZoneOffset.UTC);
      commit.setAuthor(who);
      commit.setCommitter(who);
      commit.setMessage("Ref budget fixture");
      ObjectId id = inserter.insert(commit);
      inserter.flush();
      return id;
    }
  }

  private static void seed(HibernateRepository repo, ObjectId commit, Set<String> expected)
      throws IOException {
    var update = repo.getRefDatabase().newBatchUpdate();
    update.setAtomic(true);
    update.disableRefLog();
    Random names = new Random(0x4a474954L);
    for (int i = 0; i < SEED_REFS; i++) {
      String ref = String.format(Locale.ROOT, PREFIX + "seed/%016x-%04x", names.nextLong(), i);
      expected.add(ref);
      update.addCommand(new ReceiveCommand(ObjectId.zeroId(), commit, ref));
    }
    try (RevWalk walk = new RevWalk(repo)) {
      update.execute(walk, org.eclipse.jgit.lib.NullProgressMonitor.INSTANCE);
    }
    for (ReceiveCommand command : update.getCommands()) {
      assertEquals(ReceiveCommand.Result.OK, command.getResult(), command.getRefName());
    }
  }
}
