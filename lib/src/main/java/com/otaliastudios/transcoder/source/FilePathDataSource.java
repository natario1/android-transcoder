package com.otaliastudios.transcoder.source;

import android.media.MediaExtractor;

import java.io.FileInputStream;
import java.io.IOException;

import androidx.annotation.NonNull;

/**
 * A {@link DataSource} backed by a file absolute path.
 *
 * This class actually wraps a {@link FileDescriptorDataSource} for the apply() operations.
 * We could pass the path directly to MediaExtractor and MediaMetadataRetriever, but that is
 * discouraged since they could not be able to open the file from another process.
 *
 * See {@link MediaExtractor#setDataSource(String)} documentation.
 */
public class FilePathDataSource extends DataSourceWrapper {
    private FileInputStream mStream;
    private final String mPath;

    public FilePathDataSource(@NonNull String path) {
        mPath = path;
    }

    @Override
    public void initialize() {
        try {
            mStream = new FileInputStream(mPath);
            setSource(new FileDescriptorDataSource(mStream.getFD()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        super.initialize();
    }

    @Override
    public void deinitialize() {
        try { mStream.close(); } catch (IOException ignore) { }
        super.deinitialize();
    }
}
