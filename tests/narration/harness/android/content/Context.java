package android.content;
import java.io.File;
public class Context {
    private final File files;
    public Context(){try{files=java.nio.file.Files.createTempDirectory("narration-test-").toFile();}catch(Exception e){throw new IllegalStateException(e);}}
    public File getFilesDir(){return files;}
    public File getCacheDir(){return files;}
}
