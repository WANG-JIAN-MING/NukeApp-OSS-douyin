package io.github.nuke.oss.ui.hostsettings

import io.github.nuke.oss.app.shared.settings.PetFoodChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The rules behind the auto pet feeding dialog that need no screen: what blocks saving, which intervals are offered and
// what a round can cost at most.
class AutoPetFeedDialogTest {
    private val free = PetFoodChoice("11", "Bun", 0, true)
    private val paid = PetFoodChoice("12", "Cake", 50, false)

    @Test fun `automatic free food with a valid limit saves`() {
        assertNull(petFeedProblem(3, null, false, 0, null))
        assertNull(petFeedProblem(0, null, false, 0, null))
    }

    @Test fun `more than a hundred friends or an unreadable limit block saving`() {
        assertEquals(PetFeedProblem.TooManyFriends, petFeedProblem(101, null, false, 0, null))
        assertEquals(PetFeedProblem.PriceInvalid, petFeedProblem(1, "12", true, null, paid))
    }

    @Test fun `paid food is never allowed without a food`() {
        assertEquals(PetFeedProblem.AutomaticNotPaid, petFeedProblem(1, null, true, 50, null))
    }

    @Test fun `a known paid food needs the permission and a limit that covers its price`() {
        assertEquals(PetFeedProblem.PaidNotAllowed, petFeedProblem(1, "12", false, 0, paid))
        val exceeded = petFeedProblem(1, "12", true, 49, paid)
        assertTrue(exceeded is PetFeedProblem.PriceExceeded && exceeded.price == 50L)
        assertNull(petFeedProblem(1, "12", true, 50, paid))
        assertNull(petFeedProblem(1, "11", false, 0, free))
    }

    @Test fun `a saved food that has not been read yet is judged when the feature runs`() {
        assertNull(petFeedProblem(1, "12", false, 0, null))
    }

    @Test fun `the intervals offered include a saved one that is not among them`() {
        assertEquals(listOf(5, 15, 30, 60), petFeedIntervals(15))
        assertEquals(listOf(5, 15, 20, 30, 60), petFeedIntervals(20))
    }

    @Test fun `a round costs at most the limit for every friend`() {
        assertEquals("150", petFeedRoundMaximum(50, 3))
        assertEquals("0", petFeedRoundMaximum(0, 100))
        assertEquals("100000000", petFeedRoundMaximum(1_000_000, 100))
    }
}
