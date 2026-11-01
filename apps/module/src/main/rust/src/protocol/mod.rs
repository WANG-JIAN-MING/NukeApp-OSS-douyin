//! The Douyin IM packet protocol: wire codec, field tables, request wrapping and response correlation, with its JNI
//! binding in `bridge`.

mod bridge;
mod packet;
mod table;
mod wire;

pub use packet::{correlate, validate_request, wrap_payload, Correlation};
pub use table::{decode_table, encode_table};
pub use wire::{decode, encode, Field, Value, MAX_BODY_BYTES};
