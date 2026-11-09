"""One preregistered, native 7.8.1 PACK-buffer diagnostic; no retries or policy qualification."""
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import signal
import statistics
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request
import zipfile

PINS = dict(benchmark="e08ca28e0373b799e558f7670b3648c420020459", core="96f1b08d5d0ca32f98508828c78074f12da688fc",
            baseline="2784b2cb0164726a2923848dc9c8087925d51b97", candidate="5f1c52f249be4f45c29101eb2371e766d74c91fd")
ORDER = ("baseline", "candidate", "candidate", "baseline", "baseline", "candidate", "candidate", "baseline")
DFS = "org/eclipse/jgit/internal/storage/dfs/DfsInserter.class"
PACK = DFS.replace(".class", "$PackStream.class")
REF = "org/eclipse/jgit/internal/storage/reftable/ReftableOutputStream.class"
CORE = "io/github/carstenartur/jgit/storage/hibernate/objects/ReadAheadHibernateObjDatabase.class"
BENCH = "io/github/carstenartur/jgit/storage/hibernate/benchmark/ConcurrentPublicationBenchmark.class"
PROF = BENCH.replace("ConcurrentPublicationBenchmark", "PackPublicationProfiler")
METHOD = BENCH.removesuffix(".class").replace("/", ".") + ".publishToDifferentRepositories"
PARAMS = dict(backend="postgresql-hikari", writeMode="stateful", payloadKiB="256", payloadMiB="0",
              publicationPolicy="size", deployment="paired-independent-16w-0ms")


def require(ok, message):
    if not ok:
        raise ValueError(message)


def command(args, **kwargs):
    return subprocess.check_output([str(x) for x in args], text=True, timeout=30, **kwargs).strip()


def digest(data):
    return hashlib.sha256(data).hexdigest()


def save(path, value):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value, indent=2, allow_nan=False) + "\n")
    temporary.replace(path)


def entries(path):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "Duplicate JAR entry")
        require(sum(x.file_size for x in archive.infolist()) < 256 * 1024**2, "Oversized JAR")
        manifest = archive.read("META-INF/MANIFEST.MF") if "META-INF/MANIFEST.MF" in names else b""
        require(not re.search(rb"(?im)^Class-Path:", manifest), "Hidden manifest Class-Path")
        return {n: digest(archive.read(n)) for n in names if not n.endswith("/")}


def audit_jars(baseline, candidate):
    old, new = entries(baseline), entries(candidate)
    require(old.keys() == new.keys(), "JAR entry inventories differ")
    require(old[REF] == new[REF] and old[PACK] != new[PACK], "Reftable changed or PACK fix missing")
    changed = sorted(k for k in old if old[k] != new[k])
    metadata = {"META-INF/MANIFEST.MF", "META-INF/maven/org.eclipse.jgit/org.eclipse.jgit/pom.properties"}
    require(all(k in metadata or k == DFS or (k.startswith(DFS[:-6] + "$") and k.endswith(".class"))
                for k in changed), "Unrelated JAR class/resource changed")
    return changed


def classpaths(paths, core, variants):
    require(all(p.is_absolute() and p.is_file() and not re.search(r"[\s*]", str(p)) for p in paths), "Non-explicit classpath")
    require(len(paths) == len(set(p.resolve() for p in paths)), "Duplicate classpath")
    inventories = {p: entries(p) for p in {*paths, core, *variants.values()}}
    for anchor in (DFS, PACK, REF, CORE, BENCH, PROF):
        require(sum(anchor in inventories[p] for p in paths) == 1, "Missing or shadowed class: " + anchor)
    result = {name: [jar if DFS in inventories[p] else core if CORE in inventories[p] else p for p in paths]
              for name, jar in variants.items()}
    classes = {p: {re.sub(r"^META-INF/versions/\d+/", "", k) for k in values if k.endswith(".class")}
               for p, values in inventories.items()}
    controlled = set().union(*(classes[p] for p in {core, *variants.values(), *(p for p in paths if BENCH in inventories[p])})) - {"module-info.class"}
    for final in result.values():
        require(all(sum(k in classes[p] for p in final) == 1 for k in controlled), "Controlled class is shadowed after replacement")
    return result


def planned():
    return [dict(ordinal=i + 1, pair=i // 2 + 1, variant=v, status="not_started") for i, v in enumerate(ORDER)]


def sequence(attempt, retain, rows=None):
    rows = planned() if rows is None else rows
    retain(rows)
    for row in rows:
        row.update(status="running", startedAt=time.time())
        retain(rows)
        try:
            row.update(attempt(row))
        except BaseException as error:
            row.update(status="interrupted" if isinstance(error, KeyboardInterrupt) else "invalid", errorType=type(error).__name__)
            break
        finally:
            row["finishedAt"] = time.time()
            retain(rows)
    return rows


def summary(rows, valid=True):
    valid = valid and not any(r["status"] in {"invalid", "interrupted"} or r.get("identityVerified") is False for r in rows)
    ratios = []
    for pair in range(1, 5):
        values = {r["variant"]: r["score"] for r in rows if r["pair"] == pair and r["status"] == "completed"}
        if values.keys() == {"baseline", "candidate"}:
            ratios.append(math.log(values["candidate"] / values["baseline"]))
    result = dict(schema="jgit-pack-buffer-summary/v1", diagnosticOnly=True, policyQualification=False,
                  historicalStudyReplacement=False, provenanceValid=valid, completePairs=len(ratios), effectPercent=None, interval95Percent=None)
    if len(ratios) == 4 and valid:
        mean, half = statistics.mean(ratios), 3.182446305284263 * statistics.stdev(ratios) / 2
        result.update(effectPercent=100 * math.expm1(mean), interval95Percent=[100 * math.expm1(mean - half), 100 * math.expm1(mean + half)])
    return result


class MissingProof(ValueError):
    pass


def child_identity(text, cp, anchors):
    if "java_command: org.openjdk.jmh.runner.ForkedMain " not in text:
        raise MissingProof("No actual JMH child command")
    require("java_class_path (initial): " + cp + "\n" in text, "Child classpath mismatch")
    arguments = re.findall(r"jvm_args: (.*)", text)
    require(len(arguments) == 1, "Missing or duplicate child arguments")
    heap = [x for x in arguments[0].split() if x.startswith(("-Xms", "-Xmx"))]
    require(heap == ["-Xms256m", "-Xmx1024m"], "Child heap mismatch")
    for anchor, jar in anchors.items():
        name = anchor.removesuffix(".class").replace("/", ".")
        locations = re.findall(r"\[class,load\]\s+" + re.escape(name) + r" source: (.+)", text)
        if not locations:
            raise MissingProof("No class-load proof: " + name)
        require(len(locations) == 1 and urllib.parse.urlparse(locations[0]).scheme == "file"
                and Path(urllib.parse.unquote(urllib.parse.urlparse(locations[0]).path)).resolve() == jar.resolve(), "Child class source mismatch")
    pids = set(re.findall(r"\[\d+ms\]\[(\d+)\]", text))
    require(len(pids) == 1, "Missing or multiple child PIDs")
    return int(pids.pop())


def prepare(sources, cp_file, out):
    identities = {}
    for name, pin in PINS.items():
        root = sources / name
        require(command(["git", "-C", root, "rev-parse", "HEAD"]) == pin, "Wrong source pin: " + name)
        require(not command(["git", "-C", root, "status", "--porcelain"]), "Dirty source: " + name)
        identities[name] = dict(commit=pin, tree=command(["git", "-C", root, "rev-parse", "HEAD^{tree}"]))
    original = command(["git", "-C", sources / "core", "rev-parse", "HEAD:jgit-storage-hibernate-core/src/main/java/io/github/carstenartur/jgit/storage/hibernate/objects/ReadAheadHibernateObjDatabase.java"])
    require(original == "86b9157087e84b826ac3431250f65912413cd564", "Original Core alignment missing")
    jars = {}
    for name in ("baseline", "candidate", "core"):
        relative = "jgit-storage-hibernate-core/target/jgit-storage-hibernate-core-0.12.1-SNAPSHOT.jar" if name == "core" else "org.eclipse.jgit/target/org.eclipse.jgit-7.8.1.202611041320-r.jar"
        jars[name] = out / "inputs" / (name + ".jar")
        shutil.copyfile(sources / name / relative, jars[name])
        jars[name].chmod(0o444)
    benchmark = sources / "benchmark/jgit-storage-hibernate-benchmarks/target/jgit-storage-hibernate-benchmarks-0.12.1-SNAPSHOT.jar"
    paths = classpaths([benchmark, *(Path(p) for p in cp_file.read_text().strip().split(os.pathsep))], jars["core"], {v: jars[v] for v in set(ORDER)})
    manifest = dict(sources=identities, originalCoreBlob=original, compiledDifferences=audit_jars(jars["baseline"], jars["candidate"]),
                    consumerBuildJgitVersion="7.8.1.202611041320-r", harnessCommit=command(["git", "rev-parse", "HEAD"]),
                    driverSha256=digest(Path(__file__).read_bytes()), classpaths={v: [str(p) for p in ps] for v, ps in paths.items()},
                    classpathSha256={v: digest(os.pathsep.join(str(p) for p in ps).encode()) for v, ps in paths.items()},
                    jarSha256={str(p): digest(p.read_bytes()) for p in set(sum(paths.values(), []))},
                    jgitEntries={v: entries(jars[v]) for v in set(ORDER)})
    save(out / "manifest.json", manifest)
    return paths, jars, manifest


def database(private, name):
    # PostgreSQL owns its bind directory; the disposable runner removes it after the job.
    db = Path(tempfile.mkdtemp(prefix="pack-buffer-postgres-"))
    filesystem = command(["stat", "-f", "-c", "%T", db])
    require(filesystem not in {"tmpfs", "ramfs"}, "Database must be disk-backed")
    env_file = private / "postgres.env"
    env_file.write_text("POSTGRES_DB=publication_paired\nPOSTGRES_USER=benchmark\nPOSTGRES_PASSWORD=benchmark\n")
    env_file.chmod(0o600)
    command(["docker", "network", "create", name])
    command(["docker", "run", "-d", "--name", name + "-pg", "--network", name, "--network-alias", "publication-paired-postgresql", "--env-file", env_file,
             "--mount", f"type=bind,source={db},target=/var/lib/postgresql/data", "postgres:17.10-alpine", "postgres", "-c", "fsync=on", "-c", "synchronous_commit=on",
             "-c", "full_page_writes=on", "-c", "shared_buffers=128MB", "-c", "max_connections=100", "-c", "max_wal_size=1GB"])
    deadline = time.monotonic() + 60
    while subprocess.run(["docker", "exec", name + "-pg", "pg_isready", "-U", "benchmark"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10).returncode:
        require(time.monotonic() < deadline, "PostgreSQL readiness timeout")
        time.sleep(1)
    command(["docker", "run", "-d", "--name", name + "-toxi", "--network", name, "-p", "127.0.0.1::8474", "-p", "127.0.0.1::8666", "ghcr.io/shopify/toxiproxy:2.12.0"])
    control = command(["docker", "port", name + "-toxi", "8474/tcp"])
    port = command(["docker", "port", name + "-toxi", "8666/tcp"]).rsplit(":", 1)[1]
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    for _ in range(30):
        try:
            with opener.open("http://" + control + "/proxies", timeout=2):
                break
        except OSError:
            time.sleep(1)
    else:
        raise ValueError("Toxiproxy readiness timeout")
    request = urllib.request.Request("http://" + control + "/proxies", data=json.dumps(dict(name="publication-paired", listen="0.0.0.0:8666", upstream="publication-paired-postgresql:5432")).encode(), headers={"Content-Type": "application/json"})
    with opener.open(request, timeout=10) as response:
        require(response.status == 201, "Proxy creation failed")
    connection = private / "connection.properties"
    connection.write_text(f"jgit.storage.benchmark.postgresql.url=jdbc:postgresql://127.0.0.1:{port}/publication_paired\njgit.storage.benchmark.postgresql.user=benchmark\njgit.storage.benchmark.postgresql.password=benchmark\n")
    connection.chmod(0o600)
    env = dict(os.environ, LC_ALL="C", PGHOST="127.0.0.1", PGPORT=port, PGDATABASE="publication_paired", PGUSER="benchmark", PGPASSWORD="benchmark", PGCONNECT_TIMEOUT="10", PGOPTIONS="-c statement_timeout=10000")
    def query(sql):
        return command(["psql", "-XAtq", "-c", sql], env=env)
    def calibrate():
        output = command(["psql", "-XAtq"], env=env, input="\\timing on\n" + "select 1;\n" * 25)
        samples = [float(x) for x in re.findall(r"Time: ([0-9.]+) ms", output)][5:]
        require(len(samples) == 20 and 0 <= statistics.median(samples) <= 25, "Proxy RTT outside fixed 0..25ms range")
        return samples
    settings = {key: query("show " + key) for key in ("fsync", "synchronous_commit", "full_page_writes", "shared_buffers", "max_connections", "max_wal_size")}
    require(list(settings.values()) == ["on", "on", "on", "128MB", "100", "1GB"], "Unexpected PostgreSQL settings")
    metadata = dict(databaseSettings=settings, databaseVersion=query("select version()"), fileStoreType=filesystem,
                    images={suffix: command(["docker", "inspect", "--format", "{{.Image}}", name + suffix]) for suffix in ("-pg", "-toxi")}, addedLatencyMillis=0,
                    psqlVersion=command(["psql", "--version"]), psqlSelect1RttMillis=calibrate())
    return connection, query, metadata, calibrate


def stop_group(process):
    # The launcher can exit before its child; inspect the whole group, ignoring zombies.
    def alive():
        for path in Path("/proc").glob("[0-9]*/stat"):
            try:
                fields = path.read_text().rsplit(")", 1)[1].split()
                if os.getpgid(int(path.parent.name)) == process.pid and fields[0] not in "ZX":
                    return True
            except (OSError, ValueError, IndexError):
                continue
        return False
    for sig in (signal.SIGTERM, signal.SIGKILL):
        try:
            os.killpg(process.pid, sig)
        except ProcessLookupError:
            break
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline and alive():
            process.poll()
            time.sleep(.05)
        if not alive():
            break
    require(not alive(), "Child process group survived SIGKILL")
    process.wait(timeout=10)


def run_fork(row, paths, jars, java, connection, out, pids):
    base = out / "raw" / f"{row['ordinal']:02d}-{row['variant']}"
    cp = os.pathsep.join(str(p) for p in paths[row["variant"]])
    vm = ["-Xms256m", "-Xmx1024m", "-Djgit.storage.benchmark.connection-properties-file=" + str(connection)]
    for tags, suffix in (("gc*=info,gc+heap=debug,gc+humongous=debug,gc+alloc=trace", "gc"), ("gc+jni=debug", "jni"), ("gc+init=info", "init"), ("arguments=info,class+load=info", "identity")):
        vm.append(f"-Xlog:{tags}:file={base}-{suffix}.log:uptimemillis,pid,level,tags:filecount=2,filesize=16m")
    args = [str(java), "-Djmh.separateClasspathJAR=false", "-cp", cp, "org.openjdk.jmh.Main", "^" + re.escape(METHOD) + "$",
            "-bm", "thrpt", "-tu", "s", "-t", "16", "-wi", "2", "-w", "3s", "-i", "3", "-r", "5s", "-f", "1", "-wf", "0", "-to", "120s", "-foe", "true",
            "-prof", "gc", "-prof", PROF[:-6].replace("/", "."), "-rf", "json", "-rff", str(base) + ".json", "-o", str(base) + ".log", "-jvm", str(java), "-jvmArgs", " ".join(vm)]
    for key, value in PARAMS.items():
        args += ["-p", key + "=" + value]
    save(Path(str(base) + "-command.json"), args)
    timed_out = False
    with Path(str(base) + "-launcher.log").open("w") as log:
        process = subprocess.Popen(args, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, start_new_session=True)
        try:
            code = process.wait(timeout=180)
        except subprocess.TimeoutExpired:
            timed_out = True
        finally:
            stop_group(process)
        code = process.returncode
    result = dict(exitCode=code, status="timed_out" if timed_out else "failed", identityVerified=False)
    logs = "\n".join(p.read_text(errors="replace") for p in base.parent.glob(base.name + "*.log*"))
    result.update(oomDetected="OutOfMemoryError" in logs, gcLockerWarningDetected="Retried waiting for GCLocker" in logs)
    identity = "\n".join(p.read_text() for p in base.parent.glob(base.name + "-identity.log*"))
    anchors = {k: jars[row["variant"]] for k in (DFS, PACK, REF)} | {CORE: jars["core"], BENCH: paths[row["variant"]][0], PROF: paths[row["variant"]][0]}
    try:
        pid = child_identity(identity, cp, anchors)
        require(pid not in pids, "Reused child PID")
        pids.add(pid)
        result.update(childPid=pid, identityVerified=True)
    except MissingProof as error:
        require(code != 0 or timed_out, "Successful fork lacks child identity")
        result["identityFailure"] = str(error)
    if code == 0 and not timed_out:
        data = json.loads(Path(str(base) + ".json").read_text())
        require(len(data) == 1, "Unexpected JMH result count")
        r = data[0]
        require(r["benchmark"] == METHOD and r["params"] == PARAMS and r["threads"] == 16 and r["forks"] == 1 and r["warmupIterations"] == 2 and r["measurementIterations"] == 3
                and r["warmupTime"] == "3 s" and r["measurementTime"] == "5 s" and r["mode"] == "thrpt" and r["jdkVersion"] == "21.0.13", "Unexpected JMH settings")
        score = r["primaryMetric"]["score"]
        require(math.isfinite(score) and score > 0 and r["primaryMetric"]["scoreUnit"] == "ops/s", "Invalid throughput")
        require(r["secondaryMetrics"]["prePersistedPublicationSelections"]["score"] == 0, "Unexpected publication policy selection")
        allocation = r["secondaryMetrics"]["gc.alloc.rate.norm"]["score"]
        require(math.isfinite(allocation) and allocation >= 0, "Invalid allocation metric")
        result.update(status="completed", score=score, allocationBytesPerOp=allocation)
    return result


def main(sources, cp_file, out):
    out.mkdir(parents=True, exist_ok=False)
    for directory in ("inputs", "raw"):
        (out / directory).mkdir()
    rows, name = planned(), "pack-buffer-" + str(os.getpid())
    save(out / "outcomes.json", rows)
    save(out / "protocol.json", dict(schema="jgit-pack-buffer-controlled/v1", diagnosticOnly=True, policyQualification=False, historicalStudyReplacement=False,
         pins=PINS, order=ORDER, pairCount=4, params=PARAMS, threads=16, warmup="2 x 3s", measurement="3 x 5s", jdk="21.0.13+9", heap="256m/1024m", iterationTimeoutSeconds=120, attemptTimeoutSeconds=180, terminationGraceSeconds=10, killVerificationSeconds=10, retries=0))
    try:
        require(not any(os.environ.get(k) for k in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")), "Injected JVM options")
        java = Path(os.environ["JAVA_HOME"]) / "bin/java"
        version = command([java, "-version"], stderr=subprocess.STDOUT)
        require('openjdk version "21.0.13"' in version and "21.0.13+9" in version, "Wrong JVM")
        paths, jars, manifest = prepare(sources, cp_file, out)
        with tempfile.TemporaryDirectory(prefix="pack-buffer-private-") as temporary:
            connection, query, environment, calibrate = database(Path(temporary), name)
            environment.update(javaVersion=version, javaSha256=digest(java.read_bytes()), uname=command(["uname", "-a"]), cpuModel=command(["lscpu"]),
                               limits={p: Path(p).read_text() for p in ("/sys/fs/cgroup/cpu.max", "/sys/fs/cgroup/memory.max") if Path(p).exists()})
            save(out / "environment.json", environment)
            pids = set()
            def attempt(row):
                row["checkpointBefore"] = json.loads(query("select to_jsonb(s)::text from pg_stat_checkpointer s"))
                row.update(run_fork(row, paths, jars, java, connection, out, pids))
                row["checkpointAfter"] = json.loads(query("select to_jsonb(s)::text from pg_stat_checkpointer s"))
                return {}
            sequence(attempt, lambda value: save(out / "outcomes.json", value), rows)
            environment["finalPsqlSelect1RttMillis"] = calibrate()
            save(out / "environment.json", environment)
        require(all(digest(Path(p).read_bytes()) == h for p, h in manifest["jarSha256"].items()), "Runtime JAR changed during sequence")
    except BaseException as error:
        save(out / "infrastructure-error.json", dict(errorType=type(error).__name__))
    finally:
        save(out / "summary.json", summary(rows, valid=not (out / "infrastructure-error.json").exists()))
        for args in (["docker", "rm", "-f", name + "-pg", name + "-toxi"], ["docker", "network", "rm", name]):
            try:
                subprocess.run(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
            except (OSError, subprocess.TimeoutExpired):
                pass
    return 0 if all(r["status"] == "completed" for r in rows) and not (out / "infrastructure-error.json").exists() else 1


if __name__ == "__main__":
    def interrupted(_signal, _frame):
        raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM, interrupted)
    sys.exit(main(*(Path(p).resolve() for p in sys.argv[1:])))
