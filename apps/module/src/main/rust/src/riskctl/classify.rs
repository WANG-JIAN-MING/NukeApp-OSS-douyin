use super::flags::*;
use super::{Check, ImageResult, Level};

fn mentions(text: &str, needles: &[&str]) -> bool {
    let lowered = text.to_ascii_lowercase();
    needles.iter().any(|needle| lowered.contains(needle))
}

fn joined(parts: &[&str]) -> String {
    parts.join(", ")
}

/// A debugger or tracer attached to this process: `TracerPid` in `/proc/self/status`.
pub fn debugger(status: Option<&str>) -> Check {
    let Some(status) = status else {
        return Check::new("debugger", Level::Unknown, "process status not readable");
    };
    let tracer = status.lines().find_map(|line| {
        line.strip_prefix("TracerPid:")
            .and_then(|value| value.trim().parse::<u64>().ok())
    });
    match tracer {
        Some(0) => Check::new("debugger", Level::Ok, "no tracer attached"),
        Some(pid) => Check::new("debugger", Level::Alert, format!("traced by process {pid}"))
            .flagged(DEBUGGER | GDB_PTRACE),
        None => Check::new("debugger", Level::Unknown, "no tracer field in the status"),
    }
}

/// A linker injection set up through the environment of this process.
pub fn injection(preload: Option<&str>, audit: Option<&str>) -> Check {
    let set = |value: Option<&str>| value.is_some_and(|text| !text.trim().is_empty());
    match (set(preload), set(audit)) {
        (false, false) => Check::new("injection", Level::Ok, "no LD_PRELOAD or LD_AUDIT"),
        (true, false) => {
            Check::new("injection", Level::Alert, "LD_PRELOAD is set").flagged(LINKER_ENV)
        }
        (false, true) => {
            Check::new("injection", Level::Alert, "LD_AUDIT is set").flagged(LINKER_ENV)
        }
        (true, true) => Check::new("injection", Level::Alert, "LD_PRELOAD and LD_AUDIT are set")
            .flagged(LINKER_ENV),
    }
}

/// Frida in this process: mapped agent libraries, its worker thread names, and file descriptors that name it.
pub fn frida(maps: Option<&str>, threads: &[String], descriptors: &[String]) -> Check {
    if maps.is_none() && threads.is_empty() && descriptors.is_empty() {
        return Check::new(
            "frida",
            Level::Unknown,
            "memory map and threads not readable",
        );
    }
    let mut found = Vec::new();
    let mut flags = 0;
    if maps.is_some_and(|text| mentions(text, &["frida", "gum-js", "linjector"])) {
        found.push("memory map");
        flags |= PROC_MAPS | HOOK_SURFACE;
    }
    if threads
        .iter()
        .any(|name| mentions(name, &["gum-js-loop", "frida"]))
    {
        found.push("thread names");
    }
    if descriptors
        .iter()
        .any(|target| mentions(target, &["frida"]))
    {
        found.push("file descriptors");
    }
    if found.is_empty() {
        Check::new("frida", Level::Ok, "no Frida trace found")
    } else {
        Check::new(
            "frida",
            Level::Alert,
            format!("Frida seen in the {}", found.join(", ")),
        )
        .flagged(FRIDA | flags)
    }
}

/// Descriptors that only a debugger or a memory dumper would hold: a debug server, a memory dump file, this process's own
/// memory file.
pub fn descriptors(targets: &[String]) -> Check {
    if targets.is_empty() {
        return Check::new(
            "descriptors",
            Level::Unknown,
            "file descriptors not readable",
        );
    }
    let mut found = Vec::new();
    let mut flags = 0;
    if targets
        .iter()
        .any(|target| mentions(target, &["gdbserver", "lldb-server"]))
    {
        found.push("a debug server");
        flags |= GDB_PTRACE | HOOK_SURFACE;
    }
    if targets
        .iter()
        .any(|target| target == "/proc/self/mem" || mentions(target, &["memdump"]))
    {
        found.push("a memory dump");
        flags |= DUMP_SURFACE;
    }
    if found.is_empty() {
        Check::new("descriptors", Level::Ok, "no debugger or dump descriptor")
    } else {
        Check::new(
            "descriptors",
            Level::Alert,
            format!("holds {}", found.join(" and ")),
        )
        .flagged(FD_SUSPICIOUS | flags)
    }
}

fn describe(result: ImageResult) -> &'static str {
    match result {
        ImageResult::Match => "matches",
        ImageResult::Mismatch => "differs",
        ImageResult::Unavailable => "not readable",
        ImageResult::NotLoaded => "not loaded",
    }
}

/// The path of the module's signature check: the executable code of the library that holds it and of libc, compared
/// with their files. A library whose code was patched can answer the check however its patcher likes, and a patched libc
/// can make the file the check reads lie.
pub fn signature_path(module: ImageResult, libc: ImageResult) -> Check {
    let mut changed = Vec::new();
    let mut flags = 0;
    if module == ImageResult::Mismatch {
        changed.push("the module library");
        flags |= EXEC_INTEGRITY | SIGNATURE_HOOK | PROC_MAPS;
    }
    if libc == ImageResult::Mismatch {
        changed.push("libc");
        flags |= EXEC_INTEGRITY | SIGNATURE_HOOK | HOOK_SURFACE;
    }
    if !changed.is_empty() {
        return Check::new(
            "signature_path",
            Level::Alert,
            format!("code of {} differs from its file", changed.join(" and ")),
        )
        .flagged(flags);
    }
    if module == ImageResult::Match && libc == ImageResult::Match {
        return Check::new(
            "signature_path",
            Level::Ok,
            "code of the module library and libc matches the files",
        );
    }
    Check::new(
        "signature_path",
        Level::Unknown,
        format!(
            "module library {}, libc {}",
            describe(module),
            describe(libc)
        ),
    )
}

// Names of tools that show in a memory map, grouped by what they mean and the flags they raise.
const XPOSED_NAMES: &[&str] = &[
    "xposed",
    "lsposed",
    "edxp",
    "edxposed",
    "lspatch",
    "taichi",
    "virtualxposed",
    "liblspd",
];
const ZYGISK_NAMES: &[&str] = &["riru", "zygisk"];
const MAGISK_NAMES: &[&str] = &["magisk", "shamiko"];
const ROOT_TOOL_NAMES: &[&str] = &["kernelsu", "apatch", "adbroot"];
const OTHER_HOOK_NAMES: &[&str] = &["substrate", "cydia", "libwhale", "sandhook", "libepic"];
const BRIDGE_NAMES: &[&str] = &["libhoudini", "ndk_translation"];

/// Hook frameworks, root tools and translation layers that name themselves in a memory map: the Xposed family, Riru and
/// Zygisk, Magisk, KernelSU and APatch, other hooking libraries, an ARM translation layer. The module's own libraries
/// are left out so that they do not report themselves.
pub fn maps_tools(maps: Option<&str>) -> Check {
    let Some(maps) = maps else {
        return Check::new("maps_tools", Level::Unknown, "memory map not readable");
    };
    let text: String = maps
        .lines()
        .filter(|line| !line.to_ascii_lowercase().contains("libnuke_"))
        .collect::<Vec<_>>()
        .join("\n");
    let mut found = Vec::new();
    let mut flags = 0;
    let mut level = Level::Ok;
    let mut note = |level_of: Level, name: &'static str, raised: u64| {
        found.push(name);
        flags |= raised;
        if level == Level::Ok || level_of == Level::Warn {
            level = level_of;
        }
    };
    if mentions(&text, XPOSED_NAMES) {
        note(Level::Info, "Xposed family", XPOSED | HOOK_SURFACE);
    }
    if mentions(&text, ZYGISK_NAMES) {
        note(Level::Info, "Riru or Zygisk", ZYGISK | HOOK_SURFACE);
    }
    if mentions(&text, MAGISK_NAMES) {
        note(Level::Info, "Magisk", MAGISK);
    }
    if mentions(&text, ROOT_TOOL_NAMES) {
        note(Level::Info, "KernelSU or APatch", ROOT_TOOL | KERNEL_TAMPER);
    }
    if mentions(&text, OTHER_HOOK_NAMES) {
        note(
            Level::Warn,
            "another hooking library",
            OTHER_HOOK | HOOK_SURFACE,
        );
    }
    if mentions(&text, BRIDGE_NAMES) {
        note(Level::Info, "an ARM translation layer", NATIVE_BRIDGE);
    }
    if found.is_empty() {
        Check::new(
            "maps_tools",
            Level::Ok,
            "no hook or root tool named in the memory map",
        )
    } else {
        Check::new("maps_tools", level, joined(&found)).flagged(flags | PROC_MAPS)
    }
}

// The JIT and its double mapping leave executable mappings that look like these on every device.
fn is_jit_mapping(line: &str) -> bool {
    mentions(line, &["jit-", "jit_", "doublemapper", "dalvik-jit"])
}

const MODULE_LIBRARY: &str = "libnuke_protocol_core.so";

/// Oddities in the memory map: a mapping that is both writable and executable, executable memory backed by a deleted or
/// memory-backed file (the JIT's own are not counted), and the module library mapped from a temporary location, a
/// deleted file or from two different paths.
pub fn maps_memory(maps: Option<&str>) -> Check {
    let Some(maps) = maps else {
        return Check::new("maps_memory", Level::Unknown, "memory map not readable");
    };
    let mut found = Vec::new();
    let mut flags = 0;
    let mut level = Level::Ok;
    let mut library_paths: Vec<&str> = Vec::new();
    let mut writable_executable = false;
    let mut backed_by_deleted = false;
    let mut odd_path = false;
    for line in maps.lines() {
        let mut fields = line.split_whitespace();
        let _range = fields.next();
        let permissions = fields.next().unwrap_or("");
        let path = line.split_whitespace().nth(5).unwrap_or("");
        if path.rsplit('/').next() == Some(MODULE_LIBRARY) {
            if !library_paths.contains(&path) {
                library_paths.push(path);
            }
            if mentions(path, &["/data/local/tmp", "/sdcard", "memfd:"])
                || line.contains("(deleted)")
            {
                odd_path = true;
            }
        }
        if !permissions.contains('x') || is_jit_mapping(line) {
            continue;
        }
        if permissions.contains('w') {
            writable_executable = true;
        }
        if mentions(line, &["memfd:"]) || line.contains("(deleted)") {
            backed_by_deleted = true;
        }
    }
    if writable_executable {
        found.push("writable and executable mapping");
        flags |= WX_MAPPING | HOOK_SURFACE;
        level = Level::Info;
    }
    if backed_by_deleted {
        found.push("executable memory from a deleted or memory file");
        flags |= MEMFD_DELETED | HOOK_SURFACE;
        level = Level::Info;
    }
    if odd_path {
        found.push("module library from a temporary or deleted file");
        flags |= NATIVE_PATH;
        level = Level::Warn;
    }
    if library_paths.len() > 1 {
        found.push("module library mapped from more than one path");
        flags |= MULTI_MAPPING;
        level = Level::Warn;
    }
    if found.is_empty() {
        Check::new("maps_memory", Level::Ok, "no unusual executable mapping")
    } else {
        Check::new("maps_memory", level, joined(&found)).flagged(flags)
    }
}

// Frida's default ports, 27042 and 27043, as they appear in /proc/net/tcp.
const FRIDA_PORTS: [&str; 2] = ["69A2", "69A3"];

fn listening_on_frida_port(tcp: &str) -> bool {
    tcp.lines().skip(1).any(|line| {
        let fields: Vec<&str> = line.split_whitespace().collect();
        let (Some(local), Some(state)) = (fields.get(1), fields.get(3)) else {
            return false;
        };
        let port = local.rsplit(':').next().unwrap_or("");
        *state == "0A"
            && FRIDA_PORTS
                .iter()
                .any(|known| port.eq_ignore_ascii_case(known))
    })
}

const PROXY_VARIABLES: [&str; 5] = [
    "http_proxy=",
    "https_proxy=",
    "all_proxy=",
    "proxy_host=",
    "socks_proxy_host=",
];

/// The network surface: something listening on Frida's default port, sockets named after instrumentation tools, and a
/// proxy set in the environment of this process. Newer Android versions keep most of `/proc/net` from apps, in which case
/// what is left is only the proxy.
pub fn network(tcp: Option<&str>, unix: Option<&str>, environ: Option<&str>) -> Check {
    if tcp.is_none() && unix.is_none() && environ.is_none() {
        return Check::new("network", Level::Unknown, "network state not readable");
    }
    let mut found = Vec::new();
    let mut flags = 0;
    let mut level = Level::Ok;
    if tcp.is_some_and(listening_on_frida_port) {
        found.push("Frida's default port is listening");
        flags |= NETWORK_SURFACE;
        level = Level::Warn;
    }
    if unix.is_some_and(|text| mentions(text, &["frida", "gum-js", "linjector"])) {
        found.push("a socket named after Frida");
        flags |= NETWORK_SURFACE | HOOK_SURFACE;
        level = Level::Warn;
    }
    let proxied = environ.is_some_and(|text| {
        text.split('\0').any(|entry| {
            let lowered = entry.to_ascii_lowercase();
            PROXY_VARIABLES.iter().any(|name| lowered.starts_with(name))
        })
    });
    if proxied {
        found.push("a proxy is set in the environment");
        flags |= PROXY;
        if level == Level::Ok {
            level = Level::Info;
        }
    }
    if found.is_empty() {
        Check::new("network", Level::Ok, "no Frida port, socket or proxy")
    } else {
        Check::new("network", level, joined(&found)).flagged(flags)
    }
}

/// A `su` binary in the places root managers put it. The paths are the same on every device, so they are safe to show.
/// This is a weak signal: a manager such as KernelSU hides `su` from every process but root, so an app sees nothing on a
/// rooted device, and a clean result does not mean the device is not rooted.
pub fn root(found: &[&str]) -> Check {
    if found.is_empty() {
        Check::new("root", Level::Ok, "no su binary in the usual places")
    } else {
        Check::new(
            "root",
            Level::Info,
            format!("su found: {}", found.join(", ")),
        )
        .flagged(SU_BINARY)
    }
}

/// Verified boot as the bootloader reported it in system properties, and whether dm-verity still checks the system
/// partitions. A failed verified boot and a verity that does not enforce both mean the platform's protection is gone;
/// an unlocked bootloader is only a state, so it never raises the level above `Info`.
pub fn bootloader(
    device_state: Option<&str>,
    flash_locked: Option<&str>,
    verified_state: Option<&str>,
    verity_mode: Option<&str>,
) -> Check {
    let state = |value: Option<&str>| value.map(|text| text.trim().to_ascii_lowercase());
    let (device, flash, verified, verity) = (
        state(device_state),
        state(flash_locked),
        state(verified_state),
        state(verity_mode),
    );
    if device.is_none() && flash.is_none() && verified.is_none() && verity.is_none() {
        return Check::new(
            "bootloader",
            Level::Unknown,
            "boot state properties not readable",
        );
    }
    let mut found = Vec::new();
    let mut flags = 0;
    let mut level = Level::Ok;
    if verified.as_deref() == Some("red") {
        found.push("verified boot failed (red)");
        flags |= BOOT_UNLOCKED;
        level = Level::Warn;
    }
    if matches!(verity.as_deref(), Some("disabled" | "eio")) {
        found.push("dm-verity is not enforcing");
        flags |= KERNEL_TAMPER;
        level = Level::Warn;
    }
    let unlocked = device.as_deref() == Some("unlocked")
        || flash.as_deref() == Some("0")
        || verified.as_deref() == Some("orange");
    if unlocked {
        found.push("bootloader is unlocked");
        flags |= BOOT_UNLOCKED;
        if level == Level::Ok {
            level = Level::Info;
        }
    }
    if verified.as_deref() == Some("yellow") {
        found.push("verified boot with a custom key (yellow)");
        if level == Level::Ok {
            level = Level::Info;
        }
    }
    if found.is_empty() {
        return Check::new(
            "bootloader",
            Level::Ok,
            "bootloader locked, verified boot intact",
        );
    }
    Check::new("bootloader", level, joined(&found)).flagged(flags)
}

/// SELinux mode from `/sys/fs/selinux/enforce`.
pub fn selinux(enforce: Option<&str>) -> Check {
    match enforce.map(str::trim) {
        Some("1") => Check::new("selinux", Level::Ok, "enforcing"),
        Some("0") => Check::new("selinux", Level::Warn, "permissive")
            .flagged(SELINUX_PERMISSIVE | KERNEL_TAMPER),
        Some(_) => Check::new("selinux", Level::Unknown, "unexpected enforce value"),
        None => Check::new("selinux", Level::Unknown, "mode not readable"),
    }
}

/// Whether the loaded SELinux policy is the one a normal boot leaves. `/sys/fs/selinux/status` holds five 32-bit numbers:
/// its version, a sequence number, whether it enforces, how many policies were loaded and whether unknown permissions are
/// denied. A root tool that patches the policy while the system runs moves the counters. The usual pairs, sequence 4 with
/// one load or sequence 0 with none, are the ones the older native engine knew; another vendor may boot differently, so a
/// difference is a hint and not a finding.
pub fn selinux_policy(status: Option<&[u8]>) -> Check {
    let Some(bytes) = status.filter(|bytes| bytes.len() >= 20) else {
        return Check::new(
            "selinux_policy",
            Level::Unknown,
            "policy status not readable",
        );
    };
    let number = |index: usize| {
        let at = index * 4;
        u32::from_ne_bytes([bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]])
    };
    let (version, sequence, enforcing, loads, deny_unknown) =
        (number(0), number(1), number(2), number(3), number(4));
    let usual = (sequence == 4 && loads == 1) || (sequence == 0 && loads == 0);
    if version == 1 && enforcing == 1 && deny_unknown == 1 && usual {
        Check::new(
            "selinux_policy",
            Level::Ok,
            "policy status as after a normal boot",
        )
    } else {
        Check::new(
            "selinux_policy",
            Level::Warn,
            format!(
                "policy status differs from the usual boot (sequence {sequence}, loads {loads})"
            ),
        )
        .flagged(KERNEL_TAMPER)
    }
}

/// Kernel memory and symbols this process can see: real addresses in `/proc/kallsyms`, a readable `/proc/kcore`. Both are
/// closed to apps on a normal system.
pub fn kernel_exposure(kallsyms: Option<&str>, kcore_readable: bool) -> Check {
    let symbols_visible = kallsyms.is_some_and(|text| {
        text.lines().any(|line| {
            let mut parts = line.split_whitespace();
            let address = parts.next().unwrap_or("");
            let kind = parts.next().unwrap_or("");
            address.chars().any(|digit| digit != '0') && (kind == "T" || kind == "t")
        })
    });
    let mut found = Vec::new();
    let mut flags = 0;
    if symbols_visible {
        found.push("kernel symbol addresses are readable");
        flags |= KERNEL_TAMPER;
    }
    if kcore_readable {
        found.push("/proc/kcore is readable");
        flags |= KERNEL_TAMPER | DUMP_SURFACE;
    }
    if found.is_empty() {
        Check::new(
            "kernel_exposure",
            Level::Ok,
            "kernel symbols hidden, /proc/kcore closed",
        )
    } else {
        Check::new("kernel_exposure", Level::Warn, joined(&found)).flagged(flags)
    }
}

const SYSTEM_MOUNTS: [&str; 5] = ["/system", "/vendor", "/product", "/system_ext", "/odm"];

/// The mounts this process sees: root tools that mount something (KernelSU, APatch, Magisk, `/data/adb`, a debug
/// ramdisk), Xposed files, and a system partition that is an overlay or mounted writable. Each system partition is
/// judged on its own line, so a writable `/data` does not count against them.
pub fn mounts(mountinfo: Option<&str>) -> Check {
    let Some(text) = mountinfo else {
        return Check::new("mounts", Level::Unknown, "mount table not readable");
    };
    let mut found = Vec::new();
    let mut flags = 0;
    let mut level = Level::Ok;
    let lowered = text.to_ascii_lowercase();
    if ["kernelsu", "/ksu", "apatch", "/data/adb", "debug_ramdisk"]
        .iter()
        .any(|name| lowered.contains(name))
    {
        found.push("a root tool mounts something");
        flags |= ROOT_TOOL | KERNEL_TAMPER;
        level = Level::Info;
    }
    if lowered.contains("magisk") {
        found.push("Magisk mounts something");
        flags |= MAGISK;
        level = Level::Info;
    }
    if lowered.contains("lsposed") || lowered.contains("xposed") {
        found.push("Xposed files are mounted");
        flags |= XPOSED | HOOK_SURFACE;
        level = Level::Info;
    }
    let mut overlay = false;
    let mut writable = false;
    for line in text.lines() {
        let (before, after) = line.split_once(" - ").unwrap_or((line, ""));
        let fields: Vec<&str> = before.split_whitespace().collect();
        let (Some(mount_point), Some(options)) = (fields.get(4), fields.get(5)) else {
            continue;
        };
        if !SYSTEM_MOUNTS.contains(mount_point) {
            continue;
        }
        if after.split_whitespace().next() == Some("overlay") {
            overlay = true;
        }
        if options.split(',').any(|option| option == "rw") {
            writable = true;
        }
    }
    if overlay {
        found.push("a system partition is an overlay");
        flags |= KERNEL_TAMPER | ROOT_TOOL;
        level = Level::Warn;
    }
    if writable {
        found.push("a system partition is writable");
        flags |= KERNEL_TAMPER;
        level = Level::Warn;
    }
    if found.is_empty() {
        Check::new(
            "mounts",
            Level::Ok,
            "no root tool mounts, system partitions read-only",
        )
    } else {
        Check::new("mounts", level, joined(&found)).flagged(flags)
    }
}

/// How the system image was built: a debuggable build and security switched off weaken the platform, test keys only
/// say it is not an official build.
pub fn build(tags: Option<&str>, debuggable: Option<&str>, secure: Option<&str>) -> Check {
    if tags.is_none() && debuggable.is_none() && secure.is_none() {
        return Check::new("build", Level::Unknown, "build properties not readable");
    }
    let mut weak = Vec::new();
    if debuggable.map(str::trim) == Some("1") {
        weak.push("debuggable build");
    }
    if secure.map(str::trim) == Some("0") {
        weak.push("ro.secure is 0");
    }
    if !weak.is_empty() {
        return Check::new("build", Level::Warn, weak.join(", ")).flagged(DEBUGGABLE_BUILD);
    }
    if tags.is_some_and(|text| mentions(text, &["test-keys"])) {
        return Check::new("build", Level::Info, "built with test keys").flagged(TEST_KEYS);
    }
    Check::new("build", Level::Ok, "release keys, not a debuggable build")
}

/// Four structural probes of this process, the ones an emulated Android runtime (unidbg) fails. `Some(true)` passed,
/// `Some(false)` failed, `None` could not be read. Only all four failing is a conclusion; a partial failure, or a probe
/// that could not be read, says nothing.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Probes {
    /// The libc this process has loaded is the code of its file.
    pub loader: Option<bool>,
    /// The auxiliary vector is the kernel's (see `auxv_sane`).
    pub auxv: Option<bool>,
    /// The Android platform properties exist. Only readable on Android.
    pub properties: Option<bool>,
    /// The memory map holds the Android runtime (see `runtime_maps_sane`).
    pub maps: Option<bool>,
}

impl Probes {
    fn all_failed(self) -> bool {
        [self.loader, self.auxv, self.properties, self.maps]
            .iter()
            .all(|probe| *probe == Some(false))
    }
}

/// An emulator, an ARM translation layer that makes an x86 machine run this code, or a runtime whose structure fails
/// every probe.
pub fn emulator(
    qemu: Option<&str>,
    hardware: Option<&str>,
    native_bridge: Option<&str>,
    probes: Probes,
) -> Check {
    let bridge = native_bridge.map(str::trim).is_some_and(|value| {
        !value.is_empty() && value != "0" && !value.eq_ignore_ascii_case("false")
    });
    let emulated = qemu.map(str::trim) == Some("1")
        || hardware.is_some_and(|text| mentions(text, &["goldfish", "ranchu"]));
    if emulated {
        Check::new("emulator", Level::Info, "running in an emulator")
    } else if bridge {
        Check::new("emulator", Level::Info, "a native bridge translates code")
            .flagged(NATIVE_BRIDGE)
    } else if probes.all_failed() {
        Check::new(
            "emulator",
            Level::Info,
            "runtime structure fails all four probes, as an emulated one does",
        )
    } else if qemu.is_none() && hardware.is_none() && native_bridge.is_none() {
        Check::new(
            "emulator",
            Level::Unknown,
            "emulator properties not readable",
        )
    } else {
        Check::new("emulator", Level::Ok, "real device, no native bridge")
    }
}

/// Whether `/proc/self/auxv` is the kernel's: 16-byte entries of two 64-bit words, ending with `AT_NULL`, with a page
/// size the kernel can give, a platform string and random bytes.
pub fn auxv_sane(bytes: &[u8]) -> bool {
    const AT_NULL: u64 = 0;
    const AT_PAGESZ: u64 = 6;
    const AT_PLATFORM: u64 = 15;
    const AT_RANDOM: u64 = 25;

    let word = |raw: &[u8]| <[u8; 8]>::try_from(raw).ok().map(u64::from_le_bytes);
    let mut terminated = false;
    let mut page_size_ok = false;
    let mut platform_ok = false;
    let mut random_ok = false;
    for entry in bytes.as_chunks::<16>().0.iter().take(256) {
        let (Some(kind), Some(value)) = (word(&entry[..8]), word(&entry[8..])) else {
            return false;
        };
        match kind {
            AT_NULL => {
                terminated = true;
                break;
            }
            AT_PAGESZ => page_size_ok = value.is_power_of_two() && (4096..=65536).contains(&value),
            AT_PLATFORM => platform_ok = value != 0,
            AT_RANDOM => random_ok = value != 0,
            _ => {}
        }
    }
    terminated && page_size_ok && platform_ok && random_ok
}

/// Whether the memory map holds the Android runtime the way a device does: bionic's libc, from the APEX runtime or the
/// system partition, with the ART runtime or the linker. Paths are matched as directories (`/system/`), because the
/// map lists the files; an emulated runtime often maps none of these.
pub fn runtime_maps_sane(maps: &str) -> bool {
    maps.contains("libc.so")
        && (maps.contains("/apex/com.android.runtime/") || maps.contains("/system/"))
        && (maps.contains("libart.so") || maps.contains("linker64"))
}

/// Whether the debug bridge daemon is running on the device.
pub fn adbd(service: Option<&str>) -> Check {
    match service.map(|value| value.trim().to_ascii_lowercase()) {
        Some(state) if state == "running" => {
            Check::new("adbd", Level::Info, "adbd is running").flagged(ADBD_RUNNING)
        }
        Some(_) => Check::new("adbd", Level::Ok, "adbd is not running"),
        None => Check::new("adbd", Level::Unknown, "service state not readable"),
    }
}
