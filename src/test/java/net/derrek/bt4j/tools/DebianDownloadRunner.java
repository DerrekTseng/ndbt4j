package net.derrek.bt4j.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import net.derrek.bt4j.Bt;
import net.derrek.bt4j.TaskState;
import net.derrek.bt4j.TorrentContent;
import net.derrek.bt4j.TorrentDownloadJob;
import net.derrek.bt4j.TorrentDownloadTask;

/**
 * A manually-run full download demo (not a unit test): verifies downloading to 100% using the official Debian netinst torrent.
 *
 * Run: after mvn -q test-compile
 *   mvn org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
 *       -Dexec.mainClass=net.derrek.bt4j.tools.DebianDownloadRunner \
 *       -Dexec.classpathScope=test \
 *       -Dexec.args="downloads/debian.torrent downloads"
 */
public final class DebianDownloadRunner {

    public static void main(String[] args) throws Exception {
        Path torrent = Path.of(args.length > 0 ? args[0] : "downloads/debian.torrent");
        Path outDir = Path.of(args.length > 1 ? args[1] : "downloads");

        try (Bt bt = Bt.builder().listenPort(6881).build()) {
            TorrentContent content = bt.fromTorrent(torrent);
            System.out.println("[debian] content: " + content.name()
                    + " (" + content.totalSize() + " bytes)");

            TorrentDownloadJob job = bt.createDownloadJob(content.getFileList(), outDir, false);
            TorrentDownloadTask task = bt.download(job);

            long start = System.currentTimeMillis();
            long deadline = start + Duration.ofMinutes(40).toMillis();
            while (task.state() == TaskState.DOWNLOADING && System.currentTimeMillis() < deadline) {
                Thread.sleep(3000);
                System.out.printf("[debian] %.2f%%  %d/%d MB  peers=%d  down=%d KB/s%n",
                        task.progress() * 100,
                        task.downloadedBytes() / (1024 * 1024),
                        task.totalBytes() / (1024 * 1024),
                        task.connectedPeers(),
                        task.downloadRate() / 1024);
            }

            double secs = (System.currentTimeMillis() - start) / 1000.0;
            System.out.printf("[debian] finished: state=%s progress=%.2f%% in %.0fs%n",
                    task.state(), task.progress() * 100, secs);

            Path iso = outDir.resolve(content.name());
            if (Files.exists(iso)) {
                System.out.printf("[debian] file on disk: %s (%d bytes, expected %d) match=%b%n",
                        iso, Files.size(iso), content.totalSize(),
                        Files.size(iso) == content.totalSize());
            }
            System.out.println(task.progress() >= 1.0 ? "[debian] RESULT: COMPLETE" : "[debian] RESULT: INCOMPLETE");
        }
    }
}
