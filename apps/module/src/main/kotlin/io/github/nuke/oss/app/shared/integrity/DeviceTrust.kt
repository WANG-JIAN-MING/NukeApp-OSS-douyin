package io.github.nuke.oss.app.shared.integrity

// JNI binding of the device trust checks in the Rust core (src/main/rust/src/riskctl), which shares the protocol core's
// library. scan returns the report as lines, or null when it could not run: check<TAB>id<TAB>level<TAB>detail for each
// check, and flag<TAB>NAME<TAB>class<TAB>1 or 0 for each flag that was raised, 1 when it trips the wire.
internal object NativeRisk {
    external fun scan(): String?
}

// How much a finding matters; the codes are the ones riskctl/mod.rs writes.
internal enum class TrustLevel(val code: String) {
    OK("ok"),
    INFO("info"),
    WARN("warn"),
    ALERT("alert"),
    UNKNOWN("unknown"),
}

internal data class TrustCheck(val id: String, val level: TrustLevel, val detail: String)

// How much a flag counts for on its own; the codes are the ones riskctl/flags.rs writes. A hard flag trips the wire by
// itself; weak flags and the kernel-tamper flag only count next to a hard one; device-state and note flags never trip it.
internal enum class FlagClass(val code: String) {
    HARD("hard"),
    WEAK("weak"),
    ENV("env"),
    NOTE("note"),
}

// A flag the checks raised: its name as the native side gives it, its class, and whether it tripped the wire.
internal data class TrustFlag(val name: String, val flagClass: FlagClass, val tripped: Boolean)

internal enum class TrustVerdict {
    // Nothing weakens the device, though what could not be read is not counted for it.
    TRUSTED,

    // A protection of the platform is weakened.
    CAUTION,

    // A debugger, an injection or an instrumentation tool is in this process.
    ALERT,

    // Nothing could be read.
    UNKNOWN,
}

// The checks of one run and the flags they raised. A tripped wire or an alert is an alert. Info findings, such as root or
// an unlocked bootloader, are common among people who run this module and do not change the verdict. A run where nothing
// could be read is unknown and never trusted.
//
// TODO(log-sending): sending detailed logs is not built yet, and it has to be built on this report: take one run at the
// moment of sending, put its verdict, its tripwire and the names of its flags, and its checks (levels and details only,
// never raw maps, paths or property values) at the top of what is sent, and decide from the verdict and the tripwire
// whether a log from this device can be trusted at all. Until then the report is only shown in the dialog behind the
// avatar on the settings screen, and nothing here leaves the device.
internal data class TrustReport(val checks: List<TrustCheck>, val flags: List<TrustFlag> = emptyList()) {
    val tripped: List<TrustFlag>
        get() = flags.filter { it.tripped }

    val verdict: TrustVerdict
        get() = when {
            checks.any { it.level == TrustLevel.ALERT } || flags.any { it.tripped } -> TrustVerdict.ALERT
            checks.any { it.level == TrustLevel.WARN } -> TrustVerdict.CAUTION
            checks.none { it.level != TrustLevel.UNKNOWN } -> TrustVerdict.UNKNOWN
            else -> TrustVerdict.TRUSTED
        }
}

internal sealed interface TrustResult {
    class Ready(val report: TrustReport) : TrustResult

    // The checks could not run at all; reason says why.
    class Unavailable(val reason: String) : TrustResult
}

internal object DeviceTrust {
    interface Natives {
        fun load()
        fun scan(): String?
    }

    private val native = object : Natives {
        override fun load() = NativeSignature.ensureLoaded()
        override fun scan() = NativeRisk.scan()
    }

    // A line of another kind, or without its four fields, is left out. A level or a class this build does not know reads as
    // unknown or as a note, so a library of another build cannot make the report fail.
    fun parse(text: String): TrustReport {
        val checks = ArrayList<TrustCheck>()
        val flags = ArrayList<TrustFlag>()
        for (line in text.lineSequence()) {
            val fields = line.split('\t', limit = 4)
            if (fields.size != 4 || fields[1].isBlank()) continue
            when (fields[0]) {
                "check" -> checks += TrustCheck(
                    fields[1], TrustLevel.entries.firstOrNull { it.code == fields[2] } ?: TrustLevel.UNKNOWN, fields[3],
                )
                "flag" -> flags += TrustFlag(
                    fields[1], FlagClass.entries.firstOrNull { it.code == fields[2] } ?: FlagClass.NOTE, fields[3] == "1",
                )
            }
        }
        return TrustReport(checks, flags)
    }

    fun check(natives: Natives = native): TrustResult {
        try {
            natives.load()
        } catch (error: UnsatisfiedLinkError) {
            return TrustResult.Unavailable(error.message ?: "native library missing")
        }
        val text = try {
            natives.scan()
        } catch (error: UnsatisfiedLinkError) {
            return TrustResult.Unavailable(error.message ?: "native library has no device trust checks")
        } ?: return TrustResult.Unavailable("the native checks did not run")
        val report = parse(text)
        return if (report.checks.isEmpty()) TrustResult.Unavailable("the native checks returned nothing") else TrustResult.Ready(report)
    }
}
