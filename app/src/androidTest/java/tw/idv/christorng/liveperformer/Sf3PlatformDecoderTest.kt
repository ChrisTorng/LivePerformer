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
            assertTrue("Decoded PCM expands source", output.length() > input.length())
            assertTrue("At least one Vorbis sample must be decoded",
                progress.any { it.contains("SF3 → SF2") })
            assertTrue("Result should contain presets",
                SoundFontPresets.read(output).presets.isNotEmpty())
        } finally {
            input.delete()
            output.delete()
        }
    }
}
