package com.otaliastudios.transcoder.integration

import android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.otaliastudios.transcoder.Transcoder
import com.otaliastudios.transcoder.TranscoderListener
import com.otaliastudios.transcoder.TranscoderOptions
import com.otaliastudios.transcoder.internal.utils.Logger
import com.otaliastudios.transcoder.source.AssetFileDescriptorDataSource
import com.otaliastudios.transcoder.source.ClipDataSource
import com.otaliastudios.transcoder.source.FileDescriptorDataSource
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class IssuesTests {

    class Helper(val issue: Int) {

        val log = Logger("Issue$issue")
        val context = InstrumentationRegistry.getInstrumentation().context

        fun output(
                name: String = System.currentTimeMillis().toString(),
                extension: String = "mp4"
        ) = File(context.cacheDir, "$name.$extension").also { it.parentFile!!.mkdirs() }

        fun input(filename: String) = AssetFileDescriptorDataSource(
                context.assets.openFd("issue_$issue/$filename")
        )

        fun transcode(
                output: File = output(),
                assertTranscoded: Boolean = true,
                assertDuration: Boolean = true,
                builder: TranscoderOptions.Builder.() -> Unit,
        ): File {
            val transcoder = Transcoder.into(output.absolutePath)
            transcoder.apply(builder)
            transcoder.setListener(object : TranscoderListener {
                override fun onTranscodeCanceled() = Unit
                override fun onTranscodeCompleted(successCode: Int) {
                    if (assertTranscoded) {
                        require(successCode == Transcoder.SUCCESS_TRANSCODED)
                    }
                }
                override fun onTranscodeFailed(exception: Throwable) = Unit
                override fun onTranscodeProgress(progress: Double) = Unit
            })
            transcoder.transcode().get()
            if (assertDuration) {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(output.absolutePath)
                val duration = retriever.extractMetadata(METADATA_KEY_DURATION)!!.toLong()
                log.e("Video duration is $duration")
                assert(duration > 0L)
                retriever.release()
            }
            return output
        }
    }


    @Test
    fun issue137() = with(Helper(137)) {
        transcode {
            addDataSource(ClipDataSource(input("main.mp3"), 0L, 1000_000L))
            addDataSource(input("0.amr"))
            addDataSource(ClipDataSource(input("main.mp3"), 0L, 1000_000L))
            addDataSource(input("1.amr"))
            addDataSource(ClipDataSource(input("main.mp3"), 0L, 1000_000L))
            addDataSource(input("2.amr"))
            addDataSource(ClipDataSource(input("main.mp3"), 0L, 1000_000L))
            addDataSource(input("3.amr"))
            addDataSource(ClipDataSource(input("main.mp3"), 0L, 1000_000L))
            addDataSource(input("4.amr"))
            addDataSource(ClipDataSource(input("main.mp3"), 0L, 1000_000L))
            addDataSource(input("5.amr"))
            addDataSource(ClipDataSource(input("main.mp3"), 0L, 1000_000L))
            addDataSource(input("6.amr"))
            addDataSource(ClipDataSource(input("main.mp3"), 0L, 1000_000L))
            addDataSource(input("7.amr"))
            addDataSource(ClipDataSource(input("main.mp3"), 0L, 1000_000L))
            addDataSource(input("8.amr"))
        }
        Unit
    }
}