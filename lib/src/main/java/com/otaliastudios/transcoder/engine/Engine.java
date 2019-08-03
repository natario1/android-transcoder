/*
 * Copyright (C) 2014 Yuya Tanaka
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.otaliastudios.transcoder.engine;

import android.media.MediaFormat;

import com.otaliastudios.transcoder.TranscoderOptions;
import com.otaliastudios.transcoder.internal.TrackTypeMap;
import com.otaliastudios.transcoder.internal.ValidatorException;
import com.otaliastudios.transcoder.sink.DataSink;
import com.otaliastudios.transcoder.sink.InvalidOutputFormatException;
import com.otaliastudios.transcoder.sink.MediaMuxerDataSink;
import com.otaliastudios.transcoder.source.DataSource;
import com.otaliastudios.transcoder.strategy.TrackStrategy;
import com.otaliastudios.transcoder.time.TimeInterpolator;
import com.otaliastudios.transcoder.transcode.AudioTrackTranscoder;
import com.otaliastudios.transcoder.transcode.NoOpTrackTranscoder;
import com.otaliastudios.transcoder.transcode.PassThroughTrackTranscoder;
import com.otaliastudios.transcoder.transcode.TrackTranscoder;
import com.otaliastudios.transcoder.transcode.VideoTrackTranscoder;
import com.otaliastudios.transcoder.internal.Logger;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Internal engine, do not use this directly.
 */
public class Engine {

    private static final String TAG = Engine.class.getSimpleName();
    private static final Logger LOG = new Logger(TAG);

    private static final long SLEEP_TO_WAIT_TRACK_TRANSCODERS = 10;
    private static final long PROGRESS_INTERVAL_STEPS = 10;


    public interface ProgressCallback {

        /**
         * Called to notify progress. Same thread which initiated transcode is used.
         * @param progress Progress in [0.0, 1.0] range, or negative value if progress is unknown.
         */
        void onProgress(double progress);
    }

    private DataSink mDataSink;
    private final TrackTypeMap<List<DataSource>> mDataSources = new TrackTypeMap<>();
    private final TrackTypeMap<ArrayList<TrackTranscoder>> mTranscoders = new TrackTypeMap<>();
    private final TrackTypeMap<ArrayList<TimeInterpolator>> mInterpolators = new TrackTypeMap<>();
    private final TrackTypeMap<Integer> mCurrentStep = new TrackTypeMap<>();
    private final TrackTypeMap<TrackStatus> mStatuses = new TrackTypeMap<>();
    private final TrackTypeMap<MediaFormat> mOutputFormats = new TrackTypeMap<>();
    private volatile double mProgress;
    private final ProgressCallback mProgressCallback;

    public Engine(@Nullable ProgressCallback progressCallback) {
        mProgressCallback = progressCallback;
        mCurrentStep.setVideo(0);
        mCurrentStep.setAudio(0);
        mTranscoders.setVideo(new ArrayList<TrackTranscoder>());
        mTranscoders.setAudio(new ArrayList<TrackTranscoder>());
    }

    /**
     * Returns the current progress.
     * Note: This method is thread safe.
     * @return the current progress
     */
    @SuppressWarnings("unused")
    public double getProgress() {
        return mProgress;
    }

    private void setProgress(double progress) {
        mProgress = progress;
        if (mProgressCallback != null) {
            mProgressCallback.onProgress(progress);
        }
    }

    private boolean hasVideoSources() {
        return !mDataSources.requireVideo().isEmpty();
    }

    private boolean hasAudioSources() {
        return !mDataSources.requireAudio().isEmpty();
    }

    private Set<DataSource> getUniqueSources() {
        Set<DataSource> sources = new HashSet<>();
        sources.addAll(mDataSources.requireVideo());
        sources.addAll(mDataSources.requireAudio());
        return sources;
    }

    private void computeTrackStatus(@NonNull TrackType type,
                                    @NonNull TrackStrategy strategy,
                                    @NonNull List<DataSource> sources) {
        TrackStatus status = TrackStatus.ABSENT;
        MediaFormat outputFormat = new MediaFormat();
        if (!sources.isEmpty()) {
            List<MediaFormat> inputFormats = new ArrayList<>();
            for (DataSource source : sources) {
                MediaFormat inputFormat = source.getTrackFormat(type);
                if (inputFormat != null) {
                    inputFormats.add(inputFormat);
                } else if (sources.size() > 1) {
                    throw new IllegalArgumentException("More than one source selected for type " + type
                            + ", but getTrackFormat returned null.");
                }
            }
            status = strategy.createOutputFormat(inputFormats, outputFormat);
        }
        mOutputFormats.set(type, outputFormat);
        mDataSink.setTrackStatus(type, status);
        mStatuses.set(type, status);
    }

    private boolean isCompleted(@NonNull TrackType type) {
        int current = mCurrentStep.require(type);
        return !mDataSources.require(type).isEmpty()
                && current == mDataSources.require(type).size() - 1
                && current == mTranscoders.require(type).size() - 1
                && mTranscoders.require(type).get(current).isFinished();
    }

    private void openCurrentStep(@NonNull TrackType type, @NonNull TranscoderOptions options) {
        int current = mCurrentStep.require(type);
        TrackStatus status = mStatuses.require(type);

        // Notify the data source that we'll be transcoding this track.
        DataSource dataSource = mDataSources.require(type).get(current);
        if (status.isTranscoding()) {
            dataSource.selectTrack(type);
        }

        // Create a TimeInterpolator, wrapping the external one.
        TimeInterpolator interpolator = createStepTimeInterpolator(type, current,
                options.getTimeInterpolator());
        mInterpolators.require(type).add(interpolator);

        // Create a Transcoder for this track.
        TrackTranscoder transcoder;
        switch (status) {
            case PASS_THROUGH: {
                transcoder = new PassThroughTrackTranscoder(dataSource,
                        mDataSink, type, interpolator);
                break;
            }
            case COMPRESSING: {
                switch (type) {
                    case VIDEO:
                        transcoder = new VideoTrackTranscoder(dataSource, mDataSink, interpolator);
                        break;
                    case AUDIO:
                        transcoder = new AudioTrackTranscoder(dataSource, mDataSink,
                                interpolator, options.getAudioStretcher());
                        break;
                    default:
                        throw new RuntimeException("Unknown type: " + type);
                }
                break;
            }
            case ABSENT:
            case REMOVING:
            default: {
                transcoder = new NoOpTrackTranscoder();
                break;
            }
        }
        transcoder.setUp(mOutputFormats.require(type));
        mTranscoders.require(type).add(transcoder);
    }

    private void closeCurrentStep(@NonNull TrackType type) {
        int current = mCurrentStep.require(type);
        TrackTranscoder transcoder = mTranscoders.require(type).get(current);
        DataSource dataSource = mDataSources.require(type).get(current);
        transcoder.release();
        dataSource.release();
        mCurrentStep.set(type, current + 1);
    }

    @NonNull
    private TrackTranscoder getCurrentTrackTranscoder(@NonNull TrackType type, @NonNull TranscoderOptions options) {
        int current = mCurrentStep.require(type);
        int last = mTranscoders.require(type).size() - 1;
        if (last == current) {
            // We have already created a transcoder for this step.
            // But this step might be completed and we might need to create a new one.
            TrackTranscoder transcoder = mTranscoders.require(type).get(last);
            if (transcoder.isFinished()) {
                closeCurrentStep(type);
                return getCurrentTrackTranscoder(type, options);
            } else {
                return mTranscoders.require(type).get(current);
            }
        } else if (last < current) {
            // We need to create a new step.
            openCurrentStep(type, options);
            return mTranscoders.require(type).get(current);
        } else {
            throw new IllegalStateException("This should never happen. last:" + last + ", current:" + current);
        }
    }

    @NonNull
    private TimeInterpolator createStepTimeInterpolator(@NonNull TrackType type, int step,
                                                        final @NonNull TimeInterpolator wrap) {
        final long timebase;
        if (step > 0) {
            TimeInterpolator previous = mInterpolators.require(type).get(step - 1);
            timebase = previous.interpolate(type, Long.MAX_VALUE);
        } else {
            timebase = 0;
        }
        return new TimeInterpolator() {

            private long mLastInterpolatedTime;
            private long mFirstInputTime = Long.MAX_VALUE;
            private long mTimeBase = timebase + 10;

            @Override
            public long interpolate(@NonNull TrackType type, long time) {
                if (time == Long.MAX_VALUE) return mLastInterpolatedTime;
                if (mFirstInputTime == Long.MAX_VALUE) mFirstInputTime = time;
                mLastInterpolatedTime = mTimeBase + (time - mFirstInputTime);
                return wrap.interpolate(type, mLastInterpolatedTime);
            }
        };
    }

    private double getTrackProgress(@NonNull TrackType type) {
        if (!mStatuses.require(type).isTranscoding()) return 0.0D;
        int current = mCurrentStep.require(type);
        long totalDurationUs = 0;
        long completedDurationUs = 0;
        for (int i = 0; i < mDataSources.require(type).size(); i++) {
            DataSource source = mDataSources.require(type).get(i);
            if (i < current) {
                totalDurationUs += source.getLastTimestampUs() - source.getFirstTimestampUs();
                completedDurationUs += source.getLastTimestampUs() - source.getFirstTimestampUs();
            } else if (i == current) {
                totalDurationUs += source.getDurationUs();
                completedDurationUs += source.getLastTimestampUs() - source.getFirstTimestampUs();
            } else {
                totalDurationUs += source.getDurationUs();
                completedDurationUs += 0;
            }
        }
        return (double) completedDurationUs / (double) totalDurationUs;
    }

    /**
     * Performs transcoding. Blocks current thread.
     *
     * @param options Transcoding options.
     * @throws InvalidOutputFormatException when output format is not supported.
     * @throws InterruptedException when cancel to transcode
     */
    public void transcode(@NonNull TranscoderOptions options) throws InterruptedException {
        mDataSink = new MediaMuxerDataSink(options.getOutputPath());
        mDataSources.setVideo(options.getVideoDataSources());
        mDataSources.setAudio(options.getAudioDataSources());

        // Pass metadata from DataSource to DataSink
        if (hasVideoSources()) {
            DataSource firstVideoSource = mDataSources.requireVideo().get(0);
            mDataSink.setOrientation((firstVideoSource.getOrientation() + options.getRotation()) % 360);
        }
        for (DataSource locationSource : getUniqueSources()) {
            double[] location = locationSource.getLocation();
            if (location != null) {
                mDataSink.setLocation(location[0], location[1]);
                break;
            }
        }

        // Compute total duration: it is the minimum between the two.
        long audioDurationUs = hasAudioSources() ? 0 : Long.MAX_VALUE;
        long videoDurationUs = hasVideoSources() ? 0 : Long.MAX_VALUE;
        for (DataSource source : options.getVideoDataSources()) videoDurationUs += source.getDurationUs();
        for (DataSource source : options.getAudioDataSources()) audioDurationUs += source.getDurationUs();
        long totalDurationUs = Math.min(audioDurationUs, videoDurationUs);
        LOG.v("Duration (us): " + totalDurationUs);
        // TODO if audio and video have different lengths, we should clip the longer one!

        // Compute the TrackStatus.
        int activeTracks = 0;
        computeTrackStatus(TrackType.AUDIO, options.getAudioTrackStrategy(), options.getAudioDataSources());
        computeTrackStatus(TrackType.VIDEO, options.getVideoTrackStrategy(), options.getVideoDataSources());
        TrackStatus videoStatus = mStatuses.requireVideo();
        TrackStatus audioStatus = mStatuses.requireAudio();
        if (videoStatus.isTranscoding()) activeTracks++;
        if (audioStatus.isTranscoding()) activeTracks++;

        // Pass to Validator.
        //noinspection UnusedAssignment
        boolean ignoreValidatorResult = false;
        // If we have to apply some rotation, and the video should be transcoded,
        // ignore any Validator trying to abort the operation. The operation must happen
        // because we must apply the rotation.
        ignoreValidatorResult = videoStatus.isTranscoding() && options.getRotation() != 0;
        if (!options.getValidator().validate(videoStatus, audioStatus) && !ignoreValidatorResult) {
            throw new ValidatorException("Validator returned false.");
        }

        // Do the actual transcoding work.
        try {
            long loopCount = 0;
            while (!(isCompleted(TrackType.AUDIO) && isCompleted(TrackType.VIDEO))) {
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
                boolean stepped = getCurrentTrackTranscoder(TrackType.VIDEO, options).transcode()
                        || getCurrentTrackTranscoder(TrackType.AUDIO, options).transcode();
                if (++loopCount % PROGRESS_INTERVAL_STEPS == 0) {
                    setProgress((getTrackProgress(TrackType.VIDEO)
                            + getTrackProgress(TrackType.AUDIO)) / activeTracks);
                }
                if (!stepped) {
                    Thread.sleep(SLEEP_TO_WAIT_TRACK_TRANSCODERS);
                }
            }
            mDataSink.stop();
        } finally {
            closeCurrentStep(TrackType.VIDEO);
            closeCurrentStep(TrackType.AUDIO);
            mDataSink.release();
        }
    }
}
