package com.otaliastudios.transcoder.internal

import android.media.MediaFormat
import com.otaliastudios.transcoder.common.TrackStatus
import com.otaliastudios.transcoder.common.TrackType
import com.otaliastudios.transcoder.internal.media.MediaFormatProvider
import com.otaliastudios.transcoder.internal.utils.Logger
import com.otaliastudios.transcoder.internal.utils.TrackMap
import com.otaliastudios.transcoder.internal.utils.trackMapOf
import com.otaliastudios.transcoder.source.DataSource
import com.otaliastudios.transcoder.strategy.TrackStrategy

internal class Tracks(
        strategies: TrackMap<TrackStrategy>,
        sources: DataSources,
        videoRotation: Int,
        forceCompression: Boolean
) {

    private val log = Logger("Tracks")

    val all: TrackMap<TrackStatus>

    val outputFormats: TrackMap<MediaFormat>

    init {
        val (audioFormat, audioStatus) = resolveTrack(TrackType.AUDIO, strategies.audio, sources.audioOrNull())
        val (videoFormat, videoStatus) = resolveTrack(TrackType.VIDEO, strategies.video, sources.videoOrNull())
        all = trackMapOf(
                video = resolveVideoStatus(videoStatus, forceCompression, videoRotation),
                audio = resolveAudioStatus(audioStatus, forceCompression)
        )
        outputFormats = trackMapOf(video = videoFormat, audio = audioFormat)
        log.i("init: videoStatus=$videoStatus, resolvedVideoStatus=${all.video}, videoFormat=$videoFormat")
        log.i("init: audioStatus=$audioStatus, resolvedAudioStatus=${all.audio}, audioFormat=$audioFormat")
    }

    val active: TrackMap<TrackStatus> = trackMapOf(
            video = all.video.takeIf { it.isTranscoding },
            audio = all.audio.takeIf { it.isTranscoding }
    )

    private fun resolveVideoStatus(status: TrackStatus, forceCompression: Boolean, rotation: Int): TrackStatus {
        val force = forceCompression || rotation != 0
        val canForce = status == TrackStatus.PASS_THROUGH
        return if (canForce && force) TrackStatus.COMPRESSING else status
    }

    private fun resolveAudioStatus(status: TrackStatus, forceCompression: Boolean): TrackStatus {
        val force = forceCompression
        val canForce = status == TrackStatus.PASS_THROUGH
        return if (canForce && force) TrackStatus.COMPRESSING else status
    }

    private fun resolveTrack(
            type: TrackType,
            strategy: TrackStrategy,
            sources: List<DataSource>? // null or not-empty
    ): Pair<MediaFormat, TrackStatus> {
        log.i("resolveTrack($type), sources=${sources?.size}, strategy=${strategy::class.simpleName}")
        if (sources == null) {
            return MediaFormat() to TrackStatus.ABSENT
        }

        val provider = MediaFormatProvider()
        val inputs = sources.mapNotNull {
            val format = it.getTrackFormat(type) ?: return@mapNotNull null
            provider.provideMediaFormat(it, type, format)
        }

        // The DataSources class already tries to address this for audio, by inserting
        // a BlankAudioDataSource. However we still don't have a solution for video.
        return when (inputs.size) {
            0 -> MediaFormat() to TrackStatus.ABSENT
            sources.size -> {
                val output = MediaFormat()
                val status = strategy.createOutputFormat(inputs, output)
                output to status
            }
            else -> error("Of all $type sources, some have a $type track, some don't.")
        }
    }
}