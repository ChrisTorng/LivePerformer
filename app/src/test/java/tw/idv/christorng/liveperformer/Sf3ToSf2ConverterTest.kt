package tw.idv.christorng.liveperformer

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

class Sf3ToSf2ConverterTest {
    private fun ByteArrayOutputStream.id(text: String) = write(text.toByteArray(Charsets.US_ASCII))
    private fun ByteArrayOutputStream.u32(n: Long) {
        repeat(4) { write((n ushr (8 * it)).toInt() and 255) }
    }
    private fun ByteArrayOutputStream.chunk(name: String, bytes: ByteArray) {
        id(name)
        u32(bytes.size.toLong())
        write(bytes)
        if (bytes.size % 2 != 0) write(0)
    }
    private fun ByteArrayOutputStream.list(name: String, body: ByteArray) {
        chunk("LIST", ByteArrayOutputStream().apply { id(name); write(body) }.toByteArray())
    }
    private fun makeSF3(): ByteArray {
        val info = ByteArrayOutputStream().apply {
            chunk("ifil", byteArrayOf(3, 0, 0, 0))
            chunk("INAM", "Sound Bank\u0000".toByteArray())
        }.toByteArray()
        val audio = ByteArrayOutputStream().apply {
            chunk("smpl", byteArrayOf(
                'O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte(),
                1, 2, 3, 4, 5, 6
            ))
        }.toByteArray()
        val shdr = ByteArray(46 * 2)
        fun setU32(at: Int, n: Long) {
            repeat(4) { i -> shdr[at + i] = (n ushr (8 * i)).toByte() }
        }
        "Piano".toByteArray().copyInto(shdr)
        setU32(20, 0)
        setU32(24, 9)
        setU32(28, 1)
        setU32(32, 3)
        setU32(36, 44100)
        shdr[44] = 0x11 // Mono + compressed Ogg.
        "EOS".toByteArray().copyInto(shdr, 46)
        val pdta = ByteArrayOutputStream().apply {
            chunk("shdr", shdr)
        }.toByteArray()
        val body = ByteArrayOutputStream().apply {
            id("sfbk")
            list("INFO", info)
            list("sdta", audio)
            list("pdta", pdta)
        }.toByteArray()
        return ByteArrayOutputStream().apply { chunk("RIFF", body) }.toByteArray()
    }
    private fun u32(bytes: ByteArray, offset: Int): Long {
        return (0..3).fold(0L) { n, i ->
            n or ((bytes[offset + i].toLong() and 255) shl (i * 8))
        }
    }

    @Test fun reconstructsPCMAndSampleHeadersWithoutChangingPresets() {
        val input = File.createTempFile("sf3-struct", ".sf3")
        val output = File.createTempFile("sf2-struct", ".sf2")
        try {
            input.writeBytes(makeSF3())
            val steps = mutableListOf<SoundFontImportProgress>()
            Sf3ToSf2Converter.convert(input, output, { steps.add(it) },
                VorbisSampleDecoder { _, _, _, target, rate ->
                    assertEquals(44100, rate)
                    target.write(byteArrayOf(0, 0, 1, 0, 2, 0, 3, 0))
                    4L
                })
            val bytes = output.readBytes()
            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals(bytes.size.toLong() - 8, u32(bytes, 4))
            assertEquals("sfbk", String(bytes, 8, 4, Charsets.US_ASCII))
            val ifil = bytes.indexOfSubsequence("ifil".toByteArray())
            assertTrue(ifil >= 0)
            assertEquals(2, bytes[ifil + 8].toInt())
            val smpl = bytes.indexOfSubsequence("smpl".toByteArray())
            assertTrue(smpl >= 0)
            assertEquals(100L, u32(bytes, smpl + 4)) // 4 frames + 46 trailing guard frames.
            val shdr = bytes.indexOfSubsequence("shdr".toByteArray())
            assertTrue(shdr >= 0)
            val h = shdr + 8
            assertEquals(0L, u32(bytes, h + 20))
            assertEquals(4L, u32(bytes, h + 24))
            assertEquals(1L, u32(bytes, h + 28))
            assertEquals(3L, u32(bytes, h + 32))
            assertEquals(1, bytes[h + 44].toInt())
            assertEquals(50L, u32(bytes, h + 46 + 20))
            assertTrue(steps.any {
                it.completedItems == 1 && it.totalItems == 1 && it.fraction == 1f
            })
        } finally { input.delete(); output.delete() }
    }

    @Test fun converterCleansPartialOutputAfterDecoderFailure() {
        val input = File.createTempFile("sf3-error", ".sf3")
        val output = File.createTempFile("sf2-error", ".sf2")
        try {
            input.writeBytes(makeSF3())
            assertThrows(IllegalStateException::class.java) {
                Sf3ToSf2Converter.convert(input, output, decoder =
                    VorbisSampleDecoder { _, _, _, _, _ -> error("decoder failed") })
            }
            assertFalse(output.exists())
        } finally { input.delete(); output.delete() }
    }

    private fun ByteArray.indexOfSubsequence(sequence: ByteArray): Int =
        (0..size - sequence.size).firstOrNull { offset ->
            sequence.indices.all { this[offset + it] == sequence[it] }
        } ?: -1
}
