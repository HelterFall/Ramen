package org.renpy.android;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;

import android.app.Activity;
import android.content.res.AssetManager;
import android.util.Log;

import org.kamranzafar.jtar.TarEntry;
import org.kamranzafar.jtar.TarInputStream;

/**
 * Extracts the app-bundled Ren'Py private runtime archive into app-private storage.
 * Archive entry paths are treated as untrusted and must remain under the target root.
 */
class AssetExtract {

    private final AssetManager assetManager;

    AssetExtract(Activity activity) {
        assetManager = activity.getAssets();
    }

    public boolean extractTar(String asset, String target) {
        Log.i("python", "extracting " + asset + " to " + target);

        final File targetRoot;
        try {
            targetRoot = new File(target).getCanonicalFile();
            if (!targetRoot.mkdirs() && !targetRoot.isDirectory()) {
                throw new IOException("Could not create extraction root: " + targetRoot);
            }
        } catch (IOException | SecurityException error) {
            Log.e("python", "Could not prepare extraction directory.", error);
            return false;
        }

        final byte[] buffer = new byte[1024 * 1024];

        try (
            InputStream assetStream = assetManager.open(asset, AssetManager.ACCESS_STREAMING);
            BufferedInputStream bufferedAsset = new BufferedInputStream(assetStream, 8192);
            GZIPInputStream gzipStream = new GZIPInputStream(bufferedAsset, 8192);
            TarInputStream tarStream = new TarInputStream(
                new BufferedInputStream(gzipStream, 8192)
            )
        ) {
            TarEntry entry;
            while ((entry = tarStream.getNextEntry()) != null) {
                String entryName = entry.getName();
                if (entryName == null || entryName.isEmpty()) {
                    throw new IOException("Runtime archive contains an empty entry name.");
                }

                File output = new File(targetRoot, entryName).getCanonicalFile();
                if (!output.toPath().startsWith(targetRoot.toPath()) || output.equals(targetRoot)) {
                    throw new IOException("Runtime archive entry escapes the extraction directory: " + entryName);
                }

                if (entry.isDirectory()) {
                    if (!output.mkdirs() && !output.isDirectory()) {
                        throw new IOException("Could not create extracted directory: " + output);
                    }
                    continue;
                }

                File parent = output.getParentFile();
                if (parent == null || (!parent.mkdirs() && !parent.isDirectory())) {
                    throw new IOException("Could not create parent directory for: " + output);
                }
                if (output.isDirectory()) {
                    throw new IOException("Runtime archive file conflicts with a directory: " + output);
                }

                try (OutputStream outputStream = new BufferedOutputStream(
                    new FileOutputStream(output, false),
                    8192
                )) {
                    while (true) {
                        int length = tarStream.read(buffer);
                        if (length < 0) {
                            break;
                        }
                        if (length == 0) {
                            continue;
                        }
                        outputStream.write(buffer, 0, length);
                    }
                    outputStream.flush();
                }
            }

            return true;
        } catch (Exception error) {
            Log.e("python", "Could not extract " + asset + " safely.", error);
            return false;
        }
    }
}
