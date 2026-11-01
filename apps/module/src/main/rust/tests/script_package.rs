// Contract tests of the script package reader through its public API.
use nuke_protocol_core::script::package::*;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::io::Cursor;
use std::io::Write;
use zip::write::SimpleFileOptions;

const MANIFEST: &str = r#"{"manifestVersion":1,"apiVersion":1,"id":"com.example.hello","name":"Hello","version":"1.0.0","versionCode":3}"#;

fn archive(files: &[(&str, &[u8])]) -> Vec<u8> {
    let mut writer = zip::ZipWriter::new(Cursor::new(Vec::new()));
    for (name, content) in files {
        writer
            .start_file(*name, SimpleFileOptions::default())
            .unwrap();
        writer.write_all(content).unwrap();
    }
    writer.finish().unwrap().into_inner()
}

fn manifest_error(text: &str) -> String {
    validate_manifest(text, 1, None).unwrap_err().message
}

#[test]
fn normalizes_a_minimal_manifest() {
    let manifest = validate_manifest(MANIFEST, 1, None).unwrap();
    assert_eq!(manifest["entry"], "main.js");
    assert_eq!(manifest["permissions"]["network"], Value::Null);
    assert_eq!(manifest["permissions"]["java"], false);
    assert_eq!(manifest["permissions"]["host"], json!([]));
    assert_eq!(manifest["hosts"], json!([]));
    assert_eq!(manifest["readme"], Value::Null);
}

#[test]
fn normalizes_permissions() {
    let manifest = validate_manifest(
        r#"{"manifestVersion":1,"apiVersion":1,"id":"a.b","name":"N","version":"1","versionCode":1,
           "permissions":{"network":{"origins":["HTTPS://*.Example.com","http://10.0.0.2:8080"],"privateNetwork":true},
           "java":true,"hook":true,"filesystem":{"host":true,"reason":"cache"},"host":["douyin.messaging","douyin.messaging"]}}"#,
        1,
        None,
    )
    .unwrap();
    let permissions = &manifest["permissions"];
    assert_eq!(
        permissions["network"]["origins"],
        json!(["https://*.example.com", "http://10.0.0.2:8080"])
    );
    assert_eq!(permissions["network"]["privateNetwork"], true);
    assert_eq!(
        permissions["filesystem"],
        json!({"external": false, "host": true, "reason": "cache"})
    );
    assert_eq!(permissions["host"], json!(["douyin.messaging"]));
    let any = validate_manifest(
        &MANIFEST.replace('}', r#","permissions":{"network":true}}"#),
        1,
        None,
    )
    .unwrap();
    assert_eq!(any["permissions"]["network"]["any"], true);
}

#[test]
fn rejects_bad_manifests() {
    assert!(
        manifest_error(&MANIFEST.replace("\"Hello\"", "\"Hello\",\"extra\":1"))
            .contains("unknown fields")
    );
    assert!(manifest_error(&MANIFEST.replace("com.example.hello", "hello")).contains("id must"));
    assert!(
        manifest_error(&MANIFEST.replace("com.example.hello", "com.Example.x")).contains("id must")
    );
    assert!(
        manifest_error(&MANIFEST.replace("\"versionCode\":3", "\"versionCode\":0"))
            .contains("positive")
    );
    assert!(
        manifest_error(&MANIFEST.replace("\"versionCode\":3", "\"versionCode\":\"3\""))
            .contains("safe integer")
    );
    assert!(
        manifest_error(&MANIFEST.replace("\"manifestVersion\":1", "\"manifestVersion\":2"))
            .contains("manifestVersion")
    );
    assert!(manifest_error("[]").contains("JSON object"));
    assert!(manifest_error(&MANIFEST.replace(
        '}',
        r#","permissions":{"network":{"origins":["ftp://x.com"]}}}"#
    ))
    .contains("origin"));
    assert!(manifest_error(&MANIFEST.replace(
        '}',
        r#","permissions":{"network":{"origins":["https://x.com/path"]}}}"#
    ))
    .contains("origin"));
    assert!(
        manifest_error(&MANIFEST.replace('}', r#","permissions":{"java":"yes"}}"#))
            .contains("boolean")
    );
    assert!(
        manifest_error(&MANIFEST.replace('}', r#","hosts":["not a package"]}"#)).contains("host")
    );
    assert!(manifest_error(&MANIFEST.replace('}', r#","entry":"main.ts"}"#)).contains("entry"));
    let api = validate_manifest(
        &MANIFEST.replace("\"apiVersion\":1", "\"apiVersion\":2"),
        1,
        None,
    )
    .unwrap_err();
    assert_eq!(api.code, "UNSUPPORTED_API");
}

#[test]
fn validates_config_schemas() {
    let schema = r#"{"fields":[
        {"key":"greeting","type":"string","title":"Greeting","default":"hi","maxLength":10},
        {"key":"count","type":"integer","title":"Count","min":1,"max":5,"default":3},
        {"key":"mode","type":"select","title":"Mode","options":[{"value":"a","label":"A"}],"default":"a"},
        {"key":"on","type":"boolean","title":"On","default":true}]}"#;
    assert!(validate_config_schema(schema).is_ok());
    for bad in [
        r#"{"fields":[{"key":"a","type":"color","title":"A"}]}"#,
        r#"{"fields":[{"key":"a","type":"string","title":"A"},{"key":"a","type":"string","title":"B"}]}"#,
        r#"{"fields":[{"key":"a","type":"integer","title":"A","min":5,"max":1}]}"#,
        r#"{"fields":[{"key":"a","type":"integer","title":"A","max":3,"default":4}]}"#,
        r#"{"fields":[{"key":"a","type":"select","title":"A","options":[]}]}"#,
        r#"{"fields":[{"key":"a","type":"boolean","title":"A","min":1}]}"#,
        r#"{"fields":[{"key":"1a","type":"boolean","title":"A"}]}"#,
        r#"{"fields":[{"key":"a","type":"boolean"}]}"#,
        r#"{"fields":{}}"#,
    ] {
        assert_eq!(
            validate_config_schema(bad).unwrap_err().code,
            "INVALID_CONFIG_SCHEMA",
            "{bad}"
        );
    }
}

#[test]
fn rejects_unsafe_paths() {
    for bad in [
        "/abs.js",
        "../up.js",
        "a/../b.js",
        "a//b.js",
        ".hidden",
        "a\\b.js",
        "naïve.js",
        "a/./b.js",
        "",
    ] {
        assert!(check_path(bad).is_err(), "{bad}");
    }
    for good in ["main.js", "lib/util-1.js", "assets/icon_2.png"] {
        assert!(check_path(good).is_ok(), "{good}");
    }
}

#[test]
fn installs_a_valid_package() {
    let directory = tempfile::tempdir().unwrap();
    let package = directory.path().join("hello.nks");
    let manifest = MANIFEST.replace('}', r#","configSchema":"config.schema.json"}"#);
    std::fs::write(
        &package,
        archive(&[
            ("manifest.json", manifest.as_bytes()),
            ("main.js", b"export {};"),
            ("config.schema.json", br#"{"fields":[]}"#),
            ("README.md", b"# Hello"),
        ]),
    )
    .unwrap();
    let destination = directory.path().join("out");
    std::fs::create_dir(&destination).unwrap();
    let installed = install(&package, &destination, 1).unwrap();
    assert_eq!(installed.manifest["readme"], "README.md");
    assert_eq!(installed.manifest["configSchema"], "config.schema.json");
    assert_eq!(
        installed.sha256,
        Sha256::digest(std::fs::read(&package).unwrap())
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect::<String>()
    );
    assert_eq!(
        std::fs::read(destination.join("main.js")).unwrap(),
        b"export {};"
    );
    assert!(destination.join("README.md").is_file());
}

#[test]
fn writes_nothing_for_an_invalid_package() {
    let directory = tempfile::tempdir().unwrap();
    let cases: Vec<(&str, Vec<u8>)> = vec![
        ("no manifest", archive(&[("main.js", b"")])),
        (
            "no entry",
            archive(&[("manifest.json", MANIFEST.as_bytes())]),
        ),
        (
            "traversal",
            archive(&[
                ("manifest.json", MANIFEST.as_bytes()),
                ("main.js", b""),
                ("../x.js", b""),
            ]),
        ),
        (
            "case clash",
            archive(&[
                ("manifest.json", MANIFEST.as_bytes()),
                ("main.js", b""),
                ("Main.js", b""),
            ]),
        ),
        ("bad schema", {
            let manifest = MANIFEST.replace('}', r#","configSchema":"c.json"}"#);
            archive(&[
                ("manifest.json", manifest.as_bytes()),
                ("main.js", b""),
                ("c.json", b"{}"),
            ])
        }),
        (
            "bad icon",
            archive(&[
                ("manifest.json", MANIFEST.as_bytes()),
                ("main.js", b""),
                ("icon.png", b"GIF89a"),
            ]),
        ),
        ("not zip", b"plain text".to_vec()),
    ];
    for (name, bytes) in cases {
        let package = directory.path().join("p.nks");
        std::fs::write(&package, bytes).unwrap();
        let destination = directory.path().join(name.replace(' ', "_"));
        std::fs::create_dir(&destination).unwrap();
        assert!(install(&package, &destination, 1).is_err(), "{name}");
        assert_eq!(
            std::fs::read_dir(&destination).unwrap().count(),
            0,
            "{name}"
        );
    }
}

#[test]
fn refuses_a_non_empty_destination() {
    let directory = tempfile::tempdir().unwrap();
    let package = directory.path().join("p.nks");
    std::fs::write(
        &package,
        archive(&[("manifest.json", MANIFEST.as_bytes()), ("main.js", b"")]),
    )
    .unwrap();
    let destination = directory.path().join("out");
    std::fs::create_dir(&destination).unwrap();
    std::fs::write(destination.join("old"), b"").unwrap();
    assert_eq!(install(&package, &destination, 1).unwrap_err().code, "IO");
}

// The SDK keeps the manifests both sides must agree on, and a package its writer produced.
fn sdk_fixtures() -> std::path::PathBuf {
    std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../../../sdk/script-sdk/test/fixtures")
}

fn fixture_manifests(kind: &str) -> Vec<(String, String)> {
    let mut found: Vec<(String, String)> =
        std::fs::read_dir(sdk_fixtures().join("manifests").join(kind))
            .unwrap()
            .map(|entry| entry.unwrap().path())
            .filter(|path| {
                path.extension()
                    .is_some_and(|extension| extension == "json")
            })
            .map(|path| {
                (
                    path.display().to_string(),
                    std::fs::read_to_string(&path).unwrap(),
                )
            })
            .collect();
    found.sort();
    assert!(!found.is_empty(), "no {kind} fixtures");
    found
}

#[test]
fn agrees_with_the_sdk_on_valid_manifests() {
    for (path, text) in fixture_manifests("valid") {
        assert!(validate_manifest(&text, 1, None).is_ok(), "{path}");
    }
}

#[test]
fn agrees_with_the_sdk_on_invalid_manifests() {
    for (path, text) in fixture_manifests("invalid") {
        assert!(validate_manifest(&text, 1, None).is_err(), "{path}");
    }
}

#[test]
fn installs_a_package_the_sdk_wrote() {
    let directory = tempfile::tempdir().unwrap();
    let destination = directory.path().join("out");
    std::fs::create_dir(&destination).unwrap();
    let installed = install(&sdk_fixtures().join("packages/hello.nks"), &destination, 1).unwrap();
    assert_eq!(installed.manifest["id"], "com.example.hello");
    assert_eq!(installed.manifest["readme"], "README.md");
    for name in [
        "manifest.json",
        "main.js",
        "config.schema.json",
        "README.md",
    ] {
        assert_eq!(
            std::fs::read(destination.join(name)).unwrap(),
            std::fs::read(sdk_fixtures().join("packages/hello").join(name)).unwrap(),
            "{name}"
        );
    }
}
