package org.renpy.android;

import androidx.core.content.FileProvider;

public class RenPyFileProvider extends FileProvider {
    public RenPyFileProvider() {
        super(io.github.helterfall.ramen.R.xml.file_paths);
    }
}
