//! Script package (`.nks`) validation and extraction. A package is a ZIP archive with a `manifest.json`, a
//! single-file ES module entry and optional configuration schema, README and icon. Every rule a package must meet
//! lives here; the Kotlin side only reads the normalized manifest this module returns.

use serde_json::{json, Map, Value};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet};
use std::fs::{File, OpenOptions};
use std::io::{Read, Write};
use std::path::Path;

pub const MANIFEST_VERSION: i64 = 1;
pub const MAX_ENTRIES: usize = 64;
pub const MAX_FILE_BYTES: u64 = 8 * 1024 * 1024;
pub const MAX_TOTAL_BYTES: u64 = 16 * 1024 * 1024;
pub const MAX_ICON_BYTES: u64 = 512 * 1024;
const MAX_PATH: usize = 200;
const MAX_ORIGINS: usize = 64;
const MAX_FIELDS: usize = 64;
// Integers scripts see as JavaScript numbers must stay exact.
const MAX_SAFE_INTEGER: i64 = 9_007_199_254_740_991;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PackageError {
    pub code: &'static str,
    pub message: String,
}

impl PackageError {
    fn new(code: &'static str, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }
}

fn invalid_package(message: impl Into<String>) -> PackageError {
    PackageError::new("INVALID_PACKAGE", message)
}

fn invalid_manifest(message: impl Into<String>) -> PackageError {
    PackageError::new("INVALID_MANIFEST", message)
}

fn invalid_schema(message: impl Into<String>) -> PackageError {
    PackageError::new("INVALID_CONFIG_SCHEMA", message)
}

#[derive(Debug)]
pub struct Installed {
    /// The manifest with every optional field filled in.
    pub manifest: Value,
    /// Lowercase hex SHA-256 of the package file.
    pub sha256: String,
}

/// Validates the package at `package` and writes its files into `destination`, which must be an existing empty
/// directory. Nothing is written unless the whole package is valid. `max_api` is the highest script API level the
/// running engine implements.
pub fn install(
    package: &Path,
    destination: &Path,
    max_api: i64,
) -> Result<Installed, PackageError> {
    let mut bytes = Vec::new();
    File::open(package)
        .and_then(|file| file.take(MAX_TOTAL_BYTES + 1).read_to_end(&mut bytes))
        .map_err(|error| PackageError::new("IO", format!("Cannot read the package: {error}")))?;
    if bytes.len() as u64 > MAX_TOTAL_BYTES {
        return Err(PackageError::new(
            "TOO_LARGE",
            "The package is larger than 16 MiB",
        ));
    }
    let files = read_archive(&bytes)?;
    let manifest = validate_files(&files, max_api)?;
    let mut entries = std::fs::read_dir(destination).map_err(|error| {
        PackageError::new("IO", format!("Cannot open the destination: {error}"))
    })?;
    if entries.next().is_some() {
        return Err(PackageError::new("IO", "The destination is not empty"));
    }
    for (name, content) in &files {
        let target = destination.join(name);
        if let Some(parent) = target.parent() {
            std::fs::create_dir_all(parent).map_err(|error| {
                PackageError::new("IO", format!("Cannot create {name}: {error}"))
            })?;
        }
        OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&target)
            .and_then(|mut file| file.write_all(content).and_then(|_| file.sync_all()))
            .map_err(|error| PackageError::new("IO", format!("Cannot write {name}: {error}")))?;
    }
    Ok(Installed {
        manifest,
        sha256: hex(&Sha256::digest(&bytes)),
    })
}

/// Reads every file of the archive into memory, enforcing the path and size rules.
pub fn read_archive(bytes: &[u8]) -> Result<BTreeMap<String, Vec<u8>>, PackageError> {
    let mut archive = zip::ZipArchive::new(std::io::Cursor::new(bytes))
        .map_err(|error| invalid_package(format!("Not a ZIP archive: {error}")))?;
    if archive.len() > MAX_ENTRIES {
        return Err(invalid_package(format!("More than {MAX_ENTRIES} entries")));
    }
    let mut files = BTreeMap::new();
    let mut total = 0u64;
    for index in 0..archive.len() {
        let mut entry = archive
            .by_index(index)
            .map_err(|error| invalid_package(format!("Unreadable entry {index}: {error}")))?;
        let name = entry.name().to_owned();
        if entry.encrypted() {
            return Err(invalid_package(format!("{name} is encrypted")));
        }
        if entry
            .unix_mode()
            .is_some_and(|mode| mode & 0o170000 == 0o120000)
        {
            return Err(invalid_package(format!("{name} is a symbolic link")));
        }
        if entry.is_dir() {
            check_path(name.trim_end_matches('/'))?;
            continue;
        }
        check_path(&name)?;
        if entry.size() > MAX_FILE_BYTES {
            return Err(PackageError::new(
                "TOO_LARGE",
                format!("{name} is larger than 8 MiB"),
            ));
        }
        let mut content = Vec::new();
        (&mut entry)
            .take(MAX_FILE_BYTES + 1)
            .read_to_end(&mut content)
            .map_err(|error| invalid_package(format!("Cannot read {name}: {error}")))?;
        if content.len() as u64 > MAX_FILE_BYTES || content.len() as u64 != entry.size() {
            return Err(invalid_package(format!(
                "{name} does not match its declared size"
            )));
        }
        total += content.len() as u64;
        if total > MAX_TOTAL_BYTES {
            return Err(PackageError::new(
                "TOO_LARGE",
                "The files add up to more than 16 MiB",
            ));
        }
        if files.insert(name.clone(), content).is_some() {
            return Err(invalid_package(format!("{name} appears twice")));
        }
    }
    Ok(files)
}

/// Package paths are relative, forward-slashed and limited to a portable character set, so no entry can leave the
/// install directory or collide with another on a case-insensitive file system.
pub fn check_path(name: &str) -> Result<(), PackageError> {
    let fail = || invalid_package(format!("Invalid entry path: {name:?}"));
    if name.is_empty() || name.len() > MAX_PATH || name.starts_with('/') {
        return Err(fail());
    }
    for segment in name.split('/') {
        if segment.is_empty() || segment == "." || segment == ".." || segment.starts_with('.') {
            return Err(fail());
        }
        if !segment
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
        {
            return Err(fail());
        }
    }
    Ok(())
}

fn validate_files(files: &BTreeMap<String, Vec<u8>>, max_api: i64) -> Result<Value, PackageError> {
    let lower: BTreeSet<String> = files.keys().map(|name| name.to_ascii_lowercase()).collect();
    if lower.len() != files.len() {
        return Err(invalid_package("Two entries differ only in letter case"));
    }
    let manifest = files
        .get("manifest.json")
        .ok_or_else(|| invalid_package("manifest.json is missing"))?;
    let text = std::str::from_utf8(manifest)
        .map_err(|_| invalid_manifest("manifest.json is not UTF-8"))?;
    let names: BTreeSet<String> = files.keys().cloned().collect();
    let normalized = validate_manifest(text, max_api, Some(&names))?;
    let utf8 = |path: &str| {
        std::str::from_utf8(&files[path])
            .map_err(|_| invalid_package(format!("{path} is not UTF-8")))
    };
    utf8(normalized["entry"].as_str().unwrap_or_default())?;
    if let Some(path) = normalized["configSchema"].as_str() {
        validate_config_schema(utf8(path)?)?;
    }
    if let Some(path) = normalized["readme"].as_str() {
        utf8(path)?;
    }
    if let Some(path) = normalized["icon"].as_str() {
        if files[path].len() as u64 > MAX_ICON_BYTES
            || !files[path].starts_with(b"\x89PNG\r\n\x1a\n")
        {
            return Err(invalid_package(format!(
                "{path} must be a PNG of at most 512 KiB"
            )));
        }
    }
    Ok(normalized)
}

/// Validates a manifest and returns it normalized. With `files`, the paths it names must exist in the package and the
/// optional README.md and icon.png are picked up by their default names.
pub fn validate_manifest(
    text: &str,
    max_api: i64,
    files: Option<&BTreeSet<String>>,
) -> Result<Value, PackageError> {
    let root: Value = serde_json::from_str(text.trim_start_matches('\u{feff}'))
        .map_err(|error| invalid_manifest(format!("Not JSON: {error}")))?;
    let object = root
        .as_object()
        .ok_or_else(|| invalid_manifest("The manifest must be a JSON object"))?;
    let fields = Fields::new(object, "manifest", invalid_manifest)?;
    fields.only(&[
        "manifestVersion",
        "apiVersion",
        "id",
        "name",
        "version",
        "versionCode",
        "entry",
        "description",
        "author",
        "homepage",
        "hosts",
        "configSchema",
        "readme",
        "icon",
        "permissions",
    ])?;
    let manifest_version = fields
        .integer("manifestVersion")?
        .ok_or_else(|| invalid_manifest("manifestVersion is required"))?;
    if manifest_version != MANIFEST_VERSION {
        return Err(invalid_manifest(format!(
            "Unsupported manifestVersion {manifest_version}"
        )));
    }
    let api = fields
        .integer("apiVersion")?
        .ok_or_else(|| invalid_manifest("apiVersion is required"))?;
    if api < 1 || api > max_api {
        return Err(PackageError::new(
            "UNSUPPORTED_API",
            format!("The script needs API {api}; this module supports API 1 to {max_api}"),
        ));
    }
    let id = fields
        .text("id", 1, 128)?
        .ok_or_else(|| invalid_manifest("id is required"))?;
    if !is_dotted(&id, false) {
        return Err(invalid_manifest(
            "id must be at least two dot-separated lowercase segments, such as com.example.hello",
        ));
    }
    let name = fields
        .text("name", 1, 64)?
        .ok_or_else(|| invalid_manifest("name is required"))?;
    let version = fields
        .text("version", 1, 32)?
        .ok_or_else(|| invalid_manifest("version is required"))?;
    let version_code = fields
        .integer("versionCode")?
        .ok_or_else(|| invalid_manifest("versionCode is required"))?;
    if version_code < 1 {
        return Err(invalid_manifest("versionCode must be positive"));
    }
    let entry = fields
        .text("entry", 1, MAX_PATH)?
        .unwrap_or_else(|| "main.js".to_owned());
    if !entry.ends_with(".js") && !entry.ends_with(".mjs") {
        return Err(invalid_manifest("entry must be a .js or .mjs file"));
    }
    let config_schema = fields.text("configSchema", 1, MAX_PATH)?;
    if config_schema
        .as_deref()
        .is_some_and(|path| !path.ends_with(".json"))
    {
        return Err(invalid_manifest("configSchema must be a .json file"));
    }
    let mut readme = fields.text("readme", 1, MAX_PATH)?;
    let mut icon = fields.text("icon", 1, MAX_PATH)?;
    if icon.as_deref().is_some_and(|path| !path.ends_with(".png")) {
        return Err(invalid_manifest("icon must be a .png file"));
    }
    let homepage = fields.text("homepage", 1, 512)?;
    if homepage
        .as_deref()
        .is_some_and(|url| !url.starts_with("https://"))
    {
        return Err(invalid_manifest("homepage must be an https URL"));
    }
    let hosts = match object.get("hosts") {
        None => Vec::new(),
        Some(value) => strings(value, "hosts", 16, invalid_manifest)?
            .into_iter()
            .map(|host| {
                if is_dotted(&host, true) {
                    Ok(host)
                } else {
                    Err(invalid_manifest(format!(
                        "Invalid host package name: {host}"
                    )))
                }
            })
            .collect::<Result<_, _>>()?,
    };
    if let Some(files) = files {
        for (field, path) in [
            ("entry", Some(&entry)),
            ("configSchema", config_schema.as_ref()),
            ("readme", readme.as_ref()),
            ("icon", icon.as_ref()),
        ] {
            if let Some(path) = path {
                check_path(path).map_err(|_| {
                    invalid_manifest(format!("{field} is not a valid package path"))
                })?;
                if !files.contains(path) {
                    return Err(invalid_manifest(format!(
                        "{field} names {path}, which is not in the package"
                    )));
                }
            }
        }
        if readme.is_none() && files.contains("README.md") {
            readme = Some("README.md".to_owned());
        }
        if icon.is_none() && files.contains("icon.png") {
            icon = Some("icon.png".to_owned());
        }
    }
    let permissions = match object.get("permissions") {
        None => permissions(&Map::new())?,
        Some(Value::Object(value)) => permissions(value)?,
        Some(_) => return Err(invalid_manifest("permissions must be an object")),
    };
    Ok(json!({
        "manifestVersion": manifest_version,
        "apiVersion": api,
        "id": id,
        "name": name,
        "version": version,
        "versionCode": version_code,
        "entry": entry,
        "description": fields.text("description", 1, 2048)?,
        "author": fields.text("author", 1, 128)?,
        "homepage": homepage,
        "hosts": hosts,
        "configSchema": config_schema,
        "readme": readme,
        "icon": icon,
        "permissions": permissions,
    }))
}

fn permissions(object: &Map<String, Value>) -> Result<Value, PackageError> {
    let fields = Fields::new(object, "permissions", invalid_manifest)?;
    fields.only(&["network", "java", "hook", "filesystem", "host"])?;
    let network = match object.get("network") {
        None | Some(Value::Bool(false)) => Value::Null,
        Some(Value::Bool(true)) => json!({ "any": true, "origins": [], "privateNetwork": false }),
        Some(Value::Object(value)) => {
            let network = Fields::new(value, "permissions.network", invalid_manifest)?;
            network.only(&["origins", "privateNetwork"])?;
            let origins = match value.get("origins") {
                Some(list) => strings(
                    list,
                    "permissions.network.origins",
                    MAX_ORIGINS,
                    invalid_manifest,
                )?,
                None => {
                    return Err(invalid_manifest(
                        "permissions.network needs origins or must be true",
                    ))
                }
            };
            if origins.is_empty() {
                return Err(invalid_manifest(
                    "permissions.network.origins must not be empty",
                ));
            }
            let origins = origins
                .iter()
                .map(|origin| origin_of(origin))
                .collect::<Result<Vec<_>, _>>()?;
            json!({ "any": false, "origins": origins, "privateNetwork": network.boolean("privateNetwork")?.unwrap_or(false) })
        }
        Some(_) => {
            return Err(invalid_manifest(
                "permissions.network must be a boolean or an object",
            ))
        }
    };
    let filesystem = match object.get("filesystem") {
        None => json!({ "external": false, "host": false, "reason": null }),
        Some(Value::Object(value)) => {
            let files = Fields::new(value, "permissions.filesystem", invalid_manifest)?;
            files.only(&["external", "host", "reason"])?;
            json!({
                "external": files.boolean("external")?.unwrap_or(false),
                "host": files.boolean("host")?.unwrap_or(false),
                "reason": files.text("reason", 1, 512)?,
            })
        }
        Some(_) => return Err(invalid_manifest("permissions.filesystem must be an object")),
    };
    let host = match object.get("host") {
        None => Vec::new(),
        Some(value) => {
            let mut names = strings(value, "permissions.host", 32, invalid_manifest)?;
            for name in &names {
                if !is_dotted(name, false) {
                    return Err(invalid_manifest(format!("Invalid host permission: {name}")));
                }
            }
            names.sort();
            names.dedup();
            names
        }
    };
    Ok(json!({
        "network": network,
        "java": fields.boolean("java")?.unwrap_or(false),
        "hook": fields.boolean("hook")?.unwrap_or(false),
        "filesystem": filesystem,
        "host": host,
    }))
}

/// Accepts `scheme://host[:port]` with an optional leading `*.` wildcard and returns it in lower case.
pub fn origin_of(origin: &str) -> Result<String, PackageError> {
    let fail = || invalid_manifest(format!("Invalid network origin: {origin}"));
    let lower = origin.to_ascii_lowercase();
    let rest = lower
        .strip_prefix("https://")
        .or_else(|| lower.strip_prefix("http://"))
        .ok_or_else(fail)?;
    let (host, port) = match rest.rsplit_once(':') {
        Some((host, port)) => (host, Some(port)),
        None => (rest, None),
    };
    if let Some(port) = port {
        if port.parse::<u16>().ok().filter(|port| *port > 0).is_none() {
            return Err(fail());
        }
    }
    let bare = host.strip_prefix("*.").unwrap_or(host);
    let valid_label = |label: &str| {
        !label.is_empty()
            && label.len() <= 63
            && !label.starts_with('-')
            && !label.ends_with('-')
            && label
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
    };
    if bare.is_empty() || bare.len() > 253 || !bare.split('.').all(valid_label) {
        return Err(fail());
    }
    Ok(lower)
}

/// Validates a configuration schema: `{ "fields": [...] }`, each field describing one setting the settings page
/// renders and the script reads through nuke:config.
pub fn validate_config_schema(text: &str) -> Result<Value, PackageError> {
    let root: Value = serde_json::from_str(text.trim_start_matches('\u{feff}'))
        .map_err(|error| invalid_schema(format!("Not JSON: {error}")))?;
    let object = root
        .as_object()
        .ok_or_else(|| invalid_schema("The schema must be a JSON object"))?;
    Fields::new(object, "schema", invalid_schema)?.only(&["fields"])?;
    let list = object
        .get("fields")
        .and_then(Value::as_array)
        .ok_or_else(|| invalid_schema("fields must be an array"))?;
    if list.len() > MAX_FIELDS {
        return Err(invalid_schema(format!("More than {MAX_FIELDS} fields")));
    }
    let mut keys = BTreeSet::new();
    for (index, field) in list.iter().enumerate() {
        let object = field
            .as_object()
            .ok_or_else(|| invalid_schema(format!("fields[{index}] must be an object")))?;
        let where_ = format!("fields[{index}]");
        let fields = Fields::new(object, &where_, invalid_schema)?;
        fields.only(&[
            "key",
            "type",
            "title",
            "description",
            "default",
            "options",
            "min",
            "max",
            "maxLength",
        ])?;
        let key = fields
            .text("key", 1, 64)?
            .ok_or_else(|| invalid_schema(format!("{where_}.key is required")))?;
        let key_valid = key
            .bytes()
            .next()
            .is_some_and(|byte| byte.is_ascii_alphabetic())
            && key
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_');
        if !key_valid || !keys.insert(key.clone()) {
            return Err(invalid_schema(format!(
                "{where_}.key is invalid or repeated: {key}"
            )));
        }
        fields
            .text("title", 1, 64)?
            .ok_or_else(|| invalid_schema(format!("{where_}.title is required")))?;
        fields.text("description", 1, 512)?;
        let kind = fields
            .text("type", 1, 16)?
            .ok_or_else(|| invalid_schema(format!("{where_}.type is required")))?;
        let numeric = matches!(kind.as_str(), "number" | "integer");
        let textual = matches!(kind.as_str(), "string" | "text");
        if !numeric && !textual && !matches!(kind.as_str(), "boolean" | "select") {
            return Err(invalid_schema(format!(
                "{where_}.type {kind} is not one of string, text, number, integer, boolean, select"
            )));
        }
        let allowed_extra: &[&str] = match kind.as_str() {
            "number" | "integer" => &["min", "max"],
            "string" | "text" => &["maxLength"],
            "select" => &["options"],
            _ => &[],
        };
        for extra in ["options", "min", "max", "maxLength"] {
            if object.contains_key(extra) && !allowed_extra.contains(&extra) {
                return Err(invalid_schema(format!(
                    "{where_}.{extra} does not apply to type {kind}"
                )));
            }
        }
        let min = fields.number("min")?;
        let max = fields.number("max")?;
        if let (Some(min), Some(max)) = (min, max) {
            if min > max {
                return Err(invalid_schema(format!("{where_}.min is above max")));
            }
        }
        let max_length = fields.integer("maxLength")?;
        if max_length.is_some_and(|length| !(1..=10_000).contains(&length)) {
            return Err(invalid_schema(format!(
                "{where_}.maxLength must be between 1 and 10000"
            )));
        }
        let options: Vec<String> = if kind == "select" {
            let list = object
                .get("options")
                .and_then(Value::as_array)
                .filter(|list| !list.is_empty() && list.len() <= 64)
                .ok_or_else(|| {
                    invalid_schema(format!("{where_}.options must list 1 to 64 choices"))
                })?;
            let mut values = Vec::new();
            for (choice_index, choice) in list.iter().enumerate() {
                let choice = choice.as_object().ok_or_else(|| {
                    invalid_schema(format!(
                        "{where_}.options[{choice_index}] must be an object"
                    ))
                })?;
                let choice_fields = Fields::new(
                    choice,
                    &format!("{where_}.options[{choice_index}]"),
                    invalid_schema,
                )?;
                choice_fields.only(&["value", "label"])?;
                let value = choice_fields.text("value", 1, 128)?.ok_or_else(|| {
                    invalid_schema(format!(
                        "{where_}.options[{choice_index}].value is required"
                    ))
                })?;
                choice_fields.text("label", 1, 64)?.ok_or_else(|| {
                    invalid_schema(format!(
                        "{where_}.options[{choice_index}].label is required"
                    ))
                })?;
                if values.contains(&value) {
                    return Err(invalid_schema(format!("{where_}.options repeats {value}")));
                }
                values.push(value);
            }
            values
        } else {
            Vec::new()
        };
        if let Some(default) = object.get("default") {
            let fits = match kind.as_str() {
                "boolean" => default.is_boolean(),
                "string" | "text" => default.as_str().is_some_and(|text| {
                    max_length.is_none_or(|length| text.chars().count() as i64 <= length)
                }),
                "select" => default
                    .as_str()
                    .is_some_and(|value| options.iter().any(|option| option == value)),
                _ => {
                    let number = if kind == "integer" {
                        default.as_i64().map(|value| value as f64)
                    } else {
                        default.as_f64()
                    };
                    number.is_some_and(|number| {
                        min.is_none_or(|min| number >= min) && max.is_none_or(|max| number <= max)
                    })
                }
            };
            if !fits {
                return Err(invalid_schema(format!(
                    "{where_}.default does not fit the field"
                )));
            }
        }
    }
    Ok(root)
}

// Field access that rejects wrong types instead of treating them as absent.
struct Fields<'a> {
    object: &'a Map<String, Value>,
    place: String,
    error: fn(String) -> PackageError,
}

impl<'a> Fields<'a> {
    fn new(
        object: &'a Map<String, Value>,
        place: &str,
        error: fn(String) -> PackageError,
    ) -> Result<Self, PackageError> {
        Ok(Self {
            object,
            place: place.to_owned(),
            error,
        })
    }

    fn fail(&self, message: String) -> PackageError {
        (self.error)(message)
    }

    fn only(&self, allowed: &[&str]) -> Result<(), PackageError> {
        let unknown: Vec<&String> = self
            .object
            .keys()
            .filter(|key| !allowed.contains(&key.as_str()))
            .collect();
        if unknown.is_empty() {
            Ok(())
        } else {
            Err(self.fail(format!("{} has unknown fields: {unknown:?}", self.place)))
        }
    }

    fn text(&self, key: &str, min: usize, max: usize) -> Result<Option<String>, PackageError> {
        match self.object.get(key) {
            None | Some(Value::Null) => Ok(None),
            Some(Value::String(text)) => {
                let length = text.trim().chars().count();
                if length < min || text.chars().count() > max {
                    Err(self.fail(format!(
                        "{}.{key} must be {min} to {max} characters",
                        self.place
                    )))
                } else {
                    Ok(Some(text.clone()))
                }
            }
            Some(_) => Err(self.fail(format!("{}.{key} must be a string", self.place))),
        }
    }

    fn integer(&self, key: &str) -> Result<Option<i64>, PackageError> {
        match self.object.get(key) {
            None | Some(Value::Null) => Ok(None),
            Some(value) => value
                .as_i64()
                .filter(|number| number.abs() <= MAX_SAFE_INTEGER)
                .map(Some)
                .ok_or_else(|| self.fail(format!("{}.{key} must be a safe integer", self.place))),
        }
    }

    fn number(&self, key: &str) -> Result<Option<f64>, PackageError> {
        match self.object.get(key) {
            None | Some(Value::Null) => Ok(None),
            Some(value) => value
                .as_f64()
                .map(Some)
                .ok_or_else(|| self.fail(format!("{}.{key} must be a number", self.place))),
        }
    }

    fn boolean(&self, key: &str) -> Result<Option<bool>, PackageError> {
        match self.object.get(key) {
            None | Some(Value::Null) => Ok(None),
            Some(Value::Bool(value)) => Ok(Some(*value)),
            Some(_) => Err(self.fail(format!("{}.{key} must be a boolean", self.place))),
        }
    }
}

fn strings(
    value: &Value,
    place: &str,
    max: usize,
    error: fn(String) -> PackageError,
) -> Result<Vec<String>, PackageError> {
    let list = value
        .as_array()
        .filter(|list| list.len() <= max)
        .ok_or_else(|| error(format!("{place} must be an array of at most {max} strings")))?;
    list.iter()
        .map(|item| {
            item.as_str()
                .filter(|text| !text.is_empty() && text.len() <= 253)
                .map(str::to_owned)
                .ok_or_else(|| error(format!("{place} must contain non-empty strings")))
        })
        .collect()
}

// Lowercase dot-separated segments starting with a letter; package names may also use underscores and upper case.
fn is_dotted(text: &str, package: bool) -> bool {
    let segments: Vec<&str> = text.split('.').collect();
    segments.len() >= 2
        && segments.iter().all(|segment| {
            let mut bytes = segment.bytes();
            bytes.next().is_some_and(|first| {
                first.is_ascii_alphabetic() && (package || first.is_ascii_lowercase())
            }) && bytes.all(|byte| {
                if package {
                    byte.is_ascii_alphanumeric() || byte == b'_'
                } else {
                    byte.is_ascii_lowercase()
                        || byte.is_ascii_digit()
                        || byte == b'_'
                        || byte == b'-'
                }
            })
        })
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}
