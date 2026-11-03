package com.capstone.stethoscope.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the two things about [AuscultationSite] that break silently.
 *
 * The keys are written into the database, so renaming one turns every stored recording's
 * site into null — no crash, no error, just a report that quietly stops saying where the
 * bell was. And the coordinates decide both where the marker draws and where the tap
 * lands, so a value outside the diagram is a site nobody can select.
 */
class AuscultationSiteTest {

    @Test
    fun `keys round-trip through storage`() {
        for (site in AuscultationSite.entries) {
            assertEquals(site, AuscultationSite.fromKey(site.key))
        }
    }

    @Test
    fun `keys are the exact strings already written to the database`() {
        // Spelled out rather than derived from the enum, so a rename fails here instead
        // of silently agreeing with itself.
        assertEquals(
            listOf("AORTIC", "PULMONIC", "ERBS", "TRICUSPID", "MITRAL"),
            AuscultationSite.entries.map { it.key },
        )
    }

    @Test
    fun `an unknown or missing key reads as no site`() {
        // A row written by a future version must degrade to "not stated" rather than
        // throwing on every list query.
        assertNull(AuscultationSite.fromKey(null))
        assertNull(AuscultationSite.fromKey(""))
        assertNull(AuscultationSite.fromKey("SOMETHING_NEW"))
    }

    @Test
    fun `markers sit inside the diagram`() {
        for (site in AuscultationSite.entries) {
            assertTrue("${site.key} x=${site.x}", site.x in 0.05f..0.95f)
            assertTrue("${site.key} y=${site.y}", site.y in 0.05f..0.95f)
        }
    }

    @Test
    fun `the diagram faces the viewer`() {
        // Anatomical convention: the patient's right is on the left of the screen. Aortic
        // is the patient's right, the four left-heart positions are the patient's left.
        // Mirroring this is an easy mistake and would put every site on the wrong side of
        // the chest while still looking plausible.
        assertTrue("Aortic should draw left of the sternum", AuscultationSite.AORTIC.x < 0.5f)
        for (site in listOf(
            AuscultationSite.PULMONIC,
            AuscultationSite.ERBS,
            AuscultationSite.TRICUSPID,
            AuscultationSite.MITRAL,
        )) {
            assertTrue("${site.key} should draw right of the sternum", site.x > 0.5f)
        }
    }

    @Test
    fun `sites run down the chest in anatomical order`() {
        // 2nd ICS, 2nd ICS, 3rd, 4th, 5th — so y increases (apart from the two 2nd-ICS
        // positions, which share a height). A site out of order means the enum and the
        // anatomy have drifted apart.
        assertEquals(AuscultationSite.AORTIC.y, AuscultationSite.PULMONIC.y, 0.001f)
        val descending = listOf(
            AuscultationSite.PULMONIC,
            AuscultationSite.ERBS,
            AuscultationSite.TRICUSPID,
            AuscultationSite.MITRAL,
        )
        for ((above, below) in descending.zipWithNext()) {
            assertTrue("${above.key} should sit above ${below.key}", above.y < below.y)
        }
    }
}
