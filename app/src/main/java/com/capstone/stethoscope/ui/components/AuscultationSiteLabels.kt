package com.capstone.stethoscope.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.capstone.stethoscope.R
import com.capstone.stethoscope.data.model.AuscultationSite

/**
 * Localized names for the auscultation sites.
 *
 * Kept out of [AuscultationSite] deliberately: that enum is a data-layer type, its [AuscultationSite.key]
 * values are written to the database, and giving it a string resource would tie the
 * stored schema to the resource table. These are also what the chest diagram labels
 * itself with, so the picker and the report always read the same.
 */
@Composable
fun AuscultationSite.label(): String = stringResource(
    when (this) {
        AuscultationSite.AORTIC -> R.string.site_aortic
        AuscultationSite.PULMONIC -> R.string.site_pulmonic
        AuscultationSite.ERBS -> R.string.site_erbs
        AuscultationSite.TRICUSPID -> R.string.site_tricuspid
        AuscultationSite.MITRAL -> R.string.site_mitral
    }
)

/** The anatomical landmark, for the line under the picker. */
@Composable
fun AuscultationSite.detail(): String = stringResource(
    when (this) {
        AuscultationSite.AORTIC -> R.string.site_aortic_detail
        AuscultationSite.PULMONIC -> R.string.site_pulmonic_detail
        AuscultationSite.ERBS -> R.string.site_erbs_detail
        AuscultationSite.TRICUSPID -> R.string.site_tricuspid_detail
        AuscultationSite.MITRAL -> R.string.site_mitral_detail
    }
)

/**
 * Name for use outside composition — the PDF report and share text.
 *
 * English, matching the rest of [com.capstone.stethoscope.export.ReportExporter]: the
 * report goes to a doctor over WhatsApp and the app has no way to know what they read.
 */
fun AuscultationSite.reportLabel(): String = when (this) {
    AuscultationSite.AORTIC -> "Aortic (2nd ICS, right sternal border)"
    AuscultationSite.PULMONIC -> "Pulmonic (2nd ICS, left sternal border)"
    AuscultationSite.ERBS -> "Erb's point (3rd ICS, left sternal border)"
    AuscultationSite.TRICUSPID -> "Tricuspid (4th ICS, lower left sternal border)"
    AuscultationSite.MITRAL -> "Mitral / apex (5th ICS, midclavicular line)"
}
