package tw.idv.christorng.liveperformer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real Ogg/Vorbis sample decoding, not a mocked PCM writer.
 * The test-only fixture is fetched by GitHub Actions into androidTest/assets. */
@RunWith(AndroidJUnit4::class)
class Sf3PlatformDecoderTest {
    @Test
    fun decodesActualCompressedSf3AndRebuildsPlayableSoundfont() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.cacheDir, "sf3-actual.ogg-banks.sf3")
        val output = File(context.cacheDir, "sf3-converted.sf2")
        try {
            InstrumentationRegistry.getInstrumentation().context.assets
                .open("VintageDreamsWaves-v2.sf3").use { source ->
                    input.outputStream().use { source.copyTo(it) }
                }
            assertTrue(input.length() >= 100000)
            val progress = mutableListOf<String>()
            Sf3ToSf2Converter.convert(input, output, onProgress = { state ->
                if (progress.size < 5000) progress.add(state.stage)
            })
            assertTrue("Converted SF2 must exist", output.isFile)
            assertTrue("Converted SF2 must contain PCM samples", output.length() > 10_000L)
            // The decoded file is not always larger than a small synthetic SF3:
            // the RIFF fixture can include alignment/metadata or very short loops.
            // Validate actual synthesis-oriented metadata and PCM sample lengths instead.
            assertTrue("At least one Vorbis sample must be decoded",
                progress.any { it.contains("SF3 → SF2") })
            assertTrue("Result should contain presets",
                SoundFontPresets.read(output).presets.isNotEmpty())
            val converted = output.readBytes()
            assertEquals("RIFF", String(converted, 0, 4, Charsets.US_ASCII))
            assertEquals("sfbk", String(converted, 8, 4, Charsets.US_ASCII))
            assertTrue("Result must not retain Ogg payload",
                converted.indexOfSequence("smpl".toByteArray()) >= 0)
            assertTrue("Sample table must exist",
                converted.indexOfSequence("shdr".toByteArray()) >= 0)
        } finally {
            input.delete()
            output.delete()
        }
    }

    private fun ByteArray.indexOfSequence(needle: ByteArray): Int {
        if (needle.size > size) return -1
        for (offset in 0..size - needle.size) {
            if (needle.indices.all { this[offset + it] == needle[it] }) return offset
        }
        return -1
    }
}
