package com.otaliastudios.transcoder.internal

import com.otaliastudios.transcoder.ThumbnailerOptions
import com.otaliastudios.transcoder.TranscoderOptions
import com.otaliastudios.transcoder.common.TrackType
import com.otaliastudios.transcoder.internal.utils.Logger
import com.otaliastudios.transcoder.internal.utils.TrackMap
import com.otaliastudios.transcoder.internal.utils.trackMapOf
import com.otaliastudios.transcoder.source.BlankAudioDataSource
import com.otaliastudios.transcoder.source.DataSource

internal class DataSources private constructor(
        videoSources: List<DataSource>,
        audioSources: List<DataSource>,
) : TrackMap<List<DataSource>> {

    constructor(options: TranscoderOptions) : this(options.videoDataSources, options.audioDataSources)
    constructor(options: ThumbnailerOptions) : this(options.dataSources, listOf())

    private val log = Logger("DataSources")

    private fun DataSource.init() = if (!isInitialized) initialize() else Unit
    private fun DataSource.deinit() = if (isInitialized) deinitialize() else Unit
    private fun List<DataSource>.init() = forEach {
        log.i("initializing $it... (isInit=${it.isInitialized})")
        it.init()
    }
    private fun List<DataSource>.deinit() = forEach {
        log.i("deinitializing $it... (isInit=${it.isInitialized})")
        it.deinit()
    }

    init {
        log.i("initializing videoSources...")
        videoSources.init()
        log.i("initializing audioSources...")
        audioSources.init()
    }

    // Save and deinit on release, because a source that is discarded for video
    // might be active for audio. We don't want to deinit right away.
    private val discarded = mutableListOf<DataSource>()

    private val videoSources: List<DataSource> = run {
        val valid = videoSources.count { it.getTrackFormat(TrackType.VIDEO) != null }
        when (valid) {
            0 -> listOf<DataSource>().also { discarded += videoSources }
            videoSources.size -> videoSources
            else -> videoSources // Tracks will crash
        }
    }

    private val audioSources: List<DataSource> = run {
        val valid = audioSources.count { it.getTrackFormat(TrackType.AUDIO) != null }
        log.i("computing audioSources, valid=$valid")
        when (valid) {
            0 -> listOf<DataSource>().also { discarded += audioSources }
            audioSources.size -> audioSources
            else -> {
                // Some tracks do not have audio, while some do. Replace with BlankAudio.
                audioSources.map { source ->
                    if (source.getTrackFormat(TrackType.AUDIO) != null) source
                    else BlankAudioDataSource(source.durationUs).also {
                        discarded += source
                    }
                }
            }
        }
    }

    override fun get(type: TrackType) = when (type) {
        TrackType.AUDIO -> audioSources
        TrackType.VIDEO -> videoSources
    }

    override fun has(type: TrackType) = this[type].isNotEmpty()

    fun all() = (audio + video).distinct()

    fun release() {
        log.i("release(): releasing...")
        video.deinit()
        audio.deinit()
        discarded.deinit()
        log.i("release(): released.")
    }
}