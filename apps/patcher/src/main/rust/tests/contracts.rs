use nuke_patch_core::{inspect_apk, stage_apk, verify_patched, MAX_APK_BYTES};
use std::fs::{self, File};
use std::io::{Cursor, Read, Seek, SeekFrom, Write};
use std::os::fd::AsRawFd;
use std::path::Path;
use tempfile::TempDir;
use zip::write::SimpleFileOptions;
use zip::{CompressionMethod, ZipWriter};

fn dex(marker: u8) -> Vec<u8> {
    let mut bytes = vec![0u8; 112];
    bytes[..8].copy_from_slice(b"dex\n035\0");
    bytes[32..36].copy_from_slice(&112u32.to_le_bytes());
    bytes[36..40].copy_from_slice(&112u32.to_le_bytes());
    bytes[40..44].copy_from_slice(&0x1234_5678u32.to_le_bytes());
    bytes[111] = marker;
    bytes
}

fn elf(abi: &str) -> Vec<u8> {
    let (class, machine): (u8, u16) = match abi {
        "armeabi-v7a" => (1, 40),
        "arm64-v8a" => (2, 183),
        "x86" => (1, 3),
        "x86_64" => (2, 62),
        _ => unreachable!(),
    };
    let mut bytes = vec![0u8; 64];
    bytes[..4].copy_from_slice(b"\x7fELF");
    bytes[4..7].copy_from_slice(&[class, 1, 1]);
    bytes[16..18].copy_from_slice(&3u16.to_le_bytes());
    bytes[18..20].copy_from_slice(&machine.to_le_bytes());
    bytes[20..24].copy_from_slice(&1u32.to_le_bytes());
    bytes
}

fn zip(entries: &[(String, Vec<u8>)]) -> Vec<u8> {
    let mut writer = ZipWriter::new(Cursor::new(Vec::new()));
    for (name, data) in entries {
        writer
            .start_file(
                name,
                SimpleFileOptions::default().compression_method(CompressionMethod::Stored),
            )
            .unwrap();
        writer.write_all(data).unwrap();
    }
    writer.finish().unwrap().into_inner()
}

fn apk(marker: u8) -> Vec<u8> {
    zip(&[
        ("AndroidManifest.xml".into(), b"test manifest".to_vec()),
        ("classes.dex".into(), dex(marker)),
    ])
}

fn write(path: &Path, bytes: &[u8]) {
    fs::write(path, bytes).unwrap();
}

struct Inputs {
    directory: TempDir,
    original: Vec<u8>,
    module: Vec<u8>,
    entries: Vec<(String, Vec<u8>)>,
}

impl Inputs {
    fn new() -> Self {
        let original = apk(1);
        let module = apk(2);
        let mut entries = vec![
            ("AndroidManifest.xml".into(), b"patched manifest".to_vec()),
            ("classes.dex".into(), dex(3)),
            ("assets/lspatch/loader.dex".into(), dex(4)),
            ("assets/lspatch/origin.apk".into(), original.clone()),
            (
                "assets/lspatch/modules/io.github.nuke.oss.apk".into(),
                module.clone(),
            ),
            (
                "assets/lspatch/config.json".into(),
                br#"{"useManager":false,"sigBypassLevel":0,"injectDex":false}"#.to_vec(),
            ),
        ];
        for abi in ["armeabi-v7a", "arm64-v8a", "x86", "x86_64"] {
            entries.push((format!("assets/lspatch/so/{abi}/liblspatch.so"), elf(abi)));
        }
        Self {
            directory: tempfile::tempdir().unwrap(),
            original,
            module,
            entries,
        }
    }

    fn replace(&mut self, name: &str, bytes: Vec<u8>) {
        self.entries
            .iter_mut()
            .find(|entry| entry.0 == name)
            .unwrap()
            .1 = bytes;
    }

    fn verify(&self) -> Result<nuke_patch_core::ApkReport, nuke_patch_core::PatchError> {
        let base = self.directory.path();
        write(&base.join("original.apk"), &self.original);
        write(&base.join("module.apk"), &self.module);
        write(&base.join("patched.apk"), &zip(&self.entries));
        verify_patched(
            &base.join("original.apk"),
            &base.join("module.apk"),
            &base.join("patched.apk"),
        )
    }
}

#[test]
fn borrowed_fd_is_not_closed_and_destination_is_verified() {
    let directory = tempfile::tempdir().unwrap();
    let source = directory.path().join("source.apk");
    let destination = directory.path().join("destination.apk");
    write(&source, &apk(1));
    let mut file = File::open(source).unwrap();
    let report = stage_apk(file.as_raw_fd(), &destination).unwrap();
    assert_eq!(report.dex_count, 1);
    assert_eq!(report.sha256.len(), 64);
    assert!(!report.already_patched);
    file.seek(SeekFrom::Start(0)).unwrap();
    let mut magic = [0u8; 4];
    file.read_exact(&mut magic).unwrap();
    assert_eq!(&magic, b"PK\x03\x04");
    assert_eq!(fs::read(destination).unwrap(), apk(1));
}

#[test]
fn stage_does_not_overwrite_existing_file() {
    let directory = tempfile::tempdir().unwrap();
    let source = directory.path().join("source.apk");
    let destination = directory.path().join("existing.apk");
    write(&source, &apk(1));
    write(&destination, b"preserve");
    let file = File::open(source).unwrap();
    assert!(stage_apk(file.as_raw_fd(), &destination).is_err());
    assert_eq!(fs::read(destination).unwrap(), b"preserve");
}

#[test]
fn invalid_fd_does_not_create_a_destination() {
    let directory = tempfile::tempdir().unwrap();
    let destination = directory.path().join("invalid.apk");
    assert!(stage_apk(-1, &destination).is_err());
    assert!(!destination.exists());
}

#[test]
fn invalid_staged_archive_is_removed() {
    let directory = tempfile::tempdir().unwrap();
    let source = directory.path().join("source.apk");
    let destination = directory.path().join("destination.apk");
    write(&source, b"not an apk");
    let file = File::open(source).unwrap();
    assert!(stage_apk(file.as_raw_fd(), &destination).is_err());
    assert!(!destination.exists());
}

#[test]
fn oversized_sparse_input_is_rejected_before_streaming() {
    let directory = tempfile::tempdir().unwrap();
    let path = directory.path().join("oversized.apk");
    File::create(&path)
        .unwrap()
        .set_len(MAX_APK_BYTES + 1)
        .unwrap();
    assert_eq!(inspect_apk(&path).unwrap_err().code, "size_limit");
}

#[test]
fn missing_manifest_or_classes_are_rejected() {
    let directory = tempfile::tempdir().unwrap();
    let path = directory.path().join("missing.apk");
    for entries in [
        vec![("classes.dex".into(), dex(1))],
        vec![("AndroidManifest.xml".into(), b"manifest".to_vec())],
    ] {
        write(&path, &zip(&entries));
        assert_eq!(inspect_apk(&path).unwrap_err().code, "invalid_apk");
    }
}

#[test]
fn unsafe_entry_names_are_rejected() {
    let directory = tempfile::tempdir().unwrap();
    let path = directory.path().join("unsafe.apk");
    for name in [
        "../outside",
        "/absolute",
        "a/../b",
        "a\\b",
        "a//b",
        "a//",
        "C:/file",
    ] {
        write(&path, &zip(&[(name.to_owned(), vec![1])]));
        assert_eq!(
            inspect_apk(&path).unwrap_err().code,
            "unsafe_entry",
            "{name}"
        );
    }
}

#[test]
fn duplicate_central_directory_names_are_rejected() {
    let directory = tempfile::tempdir().unwrap();
    let path = directory.path().join("duplicate.apk");
    let mut bytes = zip(&[("sameA".into(), vec![1]), ("sameB".into(), vec![2])]);
    for offset in 0..bytes.len() - 5 {
        if &bytes[offset..offset + 5] == b"sameB" {
            bytes[offset + 4] = b'A';
        }
    }
    write(&path, &bytes);
    assert_eq!(inspect_apk(&path).unwrap_err().code, "duplicate_entry");
}

#[test]
fn zip_crc_errors_are_not_hidden() {
    let directory = tempfile::tempdir().unwrap();
    let path = directory.path().join("crc.apk");
    let mut bytes = apk(1);
    let position = bytes
        .windows(13)
        .position(|value| value == b"test manifest")
        .unwrap();
    bytes[position] ^= 1;
    write(&path, &bytes);
    assert!(inspect_apk(&path).is_err());
}

#[test]
fn entry_declaring_an_oversized_payload_is_rejected() {
    let directory = tempfile::tempdir().unwrap();
    let path = directory.path().join("expanded.apk");
    let mut bytes = apk(1);
    let position = bytes
        .windows(4)
        .position(|value| value == b"PK\x01\x02")
        .unwrap();
    bytes[position + 24..position + 28]
        .copy_from_slice(&((MAX_APK_BYTES + 1) as u32).to_le_bytes());
    write(&path, &bytes);
    assert_eq!(inspect_apk(&path).unwrap_err().code, "size_limit");
}

#[test]
fn verified_patch_requires_embedded_apks_configuration_loader_and_all_abis() {
    let inputs = Inputs::new();
    let report = inputs.verify().unwrap();
    assert!(report.already_patched);
    assert_eq!(report.dex_count, 1);
    assert!(!report.sha256.is_empty());
}

#[test]
fn embedded_original_cannot_differ_from_selected_apk() {
    let mut inputs = Inputs::new();
    inputs.replace("assets/lspatch/origin.apk", apk(99));
    assert_eq!(inputs.verify().unwrap_err().code, "payload_mismatch");
}

#[test]
fn embedded_module_cannot_differ_from_current_module() {
    let mut inputs = Inputs::new();
    inputs.replace("assets/lspatch/modules/io.github.nuke.oss.apk", apk(99));
    assert_eq!(inputs.verify().unwrap_err().code, "payload_mismatch");
}

#[test]
fn manager_mode_signature_bypass_and_dex_injection_are_rejected() {
    for json in [
        r#"{"useManager":true,"sigBypassLevel":0}"#,
        r#"{"useManager":false,"sigBypassLevel":1}"#,
        r#"{"useManager":false,"sigBypassLevel":0,"injectDex":true}"#,
        r#"{"useManager":false}"#,
        r#"{"useManager":false,"sigBypassLevel":"0"}"#,
        "not json",
    ] {
        let mut inputs = Inputs::new();
        inputs.replace("assets/lspatch/config.json", json.as_bytes().to_vec());
        assert_eq!(
            inputs.verify().unwrap_err().code,
            "invalid_config",
            "{json}"
        );
    }
}

#[test]
fn ordinary_repacking_cannot_pass_as_a_patch() {
    let mut inputs = Inputs::new();
    inputs.replace("classes.dex", dex(1));
    assert_eq!(inputs.verify().unwrap_err().code, "unmodified_dex");
}

#[test]
fn missing_abi_and_bad_elf_are_rejected() {
    let mut inputs = Inputs::new();
    inputs
        .entries
        .retain(|entry| entry.0 != "assets/lspatch/so/x86/liblspatch.so");
    assert_eq!(inputs.verify().unwrap_err().code, "missing_payload");
    let mut inputs = Inputs::new();
    inputs.replace("assets/lspatch/so/x86/liblspatch.so", elf("arm64-v8a"));
    assert_eq!(inputs.verify().unwrap_err().code, "invalid_elf");
}

#[test]
fn corrupt_loader_dex_is_rejected() {
    let mut inputs = Inputs::new();
    inputs.replace("assets/lspatch/loader.dex", b"not dex".to_vec());
    assert_eq!(inputs.verify().unwrap_err().code, "invalid_dex");
}

#[test]
fn inspect_reports_existing_patch_but_stage_rejects_it_and_removes_output() {
    let inputs = Inputs::new();
    let path = inputs.directory.path().join("patched.apk");
    let destination = inputs.directory.path().join("restaged.apk");
    write(&path, &zip(&inputs.entries));
    assert!(inspect_apk(&path).unwrap().already_patched);
    let file = File::open(path).unwrap();
    assert_eq!(
        stage_apk(file.as_raw_fd(), &destination).unwrap_err().code,
        "already_patched"
    );
    assert!(!destination.exists());
}

#[test]
fn native_abis_and_multidex_are_reported() {
    let directory = tempfile::tempdir().unwrap();
    let path = directory.path().join("multi.apk");
    write(
        &path,
        &zip(&[
            ("AndroidManifest.xml".into(), b"manifest".to_vec()),
            ("classes.dex".into(), dex(1)),
            ("classes2.dex".into(), dex(2)),
            ("lib/arm64-v8a/libexample.so".into(), elf("arm64-v8a")),
            ("lib/x86_64/libexample.so".into(), elf("x86_64")),
        ]),
    );
    let report = inspect_apk(&path).unwrap();
    assert_eq!(report.dex_count, 2);
    assert_eq!(report.native_abis, ["arm64-v8a", "x86_64"]);
}

#[test]
fn symlink_entries_are_rejected_without_extracting_anything() {
    let directory = tempfile::tempdir().unwrap();
    let path = directory.path().join("symlink.apk");
    let mut writer = ZipWriter::new(Cursor::new(Vec::new()));
    writer
        .add_symlink("link", "outside", SimpleFileOptions::default())
        .unwrap();
    write(&path, &writer.finish().unwrap().into_inner());
    assert_eq!(inspect_apk(&path).unwrap_err().code, "unsupported_zip");
}

#[test]
fn excessive_entry_count_is_rejected_before_allocating_a_zip_index() {
    let directory = tempfile::tempdir().unwrap();
    let path = directory.path().join("count.apk");
    let mut bytes = apk(1);
    let position = bytes.len() - 22;
    bytes[position + 8..position + 10].copy_from_slice(&u16::MAX.to_le_bytes());
    bytes[position + 10..position + 12].copy_from_slice(&u16::MAX.to_le_bytes());
    write(&path, &bytes);
    assert_eq!(inspect_apk(&path).unwrap_err().code, "size_limit");
}

#[test]
fn central_directory_outside_declared_bounds_is_rejected() {
    let directory = tempfile::tempdir().unwrap();
    let path = directory.path().join("bounds.apk");
    let mut bytes = apk(1);
    let position = bytes.len() - 22;
    bytes[position + 16..position + 20].copy_from_slice(&1u32.to_le_bytes());
    write(&path, &bytes);
    assert_eq!(inspect_apk(&path).unwrap_err().code, "invalid_zip");
}

#[test]
fn malformed_main_dex_is_rejected_even_for_an_unpatched_apk() {
    let directory = tempfile::tempdir().unwrap();
    let path = directory.path().join("dex.apk");
    write(
        &path,
        &zip(&[
            ("AndroidManifest.xml".into(), b"manifest".to_vec()),
            ("classes.dex".into(), b"dex\n035\0".to_vec()),
        ]),
    );
    assert_eq!(inspect_apk(&path).unwrap_err().code, "invalid_dex");
}

#[test]
fn huge_configuration_is_rejected_before_collecting_its_contents() {
    let mut inputs = Inputs::new();
    inputs.replace("assets/lspatch/config.json", vec![b' '; 65_537]);
    assert_eq!(inputs.verify().unwrap_err().code, "size_limit");
}

#[test]
fn manager_loader_resources_are_not_mistaken_for_a_patched_apk() {
    let directory = tempfile::tempdir().unwrap();
    let source = directory.path().join("manager.apk");
    let destination = directory.path().join("staged.apk");
    write(
        &source,
        &zip(&[
            ("AndroidManifest.xml".into(), b"manager manifest".to_vec()),
            ("classes.dex".into(), dex(1)),
            ("assets/lspatch/loader.dex".into(), dex(2)),
            ("assets/lspatch/metaloader.dex".into(), dex(3)),
            (
                "assets/lspatch/so/arm64-v8a/liblspatch.so".into(),
                elf("arm64-v8a"),
            ),
        ]),
    );
    assert!(!inspect_apk(&source).unwrap().already_patched);
    let file = File::open(source).unwrap();
    assert!(
        !stage_apk(file.as_raw_fd(), &destination)
            .unwrap()
            .already_patched
    );
}

// Set these only for an externally produced real LSPatch fixture; regular tests stay hermetic.
#[test]
#[ignore = "Requires a real LSPatch output and its original/module APK paths"]
fn externally_produced_patch_matches_the_same_contract() {
    let original =
        std::env::var("NUKE_TEST_ORIGINAL_APK").expect("NUKE_TEST_ORIGINAL_APK is required");
    let module = std::env::var("NUKE_TEST_MODULE_APK").expect("NUKE_TEST_MODULE_APK is required");
    let patched =
        std::env::var("NUKE_TEST_PATCHED_APK").expect("NUKE_TEST_PATCHED_APK is required");
    let report = verify_patched(
        Path::new(&original),
        Path::new(&module),
        Path::new(&patched),
    )
    .unwrap();
    assert!(report.already_patched);
}
