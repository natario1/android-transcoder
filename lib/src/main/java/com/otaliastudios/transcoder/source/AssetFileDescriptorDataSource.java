package com.otaliastudios.transcoder.source;

import android.content.res.AssetFileDescriptor;
import android.media.MediaExtractor;

import androidx.annotation.NonNull;

import java.io.FileInputStream;
import java.io.IOException;

/**
 * It is the caller responsibility to close the file descriptor.
 */
public class AssetFileDescriptorDataSource extends DataSourceWrapper {
    public AssetFileDescriptorDataSource(@NonNull AssetFileDescriptor assetFileDescriptor) {
        super(new FileDescriptorDataSource(
                assetFileDescriptor.getFileDescriptor(),
                assetFileDescriptor.getStartOffset(),
                assetFileDescriptor.getDeclaredLength()
        ));
    }
}
