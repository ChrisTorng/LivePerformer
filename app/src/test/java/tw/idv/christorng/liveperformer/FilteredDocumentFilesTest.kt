package tw.idv.christorng.liveperformer

import org.junit.Assert.*
import org.junit.Test

class FilteredDocumentFilesTest {
    @Test fun onlySoundFontExtensionsAreSelectable() {
        listOf("piano.sf2", "strings.SF3", "bank.zip", "bank.tar.xz")
            .forEach { assertTrue(it, FilteredDocumentFiles.accepts(FilteredFileKind.SOUNDFONT, it)) }
        listOf("music.mp3", "video.mp4", "notes.pdf", "preset.sfz", "font.sf2.txt")
            .forEach { assertFalse(it, FilteredDocumentFiles.accepts(FilteredFileKind.SOUNDFONT, it)) }
    }

    @Test fun onlyProjectExtensionsAreSelectable() {
        listOf("demo.lvp", "session.LVPT")
            .forEach { assertTrue(it, FilteredDocumentFiles.accepts(FilteredFileKind.PROJECT, it)) }
        listOf("demo.json", "piano.sf2", "song.lvp.bak")
            .forEach { assertFalse(it, FilteredDocumentFiles.accepts(FilteredFileKind.PROJECT, it)) }
    }
}
