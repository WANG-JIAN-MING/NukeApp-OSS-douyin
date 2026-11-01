use super::package;
use jni::objects::{JObject, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use serde_json::json;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::Path;

/// Installs the package at `package` into the empty directory `destination` and answers with JSON: either
/// `{"ok":true,"manifest":{...},"sha256":"..."}` or `{"ok":false,"code":"...","message":"..."}`. Never throws and never
/// unwinds; null only when not even the answer string can be created.
#[no_mangle]
pub extern "system" fn Java_io_github_nuke_oss_app_shared_script_NativeScriptPackage_install(
    mut env: JNIEnv<'_>,
    _receiver: JObject<'_>,
    package: JString<'_>,
    destination: JString<'_>,
    max_api: jint,
) -> jstring {
    let answer = catch_unwind(AssertUnwindSafe(|| {
        let package: String = env.get_string(&package).map(Into::into).ok()?;
        let destination: String = env.get_string(&destination).map(Into::into).ok()?;
        Some(
            match package::install(
                Path::new(&package),
                Path::new(&destination),
                i64::from(max_api),
            ) {
                Ok(installed) => {
                    json!({ "ok": true, "manifest": installed.manifest, "sha256": installed.sha256 })
                }
                Err(error) => json!({ "ok": false, "code": error.code, "message": error.message }),
            },
        )
    }));
    let answer = match answer {
        Ok(Some(value)) => value,
        Ok(None) => {
            json!({ "ok": false, "code": "INVALID_ARGUMENT", "message": "Unreadable path arguments" })
        }
        Err(_) => {
            json!({ "ok": false, "code": "INTERNAL", "message": "The package reader panicked" })
        }
    };
    env.new_string(answer.to_string())
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
