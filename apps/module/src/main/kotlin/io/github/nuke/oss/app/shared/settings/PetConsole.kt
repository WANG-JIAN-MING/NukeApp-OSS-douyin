package io.github.nuke.oss.app.shared.settings

// One food a friend's pet offers: what the settings screen shows to pick from, so no food ID is typed. price is what a
// feeding costs, and a food marked free costs 0.
internal data class PetFoodChoice(val id: String, val name: String, val price: Long, val free: Boolean)

// What the pet feeding feature offers the settings screen. Reading changes nothing: no feeding, no setting.
internal fun interface PetConsole {
    // The foods in the pet the account shares with friendUid. Blocking: callers run it off the main thread. Throws
    // PetConsoleUnavailable with the reason when the host cannot answer, such as no signed-in account, the pet page
    // never opened since the host started, or a friend without a pet.
    fun foods(friendUid: String): List<PetFoodChoice>
}

internal class PetConsoleUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)
