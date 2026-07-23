package net.derrek.bt4j.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
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
        this.metainfo = metainfo;
        this.selection = selection;
        this.root = saveTo;
        this.completed = alreadyCompleted.copy();
    }

    @Override
    public synchronized void write(int pieceIndex, int offset, byte[] data) {
        if (closed || completed.get(pieceIndex)) {
            return;
        }
        byte[] buffer = pieceBuffers.computeIfAbsent(pieceIndex, p -> new byte[metainfo.pieceLengthAt(p)]);
        System.arraycopy(data, 0, buffer, offset, data.length);
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
            if (buffer == null) {
                throw new IllegalStateException("piece " + pieceIndex + " has no data to verify");
            }
        }
        // SHA-1 is CPU-bound over a now-private buffer; run it OUTSIDE the storage monitor so other peers'
        // block writes and other pieces' verifications proceed concurrently instead of serializing behind it.
        boolean ok = java.util.Arrays.equals(sha1(buffer), metainfo.pieceHash(pieceIndex));
        synchronized (this) {
            // Drop any buffer a late endgame-duplicate write() may have recreated for this piece while we hashed.
            pieceBuffers.remove(pieceIndex);
            if (!ok) {
                LOG.log(System.Logger.Level.DEBUG, () -> "piece " + pieceIndex + " failed SHA-1 verification, discarded for re-download");
                return false;
            }
            if (completed.get(pieceIndex) || closed) {
                // Already committed by a prior call, or the storage is closing: do not persist. Since completed
                // is left unset while closing, a resume re-fetches this piece rather than trusting a partial write.
                return true;
            }
            writeVerifiedPiece(pieceIndex, buffer);
            completed.set(pieceIndex);
        }
        LOG.log(System.Logger.Level.TRACE, () -> "piece " + pieceIndex + " verified and written to disk");
        return true;
    }

    /** Write the ranges of a verified piece that "belong to selected files" to their corresponding file positions. */
    private void writeVerifiedPiece(int pieceIndex, byte[] buffer) throws IOException {
        long pieceStart = (long) pieceIndex * metainfo.pieceLength();
        long pieceEnd = pieceStart + buffer.length;
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
            FileChannel channel = channel(file);
            ByteBuffer slice = ByteBuffer.wrap(buffer, (int) (overlapStart - pieceStart), (int) (overlapEnd - overlapStart));
            long position = overlapStart - fileStart;
            while (slice.hasRemaining()) {
                position += channel.write(slice, position);
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
        channels.put(file.index(), channel);
        return channel;
    }

    @Override
    public synchronized Bitfield completedPieces() {
        return completed.copy();
    }

    @Override
    public synchronized Bitfield recheck() throws IOException {
        pieceBuffers.clear();
        for (int p = 0; p < metainfo.pieceCount(); p++) {
            if (completed.get(p)) {
                continue;
            }
            // A piece can be verified from disk only if it lies entirely within selected files; boundary pieces are always re-downloaded
            if (selection.wantedBytesInPiece(p) != metainfo.pieceLengthAt(p)) {
                continue;
            }
            byte[] buffer = new byte[metainfo.pieceLengthAt(p)];
            if (readFromFiles((long) p * metainfo.pieceLength(), buffer, false) != buffer.length) {
                continue; // file does not exist or is incomplete
            }
            if (java.util.Arrays.equals(sha1(buffer), metainfo.pieceHash(p))) {
                completed.set(p);
            }
        }
        return completed.copy();
    }

    @Override
    public synchronized void close() throws IOException {
        closed = true;
        pieceBuffers.clear();
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
