package com.otaliastudios.transcoder.strategy;

import android.media.MediaFormat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * An {@link TrackStrategy} that asks the encoder to keep this track as is.
 * Note that this is risky, as the track type might not be supported by
 * the mp4 container.
 */
@SuppressWarnings("unused")
public class PassThroughTrackStrategy implements TrackStrategy {

    @Nullable
    @Override
    public MediaFormat createOutputFormat(@NonNull MediaFormat inputFormat) throws TrackStrategyException {
        return inputFormat;
    }
}
