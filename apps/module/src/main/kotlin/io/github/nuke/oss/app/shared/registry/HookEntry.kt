package io.github.nuke.oss.app.shared.registry

import io.github.nuke.oss.runtime.RuntimeFeature

// Registers a host feature without a hand-kept list, like NukeApp's @HookEntry. Put it on an object implementing
// FeatureEntry, or on a feature class whose companion object implements it. core/hook-ksp lists every entry of a
// host package io.github.nuke.oss.app.<host>, by order and then qualified name, in that package's generated
// generatedHookEntries; the host adapter builds its features from that list.
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class HookEntry(val order: Int = 0)

// The kinds of host process a feature installs in. MAIN is the app's main process, MINI_APP its mini-app and mini-game
// processes, and OTHER any other non-isolated child process.
enum class HostProcess {
    MAIN, MINI_APP, OTHER;

    companion object {
        val MAIN_ONLY: Set<HostProcess> = setOf(MAIN)
        val MAIN_AND_MINI_APP: Set<HostProcess> = setOf(MAIN, MINI_APP)
        val ALL: Set<HostProcess> = entries.toSet()
    }
}

// Builds features from the services a host adapter hands to all of its entries. Most entries build one feature; a
// class registered under several IDs builds each of them. enabledByDefault applies while no switch is stored, and
// processes says where the features of the entry install; the main process alone unless an entry says otherwise.
interface FeatureEntry<in S> {
    val enabledByDefault: Boolean get() = false
    val processes: Set<HostProcess> get() = HostProcess.MAIN_ONLY
    fun create(services: S): List<RuntimeFeature>
}
