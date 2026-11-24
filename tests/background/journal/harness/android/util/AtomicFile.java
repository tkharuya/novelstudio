package android.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** JVM test double. Failure switches are not part of production Android code. */
public final class AtomicFile {
    public static volatile boolean failStart;
    public static volatile boolean failFinish;
    private final File base;
    private final File pending;
    public AtomicFile(File base) { this.base = base; this.pending = new File(base.getPath() + ".new"); }
    public File getBaseFile() { return base; }
    public FileInputStream openRead() throws IOException {
        File backup = new File(base.getPath() + ".bak");
        if (backup.exists()) Files.move(backup.toPath(), base.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return new FileInputStream(base);
    }
    public FileOutputStream startWrite() throws IOException {
        if (failStart) throw new IOException("Synthetic storage failure");
        if (!base.getParentFile().exists() && !base.getParentFile().mkdirs()) throw new IOException("Cannot create test directory");
        return new FileOutputStream(pending);
    }
    public void finishWrite(FileOutputStream output) {
        try {
            output.close();
            if (failFinish) { Files.deleteIfExists(pending.toPath()); return; }
            Files.move(pending.toPath(), base.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) { /* Android AtomicFile can log a rename failure. */ }
    }
    public void failWrite(FileOutputStream output) {
        try { output.close(); } catch (IOException ignored) { }
        try { Files.deleteIfExists(pending.toPath()); } catch (IOException ignored) { }
    }
}
