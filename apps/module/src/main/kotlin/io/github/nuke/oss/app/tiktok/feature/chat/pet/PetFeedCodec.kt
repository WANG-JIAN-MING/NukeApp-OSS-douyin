package io.github.nuke.oss.app.tiktok.feature.chat.pet

import io.github.nuke.oss.app.shared.data.MiniJson
import java.security.MessageDigest

internal enum class PetFeedAvailability { READY, STILL_FED, NO_FREE_FOOD, NO_PET, UNAVAILABLE, FOOD_UNAVAILABLE, PAID_NOT_ALLOWED, PRICE_LIMIT }

// Which food may be given. Without a food ID only a food the service marks free is chosen; paid food needs a fixed
// food ID, an explicit permission and a price limit per feeding.
internal data class PetFeedPolicy(val foodId: String? = null, val allowPaidFood: Boolean = false, val maxPrice: Long = 0) {
    fun validate() {
        require(foodId == null || foodId.matches(Regex("[1-9][0-9]{0,18}")) && foodId.toLongOrNull() != null) { "Food ID must be a positive integer" }
        require(maxPrice >= 0) { "Food price limit must not be negative" }
        require(!allowPaidFood || foodId != null) { "Paid food requires an explicitly selected food ID" }
    }
}

// price is what a feeding costs; a food marked free always costs 0.
internal data class PetFood(val id: String, val name: String, val price: Long = 0, val isFree: Boolean = true)

// One read of a pet's feeding state from the signed-in account's side. nextFeedAtMillis is the end of the account's
// current food, kept after it expired; cycleKey names that food period.
internal data class PetFeedSnapshot(
    val shortId: String,
    val selfSecUid: String,
    val cycleKey: String,
    val availability: PetFeedAvailability,
    val nextFeedAtMillis: Long?,
    val food: PetFood?,
    val detail: String = "",
    val serverTimeMillis: Long? = null,
    val actionFoodId: String? = null,
)

internal data class PetFeedReceipt(val shortId: String, val cycleKey: String, val confirmed: Boolean, val nextFeedAtMillis: Long?, val detail: String = "")

// The private chat a pet belongs to and the signed-in account on both sides of it.
internal data class PetBinding(val account: Long, val shortId: String, val selfSecUid: String)

// The pet service answered with a business error code.
internal class PetApiException(message: String, val businessCode: Int? = null) : IllegalStateException(message)

// Decodes the pet page's feeding responses the way the legacy module's DyPetCodec does (checked there against the
// official pet page). Only the side whose sec_uid is the signed-in account's may authorize feeding; a missing or
// empty action means no current food, as the page itself reads it.
internal object PetFeedCodec {
    const val READ_PATH = "/ttgame/social_pet/feed/get"
    const val FEED_PATH = "/ttgame/social_pet/feed"
    const val NO_PET = 994008
    // Server time must be a plausible millisecond clock: 2000-01-01 to 2100-01-01.
    private val SERVER_TIME = 946_684_800_000L..4_102_444_800_000L

    fun readBody(): Map<String, Any?> = linkedMapOf("is_exquisite_image" to false)

    fun snapshot(binding: PetBinding, root: Map<String, Any?>, policy: PetFeedPolicy): PetFeedSnapshot {
        policy.validate()
        val code = long(root["code"])?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: throw PetApiException("Pet response has no valid business code")
        if (code == NO_PET) return unavailable(binding, "Pet does not exist", PetFeedAvailability.NO_PET)
        if (code != 0) throw PetApiException("Pet service rejected the request ($code)", code)
        val data = MiniJson.obj(root["data"]) ?: return unavailable(binding, "Pet response has no data")
        val foods = catalog(data)
        val info = MiniJson.obj(data["info"]) ?: return unavailable(binding, "Pet response has no feeding state")
        val sides = listOfNotNull(MiniJson.obj(info["left"]), MiniJson.obj(info["right"])).filter { it["sec_uid"] == binding.selfSecUid }
        if (binding.selfSecUid.isBlank() || sides.size != 1) return unavailable(binding, "Own feeding side is unknown")
        val side = sides.single()
        val action = when (val raw = side["action"]) {
            null -> null
            is Map<*, *> -> MiniJson.obj(raw)
            else -> return unavailable(binding, "Own feeding action is invalid")
        }
        val empty = action.isNullOrEmpty() || long(action["food_id"]) == 0L &&
            (!action.containsKey("start_ts") || long(action["start_ts"]) == 0L) && (!action.containsKey("end_ts") || long(action["end_ts"]) == 0L)
        val start = if (empty) null else secondsMillis(action["start_ts"])
        val end = if (empty) null else secondsMillis(action["end_ts"])
        val actionFood = if (empty) null else positiveId(action["food_id"])
        if (!empty && (start == null || end == null || end <= start || actionFood == null)) return unavailable(binding, "Own feeding timestamps are invalid")
        // server_time is in milliseconds, the action's timestamps in seconds.
        val serverTime = long(root["server_time"])?.takeIf { it in SERVER_TIME } ?: return unavailable(binding, "Pet response has no reliable server time")
        if (start != null && start > serverTime) return unavailable(binding, "Own feeding starts in the future")
        val (selected, food) = selectFood(foods, policy)
        val availability = if (end != null && serverTime <= end) PetFeedAvailability.STILL_FED else selected
        return PetFeedSnapshot(binding.shortId, binding.selfSecUid, cycleKey(binding, start, end), availability, end, food,
            serverTimeMillis = serverTime, actionFoodId = actionFood)
    }

    // The foods one read of a friend's pet lists, for the settings screen's food picker. A pet that does not exist or a
    // rejected read is an error, not an empty list, so the picker can say why it has nothing to show.
    fun foods(root: Map<String, Any?>): List<PetFood> {
        val code = long(root["code"])?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: throw PetApiException("Pet response has no valid business code")
        if (code == NO_PET) throw PetApiException("The friend has no pet to feed", code)
        if (code != 0) throw PetApiException("Pet service rejected the request ($code)", code)
        return catalog(MiniJson.obj(root["data"]) ?: throw PetApiException("Pet response has no data"))
    }

    fun checkSuccess(root: Map<String, Any?>) {
        val code = long(root["code"]) ?: throw PetApiException("Pet response has no business code")
        if (code != 0L) throw PetApiException("Pet service rejected feeding ($code)", code.takeIf { it <= Int.MAX_VALUE }?.toInt())
    }

    fun feedBody(food: PetFood, policy: PetFeedPolicy): Map<String, Any?> {
        policy.validate()
        require(food.price >= 0 && (!food.isFree || food.price == 0L)) { "Invalid payable food price" }
        require(policy.foodId == null && food.isFree || policy.foodId == food.id) { "Selected food changed" }
        require(food.isFree || policy.allowPaidFood && food.price <= policy.maxPrice) { "Paid food is not authorized at this price" }
        val id = food.id.toLong().also { require(it > 0) }
        return linkedMapOf(
            "food_id" to id, "food_price" to food.price, "same_food_id" to "", "is_post_free" to false,
            "skip_post_free_check" to false, "is_exquisite_image" to false,
        )
    }

    // A food ID listed twice makes every quote of it ambiguous, so neither copy is offered.
    private fun catalog(data: Map<String, Any?>): List<PetFood> {
        val items = MiniJson.array(MiniJson.obj(data["food_info"])?.get("list")).orEmpty().mapNotNull { MiniJson.obj(it) }
        val counts = items.mapNotNull { positiveId(it["food_id"]) }.groupingBy { it }.eachCount()
        return items.mapNotNull { item ->
            val id = positiveId(item["food_id"]) ?: return@mapNotNull null
            if (counts[id] != 1) return@mapNotNull null
            val isFree = item["is_free"] as? Boolean ?: return@mapNotNull null
            if (item.containsKey("is_duoshan_exclusive") && item["is_duoshan_exclusive"] != false) return@mapNotNull null
            val price = long(item["price"]) ?: return@mapNotNull null
            val cfg = (item["cfg"] as? String)?.let { text ->
                try { MiniJson.obj(MiniJson.parse(text)) } catch (_: IllegalArgumentException) { null }
            }
            PetFood(id, (cfg?.get("foodName") as? String)?.takeIf { it.isNotBlank() } ?: id, if (isFree) 0 else price, isFree)
        }
    }

    private fun selectFood(foods: List<PetFood>, policy: PetFeedPolicy): Pair<PetFeedAvailability, PetFood?> {
        if (policy.foodId == null) {
            val free = foods.firstOrNull { it.isFree }
            return (if (free == null) PetFeedAvailability.NO_FREE_FOOD else PetFeedAvailability.READY) to free
        }
        val food = foods.singleOrNull { it.id == policy.foodId } ?: return PetFeedAvailability.FOOD_UNAVAILABLE to null
        if (!food.isFree && !policy.allowPaidFood) return PetFeedAvailability.PAID_NOT_ALLOWED to null
        if (!food.isFree && food.price > policy.maxPrice) return PetFeedAvailability.PRICE_LIMIT to null
        return PetFeedAvailability.READY to food
    }

    // The food period from the account's side: the chat, the account's secure UID and the action's start and end, hashed
    // with length prefixes so no two different periods share a key.
    fun cycleKey(binding: PetBinding, start: Long?, end: Long?): String {
        val parts = listOf(binding.shortId, binding.selfSecUid, start?.toString() ?: "none", end?.toString() ?: "none")
        val bytes = parts.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
        return "v1:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun unavailable(binding: PetBinding, detail: String, availability: PetFeedAvailability = PetFeedAvailability.UNAVAILABLE) =
        PetFeedSnapshot(binding.shortId, binding.selfSecUid, "", availability, null, null, detail)

    // A non-negative whole number, written as a JSON number or as a string of digits; anything else is absent.
    private fun long(value: Any?): Long? = when (value) {
        is Long -> value.takeIf { it >= 0 }
        is String -> value.takeIf { it.matches(DIGITS) }?.toLongOrNull()
        else -> null
    }

    private fun secondsMillis(value: Any?): Long? = long(value)?.takeIf { it > 0 && it <= Long.MAX_VALUE / 1000 }?.times(1000)

    private fun positiveId(value: Any?): String? = long(value)?.takeIf { it > 0 }?.toString()

    private val DIGITS = Regex("[0-9]+")
}
