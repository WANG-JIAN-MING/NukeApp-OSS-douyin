package io.github.nuke.oss.ui.hostsettings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostFeatureLabelsTest {
    @Test fun `every labelled feature has a specific category`() {
        val unsorted = featureLabels.filterValues { it.category == FeatureCategory.OTHER }.keys
        assertTrue("Features left under Other: $unsorted", unsorted.isEmpty())
    }

    @Test fun `every specific category has features`() {
        val used = featureLabels.values.map { it.category }.toSet()
        assertEquals(FeatureCategory.entries.toSet() - FeatureCategory.OTHER, used)
    }

    @Test fun `category keys are unique`() {
        assertEquals(FeatureCategory.entries.size, FeatureCategory.entries.map { it.key }.toSet().size)
    }

    @Test fun `an unknown ID falls under Other`() {
        assertEquals(FeatureCategory.OTHER, categoryOf("NotAFeature"))
    }
}
