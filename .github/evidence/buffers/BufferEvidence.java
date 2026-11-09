package org.eclipse.jgit.internal.storage.dfs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import com.sun.management.ThreadMXBean;
import org.eclipse.jgit.internal.storage.io.BlockSource;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.eclipse.jgit.internal.storage.reftable.ReftableConfig;
import org.eclipse.jgit.internal.storage.reftable.ReftableReader;
import org.eclipse.jgit.internal.storage.reftable.ReftableWriter;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;

public final class BufferEvidence {
    private static final int MIB = 1 << 20;
    private static final String LOG_MESSAGE = "deterministic publication";
    private static final PersonIdent AUTHOR = new PersonIdent("Example", "e@example.invalid", Instant.ofEpochSecond(1234567890), ZoneOffset.UTC);
    private static final ThreadMXBean ALLOCATIONS = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    public static void main(String[] args) throws IOException {
        ALLOCATIONS.setThreadAllocatedMemoryEnabled(true);
        Path output = Files.createDirectories(Path.of(args[0]));
        System.out.println("origin=" + DfsInserter.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("workload,alignment,input,iterations,allocated_bytes_per_operation,encoded_bytes,padding_or_chunks");
        for (int count : new int[] {1, 700, 60_000}) {
            testReftable(output, count);
        }
        for (int alignment : new int[] {MIB, MIB + 37}) {
            for (int size : new int[] {32, 256 * 1024 + 257, 3 * MIB + 257}) {
                testPack(output, alignment, size);
            }
        }
    }

    private static void testReftable(Path output, int count) throws IOException {
        ReftableConfig config = new ReftableConfig();
        config.setRefBlockSize(MIB);
        config.setLogBlockSize(MIB);
        config.setAlignBlocks(true);
        config.setIndexObjects(false);
        List<Ref> refs = new ArrayList<>(count);
        Random seed = new Random(0x524546L);
        for (int i = 0; i < count; i++) {
            byte[] id = new byte[Constants.OBJECT_ID_LENGTH];
            seed.nextBytes(id);
            refs.add(new ObjectIdRef.PeeledNonTag(Ref.Storage.PACKED,
                    String.format(Locale.ROOT, "refs/heads/fixture/%06d", i), ObjectId.fromRaw(id)));
        }
        int logs = Math.min(count, 3000);
        int iterations = count == 60_000 ? 8 : 64;
        long allocated = measure(iterations, () -> writeReftable(config, refs, logs, OutputStream.nullOutputStream()));
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        ReftableWriter.Stats stats = writeReftable(config, refs, logs, result);
        byte[] content = result.toByteArray();
        try (ReftableReader reader = new ReftableReader(BlockSource.from(content));
                var refCursor = reader.allRefs(); var logCursor = reader.allLogs()) {
            for (Ref expected : refs) {
                check(refCursor.next(), "missing ref");
                Ref actual = refCursor.getRef();
                check(expected.getName().equals(actual.getName()) && expected.getObjectId().equals(actual.getObjectId()), "ref mismatch");
            }
            check(!refCursor.next(), "unexpected ref");
            for (int i = 0; i < logs; i++) {
                check(logCursor.next(), "missing log");
                check(refs.get(i).getName().equals(logCursor.getRefName())
                        && logCursor.getUpdateIndex() == 1
                        && refs.get(i).getObjectId().equals(logCursor.getReflogEntry().getNewId())
                        && LOG_MESSAGE.equals(logCursor.getReflogEntry().getComment()), "log mismatch");
            }
            check(!logCursor.next(), "unexpected log");
        }
        Files.write(output.resolve("refs-" + count + ".ref"), content);
        System.out.printf("reftable,%d,%d,%d,%d,%d,%d%n", MIB, count, iterations, allocated, content.length, stats.paddingBytes());
    }

    private static ReftableWriter.Stats writeReftable(ReftableConfig config, List<Ref> refs, int logs, OutputStream out) throws IOException {
        ReftableWriter writer = new ReftableWriter(config, out).setMinUpdateIndex(1).setMaxUpdateIndex(1).begin();
        for (Ref ref : refs) {
            writer.writeRef(ref);
        }
        for (int i = 0; i < logs; i++) {
            Ref ref = refs.get(i);
            writer.writeLog(ref.getName(), 1, AUTHOR, ObjectId.zeroId(), ref.getObjectId(), LOG_MESSAGE);
        }
        return writer.finish().getStats();
    }

    private static void testPack(Path output, int alignment, int size) throws IOException {
        byte[] input = new byte[size];
        new Random(0x5041434bL).nextBytes(input);
        int iterations = size > MIB ? 8 : 64;
        try (InMemoryRepository repo = repository(); AlignedDatabase db = new AlignedDatabase(repo, alignment)) {
            long allocated = measure(iterations, () -> {
                try (DfsInserter inserter = new DfsInserter(db)) {
                    inserter.insert(Constants.OBJ_BLOB, input);
                }
                db.chunks.clear();
            });
            ObjectId id;
            try (DfsInserter inserter = new DfsInserter(db)) {
                id = inserter.insert(Constants.OBJ_BLOB, input);
                try (var reader = inserter.newReader(); var stream = reader.open(id).openStream()) {
                    check(Arrays.equals(input, stream.readAllBytes()), "pre-publication blob mismatch");
                }
                inserter.flush();
            }
            DfsPackDescription description = db.listPacks().get(0);
            check(description.getBlockSize(PackExt.PACK) == alignment, "block size mismatch");
            for (int i = 0; i < db.chunks.size() - 1; i++) {
                check(db.chunks.get(i).intValue() == alignment, "partial logical block");
            }
            try (var reader = db.newReader(); var stream = reader.open(id).openStream()) {
                check(Arrays.equals(input, stream.readAllBytes()), "published blob mismatch");
            }
            byte[] pack = readFile(db, description, PackExt.PACK);
            byte[] index = readFile(db, description, PackExt.INDEX);
            String name = "pack-" + alignment + "-" + size;
            Files.write(output.resolve(name + ".pack"), pack);
            Files.write(output.resolve(name + ".idx"), index);
            String chunks = String.join("+", db.chunks.stream().map(Object::toString).toList());
            System.out.printf("pack,%d,%d,%d,%d,%d,%s%n", alignment, size, iterations, allocated, pack.length, chunks);
        }
    }

    private static byte[] readFile(AlignedDatabase db, DfsPackDescription description, PackExt extension) throws IOException {
        ByteBuffer result = ByteBuffer.allocate((int) description.getFileSize(extension));
        try (ReadableChannel channel = db.openFile(description, extension)) {
            while (result.hasRemaining()) {
                check(channel.read(result) > 0, "file truncated");
            }
        }
        return result.array();
    }

    private static InMemoryRepository repository() {
        return new InMemoryRepository(new DfsRepositoryDescription("buffer-evidence"));
    }

    private static long measure(int iterations, Operation operation) throws IOException {
        for (int i = 0; i < 8; i++) {
            operation.run();
        }
        long threadId = Thread.currentThread().threadId();
        long start = ALLOCATIONS.getThreadAllocatedBytes(threadId);
        for (int i = 0; i < iterations; i++) {
            operation.run();
        }
        return (ALLOCATIONS.getThreadAllocatedBytes(threadId) - start) / iterations;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface Operation {
        void run() throws IOException;
    }

    private static final class AlignedDatabase extends InMemoryRepository.MemObjDatabase {
        private final int alignment;
        private final List<Integer> chunks = new ArrayList<>();

        AlignedDatabase(DfsRepository repo, int alignment) {
            super(repo);
            this.alignment = alignment;
            setReadableChannelBlockSizeForTest(alignment);
        }

        @Override
        protected DfsOutputStream writeFile(DfsPackDescription pack, PackExt extension) throws IOException {
            DfsOutputStream delegate = super.writeFile(pack, extension);
            return new DfsOutputStream() {
                @Override
                public int blockSize() {
                    return alignment;
                }

                @Override
                public void write(byte[] data, int offset, int length) throws IOException {
                    if (extension == PackExt.PACK) {
                        chunks.add(Integer.valueOf(length));
                    }
                    delegate.write(data, offset, length);
                }

                @Override
                public int read(long position, ByteBuffer buffer) throws IOException {
                    return delegate.read(position, buffer);
                }

                @Override
                public void close() throws IOException {
                    delegate.close();
                }
            };
        }
    }
}
