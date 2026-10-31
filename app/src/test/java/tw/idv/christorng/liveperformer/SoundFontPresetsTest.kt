package tw.idv.christorng.liveperformer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SoundFontPresetsTest {
    private fun tag(s: String) = s.toByteArray(Charsets.US_ASCII)
    private fun u32(n: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()
    private fun chunk(name: String, payload: ByteArray) =
        tag(name) + u32(payload.size) + payload + if (payload.size % 2 == 1) byteArrayOf(0) else byteArrayOf()
    private fun preset(name: String, number: Int, bank: Int): ByteArray {
        val b = ByteBuffer.allocate(38).order(ByteOrder.LITTLE_ENDIAN)
        b.put(name.toByteArray(Charsets.US_ASCII).copyOf(20))
        b.putShort(number.toShort())
        b.putShort(bank.toShort())
        b.putShort(0)
        b.putInt(0)
        b.putInt(0)
        b.putInt(0)
        return b.array()
    }
    @Test fun readsNameAndBankedPresetsWithoutSampleData() {
        val phdr = preset("Grand Piano", 0, 0) +
            preset("Drum Kit", 0, 128) + preset("EOP", 0, 0)
        val info = chunk("LIST", tag("INFO") + chunk("INAM", tag("Sample Piano") + byteArrayOf(0)))
        val pdta = chunk("LIST", tag("pdta") + chunk("phdr", phdr))
        val body = tag("sfbk") + info + pdta
        val file = File.createTempFile("soundfont-test", ".sf2")
        try {
            file.writeBytes(tag("RIFF") + u32(body.size) + body)
            val parsed = SoundFontPresets.read(file)
            assertEquals("Sample Piano", parsed.title)
            assertEquals(2, parsed.presets.size)
            assertEquals(0, parsed.presets[0].bank)
            assertEquals("Grand Piano", parsed.presets[0].name)
            assertEquals(128, parsed.presets[1].bank)
            assertTrue(parsed.presets[1].displayName.contains("Bank 128"))
        } finally { file.delete() }
    }
}
