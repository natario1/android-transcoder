package com.otaliastudios.transcoder.time;

import androidx.annotation.NonNull;

import com.otaliastudios.transcoder.common.TrackType;
import com.otaliastudios.transcoder.internal.utils.TrackMap;
import com.otaliastudios.transcoder.internal.utils.Logger;

import static com.otaliastudios.transcoder.internal.utils.TrackMapKt.trackMapOf;


/**
 * A {@link TimeInterpolator} that modifies the playback speed by the given
 * float factor. A factor less than 1 will slow down, while a bigger factor will
 * accelerate.
 */
public class SpeedTimeInterpolator implements TimeInterpolator {

    private final static Logger LOG = new Logger("SpeedTimeInterpolator");

    private final double mFactor;
    private final TrackMap<TrackData> mTrackData = trackMapOf(new TrackData(), new TrackData());

    /**
     * Creates a new speed interpolator for the given factor.
     * Throws if factor is less than 0 or equal to 0.
     * @param factor a factor
     */
    public SpeedTimeInterpolator(float factor) {
        if (factor <= 0) {
            throw new IllegalArgumentException("Invalid speed factor: " + factor);
        }
        mFactor = factor;
    }

    /**
     * Returns the factor passed to the constructor.
     * @return the factor
     */
    public float getFactor(@NonNull TrackType type, long time) {
        return (float) mFactor;
    }

    @Override
    public long interpolate(@NonNull TrackType type, long time) {
        TrackData data = mTrackData.get(type);
        if (data.lastRealTime == Long.MIN_VALUE) {
            data.lastRealTime = time;
            data.lastCorrectedTime = time;
        } else {
            long realDelta = time - data.lastRealTime;
            long correctedDelta = (long) ((double) realDelta / getFactor(type, time));
            data.lastRealTime = time;
            data.lastCorrectedTime += correctedDelta;
        }
        LOG.v("Track:" + type + " inputTime:" + time + " outputTime:" + data.lastCorrectedTime);
        return data.lastCorrectedTime;
    }

    private static class TrackData {
        private long lastRealTime = Long.MIN_VALUE;
        private long lastCorrectedTime = Long.MIN_VALUE;
    }
}
