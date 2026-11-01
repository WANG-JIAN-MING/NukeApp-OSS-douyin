package io.github.nuke.oss.app.tiktok.feature.chat.pet

import io.github.nuke.oss.app.tiktok.feature.chat.claims.ClaimTransportException

// What the pet requests need from the host. post calls beforeDispatch once, right before the host sends the request;
// an exception from it means nothing was sent. A ClaimNotDispatchedException also means nothing was sent; any other
// failure after beforeDispatch leaves the outcome of a write unknown.
internal interface PetFeedTransport {
    fun accountId(): Long
    fun resolve(uid: String, account: Long): PetBinding
    fun post(binding: PetBinding, path: String, body: Map<String, Any?>, beforeDispatch: () -> Unit): Map<String, Any?>
}

// One friend's pet, read and fed the way the legacy module's DyPetExecutor does: reads may be repeated once after a
// network failure, a feeding is sent only when a fresh read still shows the same ready period, and the account is
// checked before and after every request. A feeding counts as confirmed only when the service shows a new food of
// ours that ends later than the one before; an acknowledgement alone is not proof.
internal class PetFeedExecutor(private val transport: PetFeedTransport) {
    fun inspect(uid: String, account: Long, policy: PetFeedPolicy): PetFeedSnapshot {
        policy.validate()
        return read(binding(uid, account), policy)
    }

    fun feed(uid: String, account: Long, expectedCycle: String, policy: PetFeedPolicy, beforeSubmit: (PetFood) -> Unit): PetFeedReceipt {
        policy.validate()
        require(expectedCycle.isNotBlank()) { "A validated feeding cycle is required" }
        val binding = binding(uid, account)
        val before = read(binding, policy)
        check(before.availability == PetFeedAvailability.READY && before.cycleKey == expectedCycle) { "Feeding state changed; refresh before submitting" }
        val food = checkNotNull(before.food)
        val response = transport.post(binding, PetFeedCodec.FEED_PATH, PetFeedCodec.feedBody(food, policy)) {
            checkAccount(binding)
            beforeSubmit(food)
            // Saving the intent may take a moment; an account switch in that time still stops the request.
            checkAccount(binding)
        }
        checkAccount(binding)
        PetFeedCodec.checkSuccess(response)
        val direct = PetFeedCodec.snapshot(binding, response, policy)
        if (confirms(before, direct, food)) return receipt(before, direct, true)
        val refreshed = try {
            read(binding, policy)
        } catch (_: ClaimTransportException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
        return receipt(before, refreshed, refreshed != null && confirms(before, refreshed, food))
    }

    // The foods the friend's pet offers the account, read without feeding anything.
    fun catalog(uid: String, account: Long): List<PetFood> = PetFeedCodec.foods(readRaw(binding(uid, account)))

    private fun read(binding: PetBinding, policy: PetFeedPolicy): PetFeedSnapshot = PetFeedCodec.snapshot(binding, readRaw(binding), policy)

    // The read is a POST but changes nothing, so one network failure may be retried.
    private fun readRaw(binding: PetBinding): Map<String, Any?> {
        checkAccount(binding)
        val response = try {
            transport.post(binding, PetFeedCodec.READ_PATH, PetFeedCodec.readBody()) { checkAccount(binding) }
        } catch (_: ClaimTransportException) {
            checkAccount(binding)
            transport.post(binding, PetFeedCodec.READ_PATH, PetFeedCodec.readBody()) { checkAccount(binding) }
        }
        checkAccount(binding)
        return response
    }

    private fun binding(uid: String, account: Long): PetBinding {
        require(account > 0 && uid.toLongOrNull()?.let { it > 0 && it != account } == true) { "A private friend UID and a signed-in account are required" }
        check(transport.accountId() == account) { "Account changed before the pet lookup" }
        return transport.resolve(uid, account).also {
            check(it.account == account && it.selfSecUid.isNotBlank() && (it.shortId.toLongOrNull() ?: 0) > 0) { "Invalid pet account or conversation" }
            checkAccount(it)
        }
    }

    private fun checkAccount(binding: PetBinding) = check(transport.accountId() == binding.account) { "Account changed during the pet request" }

    private fun confirms(before: PetFeedSnapshot, after: PetFeedSnapshot, food: PetFood) =
        after.availability == PetFeedAvailability.STILL_FED && after.cycleKey.isNotBlank() && after.cycleKey != before.cycleKey &&
            after.actionFoodId == food.id && (after.nextFeedAtMillis ?: 0) > (before.nextFeedAtMillis ?: 0)

    private fun receipt(before: PetFeedSnapshot, after: PetFeedSnapshot?, confirmed: Boolean) = PetFeedReceipt(
        before.shortId, before.cycleKey, confirmed, after?.nextFeedAtMillis, if (confirmed) "" else "Acknowledged; own feeding state was not confirmed",
    )
}
