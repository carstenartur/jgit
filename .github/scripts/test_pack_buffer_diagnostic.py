"""Safety gates for the fixed diagnostic of both initial buffers."""
import importlib.util
import math
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location("diagnostic", Path(__file__).with_name("pack_buffer_diagnostic.py"))
d = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(d)


class DiagnosticTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def jar(self, name, entries):
        path = self.root / name
        with zipfile.ZipFile(path, "w") as out:
            for key, value in entries.items():
                out.writestr(key, value)
        return path

    def inputs(self):
        old = {d.DFS: b"outer", d.PACK: b"old", d.REF: b"unchanged", "org/eclipse/jgit/lib/ObjectInserter.class": b"inserter"}
        baseline = self.jar("baseline.jar", old)
        candidate = self.jar("candidate.jar", dict(old, **{d.PACK: b"new", d.REF: b"lazy"}))
        core = self.jar("core.jar", {d.CORE: b"original alignment"})
        benchmark = self.jar("benchmark.jar", {d.BENCH: b"benchmark", d.PROF: b"profiler"})
        return baseline, candidate, core, benchmark

    def test_explicit_classpaths_differ_only_in_jgit(self):
        baseline, candidate, core, benchmark = self.inputs()
        paths = d.classpaths([benchmark, core, baseline], core, {"baseline": baseline, "candidate": candidate})
        self.assertEqual([benchmark, core, baseline], paths["baseline"])
        self.assertEqual([benchmark, core, candidate], paths["candidate"])

    def test_shadowed_jgit_is_rejected(self):
        baseline, candidate, core, benchmark = self.inputs()
        shadow = self.jar("shadow.jar", {d.PACK: b"shadow"})
        with self.assertRaisesRegex(ValueError, "shadow"):
            d.classpaths([benchmark, core, baseline, shadow], core, {"baseline": baseline, "candidate": candidate})

    def test_manifest_classpath_is_rejected(self):
        jar = self.jar("hidden.jar", {"META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\r\nClass-Path: hidden.jar\r\n"})
        with self.assertRaisesRegex(ValueError, "Class-Path"):
            d.entries(jar)

    def test_non_anchor_and_multirelease_shadows_are_rejected(self):
        baseline, candidate, core, benchmark = self.inputs()
        for prefix in ("", "META-INF/versions/21/"):
            shadow = self.jar("shadow.jar", {prefix + "org/eclipse/jgit/lib/ObjectInserter.class": b"shadow"})
            with self.assertRaisesRegex(ValueError, "shadow"):
                d.classpaths([shadow, benchmark, core, baseline], core, {"baseline": baseline, "candidate": candidate})
        replacement = self.jar("replacement.jar", {d.CORE: b"original alignment", "org/eclipse/jgit/lib/ObjectInserter.class": b"shadow"})
        with self.assertRaisesRegex(ValueError, "shadow"):
            d.classpaths([benchmark, core, baseline], replacement, {"baseline": baseline, "candidate": candidate})

    def test_exited_launcher_cannot_leave_a_term_ignoring_child(self):
        pidfile = self.root / "child.pid"
        child = f"import os,signal,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); open({str(pidfile)!r},'w').write(str(os.getpid())); time.sleep(60)"
        launcher = f"import subprocess,sys,time; from pathlib import Path; subprocess.Popen([sys.executable,'-c',{child!r}]);\nwhile not Path({str(pidfile)!r}).exists(): time.sleep(.01)"
        process = subprocess.Popen([sys.executable, "-c", launcher], start_new_session=True)
        try:
            process.wait(timeout=10)
            d.stop_group(process)
            try:
                os.kill(int(pidfile.read_text()), 0)
            except ProcessLookupError:
                pass
            else:
                state = Path(f"/proc/{pidfile.read_text()}/stat")
                self.assertIn(state.read_text().rsplit(")", 1)[1].split()[0], {"Z", "X"})
        finally:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait(timeout=10)

    def test_unrelated_change_or_missing_buffer_fix_is_rejected(self):
        baseline, candidate, _, _ = self.inputs()
        self.assertEqual([d.PACK, d.REF], d.audit_jars(baseline, candidate))
        for extra in ({d.REF: b"unchanged"}, {d.PACK: b"old"}, {"org/eclipse/jgit/lib/ObjectInserter.class": b"changed"}, {"unrelated.class": b"new"}):
            bad = self.jar("bad.jar", {d.DFS: b"outer", d.PACK: b"new", d.REF: b"lazy", "org/eclipse/jgit/lib/ObjectInserter.class": b"inserter", **extra})
            with self.assertRaises(ValueError):
                d.audit_jars(baseline, bad)

    def test_oom_detection_requires_an_exception_header(self):
        loaded = "[14ms][4986][info][class,load] java.lang.OutOfMemoryError source: shared objects file\n"
        self.assertFalse(d.thrown_oom(loaded))
        self.assertFalse(d.thrown_oom(loaded + "Retried waiting for GCLocker too often allocating 131074 words\n"))
        for prefix in ("", "\tSuppressed: ", "Caused by: ", 'Exception in thread "worker-1" '):
            header = prefix + "java.lang.OutOfMemoryError: Java heap space\n"
            self.assertTrue(d.thrown_oom(loaded + header))
            self.assertTrue(d.thrown_oom(loaded + header + "\tat org.eclipse.jgit.internal.storage.reftable.ReftableOutputStream.<init>(ReftableOutputStream.java:53)\n"))
        self.assertTrue(d.thrown_oom("java.lang.OutOfMemoryError"))

    def test_failure_consumes_attempt_and_all_eight_are_retained(self):
        calls, snapshots = [], []
        def attempt(row):
            calls.append(row["variant"])
            return {"status": "failed" if row["ordinal"] == 3 else "completed", "score": 1.0}
        rows = d.sequence(attempt, lambda rows: snapshots.append([r.copy() for r in rows]))
        self.assertEqual(list(d.ORDER), calls)
        self.assertEqual(8, len(rows))
        self.assertEqual("failed", snapshots[-1][2]["status"])
        self.assertIsNone(d.summary(rows)["interval95Percent"])

    def test_invalid_child_identity_invalidates_provenance(self):
        def mismatch(_row):
            raise ValueError("Child class source mismatch")
        rows = d.sequence(mismatch, lambda _: None)
        self.assertEqual("invalid", rows[0]["status"])
        self.assertFalse(d.summary(rows)["provenanceValid"])

    def test_four_valid_pairs_only_produce_an_interval(self):
        rows = d.sequence(lambda r: {"status": "completed", "score": 12 if r["variant"] == "candidate" else 10}, lambda _: None)
        result = d.summary(rows)
        self.assertEqual(4, result["completePairs"])
        self.assertTrue(math.isclose(20, result["effectPercent"]))
        self.assertTrue(all(math.isclose(20, x) for x in result["interval95Percent"]))
        self.assertIsNone(d.summary(rows, valid=False)["interval95Percent"])
        rows[-1]["status"] = "timed_out"
        self.assertIsNone(d.summary(rows)["effectPercent"])
        self.assertIsNone(d.summary(rows)["interval95Percent"])

    def test_child_source_mismatch_is_rejected(self):
        text = "[1ms][42][info][arguments] java_command: org.openjdk.jmh.runner.ForkedMain localhost 1\n"
        text += "[1ms][42][info][arguments] java_class_path (initial): /correct.jar\n"
        text += "[1ms][42][info][arguments] jvm_args: -Xms256m -Xmx1024m\n"
        text += "[2ms][42][info][class,load] example.Sentinel source: file:/wrong.jar\n"
        self.assertEqual(42, d.child_identity(text.replace("file:/wrong.jar", "file:/correct.jar"), "/correct.jar", {"example/Sentinel.class": Path("/correct.jar")}))
        with self.assertRaisesRegex(ValueError, "source"):
            d.child_identity(text, "/correct.jar", {"example/Sentinel.class": Path("/correct.jar")})


if __name__ == "__main__":
    unittest.main()
