//! Native core of the module app, in four parts that each keep their logic and their JNI binding together:
//! `protocol` is the host-independent Douyin IM packet protocol (wire codec, request wrapping, response correlation;
//! the Kotlin side owns the host transport), `script` validates and extracts script packages for the script engine,
//! `signer` is the module APK signer check, so that the release certificate digest it compares with lives in native
//! code, and `riskctl` reads device trust checks (debugger, injection, Frida, root, boot state, SELinux and more) for
//! people to look at. `error` is shared by the protocol and the script reader.

mod error;
mod protocol;
pub mod riskctl;
pub mod script;
pub mod signer;

pub use error::{ErrorCode, ProtocolError};
pub use protocol::{
    correlate, decode, decode_table, encode, encode_table, validate_request, wrap_payload,
    Correlation, Field, Value, MAX_BODY_BYTES,
};

// Bump when a JNI signature or the field-table layout changes; Kotlin refuses a mismatching library.
pub const ABI_VERSION: i32 = 1;
