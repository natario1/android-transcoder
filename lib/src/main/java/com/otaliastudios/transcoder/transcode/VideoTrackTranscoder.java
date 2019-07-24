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
package com.otaliastudios.transcoder.transcode;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import androidx.annotation.NonNull;

import com.otaliastudios.transcoder.engine.TrackType;
import com.otaliastudios.transcoder.engine.TranscoderMuxer;
import com.otaliastudios.transcoder.internal.MediaCodecBuffers;
import com.otaliastudios.transcoder.transcode.internal.VideoDecoderOutput;
import com.otaliastudios.transcoder.transcode.internal.VideoEncoderInput;
import com.otaliastudios.transcoder.internal.Logger;
import com.otaliastudios.transcoder.internal.MediaFormatConstants;

import java.io.IOException;

// Refer: https://android.googlesource.com/platform/cts/+/lollipop-release/tests/tests/media/src/android/media/cts/ExtractDecodeEditEncodeMuxTest.java
public class VideoTrackTranscoder implements TrackTranscoder {

    private static final String TAG = VideoTrackTranscoder.class.getSimpleName();
    private static final Logger LOG = new Logger(TAG);

    private static final int DRAIN_STATE_NONE = 0;
    private static final int DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY = 1;
    private static final int DRAIN_STATE_CONSUMED = 2;

    private final MediaExtractor mExtractor;
    private final int mTrackIndex;
    private final TranscoderMuxer mMuxer;
    private final MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
    private MediaCodec mDecoder;
    private MediaCodec mEncoder;
    private MediaCodecBuffers mDecoderBuffers;
    private MediaCodecBuffers mEncoderBuffers;

    private MediaFormat mActualOutputFormat;
    private boolean mIsExtractorEOS;
    private boolean mIsDecoderEOS;
    private boolean mIsEncoderEOS;
    private boolean mDecoderStarted;
    private boolean mEncoderStarted;
    private long mWrittenPresentationTimeUs;

    private VideoDecoderOutput mDecoderOutputSurface;
    private VideoEncoderInput mEncoderInputSurface;

    // A step is defined as the microseconds between two frame.
    // The average step is basically 1 / frame rate.
    private float mAvgStep = 0;
    private float mTargetAvgStep;
    private int mRenderedSteps = -1; // frames - 1
    private long mLastRenderedUs;
    private long mLastStep;

    public VideoTrackTranscoder(
            @NonNull MediaExtractor extractor,
            int trackIndex,
            @NonNull TranscoderMuxer muxer) {
        mExtractor = extractor;
        mTrackIndex = trackIndex;
        mMuxer = muxer;
    }

    @Override
    public void setUp(@NonNull MediaFormat desiredOutputFormat) {
        mExtractor.selectTrack(mTrackIndex);

        int frameRate = desiredOutputFormat.getInteger(MediaFormat.KEY_FRAME_RATE);
        mTargetAvgStep = (1F / frameRate) * 1000 * 1000;

        // Configure encoder.
        try {
            mEncoder = MediaCodec.createEncoderByType(desiredOutputFormat.getString(MediaFormat.KEY_MIME));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        mEncoder.configure(desiredOutputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mEncoderInputSurface = new VideoEncoderInput(mEncoder.createInputSurface());
        mEncoder.start();
        mEncoderStarted = true;
        mEncoderBuffers = new MediaCodecBuffers(mEncoder);

        // Configure decoder.
        MediaFormat inputFormat = mExtractor.getTrackFormat(mTrackIndex);
        if (inputFormat.containsKey(MediaFormatConstants.KEY_ROTATION_DEGREES)) {
            // Decoded video is rotated automatically in Android 5.0 lollipop.
            // Turn off here because we don't want to encode rotated one.
            // refer: https://android.googlesource.com/platform/frameworks/av/+blame/lollipop-release/media/libstagefright/Utils.cpp
            inputFormat.setInteger(MediaFormatConstants.KEY_ROTATION_DEGREES, 0);
        }
        // Cropping support.
        float inputWidth = inputFormat.getInteger(MediaFormat.KEY_WIDTH);
        float inputHeight = inputFormat.getInteger(MediaFormat.KEY_HEIGHT);
        float inputRatio = inputWidth / inputHeight;
        float outputWidth = desiredOutputFormat.getInteger(MediaFormat.KEY_WIDTH);
        float outputHeight = desiredOutputFormat.getInteger(MediaFormat.KEY_HEIGHT);
        float outputRatio = outputWidth / outputHeight;
        float scaleX = 1, scaleY = 1;
        if (inputRatio > outputRatio) { // Input wider. We have a scaleX.
            scaleX = inputRatio / outputRatio;
        } else if (inputRatio < outputRatio) { // Input taller. We have a scaleY.
            scaleY = outputRatio / inputRatio;
        }
        // I don't think we should consider rotation and flip these - we operate on non-rotated
        // surfaces and pass the input rotation metadata to the output muxer, see TranscoderEngine.setupMetadata.
        mDecoderOutputSurface = new VideoDecoderOutput(scaleX, scaleY);
        try {
            mDecoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        mDecoder.configure(inputFormat, mDecoderOutputSurface.getSurface(), null, 0);
        mDecoder.start();
        mDecoderStarted = true;
        mDecoderBuffers = new MediaCodecBuffers(mDecoder);
    }

    @Override
    public boolean stepPipeline() {
        boolean busy = false;
        int status;
        while (drainEncoder(0) != DRAIN_STATE_NONE) busy = true;
        do {
            status = drainDecoder(0);
            if (status != DRAIN_STATE_NONE) busy = true;
            // NOTE: not repeating to keep from deadlock when encoder is full.
        } while (status == DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY);
        while (drainExtractor(0) != DRAIN_STATE_NONE) busy = true;

        return busy;
    }

    @Override
    public long getLastWrittenPresentationTime() {
        return mWrittenPresentationTimeUs;
    }

    @Override
    public boolean isFinished() {
        return mIsEncoderEOS;
    }

    // TODO: CloseGuard
    @Override
    public void release() {
        if (mDecoderOutputSurface != null) {
            mDecoderOutputSurface.release();
            mDecoderOutputSurface = null;
        }
        if (mEncoderInputSurface != null) {
            mEncoderInputSurface.release();
            mEncoderInputSurface = null;
        }
        if (mDecoder != null) {
            try{
                if (mDecoderStarted)
                    mDecoder.stop();
                mDecoder.release();
                mDecoder = null;
            }catch(Exception e){
                LOG.e("Error stopping decoder. Probably its already released "+e.getMessage());
            }
        }
        if (mEncoder != null) {
            try{
                if (mEncoderStarted)
                    mEncoder.stop();
                mEncoder.release();
                mEncoder = null;
            }catch(Exception e){
                LOG.e("Error stopping encoding. Probably its already released "+e.getMessage());
            }

        }
    }

    @SuppressWarnings("SameParameterValue")
    private int drainExtractor(long timeoutUs) {
        if (mIsExtractorEOS) return DRAIN_STATE_NONE;
        int trackIndex = mExtractor.getSampleTrackIndex();
        if (trackIndex >= 0 && trackIndex != mTrackIndex) {
            return DRAIN_STATE_NONE;
        }
        int result = mDecoder.dequeueInputBuffer(timeoutUs);
        if (result < 0) return DRAIN_STATE_NONE;
        if (trackIndex < 0) {
            mIsExtractorEOS = true;
            mDecoder.queueInputBuffer(result, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            return DRAIN_STATE_NONE;
        }
        int sampleSize = mExtractor.readSampleData(mDecoderBuffers.getInputBuffer(result), 0);
        boolean isKeyFrame = (mExtractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
        mDecoder.queueInputBuffer(result, 0, sampleSize, mExtractor.getSampleTime(), isKeyFrame ? MediaCodec.BUFFER_FLAG_SYNC_FRAME : 0);
        mExtractor.advance();
        return DRAIN_STATE_CONSUMED;
    }

    @SuppressWarnings("SameParameterValue")
    private int drainDecoder(long timeoutUs) {
        if (mIsDecoderEOS) return DRAIN_STATE_NONE;
        int result = mDecoder.dequeueOutputBuffer(mBufferInfo, timeoutUs);
        switch (result) {
            case MediaCodec.INFO_TRY_AGAIN_LATER:
                return DRAIN_STATE_NONE;
            case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
            case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY;
        }
        if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            mEncoder.signalEndOfInputStream();
            mIsDecoderEOS = true;
            mBufferInfo.size = 0;
        }
        boolean doRender = shouldRenderFrame();
        // NOTE: doRender will block if buffer (of encoder) is full.
        // Refer: http://bigflake.com/mediacodec/CameraToMpegTest.java.txt
        mDecoder.releaseOutputBuffer(result, doRender);
        if (doRender) {
            mDecoderOutputSurface.drawFrame();
            mEncoderInputSurface.onFrame(mBufferInfo.presentationTimeUs);
        }
        return DRAIN_STATE_CONSUMED;
    }

    // TODO improve this. as it is now, rendering a frame after dropping many,
    // will not decrease avgStep but rather increase it (for this single frame; then it starts decreasing).
    // This has the effect that, when a frame is rendered, the following frame is always rendered,
    // because the conditions are worse then before. After this second frame things go back to normal,
    // but this is terrible logic.
    private boolean shouldRenderFrame() {
        if (mBufferInfo.size <= 0) return false;
        if (mRenderedSteps > 0 && mAvgStep < mTargetAvgStep) {
            // We are rendering too much. Drop this frame.
            // Always render first 2 frames, we need them to compute the avg.
            LOG.v("FRAME: Dropping. avg: " + mAvgStep + " target: " + mTargetAvgStep);
            long newLastStep = mBufferInfo.presentationTimeUs - mLastRenderedUs;
            float allSteps = (mAvgStep * mRenderedSteps) - mLastStep + newLastStep;
            mAvgStep = allSteps / mRenderedSteps; // we didn't add a step, just increased the last
            mLastStep = newLastStep;
            return false;
        } else {
            // Render this frame, since our average step is too long or exact.
            LOG.v("FRAME: RENDERING. avg: " + mAvgStep + " target: " + mTargetAvgStep + "New stepCount: " + (mRenderedSteps + 1));
            if (mRenderedSteps >= 0) {
                // Update the average value, since now we have mLastRenderedUs.
                long step = mBufferInfo.presentationTimeUs - mLastRenderedUs;
                float allSteps = (mAvgStep * mRenderedSteps) + step;
                mAvgStep = allSteps / (mRenderedSteps + 1); // we added a step, so +1
                mLastStep = step;
            }
            // Increment both
            mRenderedSteps++;
            mLastRenderedUs = mBufferInfo.presentationTimeUs;
            return true;
        }
    }

    @SuppressWarnings("SameParameterValue")
    private int drainEncoder(long timeoutUs) {
        if (mIsEncoderEOS) return DRAIN_STATE_NONE;
        int result = MediaCodec.INFO_TRY_AGAIN_LATER;
        try{
            result = mEncoder.dequeueOutputBuffer(mBufferInfo, timeoutUs);
        }catch(Exception e){
            LOG.e("Error dequeingoutputbuffer ", e);
        }
        switch (result) {
            case MediaCodec.INFO_TRY_AGAIN_LATER:
                return DRAIN_STATE_NONE;
            case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                if (mActualOutputFormat != null)
                    throw new RuntimeException("Video output format changed twice.");
                mActualOutputFormat = mEncoder.getOutputFormat();
                mMuxer.setOutputFormat(TrackType.VIDEO, mActualOutputFormat);
                return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY;
            case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                mEncoderBuffers.onOutputBuffersChanged();
                return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY;
        }
        if (mActualOutputFormat == null) {
            throw new RuntimeException("Could not determine actual output format.");
        }

        if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            mIsEncoderEOS = true;
            mBufferInfo.set(0, 0, 0, mBufferInfo.flags);
        }
        if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            // SPS or PPS, which should be passed by MediaFormat.
            mEncoder.releaseOutputBuffer(result, false);
            return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY;
        }
        mMuxer.write(TrackType.VIDEO, mEncoderBuffers.getOutputBuffer(result), mBufferInfo);
        mWrittenPresentationTimeUs = mBufferInfo.presentationTimeUs;
        mEncoder.releaseOutputBuffer(result, false);
        return DRAIN_STATE_CONSUMED;
    }
}
