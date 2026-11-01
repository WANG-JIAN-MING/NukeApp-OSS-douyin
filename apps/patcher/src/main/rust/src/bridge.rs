use crate::{inspect_apk, stage_apk, verify_patched, ApkReport, PatchError};
use jni::objects::{JObject, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use serde::Serialize;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::Path;

#[derive(Serialize)]
struct Success {
    ok: bool,
    #[serde(flatten)]
    report: ApkReport,
}

#[derive(Serialize)]
struct Failure {
    ok: bool,
    error: PatchError,
}

fn path(env: &mut JNIEnv<'_>, value: &JString<'_>) -> Result<String, PatchError> {
    env.get_string(value)
        .map(Into::into)
        .map_err(|_| PatchError::new("jni", "Cannot read a Java path string"))
}

fn boundary(
    env: &mut JNIEnv<'_>,
    operation: impl FnOnce(&mut JNIEnv<'_>) -> Result<ApkReport, PatchError>,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| operation(env)))
        .unwrap_or_else(|_| Err(PatchError::new("internal", "Patch file verifier failed")));
    let json = match result {
        Ok(report) => serde_json::to_string(&Success { ok: true, report }),
        Err(error) => serde_json::to_string(&Failure { ok: false, error }),
    }.unwrap_or_else(|_| "{\"ok\":false,\"error\":{\"code\":\"internal\",\"message\":\"Cannot encode verification result\"}}".to_owned());
    match env.new_string(json) {
        Ok(value) => value.into_raw(),
        Err(_) => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                "Cannot allocate patch verification result",
            );
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_nuke_oss_patcher_patch_NativePatchFiles_stageApk(
    mut env: JNIEnv<'_>,
    _receiver: JObject<'_>,
    fd: jint,
    destination: JString<'_>,
) -> jstring {
    boundary(&mut env, |env| {
        stage_apk(fd, Path::new(&path(env, &destination)?))
    })
}

#[no_mangle]
pub extern "system" fn Java_io_github_nuke_oss_patcher_patch_NativePatchFiles_inspectApk(
    mut env: JNIEnv<'_>,
    _receiver: JObject<'_>,
    apk: JString<'_>,
) -> jstring {
    boundary(&mut env, |env| inspect_apk(Path::new(&path(env, &apk)?)))
}

#[no_mangle]
pub extern "system" fn Java_io_github_nuke_oss_patcher_patch_NativePatchFiles_verifyPatched(
    mut env: JNIEnv<'_>,
    _receiver: JObject<'_>,
    original: JString<'_>,
    module: JString<'_>,
    patched: JString<'_>,
) -> jstring {
    boundary(&mut env, |env| {
        verify_patched(
            Path::new(&path(env, &original)?),
            Path::new(&path(env, &module)?),
            Path::new(&path(env, &patched)?),
        )
    })
}
