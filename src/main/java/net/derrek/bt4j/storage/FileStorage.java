package net.derrek.bt4j.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.derrek.bt4j.metainfo.FileEntry;
import net.derrek.bt4j.metainfo.Metainfo;
import net.derrek.bt4j.piece.Bitfield;
import net.derrek.bt4j.piece.PieceSelection;

/**
 * File storage implemented with FileChannel.
 *
 * Buffering strategy: blocks of an unverified piece accumulate in an in-memory buffer (bounded by
 * {@code RarestFirstPicker.MAX_ACTIVE_PIECES} x pieceLength), and are written to disk only after
 * verification passes -- byte ranges within a boundary piece that belong to unselected files are
 * discarded outright, and unselected files are never created. A piece that fails verification is
 * discarded entirely and re-downloaded.
 *
 * All methods are synchronized and may be called concurrently by multiple peer threads.
 */
public final class FileStorage implements Storage {

    private static final System.Logger LOG = System.getLogger(FileStorage.class.getName());

    private final Metainfo metainfo;
    private final PieceSelection selection;
    private final Path root;
    private final Map<Integer, byte[]> pieceBuffers = new HashMap<>();
    /** Which blocks of each buffered piece have arrived, so in-flight progress survives a restart. */
    private final Map<Integer, BitSet> partialBlocks = new HashMap<>();
    private final Map<Integer, FileChannel> channels = new HashMap<>();
    private final Bitfield completed;
    private boolean closed;

    /**
     * @param saveTo download root directory (file path = saveTo/FileEntry.path...; for a multi-file torrent the first level is the torrent name)
     */
    public FileStorage(Metainfo metainfo, PieceSelection selection, Path saveTo) {
        this(metainfo, selection, saveTo, new Bitfield(metainfo.pieceCount()));
    }

    /**
     * For resume: construct with an existing set of completed pieces (their data is assumed already on disk, marked only if previously verified).
     */
    public FileStorage(Metainfo metainfo, PieceSelection selection, Path saveTo, Bitfield alreadyCompleted) {
        this(metainfo, selection, saveTo, alreadyCompleted, Map.of());
    }

    /**
     * For resume, including pieces that were still in flight: {@code partials} maps a piece index to the blocks
     * of it already written to disk by {@link #persistPartialPieces()}. Those pieces are read back into their
     * buffers so the restart continues from where it stopped instead of refetching them. The data is unverified
     * until the piece completes and passes SHA-1, exactly as during a normal download.
     */
    public FileStorage(Metainfo metainfo, PieceSelection selection, Path saveTo, Bitfield alreadyCompleted,
                       Map<Integer, BitSet> partials) {
        this.metainfo = metainfo;
        this.selection = selection;
        this.root = saveTo;
        this.completed = alreadyCompleted.copy();
        restorePartials(partials);
    }

    private void restorePartials(Map<Integer, BitSet> partials) {
        for (Map.Entry<Integer, BitSet> entry : partials.entrySet()) {
            int piece = entry.getKey();
            BitSet blocks = entry.getValue();
            if (piece < 0 || piece >= metainfo.pieceCount() || completed.get(piece) || blocks.isEmpty()) {
                continue;
            }
            if (selection.wantedBytesInPiece(piece) != metainfo.pieceLengthAt(piece)) {
                continue; // boundary piece: was never persisted in full
            }
            byte[] buffer = new byte[metainfo.pieceLengthAt(piece)];
            try {
                if (readFromFiles((long) piece * metainfo.pieceLength(), buffer, false) != buffer.length) {
                    continue; // the file is gone or short: just refetch the piece
                }
            } catch (IOException e) {
                LOG.log(System.Logger.Level.DEBUG, () -> "could not restore partial piece " + piece + ": " + e.getMessage());
                continue;
            }
            pieceBuffers.put(piece, buffer);
            partialBlocks.put(piece, (BitSet) blocks.clone());
        }
        if (!partialBlocks.isEmpty()) {
            LOG.log(System.Logger.Level.DEBUG, () -> "restored " + partialBlocks.size() + " partially downloaded pieces");
        }
    }

    /** The restored in-flight block map, so the picker can be seeded with the same state. */
    public synchronized Map<Integer, BitSet> restoredPartials() {
        return partialProgress();
    }

    @Override
    public synchronized void write(int pieceIndex, int offset, byte[] data) {
        if (closed || completed.get(pieceIndex)) {
            return;
        }
        byte[] buffer = pieceBuffers.computeIfAbsent(pieceIndex, p -> new byte[metainfo.pieceLengthAt(p)]);
        System.arraycopy(data, 0, buffer, offset, data.length);
        partialBlocks.computeIfAbsent(pieceIndex, p -> new BitSet())
                .set(offset / net.derrek.bt4j.piece.BlockRequest.BLOCK_SIZE);
    }

    /**
     * Which blocks of each in-flight piece are held, for resume data. Only pieces that lie entirely inside
     * selected files are reported: a boundary piece cannot be written to disk in full, so it has nothing to
     * restore from.
     */
    public synchronized Map<Integer, BitSet> partialProgress() {
        Map<Integer, BitSet> out = new HashMap<>();
        for (Map.Entry<Integer, BitSet> entry : partialBlocks.entrySet()) {
            int piece = entry.getKey();
            if (completed.get(piece) || entry.getValue().isEmpty()) {
                continue;
            }
            if (selection.wantedBytesInPiece(piece) != metainfo.pieceLengthAt(piece)) {
                continue;
            }
            out.put(piece, (BitSet) entry.getValue().clone());
        }
        return out;
    }

    /**
     * Writes the buffers of in-flight pieces to their final disk positions so a restart can pick them up.
     * The bytes are unverified — only the blocks reported by {@link #partialProgress()} are meaningful, and the
     * piece still has to pass SHA-1 before it is ever marked complete, so nothing can be trusted prematurely.
     */
    public void persistPartialPieces() throws IOException {
        Map<Integer, byte[]> snapshot = new HashMap<>();
        synchronized (this) {
            if (closed) {
                return;
            }
            for (int piece : partialProgress().keySet()) {
                byte[] buffer = pieceBuffers.get(piece);
                if (buffer != null) {
                    snapshot.put(piece, buffer.clone()); // clone: the live buffer keeps changing as blocks arrive
                }
            }
        }
        for (Map.Entry<Integer, byte[]> entry : snapshot.entrySet()) {
            List<WriteOp> pieceOps;
            synchronized (this) {
                pieceOps = planVerifiedPieceWrites(entry.getKey(), entry.getValue());
            }
            try {
                executeWrites(pieceOps, entry.getValue());
            } catch (ClosedChannelException ignored) {
                return; // closing down
            }
        }
    }

    @Override
    public synchronized byte[] read(int pieceIndex, int offset, int length) throws IOException {
        if (!completed.get(pieceIndex)) {
            throw new IllegalStateException("piece " + pieceIndex + " is not yet complete");
        }
        byte[] out = new byte[length];
        long pieceStart = (long) pieceIndex * metainfo.pieceLength() + offset;
        int filled = readFromFiles(pieceStart, out);
        if (filled != length) {
            throw new IllegalStateException("some bytes of piece " + pieceIndex + " are not on disk (ranges belonging to unselected files)");
        }
        return out;
    }

    @Override
    public boolean verifyPiece(int pieceIndex) throws IOException {
        byte[] buffer;
        synchronized (this) {
            if (completed.get(pieceIndex)) {
                return true;
            }
            // Detach the buffer from the shared map so no concurrent write() (e.g. an endgame duplicate block)
            // can mutate the array while we hash it. Each completed piece is handed here exactly once by the
            // picker, so there is no competing verifyPiece for this same index.
            buffer = pieceBuffers.remove(pieceIndex);
            partialBlocks.remove(pieceIndex);
            if (buffer == null) {
                throw new IllegalStateException("piece " + pieceIndex + " has no data to verify");
            }
        }
        // SHA-1 is CPU-bound over a now-private buffer; run it OUTSIDE the storage monitor so other peers'
        // block writes and other pieces' verifications proceed concurrently instead of serializing behind it.
        boolean ok = java.util.Arrays.equals(sha1(buffer), metainfo.pieceHash(pieceIndex));
        List<WriteOp> ops;
        synchronized (this) {
            // Drop any buffer a late endgame-duplicate write() may have recreated for this piece while we hashed.
            pieceBuffers.remove(pieceIndex);
            partialBlocks.remove(pieceIndex);
            if (!ok) {
                LOG.log(System.Logger.Level.DEBUG, () -> "piece " + pieceIndex + " failed SHA-1 verification, discarded for re-download");
                return false;
            }
            if (completed.get(pieceIndex) || closed) {
                // Already committed by a prior call, or the storage is closing: do not persist. Since completed
                // is left unset while closing, a resume re-fetches this piece rather than trusting a partial write.
                return true;
            }
            // Resolve (open) the target channels and byte ranges under the lock, but defer the actual disk I/O.
            ops = planVerifiedPieceWrites(pieceIndex, buffer);
        }
        // Perform the disk writes OUTSIDE the storage monitor: FileChannel positional writes to distinct ranges
        // are thread-safe, so other peers' block writes and other pieces' verifications no longer stall behind a
        // slow write (page-cache flush / dirty-page writeback throttling).
        try {
            executeWrites(ops, buffer);
        } catch (ClosedChannelException e) {
            // close() shut the channels while we were writing (teardown). Leave completed unset so a resume
            // re-fetches this piece rather than trusting a partially written one.
            return true;
        }
        synchronized (this) {
            // Clean up any buffer a late endgame-duplicate write() recreated between the two critical sections.
            pieceBuffers.remove(pieceIndex);
            partialBlocks.remove(pieceIndex);
            if (!closed) {
                completed.set(pieceIndex);
            }
        }
        LOG.log(System.Logger.Level.TRACE, () -> "piece " + pieceIndex + " verified and written to disk");
        return true;
    }

    /** One deferred write: a byte range of the piece buffer to a resolved file channel at a file position. */
    private record WriteOp(FileChannel channel, int bufferOffset, int length, long filePosition) {
    }

    /**
     * Resolve which selected-file ranges a verified piece maps to, opening the target channels as needed.
     * Must be called under the lock (it may mutate the channels map); the returned ops are executed lock-free.
     */
    private List<WriteOp> planVerifiedPieceWrites(int pieceIndex, byte[] buffer) throws IOException {
        long pieceStart = (long) pieceIndex * metainfo.pieceLength();
        long pieceEnd = pieceStart + buffer.length;
        List<WriteOp> ops = new ArrayList<>();
        for (FileEntry file : metainfo.files()) {
            if (!selection.isFileWanted(file.index()) || file.length() == 0) {
                continue;
            }
            long fileStart = file.offset();
            long fileEnd = fileStart + file.length();
            long overlapStart = Math.max(pieceStart, fileStart);
            long overlapEnd = Math.min(pieceEnd, fileEnd);
            if (overlapStart >= overlapEnd) {
                continue;
            }
            ops.add(new WriteOp(channel(file), (int) (overlapStart - pieceStart),
                    (int) (overlapEnd - overlapStart), overlapStart - fileStart));
        }
        return ops;
    }

    /** Execute the deferred writes. Safe to run without the lock: distinct pieces map to disjoint file ranges. */
    private static void executeWrites(List<WriteOp> ops, byte[] buffer) throws IOException {
        for (WriteOp op : ops) {
            ByteBuffer slice = ByteBuffer.wrap(buffer, op.bufferOffset(), op.length());
            long position = op.filePosition();
            while (slice.hasRemaining()) {
                position += op.channel().write(slice, position);
            }
        }
    }

    private int readFromFiles(long globalStart, byte[] out) throws IOException {
        return readFromFiles(globalStart, out, true);
    }

    /**
     * Read out.length bytes from disk starting at global offset globalStart (spanning selected files only). Returns the number of bytes actually filled.
     *
     * @param createIfMissing when false (recheck scan), do not create missing files; a missing file is treated as unreadable
     */
    private int readFromFiles(long globalStart, byte[] out, boolean createIfMissing) throws IOException {
        int filled = 0;
        long globalEnd = globalStart + out.length;
        for (FileEntry file : metainfo.files()) {
            if (!selection.isFileWanted(file.index()) || file.length() == 0) {
                continue;
            }
            long fileStart = file.offset();
            long fileEnd = fileStart + file.length();
            long overlapStart = Math.max(globalStart, fileStart);
            long overlapEnd = Math.min(globalEnd, fileEnd);
            if (overlapStart >= overlapEnd) {
                continue;
            }
            FileChannel channel = channel(file, createIfMissing);
            if (channel == null) {
                return filled; // file does not exist (during recheck scan) -> this piece is incomplete
            }
            ByteBuffer slice = ByteBuffer.wrap(out, (int) (overlapStart - globalStart), (int) (overlapEnd - overlapStart));
            long position = overlapStart - fileStart;
            while (slice.hasRemaining()) {
                int n = channel.read(slice, position);
                if (n < 0) {
                    return filled; // file is shorter than expected (incomplete)
                }
                position += n;
            }
            filled += (int) (overlapEnd - overlapStart);
        }
        return filled;
    }

    private static byte[] sha1(byte[] data) {
        try {
            return java.security.MessageDigest.getInstance("SHA-1").digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-1 is always built into the JDK", e);
        }
    }

    private FileChannel channel(FileEntry file) throws IOException {
        return channel(file, true);
    }

    /** @param createIfMissing when false and the file is not yet open / does not exist, returns null (does not create the file) */
    private FileChannel channel(FileEntry file, boolean createIfMissing) throws IOException {
        FileChannel existing = channels.get(file.index());
        if (existing != null) {
            return existing;
        }
        Path path = root;
        for (String component : file.path()) {
            path = path.resolve(component);
        }
        if (!createIfMissing && !Files.exists(path)) {
            return null;
        }
        Files.createDirectories(path.getParent());
        FileChannel channel = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        if (createIfMissing) {
            preallocate(channel, file.length());
        }
        channels.put(file.index(), channel);
        return channel;
    }

    /**
     * Reserve the file's full length up front (a single zero byte written at the last offset extends it).
     * Keeps the file in far fewer filesystem extents than growing it piece by piece in random order, and makes a
     * disk-full condition surface when the download starts rather than halfway through.
     */
    private static void preallocate(FileChannel channel, long length) throws IOException {
        if (length <= 0 || channel.size() >= length) {
            return;
        }
        channel.write(ByteBuffer.allocate(1), length - 1);
    }

    @Override
    public synchronized Bitfield completedPieces() {
        return completed.copy();
    }

    @Override
    public Bitfield recheck() throws IOException {
        List<Integer> candidates = new ArrayList<>();
        Map<Integer, FileChannel> snapshot = new HashMap<>();
        synchronized (this) {
            pieceBuffers.clear();
            partialBlocks.clear();
            for (int p = 0; p < metainfo.pieceCount(); p++) {
                if (completed.get(p)) {
                    continue;
                }
                // A piece can be verified from disk only if it lies entirely within selected files; boundary pieces are always re-downloaded
                if (selection.wantedBytesInPiece(p) != metainfo.pieceLengthAt(p)) {
                    continue;
                }
                candidates.add(p);
            }
            if (candidates.isEmpty()) {
                return completed.copy();
            }
            // Pre-open every wanted, existing file so the scan below can read positionally without the lock.
            // Missing files stay absent from the snapshot and make their pieces read as incomplete.
            for (FileEntry file : metainfo.files()) {
                if (!selection.isFileWanted(file.index()) || file.length() == 0) {
                    continue;
                }
                FileChannel channel = channel(file, false);
                if (channel != null) {
                    snapshot.put(file.index(), channel);
                }
            }
        }

        // Hash candidate pieces concurrently: positional reads are thread-safe and SHA-1 is pure CPU, so a
        // recheck of a large torrent scales with cores instead of running one piece at a time. Workers pull from
        // a shared cursor and each keeps a single buffer, bounding memory to workers x pieceLength.
        int workers = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), candidates.size()));
        AtomicInteger cursor = new AtomicInteger();
        java.util.Set<Integer> verified = ConcurrentHashMap.newKeySet();
        java.util.Queue<IOException> failures = new java.util.concurrent.ConcurrentLinkedQueue<>();
        List<Thread> threads = new ArrayList<>(workers);
        for (int w = 0; w < workers; w++) {
            threads.add(Thread.ofVirtual().name("bt4j-recheck-" + w).start(() -> {
                byte[] buffer = null;
                int i;
                while ((i = cursor.getAndIncrement()) < candidates.size()) {
                    int piece = candidates.get(i);
                    int length = metainfo.pieceLengthAt(piece);
                    if (buffer == null || buffer.length != length) {
                        buffer = new byte[length]; // exact size so the digest never covers stale trailing bytes
                    }
                    try {
                        if (readFromSnapshot(snapshot, (long) piece * metainfo.pieceLength(), buffer) != length) {
                            continue; // file missing or shorter than expected -> piece incomplete
                        }
                        if (java.util.Arrays.equals(sha1(buffer), metainfo.pieceHash(piece))) {
                            verified.add(piece);
                        }
                    } catch (IOException e) {
                        failures.add(e);
                        return;
                    }
                }
            }));
        }
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("recheck interrupted", e);
            }
        }
        IOException failure = failures.poll();
        if (failure != null) {
            throw failure;
        }
        synchronized (this) {
            for (int piece : verified) {
                completed.set(piece);
            }
            return completed.copy();
        }
    }

    /** Lock-free counterpart of {@link #readFromFiles} that reads through a pre-resolved channel snapshot. */
    private int readFromSnapshot(Map<Integer, FileChannel> snapshot, long globalStart, byte[] out) throws IOException {
        int filled = 0;
        long globalEnd = globalStart + out.length;
        for (FileEntry file : metainfo.files()) {
            if (!selection.isFileWanted(file.index()) || file.length() == 0) {
                continue;
            }
            long fileStart = file.offset();
            long fileEnd = fileStart + file.length();
            long overlapStart = Math.max(globalStart, fileStart);
            long overlapEnd = Math.min(globalEnd, fileEnd);
            if (overlapStart >= overlapEnd) {
                continue;
            }
            FileChannel channel = snapshot.get(file.index());
            if (channel == null) {
                return filled; // file does not exist -> this piece is incomplete
            }
            ByteBuffer slice = ByteBuffer.wrap(out, (int) (overlapStart - globalStart), (int) (overlapEnd - overlapStart));
            long position = overlapStart - fileStart;
            while (slice.hasRemaining()) {
                int n = channel.read(slice, position);
                if (n < 0) {
                    return filled; // file is shorter than expected (incomplete)
                }
                position += n;
            }
            filled += (int) (overlapEnd - overlapStart);
        }
        return filled;
    }

    @Override
    public void flush() throws IOException {
        List<FileChannel> open;
        synchronized (this) {
            if (closed) {
                return;
            }
            open = new ArrayList<>(channels.values());
        }
        // force() outside the lock: it can block for a long time and must not stall arriving blocks.
        for (FileChannel channel : open) {
            try {
                channel.force(false); // data only; file metadata is not part of the torrent's integrity
            } catch (ClosedChannelException ignored) {
                // closed underneath us during teardown
            }
        }
    }

    @Override
    public synchronized void close() throws IOException {
        closed = true;
        pieceBuffers.clear();
        partialBlocks.clear();
        IOException first = null;
        for (FileChannel channel : channels.values()) {
            try {
                channel.close();
            } catch (IOException e) {
                if (first == null) {
                    first = e;
                }
            }
        }
        channels.clear();
        if (first != null) {
            throw first;
        }
    }
}
