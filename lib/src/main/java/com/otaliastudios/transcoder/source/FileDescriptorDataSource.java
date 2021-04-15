package com.otaliastudios.transcoder.source;

import android.content.res.AssetFileDescriptor;
import android.media.MediaExtractor;
import android.media.MediaMetadataRetriever;

import java.io.FileDescriptor;
import java.io.IOException;

import androidx.annotation.NonNull;

/**
 * A {@link DataSource} backed by a file descriptor.
 * It is the caller responsibility to close the file descriptor.
 */
public class FileDescriptorDataSource extends DefaultDataSource {

    @NonNull
    private final FileDescriptor descriptor;
    private final long offset;
    private final long length;

    public FileDescriptorDataSource(@NonNull FileDescriptor descriptor) {
        // length is intentionally less than LONG_MAX, see retriever
        this(descriptor, 0, 0x7ffffffffffffffL);
    }

    public FileDescriptorDataSource(@NonNull FileDescriptor descriptor, long offset, long length) {
        this.descriptor = descriptor;
        this.offset = offset;
        this.length = length > 0 ? length : 0x7ffffffffffffffL;
    }

    @Override
    protected void initializeExtractor(@NonNull MediaExtractor extractor) throws IOException  {
        extractor.setDataSource(descriptor, offset, length);
    }

    @Override
    protected void initializeRetriever(@NonNull MediaMetadataRetriever retriever) {
        retriever.setDataSource(descriptor, offset, length);
    }
}
