use serde::Serialize;
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet};
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::os::fd::{FromRawFd, RawFd};
use std::os::unix::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};
use zip::{CompressionMethod, ZipArchive};

pub const MAX_APK_BYTES: u64 = 1024 * 1024 * 1024;
const MAX_ENTRY_BYTES: u64 = MAX_APK_BYTES;
const MAX_EXPANDED_BYTES: u64 = 2 * MAX_APK_BYTES;
const MAX_ENTRIES: usize = 65_534;
const MAX_CONFIG_BYTES: u64 = 64 * 1024;
const CONFIG: &str = "assets/lspatch/config.json";
const ORIGIN: &str = "assets/lspatch/origin.apk";
const MODULE: &str = "assets/lspatch/modules/io.github.nuke.oss.apk";
const LOADER: &str = "assets/lspatch/loader.dex";
const ABIS: [&str; 4] = ["armeabi-v7a", "arm64-v8a", "x86", "x86_64"];

#[derive(Debug, Serialize, Clone)]
pub struct PatchError {
    pub code: &'static str,
    pub message: String,
}

impl PatchError {
    pub(crate) fn new(code: &'static str, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }
}

impl std::fmt::Display for PatchError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(formatter, "{}: {}", self.code, self.message)
    }
}

impl std::error::Error for PatchError {}

type Result<T> = std::result::Result<T, PatchError>;

#[derive(Debug, Serialize, Clone)]
pub struct ApkReport {
    pub bytes: u64,
    pub sha256: String,
    pub dex_count: usize,
    pub native_abis: Vec<String>,
    pub already_patched: bool,
}

struct Entry {
    bytes: u64,
    sha256: String,
    header: Vec<u8>,
}

struct Inspection {
    report: ApkReport,
    entries: BTreeMap<String, Entry>,
    config: Option<Vec<u8>>,
}

fn io_error(error: std::io::Error) -> PatchError {
    PatchError::new("io", error.to_string())
}

fn zip_error(error: zip::result::ZipError) -> PatchError {
    PatchError::new("invalid_zip", error.to_string())
}

fn hex(bytes: impl AsRef<[u8]>) -> String {
    bytes
        .as_ref()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}

fn stream_digest(reader: &mut impl Read, limit: u64, capture: usize) -> Result<(Entry, Vec<u8>)> {
    let mut buffer = [0u8; 64 * 1024];
    let mut bytes = 0u64;
    let mut digest = Sha256::new();
    let mut prefix = Vec::new();
    loop {
        let count = reader.read(&mut buffer).map_err(io_error)?;
        if count == 0 {
            break;
        }
        bytes = bytes
            .checked_add(count as u64)
            .ok_or_else(|| PatchError::new("size_limit", "Byte count overflow"))?;
        if bytes > limit {
            return Err(PatchError::new(
                "size_limit",
                "File exceeds the configured size limit",
            ));
        }
        digest.update(&buffer[..count]);
        let remaining = capture.saturating_sub(prefix.len());
        prefix.extend_from_slice(&buffer[..count.min(remaining)]);
    }
    Ok((
        Entry {
            bytes,
            sha256: hex(digest.finalize()),
            header: prefix.clone(),
        },
        prefix,
    ))
}

fn read_u16(bytes: &[u8], offset: usize) -> u16 {
    u16::from_le_bytes([bytes[offset], bytes[offset + 1]])
}

fn read_u32(bytes: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes([
        bytes[offset],
        bytes[offset + 1],
        bytes[offset + 2],
        bytes[offset + 3],
    ])
}

// Check bounded EOCD metadata before the ZIP library allocates its entry index.
// APK signing blocks and LSPatch's aliased data regions are legal and retained.
fn preflight_zip(file: &mut File, bytes: u64) -> Result<usize> {
    if bytes < 22 {
        return Err(PatchError::new("invalid_zip", "ZIP end record is missing"));
    }
    let mut magic = [0u8; 4];
    file.seek(SeekFrom::Start(0)).map_err(io_error)?;
    file.read_exact(&mut magic).map_err(io_error)?;
    if magic != *b"PK\x03\x04" {
        return Err(PatchError::new(
            "invalid_zip",
            "APK must start with a ZIP local header",
        ));
    }
    let tail_len = bytes.min(65_535 + 22) as usize;
    file.seek(SeekFrom::End(-(tail_len as i64)))
        .map_err(io_error)?;
    let mut tail = vec![0u8; tail_len];
    file.read_exact(&mut tail).map_err(io_error)?;
    let end = (0..=tail_len - 22)
        .rev()
        .find(|&offset| {
            tail[offset..offset + 4] == *b"PK\x05\x06"
                && offset + 22 + usize::from(read_u16(&tail, offset + 20)) == tail_len
        })
        .ok_or_else(|| PatchError::new("invalid_zip", "ZIP end record is invalid"))?;
    let disk = read_u16(&tail, end + 4);
    let directory_disk = read_u16(&tail, end + 6);
    let disk_count = read_u16(&tail, end + 8);
    let count = usize::from(read_u16(&tail, end + 10));
    let directory_size = u64::from(read_u32(&tail, end + 12));
    let directory_start = u64::from(read_u32(&tail, end + 16));
    if disk != 0 || directory_disk != 0 || usize::from(disk_count) != count {
        return Err(PatchError::new(
            "unsupported_zip",
            "Multi-volume APK archives are unsupported",
        ));
    }
    if count == 0
        || count > MAX_ENTRIES
        || directory_size == u64::from(u32::MAX)
        || directory_start == u64::from(u32::MAX)
    {
        return Err(PatchError::new(
            "size_limit",
            "Empty, ZIP64, or excessive-entry archives are unsupported",
        ));
    }
    let expected_end = bytes - tail_len as u64 + end as u64;
    if directory_start.checked_add(directory_size) != Some(expected_end) {
        return Err(PatchError::new(
            "invalid_zip",
            "Central directory lies outside its declared bounds",
        ));
    }
    file.seek(SeekFrom::Start(0)).map_err(io_error)?;
    Ok(count)
}

fn valid_name(name: &str) -> bool {
    !name.is_empty()
        && !name.starts_with('/')
        && !name.contains(['\\', '\0', ':'])
        && name
            .strip_suffix('/')
            .unwrap_or(name)
            .split('/')
            .all(|part| !part.is_empty() && part != "." && part != "..")
}

fn is_dex_name(name: &str) -> bool {
    if name == "classes.dex" {
        return true;
    }
    name.strip_prefix("classes")
        .and_then(|value| value.strip_suffix(".dex"))
        .is_some_and(|number| {
            !number.starts_with('0') && number.parse::<usize>().is_ok_and(|value| value >= 2)
        })
}

fn check_dex(entry: &Entry) -> Result<()> {
    let header = &entry.header;
    let valid = header.len() >= 112
        && &header[..4] == b"dex\n"
        && header[4..7].iter().all(u8::is_ascii_digit)
        && header[7] == 0
        && read_u32(header, 32) as u64 == entry.bytes
        && read_u32(header, 36) == 112
        && read_u32(header, 40) == 0x1234_5678;
    if !valid {
        return Err(PatchError::new(
            "invalid_dex",
            "DEX header, size, or endian tag is invalid",
        ));
    }
    Ok(())
}

fn inspect(path: &Path) -> Result<Inspection> {
    let mut file = File::open(path).map_err(io_error)?;
    let metadata = file.metadata().map_err(io_error)?;
    if !metadata.is_file() || metadata.len() > MAX_APK_BYTES {
        return Err(PatchError::new(
            "size_limit",
            "APK must be a regular file no larger than 1 GiB",
        ));
    }
    let (digest, _) = stream_digest(&mut file, MAX_APK_BYTES, 0)?;
    if digest.bytes != metadata.len() {
        return Err(PatchError::new(
            "file_changed",
            "APK changed during verification",
        ));
    }
    let expected_count = preflight_zip(&mut file, digest.bytes)?;
    let mut archive = ZipArchive::new(file).map_err(zip_error)?;
    let data_end = archive.central_directory_start();
    // zip-rs indexes by name; a smaller index also detects duplicates hidden by that map.
    if archive.len() != expected_count {
        return Err(PatchError::new(
            "duplicate_entry",
            "ZIP contains duplicate or inconsistent entry names",
        ));
    }
    let mut entries = BTreeMap::new();
    let mut native_abis = BTreeSet::new();
    let mut config = None;
    let mut total = 0u64;
    let mut dex_count = 0;
    let mut already_patched = false;
    for index in 0..archive.len() {
        let mut file = archive.by_index(index).map_err(zip_error)?;
        let name = file.name().to_owned();
        if !valid_name(&name)
            || file.enclosed_name().is_none()
            || std::str::from_utf8(file.name_raw()) != Ok(name.as_str())
        {
            return Err(PatchError::new(
                "unsafe_entry",
                "ZIP contains an unsafe entry path",
            ));
        }
        if file.is_symlink()
            || file.encrypted()
            || !matches!(
                file.compression(),
                CompressionMethod::Stored | CompressionMethod::Deflated
            )
        {
            return Err(PatchError::new(
                "unsupported_zip",
                "APK has a link, encrypted, or unsupported entry",
            ));
        }
        if entries.contains_key(&name) {
            return Err(PatchError::new(
                "duplicate_entry",
                "ZIP contains duplicate entry names",
            ));
        }
        let declared_size = file.size();
        if file
            .data_start()
            .checked_add(file.compressed_size())
            .is_none_or(|end| end > data_end)
        {
            return Err(PatchError::new(
                "invalid_zip",
                "ZIP entry data extends beyond the central directory",
            ));
        }
        total = total
            .checked_add(declared_size)
            .ok_or_else(|| PatchError::new("size_limit", "Expanded byte count overflow"))?;
        if declared_size > MAX_ENTRY_BYTES || total > MAX_EXPANDED_BYTES {
            return Err(PatchError::new(
                "size_limit",
                "APK expanded contents exceed the configured limits",
            ));
        }
        let is_config = name == CONFIG;
        if is_config && declared_size > MAX_CONFIG_BYTES {
            return Err(PatchError::new(
                "size_limit",
                "LSPatch configuration is too large",
            ));
        }
        let capture = if is_config {
            declared_size as usize
        } else {
            112
        };
        let (entry, captured) = stream_digest(&mut file, declared_size, capture)?;
        if entry.bytes != declared_size {
            return Err(PatchError::new(
                "invalid_zip",
                "ZIP entry size does not match its contents",
            ));
        }
        if name == "AndroidManifest.xml" && entry.bytes == 0 {
            return Err(PatchError::new(
                "invalid_apk",
                "AndroidManifest.xml is empty",
            ));
        }
        if is_dex_name(&name) {
            check_dex(&entry)?;
            dex_count += 1;
        }
        if let Some(abi) = name
            .strip_prefix("lib/")
            .and_then(|value| value.split('/').next())
        {
            if !file.is_dir() {
                native_abis.insert(abi.to_owned());
            }
        }
        // Managers carry loader resources too; only generated patch markers identify an input patch.
        if name == ORIGIN || name == CONFIG {
            already_patched = true;
        }
        if is_config {
            config = Some(captured);
        }
        entries.insert(name, entry);
    }
    if !entries.contains_key("AndroidManifest.xml") || !entries.contains_key("classes.dex") {
        return Err(PatchError::new(
            "invalid_apk",
            "APK requires AndroidManifest.xml and classes.dex",
        ));
    }
    Ok(Inspection {
        report: ApkReport {
            bytes: digest.bytes,
            sha256: digest.sha256,
            dex_count,
            native_abis: native_abis.into_iter().collect(),
            already_patched,
        },
        entries,
        config,
    })
}

pub fn inspect_apk(path: &Path) -> Result<ApkReport> {
    inspect(path).map(|value| value.report)
}

struct PendingFile {
    path: PathBuf,
    keep: bool,
}

impl Drop for PendingFile {
    fn drop(&mut self) {
        if !self.keep {
            let _ = fs::remove_file(&self.path);
        }
    }
}

// The caller retains the original descriptor. Only a CLOEXEC duplicate is owned here.
pub fn stage_apk(fd: RawFd, destination: &Path) -> Result<ApkReport> {
    stage_with_limit(fd, destination, MAX_APK_BYTES)
}

fn stage_with_limit(fd: RawFd, destination: &Path, limit: u64) -> Result<ApkReport> {
    // SAFETY: fcntl accepts any integer fd; invalid descriptors fail without dereferencing memory.
    let duplicate = unsafe { libc::fcntl(fd, libc::F_DUPFD_CLOEXEC, 0) };
    if duplicate < 0 {
        return Err(io_error(std::io::Error::last_os_error()));
    }
    // SAFETY: fcntl returned a new, valid descriptor owned solely by this File.
    let mut source = unsafe { File::from_raw_fd(duplicate) };
    let mut output = OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(destination)
        .map_err(io_error)?;
    let mut pending = PendingFile {
        path: destination.to_owned(),
        keep: false,
    };
    let mut buffer = [0u8; 64 * 1024];
    let mut bytes = 0u64;
    let mut digest = Sha256::new();
    loop {
        let count = source.read(&mut buffer).map_err(io_error)?;
        if count == 0 {
            break;
        }
        bytes = bytes
            .checked_add(count as u64)
            .ok_or_else(|| PatchError::new("size_limit", "Byte count overflow"))?;
        if bytes > limit {
            return Err(PatchError::new("size_limit", "Selected APK exceeds 1 GiB"));
        }
        output.write_all(&buffer[..count]).map_err(io_error)?;
        digest.update(&buffer[..count]);
    }
    output.sync_all().map_err(io_error)?;
    drop(output);
    let report = inspect_apk(destination)?;
    if report.already_patched {
        return Err(PatchError::new(
            "already_patched",
            "Selected APK already contains LSPatch payloads",
        ));
    }
    if report.bytes != bytes || report.sha256 != hex(digest.finalize()) {
        return Err(PatchError::new(
            "file_changed",
            "Staged APK changed during verification",
        ));
    }
    pending.keep = true;
    Ok(report)
}

fn required_entry<'a>(inspection: &'a Inspection, name: &str) -> Result<&'a Entry> {
    inspection.entries.get(name).ok_or_else(|| {
        PatchError::new(
            "missing_payload",
            format!("Missing required payload: {name}"),
        )
    })
}

fn check_embedded(inspection: &Inspection, name: &str, report: &ApkReport) -> Result<()> {
    let embedded = required_entry(inspection, name)?;
    if embedded.bytes != report.bytes || embedded.sha256 != report.sha256 {
        return Err(PatchError::new(
            "payload_mismatch",
            format!("Embedded APK differs from selected input: {name}"),
        ));
    }
    Ok(())
}

pub fn verify_patched(original: &Path, module: &Path, patched: &Path) -> Result<ApkReport> {
    let original = inspect(original)?;
    let module = inspect(module)?;
    let patched = inspect(patched)?;
    if original.report.already_patched || module.report.already_patched {
        return Err(PatchError::new(
            "already_patched",
            "Patch inputs must not contain LSPatch payloads",
        ));
    }
    check_embedded(&patched, ORIGIN, &original.report)?;
    check_embedded(&patched, MODULE, &module.report)?;
    if required_entry(&patched, "classes.dex")?.sha256
        == required_entry(&original, "classes.dex")?.sha256
    {
        return Err(PatchError::new(
            "unmodified_dex",
            "Patcher did not replace the application entry DEX",
        ));
    }
    check_dex(required_entry(&patched, LOADER)?)?;
    for abi in ABIS {
        let library = required_entry(&patched, &format!("assets/lspatch/so/{abi}/liblspatch.so"))?;
        let header = &library.header;
        let (class, machine) = match abi {
            "armeabi-v7a" => (1, 40),
            "arm64-v8a" => (2, 183),
            "x86" => (1, 3),
            "x86_64" => (2, 62),
            _ => return Err(PatchError::new("invalid_elf", "Unknown loader ABI")),
        };
        let minimum_header = if class == 1 { 52 } else { 64 };
        if header.len() < minimum_header
            || &header[..4] != b"\x7fELF"
            || header[4] != class
            || header[5] != 1
            || header[6] != 1
            || read_u16(header, 16) != 3
            || read_u16(header, 18) != machine
            || read_u32(header, 20) != 1
        {
            return Err(PatchError::new(
                "invalid_elf",
                format!("LSPatch library has invalid ELF/ABI: {abi}"),
            ));
        }
    }
    let config: serde_json::Value =
        serde_json::from_slice(patched.config.as_deref().ok_or_else(|| {
            PatchError::new("missing_payload", "LSPatch configuration is missing")
        })?)
        .map_err(|_| PatchError::new("invalid_config", "LSPatch configuration is invalid JSON"))?;
    if config
        .get("useManager")
        .and_then(serde_json::Value::as_bool)
        != Some(false)
        || config
            .get("sigBypassLevel")
            .and_then(serde_json::Value::as_u64)
            != Some(0)
        || config
            .get("injectDex")
            .is_some_and(|value| value.as_bool() != Some(false))
    {
        return Err(PatchError::new(
            "invalid_config",
            "Expected embedded module mode and disabled signature bypass",
        ));
    }
    Ok(patched.report)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::fd::AsRawFd;

    #[test]
    fn copied_input_limit_removes_partial_destination_and_preserves_fd() {
        let directory = tempfile::tempdir().unwrap();
        let source = directory.path().join("source.bin");
        let destination = directory.path().join("destination.apk");
        fs::write(&source, [1u8; 128]).unwrap();
        let file = File::open(source).unwrap();
        assert_eq!(
            stage_with_limit(file.as_raw_fd(), &destination, 64)
                .unwrap_err()
                .code,
            "size_limit"
        );
        assert!(!destination.exists());
        assert!(file.metadata().is_ok());
    }

    #[test]
    fn expanded_stream_limit_cannot_be_bypassed_by_reading_more_than_declared() {
        let mut bytes = std::io::Cursor::new([1u8; 128]);
        assert_eq!(
            stream_digest(&mut bytes, 64, 0).err().unwrap().code,
            "size_limit"
        );
    }
}
