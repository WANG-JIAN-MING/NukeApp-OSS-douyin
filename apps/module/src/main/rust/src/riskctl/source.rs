use super::{check_loaded, classify, Report};
use std::fs::{self, File};
use std::io::Read;
use std::path::Path;

// A /proc text is read up to this size, and directories of entries up to the count below, so a process with a huge
// memory map or thousands of descriptors cannot make a check slow or large.
const MAX_TEXT_BYTES: u64 = 4 * 1024 * 1024;
const MAX_ENTRIES: usize = 1024;
const SU_PATHS: [&str; 5] = [
    "/system/bin/su",
    "/system/xbin/su",
    "/system/sbin/su",
    "/sbin/su",
    "/debug_ramdisk/su",
];

fn read_text_limited(path: &str, limit: u64) -> Option<String> {
    let mut bytes = Vec::new();
    File::open(path)
        .ok()?
        .take(limit)
        .read_to_end(&mut bytes)
        .ok()?;
    Some(String::from_utf8_lossy(&bytes).into_owned())
}

fn read_text(path: &str) -> Option<String> {
    read_text_limited(path, MAX_TEXT_BYTES)
}

fn thread_names() -> Vec<String> {
    let Ok(tasks) = fs::read_dir("/proc/self/task") else {
        return Vec::new();
    };
    tasks
        .flatten()
        .take(MAX_ENTRIES)
        .filter_map(|task| read_text(&format!("{}/comm", task.path().display())))
        .map(|name| name.trim().to_string())
        .collect()
}

fn descriptor_targets() -> Vec<String> {
    let Ok(entries) = fs::read_dir("/proc/self/fd") else {
        return Vec::new();
    };
    entries
        .flatten()
        .take(MAX_ENTRIES)
        .filter_map(|entry| fs::read_link(entry.path()).ok())
        .map(|target| target.to_string_lossy().into_owned())
        .collect()
}

#[cfg(target_os = "android")]
fn property(name: &str) -> Option<String> {
    use std::ffi::{CStr, CString};
    use std::os::raw::{c_char, c_int};

    // The longest value a system property can hold, terminator included.
    const PROP_VALUE_MAX: usize = 92;
    extern "C" {
        fn __system_property_get(name: *const c_char, value: *mut c_char) -> c_int;
    }
    let name = CString::new(name).ok()?;
    let mut buffer = [0 as c_char; PROP_VALUE_MAX];
    // SAFETY: `name` is a NUL-terminated string that outlives the call, and `buffer` is PROP_VALUE_MAX bytes, the most
    // bionic writes to it, terminator included.
    let length = unsafe { __system_property_get(name.as_ptr(), buffer.as_mut_ptr()) };
    if length <= 0 {
        return None;
    }
    // SAFETY: a positive length means bionic wrote a NUL-terminated string into `buffer`, which is still alive here.
    let value = unsafe { CStr::from_ptr(buffer.as_ptr()) };
    Some(value.to_string_lossy().into_owned())
}

// System properties exist only on Android; elsewhere, such as in the tests on a development machine, they read as absent.
#[cfg(not(target_os = "android"))]
fn property(_name: &str) -> Option<String> {
    None
}

/// The module library that holds the signature check, as it is named in the loaded images.
const MODULE_LIBRARY: &str = "libnuke_protocol_core.so";

#[cfg(target_os = "android")]
const LIBC: &str = "libc.so";
#[cfg(not(target_os = "android"))]
const LIBC: &str = "libc.so.6";

fn read_bytes(path: &str, limit: u64) -> Option<Vec<u8>> {
    let mut bytes = Vec::new();
    File::open(path)
        .ok()?
        .take(limit)
        .read_to_end(&mut bytes)
        .ok()?;
    Some(bytes)
}

// kcore is a huge file that is closed to apps; opening it and reading a few bytes is all that is asked of it.
fn kcore_readable() -> bool {
    let mut probe = [0u8; 16];
    File::open("/proc/kcore")
        .and_then(|mut file| file.read(&mut probe))
        .is_ok_and(|read| read > 0)
}

/// Runs every check against this process and this device, in a fixed order, and the tripwire over what they raised.
pub fn scan() -> Report {
    let status = read_text("/proc/self/status");
    let maps = read_text("/proc/self/maps");
    let threads = thread_names();
    let descriptors = descriptor_targets();
    let preload = std::env::var("LD_PRELOAD").ok();
    let audit = std::env::var("LD_AUDIT").ok();
    let su: Vec<&str> = SU_PATHS
        .iter()
        .copied()
        .filter(|path| Path::new(path).exists())
        .collect();
    let enforce = read_text("/sys/fs/selinux/enforce");
    let policy = read_bytes("/sys/fs/selinux/status", 20);
    let kallsyms = read_text_limited("/proc/kallsyms", 8192);
    let mountinfo = read_text("/proc/self/mountinfo");
    let tcp = match (read_text("/proc/net/tcp"), read_text("/proc/net/tcp6")) {
        (None, None) => None,
        (v4, v6) => Some(format!(
            "{}\n{}",
            v4.unwrap_or_default(),
            v6.unwrap_or_default()
        )),
    };
    let unix = read_text("/proc/net/unix");
    let environ = read_text("/proc/self/environ");
    Report::new(vec![
        classify::debugger(status.as_deref()),
        classify::injection(preload.as_deref(), audit.as_deref()),
        classify::frida(maps.as_deref(), &threads, &descriptors),
        classify::descriptors(&descriptors),
        classify::signature_path(
            check_loaded(MODULE_LIBRARY, maps.as_deref()),
            check_loaded(LIBC, maps.as_deref()),
        ),
        classify::maps_tools(maps.as_deref()),
        classify::maps_memory(maps.as_deref()),
        classify::network(tcp.as_deref(), unix.as_deref(), environ.as_deref()),
        classify::root(&su),
        classify::bootloader(
            property("ro.boot.vbmeta.device_state").as_deref(),
            property("ro.boot.flash.locked").as_deref(),
            property("ro.boot.verifiedbootstate").as_deref(),
        ),
        classify::selinux(enforce.as_deref()),
        classify::selinux_policy(policy.as_deref()),
        classify::kernel_exposure(kallsyms.as_deref(), kcore_readable()),
        classify::mounts(mountinfo.as_deref()),
        classify::build(
            property("ro.build.tags").as_deref(),
            property("ro.debuggable").as_deref(),
            property("ro.secure").as_deref(),
        ),
        classify::emulator(
            property("ro.kernel.qemu").as_deref(),
            property("ro.hardware").as_deref(),
            property("ro.dalvik.vm.native.bridge").as_deref(),
        ),
        classify::adbd(property("init.svc.adbd").as_deref()),
    ])
}
