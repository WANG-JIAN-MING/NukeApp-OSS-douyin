package io.github.nuke.oss.app.tiktok.feature.sharing

import io.github.nuke.oss.runtime.FeatureScope
import kotlin.math.abs

// One configured group: a name and the UIDs of its friends, in the order they were written.
internal data class ShareGroup(val name: String, val uids: List<String>) {
    override fun toString() = "ShareGroup($name, ${uids.size})"
}

// Parses the option lines, one group per line as name=uid,uid,… with the legacy module's limits: at most 20 groups,
// distinct names of 1 to 30 characters, 1 to 100 distinct positive UIDs each. Any bad line is an
// IllegalArgumentException naming it, so the feature fails to install instead of sending to a guessed group.
internal fun parseShareGroups(lines: List<String>): List<ShareGroup> {
    require(lines.size <= MAX_GROUPS) { "At most $MAX_GROUPS share groups are supported" }
    val groups = lines.mapIndexed { index, line ->
        val separator = line.lastIndexOf('=')
        require(separator > 0) { "Share group line ${index + 1} is not name=uid,uid" }
        val name = line.substring(0, separator).trim()
        require(name.length in 1..MAX_NAME) { "Share group line ${index + 1} needs a name of 1 to $MAX_NAME characters" }
        val uids = line.substring(separator + 1).split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        require(uids.size in 1..MAX_MEMBERS) { "Share group $name needs 1 to $MAX_MEMBERS friends" }
        require(uids.all { (it.toLongOrNull() ?: 0) > 0 }) { "Share group $name has an invalid UID" }
        ShareGroup(name, uids)
    }
    require(groups.map { it.name }.toSet().size == groups.size) { "Duplicate share group names" }
    return groups
}

// Parses the avatar option lines, one per group as name=file, where file is a picture the settings screen saved in the
// feature's directory under a random name. A line for a group that does not exist or with any other file name is an
// IllegalArgumentException, like a bad group line.
internal fun parseShareAvatars(lines: List<String>, groups: List<ShareGroup>): Map<String, String> {
    val names = groups.map { it.name }.toSet()
    return lines.associate { line ->
        val separator = line.lastIndexOf('=')
        require(separator > 0) { "Share group avatar line is not name=file" }
        val name = line.substring(0, separator).trim()
        val file = line.substring(separator + 1).trim()
        require(name in names) { "Share group avatar names no group" }
        require(AVATAR_FILE.matches(file)) { "Share group avatar of $name is not a saved picture" }
        name to file
    }
}

// The name the settings screen gives a saved group picture; keep in step with ShareGroupsDialog.
private val AVATAR_FILE = Regex("[a-f0-9-]{36}\\.png")
private const val MAX_GROUPS = 20
private const val MAX_NAME = 30
private const val MAX_MEMBERS = 100

// Exact membership: every UID of the group must be exactly one current friend, or nothing is sent. A group is never
// sent in part, and a friend object from an earlier read is never reused.
internal fun <T> resolveShareGroup(group: ShareGroup, current: List<Pair<String, T>>): List<T> {
    val byUid = current.groupBy({ it.first }, { it.second })
    return group.uids.map { uid ->
        checkNotNull(byUid[uid]?.singleOrNull()) { "Friend $uid of ${group.name} is not in the friend list" }
    }
}

// The host's share callbacks for one group share. They say the host handled the share, not that a friend received it.
// Unknown counts the requested friends no callback named.
internal class ShareGroupOutcome(private val requested: Set<String>) {
    val accepted = LinkedHashSet<String>()
    val failed = LinkedHashSet<String>()
    val unknownCount get() = (requested - accepted - failed).size

    fun record(uids: Collection<String>, success: Boolean) {
        val valid = uids.filter { it in requested }
        if (success) {
            accepted += valid
            failed -= valid.toSet()
        } else {
            failed += valid.filterNot { it in accepted }
        }
    }
}

// What a group share ended with. dispatched is false when the host's share call was never made; error is the reason
// a share stopped early, if any.
internal data class GroupShareResult(val dispatched: Boolean, val accepted: Int, val failed: Int, val unknown: Int, val error: String?)

internal data class ShareGroupAnchor(val position: Int, val left: Float, val top: Float, val width: Float)

internal data class ShareGroupBounds(val index: Int, val left: Float, val top: Float, val width: Float, val height: Float) {
    fun contains(x: Float, y: Float) = x >= left && x < left + width && y >= top && y < top + height
}

// Reserves space before the first row (or column) of the host's list with an item offset and lays the groups out
// in it, so the host's adapter positions never change. lanes is the span count of a grid, otherwise 1.
internal class ShareGroupPrefixLayout(
    val horizontal: Boolean,
    val lanes: Int,
    val groups: Int,
    private val cellWidth: Float,
    private val cellHeight: Float,
) {
    init { require(lanes > 0 && groups >= 0 && cellWidth > 0 && cellHeight > 0) }

    val extent: Float get() = ((groups + lanes - 1) / lanes) * if (horizontal) cellWidth else cellHeight

    fun inset(position: Int): Float = if (position in 0 until lanes) extent else 0f

    fun bounds(anchors: List<ShareGroupAnchor>): List<ShareGroupBounds> {
        val byLane = anchors.filter { it.position in 0 until lanes }.associateBy { it.position }
        return (0 until groups).mapNotNull { index ->
            val anchor = byLane[index % lanes] ?: return@mapNotNull null
            val slot = index / lanes
            if (horizontal) ShareGroupBounds(index, anchor.left - extent + slot * cellWidth, anchor.top, cellWidth, cellHeight)
            else ShareGroupBounds(index, anchor.left, anchor.top - extent + slot * cellHeight, anchor.width, cellHeight)
        }
    }
}

// A drag, a cancel or a release over another group never counts as a tap.
internal class ShareGroupTapTracker(private val slop: Float) {
    private var index: Int? = null
    private var x = 0f
    private var y = 0f

    fun down(hit: Int?, x: Float, y: Float) {
        index = hit
        this.x = x
        this.y = y
    }

    fun move(x: Float, y: Float) {
        if (abs(x - this.x) > slop || abs(y - this.y) > slop) cancel()
    }

    fun up(hit: Int?, x: Float, y: Float): Int? {
        move(x, y)
        val tapped = index?.takeIf { it == hit }
        cancel()
        return tapped
    }

    fun cancel() {
        index = null
    }
}

// A group as the list shows it, with its picture when it has one.
internal class PrefixGroup(val name: String, val members: Int, val avatar: PrefixAvatar? = null) {
    override fun toString() = "PrefixGroup($name, $members)"
}

// A group's picture, as NukeApp shows it: the custom one picked on the settings screen, otherwise the first friend's
// avatar. key names the picture, so the same key is the same picture; load reads its bytes, blocking, off the main
// thread, and answers null when there are none.
internal class PrefixAvatar(val key: String, val load: () -> ByteArray?)

// Paints groups before the first row of the host's friend list and reports taps on them, without touching the list's
// adapter. attach may be called on any thread; the View work runs on the main thread, and so does onTap. Everything
// attached is taken off when the feature's scope closes.
internal interface FriendPrefixes {
    fun install(scope: FeatureScope, featureId: String)
    fun attach(list: Any, groups: List<PrefixGroup>, onTap: (index: Int) -> Unit)
    fun isAttached(list: Any): Boolean
}
