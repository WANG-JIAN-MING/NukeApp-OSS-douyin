use nuke_protocol_core::riskctl::{
    self, adbd, bootloader, build, check_loaded, crc32c, debugger, descriptors, emulator, encode,
    frida, injection, kernel_exposure, maps_memory, maps_tools, mounts, network,
    parse_exec_segments, readable, root, scan, selinux, selinux_policy, signature_path, tripwire,
    Check, Class, ImageResult, Level, Report, CATALOGUE,
};

fn strings(items: &[&str]) -> Vec<String> {
    items.iter().map(|item| item.to_string()).collect()
}

fn policy(version: u32, sequence: u32, enforcing: u32, loads: u32, deny_unknown: u32) -> Vec<u8> {
    [version, sequence, enforcing, loads, deny_unknown]
        .iter()
        .flat_map(|value| value.to_ne_bytes())
        .collect()
}

const CLEAN_MAPS: &str = "7c00000000-7c00001000 r-xp 00000000 fe:4f 1 /system/lib64/libc.so\n\
7c00002000-7c00003000 rw-p 00000000 00:00 0 [anon:libc_malloc]\n";

// ---- the flags and the tripwire

#[test]
fn every_flag_has_its_own_bit_and_a_unique_name() {
    let mut bits = 0u64;
    let mut names = std::collections::HashSet::new();
    for flag in CATALOGUE {
        assert_eq!(flag.bit.count_ones(), 1, "{}", flag.name);
        assert_eq!(bits & flag.bit, 0, "{} shares a bit", flag.name);
        bits |= flag.bit;
        assert!(names.insert(flag.name), "{} is listed twice", flag.name);
    }
    assert_eq!(CATALOGUE.len(), 29);
}

#[test]
fn nothing_trips_without_a_hard_flag() {
    let everything_else: u64 = CATALOGUE
        .iter()
        .filter(|flag| flag.class != Class::Hard)
        .fold(0, |all, flag| all | flag.bit);
    assert_eq!(tripwire(everything_else), 0);
    assert_eq!(tripwire(0), 0);
}

#[test]
fn a_hard_flag_trips_by_itself() {
    assert_eq!(tripwire(riskctl::FRIDA), riskctl::FRIDA);
    assert_eq!(
        tripwire(riskctl::DEBUGGER | riskctl::GDB_PTRACE),
        riskctl::DEBUGGER | riskctl::GDB_PTRACE
    );
}

#[test]
fn weak_flags_and_kernel_tamper_join_a_hard_flag_and_the_rest_stay_out() {
    let flags = riskctl::EXEC_INTEGRITY
        | riskctl::SIGNATURE_HOOK
        | riskctl::MEMFD_DELETED
        | riskctl::KERNEL_TAMPER
        | riskctl::XPOSED
        | riskctl::ROOT_TOOL
        | riskctl::BOOT_UNLOCKED
        | riskctl::ADBD_RUNNING;
    assert_eq!(
        tripwire(flags),
        riskctl::EXEC_INTEGRITY
            | riskctl::SIGNATURE_HOOK
            | riskctl::MEMFD_DELETED
            | riskctl::KERNEL_TAMPER
    );
}

#[test]
fn a_report_collects_the_flags_of_its_checks_and_trips_on_them() {
    let rooted = Report::new(vec![
        root(&["/system/bin/su"]),
        bootloader(Some("unlocked"), None, None),
        adbd(Some("running")),
    ]);
    assert_eq!(rooted.tripwire, 0);
    assert_ne!(rooted.flags, 0);
    let traced = Report::new(vec![
        root(&["/system/bin/su"]),
        debugger(Some("TracerPid:\t7\n")),
        maps_memory(Some("7c00-7d00 rwxp 0 00:00 0 [anon:x]\n")),
    ]);
    assert_ne!(traced.tripwire & riskctl::DEBUGGER, 0);
    assert_ne!(traced.tripwire & riskctl::WX_MAPPING, 0);
    // Root is the state of the device and stays out of the wire even when it trips.
    assert_eq!(traced.tripwire & riskctl::SU_BINARY, 0);
}

// ---- the checks that were there before

#[test]
fn a_tracer_pid_is_an_alert_and_zero_is_fine() {
    let clean = "Name:\tapp\nTracerPid:\t0\nUid:\t10353\n";
    assert_eq!(debugger(Some(clean)).level, Level::Ok);
    assert_eq!(debugger(Some(clean)).flags, 0);
    let traced = debugger(Some("Name:\tapp\nTracerPid:\t4242\n"));
    assert_eq!(traced.level, Level::Alert);
    assert!(traced.detail.contains("4242"));
    assert_eq!(traced.flags, riskctl::DEBUGGER | riskctl::GDB_PTRACE);
    assert_eq!(debugger(Some("Name:\tapp\n")).level, Level::Unknown);
    assert_eq!(debugger(None).level, Level::Unknown);
}

#[test]
fn a_linker_injection_in_the_environment_is_an_alert() {
    assert_eq!(injection(None, None).level, Level::Ok);
    assert_eq!(injection(Some(""), Some("  ")).level, Level::Ok);
    assert_eq!(
        injection(Some("/data/local/tmp/x.so"), None).detail,
        "LD_PRELOAD is set"
    );
    assert_eq!(injection(None, Some("/x.so")).detail, "LD_AUDIT is set");
    let both = injection(Some("/a.so"), Some("/b.so"));
    assert_eq!(both.level, Level::Alert);
    assert_eq!(both.flags, riskctl::LINKER_ENV);
    // The value names a path of the process, so it is not repeated in the detail.
    assert!(!both.detail.contains("/a.so"));
}

#[test]
fn frida_is_found_in_the_map_the_threads_or_the_descriptors() {
    let none = frida(
        Some(CLEAN_MAPS),
        &strings(&["main", "RenderThread"]),
        &strings(&["/dev/null"]),
    );
    assert_eq!(none.level, Level::Ok);
    assert_eq!(none.flags, 0);

    let in_map = frida(
        Some("7c00000000-7c00001000 r-xp 0 fe:4f 1 /data/local/tmp/re.frida.server/frida-agent-64.so\n"),
        &[],
        &[],
    );
    assert_eq!(in_map.level, Level::Alert);
    assert!(in_map.detail.contains("memory map"));
    assert!(!in_map.detail.contains("/data/local/tmp"));
    assert_ne!(in_map.flags & riskctl::FRIDA, 0);
    assert_ne!(in_map.flags & riskctl::PROC_MAPS, 0);

    let in_threads = frida(Some(CLEAN_MAPS), &strings(&["gum-js-loop"]), &[]);
    assert!(in_threads.detail.contains("thread names"));
    assert_eq!(in_threads.flags, riskctl::FRIDA);
    let in_descriptors = frida(
        Some(CLEAN_MAPS),
        &[],
        &strings(&["/data/local/tmp/FRIDA-pipe"]),
    );
    assert!(in_descriptors.detail.contains("file descriptors"));
    let several = frida(
        Some("... frida-gadget ..."),
        &strings(&["gum-js-loop"]),
        &[],
    );
    assert!(several.detail.contains("memory map") && several.detail.contains("thread names"));
}

#[test]
fn frida_cannot_be_judged_when_nothing_could_be_read() {
    assert_eq!(frida(None, &[], &[]).level, Level::Unknown);
    assert_eq!(frida(Some(""), &[], &[]).level, Level::Ok);
}

#[test]
fn a_debug_server_or_a_memory_dump_descriptor_is_an_alert() {
    assert_eq!(descriptors(&[]).level, Level::Unknown);
    assert_eq!(
        descriptors(&strings(&[
            "/dev/null",
            "socket:[123]",
            "anon_inode:[eventfd]"
        ]))
        .level,
        Level::Ok
    );
    let server = descriptors(&strings(&["/data/local/tmp/gdbserver"]));
    assert_eq!(server.level, Level::Alert);
    assert_ne!(server.flags & riskctl::FD_SUSPICIOUS, 0);
    assert_ne!(server.flags & riskctl::GDB_PTRACE, 0);
    let dump = descriptors(&strings(&["/proc/self/mem"]));
    assert_ne!(dump.flags & riskctl::DUMP_SURFACE, 0);
    assert_eq!(
        descriptors(&strings(&["/sdcard/memdump.bin"])).level,
        Level::Alert
    );
    assert_eq!(
        descriptors(&strings(&["/proc/self/memory-notes"])).level,
        Level::Ok
    );
    let both = descriptors(&strings(&["/x/gdbserver", "/proc/self/mem"]));
    assert!(both.detail.contains("a debug server") && both.detail.contains("a memory dump"));
}

#[test]
fn su_is_reported_and_not_held_against_the_device() {
    assert_eq!(root(&[]).level, Level::Ok);
    let found = root(&["/system/bin/su", "/sbin/su"]);
    assert_eq!(found.level, Level::Info);
    assert_eq!(found.flags, riskctl::SU_BINARY);
    assert!(found.detail.contains("/system/bin/su") && found.detail.contains("/sbin/su"));
}

#[test]
fn the_boot_state_tells_locked_from_unlocked() {
    assert_eq!(
        bootloader(Some("locked"), Some("1"), Some("green")).level,
        Level::Ok
    );
    assert_eq!(bootloader(Some("unlocked"), None, None).level, Level::Info);
    assert_eq!(
        bootloader(Some("unlocked"), None, None).flags,
        riskctl::BOOT_UNLOCKED
    );
    assert_eq!(bootloader(None, Some("0"), None).level, Level::Info);
    assert_eq!(bootloader(None, None, Some("ORANGE")).level, Level::Info);
    assert_eq!(
        bootloader(Some("locked"), Some("1"), Some("yellow")).level,
        Level::Info
    );
    assert_eq!(
        bootloader(Some("locked"), Some("1"), Some("red")).level,
        Level::Warn
    );
    assert_eq!(bootloader(None, None, None).level, Level::Unknown);
    assert_eq!(
        bootloader(Some("unlocked"), Some("0"), Some("red")).level,
        Level::Warn
    );
}

#[test]
fn a_permissive_selinux_weakens_the_device() {
    assert_eq!(selinux(Some("1\n")).level, Level::Ok);
    let permissive = selinux(Some("0"));
    assert_eq!(permissive.level, Level::Warn);
    assert_eq!(
        permissive.flags,
        riskctl::SELINUX_PERMISSIVE | riskctl::KERNEL_TAMPER
    );
    assert_eq!(selinux(Some("7")).level, Level::Unknown);
    assert_eq!(selinux(None).level, Level::Unknown);
}

#[test]
fn the_build_properties_tell_a_release_from_a_debuggable_build() {
    assert_eq!(
        build(Some("release-keys"), Some("0"), Some("1")).level,
        Level::Ok
    );
    let test_keys = build(Some("test-keys"), Some("0"), Some("1"));
    assert_eq!(
        (test_keys.level, test_keys.flags),
        (Level::Info, riskctl::TEST_KEYS)
    );
    assert_eq!(
        build(Some("release-keys"), Some("1"), Some("1")).level,
        Level::Warn
    );
    assert_eq!(
        build(Some("release-keys"), Some("0"), Some("0")).flags,
        riskctl::DEBUGGABLE_BUILD
    );
    let both = build(Some("test-keys"), Some("1"), Some("0"));
    assert_eq!(both.level, Level::Warn);
    assert!(both.detail.contains("debuggable") && both.detail.contains("ro.secure"));
    assert_eq!(build(None, None, None).level, Level::Unknown);
}

#[test]
fn an_emulator_or_a_native_bridge_is_reported() {
    assert_eq!(emulator(Some("1"), None, None).level, Level::Info);
    assert_eq!(emulator(None, Some("ranchu"), None).level, Level::Info);
    assert_eq!(emulator(None, Some("goldfish"), None).level, Level::Info);
    let bridge = emulator(Some("0"), Some("qcom"), Some("libhoudini.so"));
    assert_eq!(bridge.detail, "a native bridge translates code");
    assert_eq!(bridge.flags, riskctl::NATIVE_BRIDGE);
    assert_eq!(
        emulator(Some("0"), Some("qcom"), Some("0")).level,
        Level::Ok
    );
    assert_eq!(
        emulator(Some("0"), Some("qcom"), Some("false")).level,
        Level::Ok
    );
    assert_eq!(emulator(None, None, None).level, Level::Unknown);
}

#[test]
fn adbd_running_is_reported() {
    let running = adbd(Some("running"));
    assert_eq!(
        (running.level, running.flags),
        (Level::Info, riskctl::ADBD_RUNNING)
    );
    assert_eq!(adbd(Some("stopped")).level, Level::Ok);
    assert_eq!(adbd(None).level, Level::Unknown);
}

// ---- the signature path: the code of the library and of libc against their files

#[test]
fn code_that_differs_from_its_file_is_an_alert_with_the_hook_flags() {
    let module = signature_path(ImageResult::Mismatch, ImageResult::Match);
    assert_eq!(module.level, Level::Alert);
    assert!(module.detail.contains("the module library") && !module.detail.contains("libc"));
    assert_ne!(module.flags & riskctl::EXEC_INTEGRITY, 0);
    assert_ne!(module.flags & riskctl::SIGNATURE_HOOK, 0);
    assert_ne!(module.flags & riskctl::PROC_MAPS, 0);

    let libc = signature_path(ImageResult::Match, ImageResult::Mismatch);
    assert_eq!(libc.level, Level::Alert);
    assert_ne!(libc.flags & riskctl::HOOK_SURFACE, 0);
    assert_eq!(libc.flags & riskctl::PROC_MAPS, 0);

    let both = signature_path(ImageResult::Mismatch, ImageResult::Mismatch);
    assert!(both.detail.contains("the module library and libc"));
}

#[test]
fn matching_code_is_fine_and_unreadable_code_is_not_judged() {
    let fine = signature_path(ImageResult::Match, ImageResult::Match);
    assert_eq!((fine.level, fine.flags), (Level::Ok, 0));
    for (module, libc) in [
        (ImageResult::Match, ImageResult::Unavailable),
        (ImageResult::NotLoaded, ImageResult::Match),
        (ImageResult::Unavailable, ImageResult::NotLoaded),
    ] {
        let check = signature_path(module, libc);
        assert_eq!(check.level, Level::Unknown, "{module:?} {libc:?}");
        assert_eq!(check.flags, 0);
    }
    assert_eq!(
        signature_path(ImageResult::NotLoaded, ImageResult::Unavailable).detail,
        "module library not loaded, libc not readable"
    );
}

#[test]
fn crc32c_is_the_castagnoli_checksum() {
    assert_eq!(crc32c(b"123456789"), 0xe306_9283);
    assert_eq!(crc32c(b""), 0);
}

fn elf_with_one_executable_segment() -> Vec<u8> {
    let mut elf = vec![0u8; 128];
    elf[0..4].copy_from_slice(b"\x7fELF");
    elf[4] = 2;
    elf[5] = 1;
    elf[0x20..0x28].copy_from_slice(&64u64.to_le_bytes());
    elf[0x36..0x38].copy_from_slice(&56u16.to_le_bytes());
    elf[0x38..0x3a].copy_from_slice(&1u16.to_le_bytes());
    let header = 64;
    elf[header..header + 4].copy_from_slice(&1u32.to_le_bytes());
    elf[header + 4..header + 8].copy_from_slice(&5u32.to_le_bytes());
    elf[header + 8..header + 16].copy_from_slice(&120u64.to_le_bytes());
    elf[header + 16..header + 24].copy_from_slice(&0x1000u64.to_le_bytes());
    elf[header + 32..header + 40].copy_from_slice(&8u64.to_le_bytes());
    elf
}

#[test]
fn the_executable_segments_of_an_elf_file_are_found() {
    let segments = parse_exec_segments(&elf_with_one_executable_segment()).unwrap();
    assert_eq!(segments.len(), 1);
    assert_eq!(
        (segments[0].offset, segments[0].vaddr, segments[0].filesz),
        (120, 0x1000, 8)
    );
}

#[test]
fn a_file_that_is_not_a_usable_elf_has_no_segments() {
    assert!(parse_exec_segments(
        b"not an elf at all, and long enough to hold a header......................"
    )
    .is_none());
    let mut data_only = elf_with_one_executable_segment();
    data_only[64 + 4..64 + 8].copy_from_slice(&4u32.to_le_bytes());
    assert!(parse_exec_segments(&data_only).is_none());
    let mut past_the_end = elf_with_one_executable_segment();
    past_the_end[64 + 32..64 + 40].copy_from_slice(&4096u64.to_le_bytes());
    assert!(parse_exec_segments(&past_the_end).is_none());
    let mut big_endian = elf_with_one_executable_segment();
    big_endian[5] = 2;
    assert!(parse_exec_segments(&big_endian).is_none());
    assert!(parse_exec_segments(&[]).is_none());
}

#[test]
fn memory_is_only_read_where_the_map_says_it_is_readable() {
    let maps =
        "1000-2000 r-xp 0 00:00 0 /a\n2000-3000 r--p 0 00:00 0 /a\n4000-5000 --xp 0 00:00 0 /b\n";
    assert!(readable(maps, 0x1000, 0x2000));
    // Across two adjacent readable mappings.
    assert!(readable(maps, 0x1800, 0x2800));
    // A gap, an execute-only mapping and an address outside the map are not readable.
    assert!(!readable(maps, 0x1800, 0x3800));
    assert!(!readable(maps, 0x4000, 0x4100));
    assert!(!readable(maps, 0x9000, 0x9100));
    assert!(!readable("", 0x1000, 0x1100));
}

#[test]
fn a_library_that_is_not_loaded_is_reported_as_not_loaded() {
    assert_eq!(
        check_loaded("libnot-a-real-library-name.so", Some(CLEAN_MAPS)),
        ImageResult::NotLoaded
    );
}

#[cfg(all(target_os = "linux", not(target_os = "android")))]
#[test]
fn the_c_library_of_this_machine_is_never_reported_as_modified() {
    let maps = std::fs::read_to_string("/proc/self/maps").unwrap();
    // On a normal machine its code is its file; where it cannot be read the answer is that, not a mismatch.
    let result = check_loaded("libc.so.6", Some(&maps));
    assert!(
        matches!(
            result,
            ImageResult::Match | ImageResult::Unavailable | ImageResult::NotLoaded
        ),
        "{result:?}"
    );
    assert_eq!(
        check_loaded("libc.so.6", None),
        if result == ImageResult::NotLoaded {
            ImageResult::NotLoaded
        } else {
            ImageResult::Unavailable
        }
    );
}

// ---- the memory map

#[test]
fn tools_that_name_themselves_in_the_map_are_grouped_by_what_they_are() {
    assert_eq!(maps_tools(Some(CLEAN_MAPS)).level, Level::Ok);
    assert_eq!(maps_tools(None).level, Level::Unknown);
    let xposed = maps_tools(Some(
        "7c00-7d00 r-xp 0 fe:4f 1 /data/adb/lspd/framework/liblspd.so\n",
    ));
    assert_eq!(xposed.level, Level::Info);
    assert!(xposed.detail.contains("Xposed family"));
    assert_ne!(xposed.flags & riskctl::XPOSED, 0);
    let zygisk = maps_tools(Some(
        "7c00-7d00 r-xp 0 fe:4f 1 /data/adb/modules/zygisk_lsposed/zygisk/arm64-v8a.so\n",
    ));
    assert!(zygisk.detail.contains("Riru or Zygisk"));
    let magisk = maps_tools(Some("7c00-7d00 r-xp 0 fe:4f 1 /debug_ramdisk/magisk64\n"));
    assert_ne!(magisk.flags & riskctl::MAGISK, 0);
    let kernelsu = maps_tools(Some(
        "7c00-7d00 r-xp 0 fe:4f 1 /data/adb/ksu/bin/kernelsu\n",
    ));
    assert_ne!(kernelsu.flags & riskctl::ROOT_TOOL, 0);
    let bridge = maps_tools(Some(
        "7c00-7d00 r-xp 0 fe:4f 1 /system/lib64/libhoudini.so\n",
    ));
    assert_ne!(bridge.flags & riskctl::NATIVE_BRIDGE, 0);
}

#[test]
fn another_hooking_library_is_a_warning_and_the_rest_are_notices() {
    let other = maps_tools(Some("7c00-7d00 r-xp 0 fe:4f 1 /data/app/libsandhook.so\n"));
    assert_eq!(other.level, Level::Warn);
    assert_ne!(other.flags & riskctl::OTHER_HOOK, 0);
    let mixed = maps_tools(Some(
        "a-b r-xp 0 0 0 /x/libsandhook.so\nc-d r-xp 0 0 0 /x/liblspd.so\n",
    ));
    assert_eq!(mixed.level, Level::Warn);
    assert!(
        mixed.detail.contains("Xposed family") && mixed.detail.contains("another hooking library")
    );
}

#[test]
fn the_modules_own_libraries_do_not_report_themselves() {
    let own = "7c00-7d00 r-xp 0 fe:4f 1 /data/app/x/lib/arm64/libnuke_zygisk.so\n";
    assert_eq!(maps_tools(Some(own)).level, Level::Ok);
}

#[test]
fn writable_executable_and_deleted_executable_mappings_are_noticed_but_the_jit_is_not() {
    assert_eq!(maps_memory(Some(CLEAN_MAPS)).level, Level::Ok);
    assert_eq!(maps_memory(None).level, Level::Unknown);
    let wx = maps_memory(Some("7c00-7d00 rwxp 0 00:00 0 [anon:x]\n"));
    assert_eq!(wx.level, Level::Info);
    assert_ne!(wx.flags & riskctl::WX_MAPPING, 0);
    let deleted = maps_memory(Some("7c00-7d00 r-xp 0 00:01 9 /memfd:payload (deleted)\n"));
    assert_ne!(deleted.flags & riskctl::MEMFD_DELETED, 0);
    let jit = "7c00-7d00 r-xp 0 00:01 9 /memfd:jit-cache (deleted)\n7e00-7f00 rwxp 0 0 0 [anon:dalvik-jit-code-cache]\n7f00-8000 r-xp 0 00:01 9 /memfd:doublemapper (deleted)\n";
    let result = maps_memory(Some(jit));
    assert_eq!((result.level, result.flags), (Level::Ok, 0));
    // A readable and writable mapping is not executable, so it is not noticed either.
    assert_eq!(
        maps_memory(Some("1-2 rw-p 0 00:01 9 /memfd:x (deleted)\n")).level,
        Level::Ok
    );
}

#[test]
fn the_module_library_from_a_temporary_place_or_two_paths_is_a_warning() {
    let tmp = maps_memory(Some(
        "7c00-7d00 r-xp 0 fe:4f 1 /data/local/tmp/libnuke_protocol_core.so\n",
    ));
    assert_eq!(tmp.level, Level::Warn);
    assert_ne!(tmp.flags & riskctl::NATIVE_PATH, 0);
    let two = maps_memory(Some(
        "1-2 r-xp 0 0 0 /data/app/a/lib/arm64/libnuke_protocol_core.so\n3-4 r-xp 0 0 0 /data/app/b/lib/arm64/libnuke_protocol_core.so\n",
    ));
    assert_ne!(two.flags & riskctl::MULTI_MAPPING, 0);
    // The same path mapped in several pieces is one mapping.
    let pieces = maps_memory(Some(
        "1-2 r--p 0 0 0 /data/app/a/lib/arm64/libnuke_protocol_core.so\n2-3 r-xp 0 0 0 /data/app/a/lib/arm64/libnuke_protocol_core.so\n",
    ));
    assert_eq!((pieces.level, pieces.flags), (Level::Ok, 0));
}

// ---- the network, the kernel and the mounts

const TCP_HEADER: &str = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n";

#[test]
fn something_listening_on_frida_port_is_a_warning_and_other_states_are_not() {
    let listening = format!("{TCP_HEADER}   0: 0100007F:69A2 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 12345 1\n");
    let found = network(Some(&listening), None, None);
    assert_eq!(found.level, Level::Warn);
    assert_ne!(found.flags & riskctl::NETWORK_SURFACE, 0);
    let second = format!("{TCP_HEADER}   0: 00000000:69a3 00000000:0000 0A 0 0 0 0 0 0 0\n");
    assert_eq!(network(Some(&second), None, None).level, Level::Warn);
    let connected = format!("{TCP_HEADER}   0: 0100007F:69A2 0100007F:C350 01 0 0 0 0 0 0 0\n");
    assert_eq!(network(Some(&connected), None, None).level, Level::Ok);
    let other_port = format!("{TCP_HEADER}   0: 0100007F:1F90 00000000:0000 0A 0 0 0 0 0 0 0\n");
    assert_eq!(network(Some(&other_port), None, None).level, Level::Ok);
}

#[test]
fn a_socket_named_after_frida_and_a_proxy_are_told_apart() {
    let unix = "Num RefCnt Protocol Flags Type St Inode Path\n0: 2 0 10000 1 1 555 @frida-1234\n";
    let socket = network(None, Some(unix), None);
    assert_eq!(socket.level, Level::Warn);
    assert_ne!(socket.flags & riskctl::HOOK_SURFACE, 0);
    let proxied = network(
        None,
        None,
        Some("PATH=/bin\0HTTP_PROXY=http://10.0.0.1:8080\0HOME=/"),
    );
    assert_eq!(
        (proxied.level, proxied.flags),
        (Level::Info, riskctl::PROXY)
    );
    let all = network(
        Some(&format!("{TCP_HEADER}   0: 0:69A2 0:0 0A 0 0 0 0 0 0 0\n")),
        None,
        Some("http_proxy=x"),
    );
    assert_eq!(all.level, Level::Warn);
    assert!(all.detail.contains("Frida") && all.detail.contains("proxy"));
    assert_eq!(
        network(None, None, Some("PATH=/bin\0HOME=/")).level,
        Level::Ok
    );
    assert_eq!(network(None, None, None).level, Level::Unknown);
}

#[test]
fn the_selinux_status_page_of_the_older_engines_pairs_is_normal() {
    assert_eq!(
        selinux_policy(Some(&policy(1, 4, 1, 1, 1))).level,
        Level::Ok
    );
    assert_eq!(
        selinux_policy(Some(&policy(1, 0, 1, 0, 1))).level,
        Level::Ok
    );
}

#[test]
fn the_status_page_of_a_device_with_a_patched_policy_is_a_warning() {
    // What the KernelSU phone this was developed on reports: sequence 6, no policy load counted.
    let patched = selinux_policy(Some(&policy(1, 6, 1, 0, 1)));
    assert_eq!(patched.level, Level::Warn);
    assert_eq!(patched.flags, riskctl::KERNEL_TAMPER);
    assert!(patched.detail.contains("sequence 6") && patched.detail.contains("loads 0"));
    assert_eq!(
        selinux_policy(Some(&policy(1, 4, 0, 1, 1))).level,
        Level::Warn
    );
    assert_eq!(
        selinux_policy(Some(&policy(2, 4, 1, 1, 1))).level,
        Level::Warn
    );
    assert_eq!(
        selinux_policy(Some(&policy(1, 4, 1, 1, 0))).level,
        Level::Warn
    );
}

#[test]
fn an_unreadable_or_short_status_page_is_not_judged() {
    assert_eq!(selinux_policy(None).level, Level::Unknown);
    assert_eq!(selinux_policy(Some(&[1, 0, 0])).level, Level::Unknown);
}

#[test]
fn readable_kernel_symbols_and_kcore_are_warnings() {
    let hidden = "0000000000000000 T _text\n0000000000000000 t _stext\n";
    let closed = kernel_exposure(Some(hidden), false);
    assert_eq!((closed.level, closed.flags), (Level::Ok, 0));
    assert_eq!(kernel_exposure(None, false).level, Level::Ok);
    let visible = kernel_exposure(Some("ffffffc010000000 T _text\n"), false);
    assert_eq!(visible.level, Level::Warn);
    assert_eq!(visible.flags, riskctl::KERNEL_TAMPER);
    // Data symbols with an address do not count, only code.
    assert_eq!(
        kernel_exposure(Some("ffffffc010000000 D some_data\n"), false).level,
        Level::Ok
    );
    let core = kernel_exposure(None, true);
    assert_eq!(core.level, Level::Warn);
    assert_eq!(core.flags, riskctl::KERNEL_TAMPER | riskctl::DUMP_SURFACE);
}

const MOUNTS_NORMAL: &str = "\
113 92 254:26 / /system_ext ro,relatime shared:20 - erofs /dev/block/dm-26 ro,seclabel\n\
114 92 254:27 / /product ro,relatime shared:21 - erofs /dev/block/dm-27 ro,seclabel\n\
122 92 254:28 / /vendor ro,relatime shared:29 - erofs /dev/block/dm-28 ro,seclabel\n\
130 92 253:42 / /data rw,nosuid,nodev,noatime shared:40 - f2fs /dev/block/dm-42 rw,lazytime\n";

#[test]
fn normal_mounts_are_fine_and_a_writable_data_partition_does_not_count() {
    let result = mounts(Some(MOUNTS_NORMAL));
    assert_eq!((result.level, result.flags), (Level::Ok, 0));
    assert_eq!(mounts(None).level, Level::Unknown);
}

#[test]
fn a_writable_or_overlaid_system_partition_is_a_warning() {
    let writable = mounts(Some(
        "100 92 254:1 / /system rw,relatime - ext4 /dev/block/dm-1 rw\n",
    ));
    assert_eq!(writable.level, Level::Warn);
    assert!(writable.detail.contains("writable"));
    assert_ne!(writable.flags & riskctl::KERNEL_TAMPER, 0);
    let overlay = mounts(Some(
        "100 92 0:50 / /vendor ro,relatime - overlay overlay ro,lowerdir=/vendor\n",
    ));
    assert_eq!(overlay.level, Level::Warn);
    assert!(overlay.detail.contains("overlay"));
    assert_ne!(overlay.flags & riskctl::ROOT_TOOL, 0);
    // The options of the superblock are not the options of the mount: only the mount's own count.
    assert_eq!(
        mounts(Some(
            "1 2 3:4 / /system ro,relatime - ext4 /dev/x rw,errors=remount-ro\n"
        ))
        .level,
        Level::Ok
    );
}

#[test]
fn root_tools_that_mount_something_are_noticed() {
    let ksu = mounts(Some("200 92 0:60 / /data/adb/modules rw - tmpfs KSU rw\n"));
    assert_eq!(ksu.level, Level::Info);
    assert_ne!(ksu.flags & riskctl::ROOT_TOOL, 0);
    let magisk = mounts(Some("201 92 0:61 / /sbin ro - tmpfs magisk ro\n"));
    assert_ne!(magisk.flags & riskctl::MAGISK, 0);
    let xposed = mounts(Some("202 92 0:62 / /data/adb/lspd rw - tmpfs lsposed rw\n"));
    assert_ne!(xposed.flags & riskctl::XPOSED, 0);
}

// ---- the report text

#[test]
fn encode_writes_a_check_line_per_check_and_a_flag_line_per_flag() {
    let report = Report::new(vec![
        debugger(Some("TracerPid:\t0\n")),
        maps_memory(Some("7c00-7d00 rwxp 0 00:00 0 [anon:x]\n")),
        frida(Some("a frida-agent b"), &[], &[]),
    ]);
    let text = encode(&report);
    let lines: Vec<&str> = text.lines().collect();
    assert_eq!(lines[0], "check\tdebugger\tok\tno tracer attached");
    assert!(lines.contains(&"flag\tFRIDA\thard\t1"));
    // A weak flag next to a hard one trips with it.
    assert!(lines.contains(&"flag\tWX_MAPPING\tweak\t1"));
    assert!(lines
        .iter()
        .all(|line| line.starts_with("check\t") || line.starts_with("flag\t")));
    assert_eq!(encode(&Report::new(vec![])), "");
}

#[test]
fn a_flag_that_did_not_trip_is_written_with_a_zero() {
    let report = Report::new(vec![
        root(&["/system/bin/su"]),
        maps_memory(Some("7c00-7d00 rwxp 0 00:00 0 [anon:x]\n")),
    ]);
    let text = encode(&report);
    assert!(text.contains("flag\tSU_BINARY\tenv\t0\n"));
    assert!(text.contains("flag\tWX_MAPPING\tweak\t0\n"));
    assert_eq!(report.tripwire, 0);
}

#[test]
fn a_detail_cannot_split_a_line_or_add_a_field() {
    let hostile = Check::new_for_test("frida", Level::Alert, "a\tb\nc\rd");
    let text = encode(&Report::new(vec![hostile]));
    assert_eq!(text.lines().count(), 1);
    assert_eq!(text.trim_end_matches('\n').split('\t').count(), 4);
    assert!(text.contains("a b c d"));
}

#[test]
fn a_long_detail_is_cut_to_120_characters() {
    let long = Check::new_for_test("build", Level::Warn, &"é".repeat(500));
    let text = encode(&Report::new(vec![long]));
    let detail = text.trim_end().split('\t').nth(3).unwrap();
    assert_eq!(detail.chars().count(), 120);
}

#[test]
fn a_scan_runs_every_check_once_in_a_fixed_order() {
    let report = scan();
    let ids: Vec<&str> = report.checks.iter().map(|check| check.id).collect();
    assert_eq!(
        ids,
        [
            "debugger",
            "injection",
            "frida",
            "descriptors",
            "signature_path",
            "maps_tools",
            "maps_memory",
            "network",
            "root",
            "bootloader",
            "selinux",
            "selinux_policy",
            "kernel_exposure",
            "mounts",
            "build",
            "emulator",
            "adbd",
        ]
    );
    assert!(report.checks.iter().all(|check| !check.detail.is_empty()));
    assert_eq!(report.tripwire, tripwire(report.flags));
    for line in encode(&report).lines() {
        let fields: Vec<&str> = line.split('\t').collect();
        assert_eq!(fields.len(), 4, "{line}");
        match fields[0] {
            "check" => assert!(
                ["ok", "info", "warn", "alert", "unknown"].contains(&fields[2]),
                "{line}"
            ),
            "flag" => {
                assert!(
                    ["hard", "weak", "env", "note"].contains(&fields[2]),
                    "{line}"
                );
                assert!(["0", "1"].contains(&fields[3]), "{line}");
            }
            other => panic!("unknown line kind {other}"),
        }
    }
}
