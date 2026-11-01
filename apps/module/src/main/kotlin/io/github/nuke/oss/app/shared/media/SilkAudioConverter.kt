package io.github.nuke.oss.app.shared.media

import me.yun.silk.AacCodec
import me.yun.silk.SilkCodec
import java.io.File

// The adapter over Silk-Codec-Android. M4A input is the host's own recording format and is used as it is. Every other
// format goes through SILK at one fixed rate and back out as M4A at that same rate: the library documents the rate of
// these two steps, but not the rate its direct decoders produce, so both ends of the chain are pinned. 24 kHz is
// SILK's highest rate and is plenty for speech. The library loads libsilk_codec.so when first used; it is published for
// arm64-v8a only and is missing where the module's own native libraries are not on the loader's path (Zygisk mode without
// an installed module APK of the same build), so a missing library is reported as a failed conversion rather than thrown.
internal class SilkAudioConverter : AudioConverter {
    private val codec: Result<SilkCodec> by lazy {
        try {
            Result.success(SilkCodec())
        } catch (failure: LinkageError) {
            // UnsatisfiedLinkError and ExceptionInInitializerError from the library's static loadLibrary.
            Result.failure(failure)
        }
    }

    override fun toM4a(input: File, output: File): AudioConversion {
        val silk = codec.getOrElse { return AudioConversion.Failed("audio codec unavailable: ${it.javaClass.simpleName}") }
        if (!input.isFile) return AudioConversion.Failed("no input file")
        output.delete()
        val code = when (silk.getFileType(input.path)) {
            M4A -> if (input.copyTo(output, overwrite = true).isFile) 0 else COPY_FAILED
            SILK -> AacCodec.silkToM4a(input.path, output.path, silk, RATE)
            MP3, WAV, FLAC, OGG -> {
                val intermediate = File(output.path + ".silk")
                try {
                    silk.autoToSilk(input.path, intermediate.path, RATE).takeIf { it != 0 }
                        ?: AacCodec.silkToM4a(intermediate.path, output.path, silk, RATE)
                } finally {
                    intermediate.delete()
                }
            }
            MP4 -> AacCodec.mp4ToM4a(input.path, output.path, RATE)
            else -> return AudioConversion.Failed("unsupported audio format")
        }
        if (code != 0 || !output.isFile) {
            output.delete()
            return AudioConversion.Failed("conversion failed ($code): ${AacCodec.getErrorMessage(code)}")
        }
        val duration = AacCodec.getDuration(output.path)
        if (duration <= 0) {
            output.delete()
            return AudioConversion.Failed("the converted file has no length")
        }
        return AudioConversion.Converted(output, duration)
    }

    private companion object {
        const val RATE = 24_000
        const val COPY_FAILED = -1
        // SilkCodec.getFileType values.
        const val SILK = 1
        const val MP3 = 2
        const val WAV = 3
        const val FLAC = 4
        const val OGG = 5
        const val M4A = 7
        const val MP4 = 8
    }
}
