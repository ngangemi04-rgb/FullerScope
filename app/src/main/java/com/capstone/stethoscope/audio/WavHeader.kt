package com.capstone.stethoscope.audio

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds a canonical 44-byte WAV (RIFF/PCM) header.
 *
 * Two callers with different needs share one implementation so the byte layout cannot
 * drift between them:
 *  - [write] patches a reserved header in place. Recording streams PCM to disk and only
 *    learns the total length when it stops, so the recorder reserves 44 bytes up front
 *    and calls this at the end.
 *  - [toByteArray] returns the bytes for a writer that already knows the length, which
 *    is how [com.capstone.stethoscope.analysis.pcg.PcgPreprocessor] emits its
 *    PhysioNet-compatible output.
 *
 * "Canonical" is load-bearing rather than stylistic: the PCG spec requires a 16-byte
 * PCM `fmt ` chunk with the `data` chunk starting at offset 36, which is exactly what
 * makes the header 44 bytes. Adding a LIST or fact chunk would break byte-compatibility
 * with the PhysioNet/CinC 2016 dataset.
 */
object WavHeader {

    const val SIZE = 44

    fun write(
        raf: RandomAccessFile,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        audioDataBytes: Long,
    ) {
        raf.seek(0)
        raf.write(toByteArray(sampleRate, channels, bitsPerSample, audioDataBytes))
    }

    fun toByteArray(
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        audioDataBytes: Long,
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val header = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)

        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((36 + audioDataBytes).toInt())        // ChunkSize
        header.put("WAVE".toByteArray(Charsets.US_ASCII))

        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)                                    // Subchunk1Size (PCM)
        header.putShort(1)                                   // AudioFormat = PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())

        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(audioDataBytes.toInt())                // Subchunk2Size

        return header.array()
    }
}
