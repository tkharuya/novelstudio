package jp.haruya.novelstudio;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Native-only credential storage. The key never leaves Android Keystore. */
final class SecureStore {
    private final String alias;
    private final byte[] aad;
    private final AtomicFile file;

    SecureStore(Context context) {
        alias = "novelstudio.siwc.v1";
        aad = "NovelStudio:SIWC:1".getBytes(StandardCharsets.UTF_8);
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "chatgpt.credentials.enc"));
    }

    /** Isolated vault for an explicitly configured, separately billed image API key. */
    SecureStore(Context context, String namespace) {
        if (!"illustration-api".equals(namespace) && !"speech-gemini".equals(namespace)) throw new IllegalArgumentException("Unknown credential namespace");
        alias = "novelstudio." + namespace + ".v1";
        aad = ("NovelStudio:" + namespace + ":1").getBytes(StandardCharsets.UTF_8);
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), namespace + ".credentials.enc"));
    }

    synchronized JSONObject read() throws Exception {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile().getPath() + ".bak").exists()) {
            return new JSONObject();
        }
        byte[] data;
        try (FileInputStream in = file.openRead(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                if (out.size() > 2 * 1024 * 1024) throw new SecurityException("Credential storage is too large");
            }
            data = out.toByteArray();
        }
        if (data.length < 30 || data[0] != 1 || data[1] != 12) {
            throw new SecurityException("Invalid credential storage format");
        }
        byte[] iv = new byte[12];
        System.arraycopy(data, 2, iv, 0, iv.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, iv));
        cipher.updateAAD(aad);
        byte[] clear = cipher.doFinal(data, 14, data.length - 14);
        return new JSONObject(new String(clear, StandardCharsets.UTF_8));
    }

    synchronized void write(JSONObject value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(true));
        cipher.updateAAD(aad);
        byte[] iv = cipher.getIV();
        if (iv.length != 12) throw new SecurityException("Invalid encryption IV");
        byte[] encrypted = cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8));
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(1);
            out.write(iv.length);
            out.write(iv);
            out.write(encrypted);
            file.finishWrite(out);
        } catch (Exception error) {
            if (out != null) file.failWrite(out);
            throw error;
        }
    }

    private SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(alias)) return (SecretKey) store.getKey(alias, null);
        if (!create) throw new SecurityException("Credential key is unavailable");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
