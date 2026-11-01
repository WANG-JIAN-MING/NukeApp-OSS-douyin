use super::verify::{self, Verdict};
use jni::objects::{JObject, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use std::fs::File;
use std::panic::{catch_unwind, AssertUnwindSafe};

// Kept in step with io.github.nuke.oss.app.shared.integrity.NativeSignature.
const MATCHES: jint = 0;
const MISMATCH: jint = 1;
const UNCONFIGURED: jint = 2;
const UNREADABLE: jint = 3;

fn path(env: &mut JNIEnv<'_>, path: &JString<'_>) -> Option<String> {
    env.get_string(path).ok().map(Into::into)
}

/// Compares the APK at `path` with the compiled-in release certificate. Never throws and never unwinds: anything that
/// goes wrong, a panic included, is reported as unreadable.
#[no_mangle]
pub extern "system" fn Java_io_github_nuke_oss_app_shared_integrity_NativeSignature_verify(
    mut env: JNIEnv<'_>,
    _receiver: JObject<'_>,
    apk: JString<'_>,
) -> jint {
    let verdict = catch_unwind(AssertUnwindSafe(|| {
        path(&mut env, &apk).map(|apk| verify::verify(&apk))
    }));
    match verdict {
        Ok(Some(Verdict::Matches)) => MATCHES,
        Ok(Some(Verdict::Mismatch)) => MISMATCH,
        Ok(Some(Verdict::Unconfigured)) => UNCONFIGURED,
        _ => UNREADABLE,
    }
}

/// The SHA-256 of the APK's signer certificate in lowercase hex, for diagnostics; null when it cannot be read.
#[no_mangle]
pub extern "system" fn Java_io_github_nuke_oss_app_shared_integrity_NativeSignature_signerDigest(
    mut env: JNIEnv<'_>,
    _receiver: JObject<'_>,
    apk: JString<'_>,
) -> jstring {
    let digest = catch_unwind(AssertUnwindSafe(|| {
        let apk = path(&mut env, &apk)?;
        let mut file = File::open(apk).ok()?;
        verify::signer_digest(&mut file)
            .ok()
            .map(|digest| verify::hex(&digest))
    }));
    match digest {
        Ok(Some(text)) => env
            .new_string(text)
            .map(|value| value.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        _ => std::ptr::null_mut(),
    }
}
