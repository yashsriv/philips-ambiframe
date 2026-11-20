package org.yashsriv.ambiframe.dream

import org.yashsriv.ambiframe.art.ArtSource
import org.yashsriv.ambiframe.art.Artwork
import org.junit.Assert.assertEquals
import org.junit.Test

class AttributionTest {
    private fun work(
        title: String = "Water Lilies",
        artist: String = "Claude Monet",
        date: String = "1906",
        credit: String = "Art Institute of Chicago, Mr. and Mrs. Martin A. Ryerson Collection",
        source: ArtSource = ArtSource.ART_INSTITUTE_OF_CHICAGO,
    ) = Artwork("aic:1", "https://example.test/1.jpg", 1920, 1080, title, artist, date, credit, source)

    @Test
    fun `AIC shows the museum but not the donor credit line`() {
        assertEquals("Water Lilies — Claude Monet, 1906 · Art Institute of Chicago", attributionLine(work()))
    }

    @Test
    fun `Google Arts and Culture shows its attribution as the museum`() {
        val line = attributionLine(
            work(credit = "The Metropolitan Museum of Art", source = ArtSource.GOOGLE_ARTS_AND_CULTURE),
        )
        assertEquals("Water Lilies — Claude Monet, 1906 · The Metropolitan Museum of Art", line)
    }

    @Test
    fun `blank parts and their separators are dropped`() {
        assertEquals("Water Lilies — 1906 · Art Institute of Chicago", attributionLine(work(artist = " ")))
        assertEquals("Water Lilies · Art Institute of Chicago", attributionLine(work(artist = "", date = "")))
        assertEquals("Claude Monet · Art Institute of Chicago", attributionLine(work(title = "", date = "")))
    }

    @Test
    fun `Google work without attribution falls back to the source name`() {
        val line = attributionLine(work(credit = "  ", source = ArtSource.GOOGLE_ARTS_AND_CULTURE))
        assertEquals("Water Lilies — Claude Monet, 1906 · Google Arts & Culture", line)
    }
}
