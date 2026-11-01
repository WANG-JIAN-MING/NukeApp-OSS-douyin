use super::{encode, scan};
use jni::objects::JObject;
use jni::sys::jstring;
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};

/// The device trust checks, one per line as `id<TAB>level<TAB>detail` (see `encode`); null when anything goes wrong, a
/// panic included, so nothing unwinds across the JNI boundary.
#[no_mangle]
pub extern "system" fn Java_io_github_nuke_oss_app_shared_integrity_NativeRisk_scan(
    env: JNIEnv<'_>,
    _receiver: JObject<'_>,
) -> jstring {
    let text = catch_unwind(AssertUnwindSafe(|| encode(&scan())));
    match text {
        Ok(text) => env
            .new_string(text)
            .map(|value| value.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Err(_) => std::ptr::null_mut(),
    }
}
