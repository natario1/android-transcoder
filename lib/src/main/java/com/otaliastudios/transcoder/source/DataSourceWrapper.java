package com.otaliastudios.transcoder.source;


import android.media.MediaFormat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.otaliastudios.transcoder.common.TrackType;

/**
 * A {@link DataSource} wrapper that simply delegates all methods to the
 * wrapped source. It is the implementor responsibility to care about the case where
 * the wrapped source is already initialized, in case they are overriding initialize.
 */
public class DataSourceWrapper implements DataSource {

    private DataSource mSource;

    @SuppressWarnings("WeakerAccess")
    protected DataSourceWrapper(@NonNull DataSource source) {
        mSource = source;
    }

    // Only use if you know what you are doing
    protected DataSourceWrapper() {
        mSource = null;
    }

    @NonNull
    protected DataSource getSource() {
        return mSource;
    }

    // Only use if you know what you are doing
    protected void setSource(@NonNull DataSource source) {
        mSource = source;
    }

    @Override
    public int getOrientation() {
        return mSource.getOrientation();
    }

    @Nullable
    @Override
    public double[] getLocation() {
        return mSource.getLocation();
    }

    @Override
    public long getDurationUs() {
        return mSource.getDurationUs();
    }

    @Nullable
    @Override
    public MediaFormat getTrackFormat(@NonNull TrackType type) {
        return mSource.getTrackFormat(type);
    }

    @Override
    public void selectTrack(@NonNull TrackType type) {
        mSource.selectTrack(type);
    }

    @Override
    public long seekTo(long desiredPositionUs) {
        return mSource.seekTo(desiredPositionUs);
    }

    @Override
    public boolean canReadTrack(@NonNull TrackType type) {
        return mSource.canReadTrack(type);
    }

    @Override
    public void readTrack(@NonNull Chunk chunk) {
        mSource.readTrack(chunk);
    }

    @Override
    public long getPositionUs() {
        return mSource.getPositionUs();
    }

    @Override
    public boolean isDrained() {
        return mSource.isDrained();
    }

    @Override
    public void releaseTrack(@NonNull TrackType type) {
        mSource.releaseTrack(type);
    }

    @Override
    public boolean isInitialized() {
        return mSource.isInitialized();
    }

    @Override
    public void initialize() {
        // Make it easier for subclasses to put their logic in initialize safely.
        if (!mSource.isInitialized()) {
            mSource.initialize();
        }
    }

    @Override
    public void deinitialize() {
        mSource.deinitialize();
    }
}
