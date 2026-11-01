//! The module APK signer check: `verify` reads and compares the signing certificate, `bridge` is the JNI binding.

mod bridge;
mod verify;

pub use verify::*;
