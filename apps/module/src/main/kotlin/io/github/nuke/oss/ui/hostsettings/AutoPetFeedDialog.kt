package io.github.nuke.oss.ui.hostsettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.settings.HostContactList
import io.github.nuke.oss.app.shared.settings.HostContactsUnavailable
import io.github.nuke.oss.app.shared.settings.HostFeature
import io.github.nuke.oss.app.shared.settings.HostFriend
import io.github.nuke.oss.app.shared.settings.PetConsole
import io.github.nuke.oss.app.shared.settings.PetConsoleUnavailable
import io.github.nuke.oss.app.shared.settings.PetFoodChoice
import io.github.nuke.oss.designsystem.NukeCheckbox
import io.github.nuke.oss.designsystem.NukeDialog
import io.github.nuke.oss.designsystem.NukeDialogAccentButton
import io.github.nuke.oss.designsystem.NukeDialogTextButton
import io.github.nuke.oss.designsystem.NukeNavRow
import io.github.nuke.oss.designsystem.NukeSelectRow
import io.github.nuke.oss.designsystem.NukeSettingGroup
import io.github.nuke.oss.designsystem.NukeSettingRow
import io.github.nuke.oss.designsystem.NukeSwitch
import io.github.nuke.oss.designsystem.NukeTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val AUTO_PET_FEED = "AutoPetFeed"

// Auto pet feeding's settings, laid out like NukeApp's: the friends whose pets are fed, how often they are checked and
// which food is given, picked from the foods a friend's pet really offers instead of typed as an ID. It edits the
// feature's options on a draft and saves them together; the running feature reads them when the host starts. The option
// keys are the feature's own (AutoPetFeed in the TikTok host), named here as strings so this screen links no host class.
@Composable
internal fun AutoPetFeedDialog(
    feature: HostFeature,
    title: String,
    pets: PetConsole?,
    onSave: (Map<String, Any>) -> Unit,
    onDismiss: () -> Unit,
    loadContacts: suspend () -> HostContactList,
) {
    val initial = remember(feature.id) { feature.options.associate { it.option.key to it.saved } }
    var draft by remember(feature.id) { mutableStateOf(initial) }
    var page by remember(feature.id) { mutableStateOf(PetPage.MAIN) }
    var friends by remember { mutableStateOf<List<HostFriend>?>(null) }
    val avatars = remember { AvatarMemory() }
    var priceText by remember(feature.id) { mutableStateOf((initial.getValue(MAX_PRICE) as Long).toString()) }
    // The foods seen so far, by ID, so the chosen one keeps its name and price after the list is left.
    var known by remember { mutableStateOf(emptyMap<String, PetFoodChoice>()) }
    LaunchedEffect(Unit) {
        // Names and avatars only decorate the dialog; without them it shows user IDs.
        friends = try {
            loadContacts().friends
        } catch (_: HostContactsUnavailable) {
            emptyList()
        }
    }
    fun set(key: String, value: Any) { draft = draft + (key to value) }
    val selected = (draft.getValue(FRIENDS) as List<*>).filterIsInstance<String>()
    val interval = (draft.getValue(INTERVAL) as Long).toInt()
    val foodId = (draft.getValue(FOOD_ID) as String).ifBlank { null }
    val allowPaid = draft.getValue(ALLOW_PAID) as Boolean
    val maxPrice = priceText.toLongOrNull()
    val food = foodId?.let { known[it] }
    val problem = petFeedProblem(selected.size, foodId, allowPaid, maxPrice, food)

    fun chooseAutomatic() {
        set(FOOD_ID, "")
        set(ALLOW_PAID, false)
        set(MAX_PRICE, 0L)
        priceText = "0"
    }

    fun choose(choice: PetFoodChoice) {
        set(FOOD_ID, choice.id)
        if (choice.free) {
            set(ALLOW_PAID, false)
            set(MAX_PRICE, 0L)
            priceText = "0"
        }
    }

    when (page) {
        PetPage.FRIENDS -> ConversationPickerDialog(
            title = stringResource(R.string.host_pet_friends_title),
            initial = selected.toSet(),
            load = loadContacts,
            onDismiss = { page = PetPage.MAIN },
            friendsOnly = true,
        ) {
            set(FRIENDS, it.sorted())
            page = PetPage.MAIN
        }
        PetPage.FOOD -> FoodPage(
            pets = pets,
            selected = selected,
            friends = friends.orEmpty(),
            foodId = foodId,
            food = food,
            allowPaid = allowPaid,
            priceText = priceText,
            problem = problem,
            onAutomatic = ::chooseAutomatic,
            onChoose = ::choose,
            onLoaded = { foods -> known = known + foods.associateBy { it.id } },
            onAllowPaid = { allowed ->
                set(ALLOW_PAID, allowed)
                if (!allowed) {
                    set(MAX_PRICE, 0L)
                    priceText = "0"
                } else if (food != null && !food.free && (maxPrice ?: 0L) < food.price) {
                    // Allowing paid food starts at the price the chosen food has now, so nothing needs typing.
                    set(MAX_PRICE, food.price)
                    priceText = food.price.toString()
                }
            },
            onPriceText = { text ->
                if (text.length <= MAX_PRICE_DIGITS && text.all { it in '0'..'9' }) {
                    // The option is capped, so a larger number is shown as the cap rather than saved as something else.
                    val value = text.toLongOrNull()?.coerceAtMost(MAX_PRICE_LIMIT)
                    priceText = value?.toString() ?: text
                    value?.let { set(MAX_PRICE, it) }
                }
            },
            onBack = { page = PetPage.MAIN },
        )
        PetPage.MAIN -> NukeDialog(
            visible = true,
            onDismissRequest = onDismiss,
            title = title,
            modifier = Modifier.padding(horizontal = 20.dp).widthIn(max = 560.dp),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            actions = {
                NukeDialogTextButton(stringResource(R.string.host_streak_close), onDismiss, Modifier.weight(1f))
                NukeDialogAccentButton(stringResource(R.string.home_save), {
                    onSave(draft.filter { (key, value) -> initial[key] != value })
                    onDismiss()
                }, Modifier.weight(1f), enabled = draft != initial && problem == null)
            },
        ) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OptionsBodyText(stringResource(R.string.host_pet_notice))
                if (!feature.configured) OptionsBodyText(stringResource(R.string.host_pet_disabled), warning = true)
                NukeSettingGroup {
                    NukeNavRow(title = stringResource(R.string.host_pet_selected, selected.size), onClick = { page = PetPage.FRIENDS })
                    if (selected.isNotEmpty()) {
                        val byUid = friends.orEmpty().associateBy { it.uid }
                        Row(
                            Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            selected.take(PREVIEW_AVATARS).forEach { uid ->
                                val friend = byUid[uid]
                                ContactAvatar(friend?.name ?: uid, friend?.avatarUrl, avatars, size = 32.dp)
                            }
                            if (selected.size > PREVIEW_AVATARS) OptionsBodyText("+${selected.size - PREVIEW_AVATARS}")
                        }
                    }
                    NukeNavRow(
                        title = stringResource(R.string.host_pet_food),
                        subtitle = foodSummary(foodId, food),
                        onClick = { page = PetPage.FOOD },
                    )
                    val intervals = petFeedIntervals(interval)
                    val labels = intervals.associateWith { stringResource(R.string.host_pet_minutes, it) }
                    NukeSelectRow(
                        title = stringResource(R.string.host_pet_interval),
                        options = intervals,
                        selectedOption = interval,
                        onOptionSelected = { set(INTERVAL, it.toLong()) },
                        optionToString = { labels.getValue(it) },
                    )
                }
                if (allowPaid) NukeSettingGroup {
                    NukeSettingRow(
                        title = stringResource(R.string.host_pet_price_cap_summary, maxPrice ?: 0L),
                        subtitle = maxPrice?.let { stringResource(R.string.host_pet_round_maximum, selected.size, petFeedRoundMaximum(it, selected.size)) },
                    )
                }
                problem?.let { OptionsBodyText(petFeedProblemText(it), warning = true) }
                OptionsBodyText(stringResource(R.string.host_options_save_hint))
            }
        }
    }
}

private enum class PetPage { MAIN, FRIENDS, FOOD }

// The food page: the automatic choice, the paid-food permission and its limit, and the foods read from one friend's pet.
@Composable
private fun FoodPage(
    pets: PetConsole?,
    selected: List<String>,
    friends: List<HostFriend>,
    foodId: String?,
    food: PetFoodChoice?,
    allowPaid: Boolean,
    priceText: String,
    problem: PetFeedProblem?,
    onAutomatic: () -> Unit,
    onChoose: (PetFoodChoice) -> Unit,
    onLoaded: (List<PetFoodChoice>) -> Unit,
    onAllowPaid: (Boolean) -> Unit,
    onPriceText: (String) -> Unit,
    onBack: () -> Unit,
) {
    var source by remember { mutableStateOf(selected.firstOrNull().orEmpty()) }
    var refresh by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<FoodsState>(FoodsState.Idle) }
    val byUid = remember(friends) { friends.associateBy { it.uid } }
    LaunchedEffect(source, refresh, pets) {
        if (source.isBlank() || pets == null) {
            state = FoodsState.Idle
            return@LaunchedEffect
        }
        state = FoodsState.Loading
        state = try {
            val foods = withContext(Dispatchers.IO) { pets.foods(source) }
            onLoaded(foods)
            FoodsState.Loaded(foods)
        } catch (failure: PetConsoleUnavailable) {
            FoodsState.Failed(failure.message ?: failure.javaClass.simpleName)
        }
    }
    NukeDialog(
        visible = true,
        onDismissRequest = onBack,
        title = stringResource(R.string.host_pet_food_title),
        modifier = Modifier.padding(horizontal = 20.dp).widthIn(max = 560.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        actions = { NukeDialogAccentButton(stringResource(R.string.host_streak_back), onBack, Modifier.weight(1f)) },
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NukeSettingGroup {
                NukeSettingRow(
                    title = stringResource(R.string.host_pet_food_auto),
                    subtitle = stringResource(R.string.host_pet_food_auto_hint),
                    onClick = onAutomatic,
                    trailingContent = { NukeCheckbox(foodId == null, { if (it) onAutomatic() }) },
                )
                NukeSettingRow(
                    title = stringResource(R.string.host_pet_allow_paid),
                    subtitle = stringResource(R.string.host_pet_allow_paid_hint),
                    trailingContent = { NukeSwitch(allowPaid, onAllowPaid, enabled = foodId != null) },
                )
            }
            if (foodId != null && allowPaid) {
                NukeSettingGroup(title = stringResource(R.string.host_pet_price_cap)) {
                    NukeTextField(
                        value = priceText,
                        onValueChange = onPriceText,
                        modifier = Modifier.padding(12.dp),
                        placeholder = "0",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OptionsBodyText(stringResource(R.string.host_pet_price_cap_hint), Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp))
                }
            }
            problem?.let { OptionsBodyText(petFeedProblemText(it), warning = true) }
            when {
                pets == null -> OptionsBodyText(stringResource(R.string.host_pet_no_console))
                selected.isEmpty() -> OptionsBodyText(stringResource(R.string.host_pet_need_friend))
                else -> {
                    NukeSettingGroup {
                        if (source.isNotBlank()) {
                            NukeSelectRow(
                                title = stringResource(R.string.host_pet_source),
                                options = selected,
                                selectedOption = source,
                                onOptionSelected = { source = it },
                                optionToString = { uid -> byUid[uid]?.name ?: uid },
                            )
                        }
                        NukeNavRow(
                            title = stringResource(if (state is FoodsState.Loading) R.string.host_pet_loading else R.string.host_pet_refresh),
                            enabled = state !is FoodsState.Loading,
                            onClick = { refresh++ },
                        )
                    }
                    when (val shown = state) {
                        is FoodsState.Failed -> OptionsBodyText(stringResource(R.string.host_pet_load_failed, shown.reason), warning = true)
                        is FoodsState.Loaded -> {
                            if (shown.foods.isEmpty()) OptionsBodyText(stringResource(R.string.host_pet_empty))
                            else if (foodId != null && shown.foods.none { it.id == foodId }) OptionsBodyText(stringResource(R.string.host_pet_missing), warning = true)
                            shown.foods.forEach { choice ->
                                NukeSettingGroup {
                                    NukeSettingRow(
                                        title = choice.name,
                                        subtitle = foodPrice(choice),
                                        onClick = { onChoose(choice) },
                                        trailingContent = { NukeCheckbox(choice.id == foodId, { if (it) onChoose(choice) }) },
                                    )
                                }
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
}

private sealed interface FoodsState {
    data object Idle : FoodsState
    data object Loading : FoodsState
    class Loaded(val foods: List<PetFoodChoice>) : FoodsState
    class Failed(val reason: String) : FoodsState
}

@Composable
private fun foodSummary(foodId: String?, food: PetFoodChoice?): String = when {
    foodId == null -> stringResource(R.string.host_pet_food_auto)
    food != null -> "${food.name} · ${foodPrice(food)}"
    else -> stringResource(R.string.host_pet_food_saved, foodId)
}

@Composable
private fun foodPrice(food: PetFoodChoice): String =
    if (food.free) stringResource(R.string.host_pet_free) else stringResource(R.string.host_pet_price, food.price)

@Composable
private fun petFeedProblemText(problem: PetFeedProblem): String = when (problem) {
    PetFeedProblem.TooManyFriends -> stringResource(R.string.host_pet_problem_limit)
    PetFeedProblem.PriceInvalid -> stringResource(R.string.host_pet_problem_invalid_price)
    PetFeedProblem.AutomaticNotPaid -> stringResource(R.string.host_pet_food_auto_hint)
    PetFeedProblem.PaidNotAllowed -> stringResource(R.string.host_pet_problem_paid)
    is PetFeedProblem.PriceExceeded -> stringResource(R.string.host_pet_problem_price, problem.price)
}

// What stops the settings from being saved. A food that is not known yet (read from no list in this session) is judged
// when the feature runs, so only a known food can raise the paid and price problems.
internal sealed interface PetFeedProblem {
    data object TooManyFriends : PetFeedProblem
    data object PriceInvalid : PetFeedProblem
    data object AutomaticNotPaid : PetFeedProblem
    data object PaidNotAllowed : PetFeedProblem
    class PriceExceeded(val price: Long) : PetFeedProblem
}

internal fun petFeedProblem(friends: Int, foodId: String?, allowPaid: Boolean, maxPrice: Long?, food: PetFoodChoice?): PetFeedProblem? = when {
    friends > MAX_FRIENDS -> PetFeedProblem.TooManyFriends
    maxPrice == null -> PetFeedProblem.PriceInvalid
    foodId == null && allowPaid -> PetFeedProblem.AutomaticNotPaid
    food != null && !food.free && !allowPaid -> PetFeedProblem.PaidNotAllowed
    food != null && !food.free && maxPrice < food.price -> PetFeedProblem.PriceExceeded(food.price)
    else -> null
}

// The check intervals offered, plus a saved one that is not among them so the screen never hides what is stored.
internal fun petFeedIntervals(current: Int): List<Int> = (INTERVALS + current).distinct().sorted()

// What one round can cost at most: the price limit for every chosen friend.
internal fun petFeedRoundMaximum(price: Long, friends: Int): String =
    java.math.BigInteger.valueOf(price).multiply(java.math.BigInteger.valueOf(friends.toLong())).toString()

private const val FRIENDS = "friends"
private const val INTERVAL = "interval-minutes"
private const val FOOD_ID = "food-id"
private const val ALLOW_PAID = "allow-paid"
private const val MAX_PRICE = "max-price"
private const val MAX_PRICE_DIGITS = 7
private const val MAX_PRICE_LIMIT = 1_000_000L
private const val MAX_FRIENDS = 100
private const val PREVIEW_AVATARS = 5
private val INTERVALS = listOf(5, 15, 30, 60)
