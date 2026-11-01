use super::{
    correlate, decode, decode_table, encode, encode_table, validate_request, wrap_payload,
    Correlation,
};
use crate::{ErrorCode, ProtocolError, ABI_VERSION};
use jni::objects::{JByteArray, JObject};
use jni::sys::{jbyteArray, jint, jlong};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};

const EXCEPTION_CLASS: &str = "io/github/nuke/oss/app/tiktok/protocol/NativeProtocolException";

// Bit flags describing which correlation inputs the host supplied.
const HAS_REQUEST_SEQUENCE: jint = 1;
const HAS_RESPONSE_CMD: jint = 2;
const HAS_RESPONSE_SEQUENCE: jint = 4;

fn throw(env: &mut JNIEnv<'_>, error: &ProtocolError) {
    if env.exception_check().unwrap_or(true) {
        return;
    }
    let thrown = env.new_string(&error.message).and_then(|message| {
        env.new_object(
            EXCEPTION_CLASS,
            "(ILjava/lang/String;)V",
            &[(error.code as jint).into(), (&message).into()],
        )
    });
    match thrown {
        Ok(object) => {
            let _ = env.throw(jni::objects::JThrowable::from(object));
        }
        Err(_) => {
            let _ = env.throw_new("java/lang/IllegalStateException", &error.message);
        }
    }
}

// Runs one native call so that neither an error nor a panic unwinds into the JVM; failures become a
// Java exception and the call returns the supplied default.
fn guarded<T>(
    env: &mut JNIEnv<'_>,
    default: T,
    operation: impl FnOnce(&mut JNIEnv<'_>) -> Result<T, ProtocolError>,
) -> T {
    match catch_unwind(AssertUnwindSafe(|| operation(env))) {
        Ok(Ok(value)) => value,
        Ok(Err(error)) => {
            throw(env, &error);
            default
        }
        Err(_) => {
            throw(
                env,
                &ProtocolError::new(ErrorCode::Internal, "Native protocol core panicked"),
            );
            default
        }
    }
}

fn read(env: &JNIEnv<'_>, array: &JByteArray<'_>) -> Result<Vec<u8>, ProtocolError> {
    env.convert_byte_array(array).map_err(|_| {
        ProtocolError::new(ErrorCode::InvalidArgument, "Cannot read a Java byte array")
    })
}

fn write(env: &JNIEnv<'_>, bytes: &[u8]) -> Result<jbyteArray, ProtocolError> {
    env.byte_array_from_slice(bytes)
        .map(|array| array.into_raw())
        .map_err(|_| ProtocolError::new(ErrorCode::Internal, "Cannot allocate a Java byte array"))
}

#[no_mangle]
pub extern "system" fn Java_io_github_nuke_oss_app_tiktok_protocol_NativeProtocol_abiVersion(
    _env: JNIEnv<'_>,
    _receiver: JObject<'_>,
) -> jint {
    ABI_VERSION
}

#[no_mangle]
pub extern "system" fn Java_io_github_nuke_oss_app_tiktok_protocol_NativeProtocol_decode(
    mut env: JNIEnv<'_>,
    _receiver: JObject<'_>,
    bytes: JByteArray<'_>,
) -> jbyteArray {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        let table = encode_table(&decode(&read(env, &bytes)?)?)?;
        write(env, &table)
    })
}

#[no_mangle]
pub extern "system" fn Java_io_github_nuke_oss_app_tiktok_protocol_NativeProtocol_encode(
    mut env: JNIEnv<'_>,
    _receiver: JObject<'_>,
    table: JByteArray<'_>,
) -> jbyteArray {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        let bytes = encode(&decode_table(&read(env, &table)?)?)?;
        write(env, &bytes)
    })
}

#[no_mangle]
pub extern "system" fn Java_io_github_nuke_oss_app_tiktok_protocol_NativeProtocol_wrapPayload(
    mut env: JNIEnv<'_>,
    _receiver: JObject<'_>,
    body_tag: jint,
    payload: JByteArray<'_>,
) -> jbyteArray {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        let body = wrap_payload(body_tag, &read(env, &payload)?)?;
        write(env, &body)
    })
}

#[no_mangle]
pub extern "system" fn Java_io_github_nuke_oss_app_tiktok_protocol_NativeProtocol_validateRequest(
    mut env: JNIEnv<'_>,
    _receiver: JObject<'_>,
    cmd: jint,
    inbox_type: jint,
    body_length: jint,
) {
    guarded(&mut env, (), |_| {
        let length = usize::try_from(body_length).map_err(|_| {
            ProtocolError::new(ErrorCode::InvalidArgument, "Negative RequestBody length")
        })?;
        validate_request(cmd, inbox_type, length)
    })
}

#[no_mangle]
pub extern "system" fn Java_io_github_nuke_oss_app_tiktok_protocol_NativeProtocol_correlate(
    mut env: JNIEnv<'_>,
    _receiver: JObject<'_>,
    expected_cmd: jint,
    present: jint,
    request_sequence: jlong,
    response_cmd: jint,
    response_sequence: jlong,
) {
    guarded(&mut env, (), |_| {
        let has = |flag: jint| present & flag != 0;
        correlate(
            expected_cmd,
            Correlation {
                request_sequence: has(HAS_REQUEST_SEQUENCE).then_some(request_sequence),
                response_cmd: has(HAS_RESPONSE_CMD).then_some(response_cmd),
                response_sequence: has(HAS_RESPONSE_SEQUENCE).then_some(response_sequence),
            },
        )
    })
}
