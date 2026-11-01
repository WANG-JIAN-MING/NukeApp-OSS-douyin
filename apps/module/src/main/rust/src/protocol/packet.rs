use super::wire::{encode, Field, Value, MAX_BODY_BYTES};
use crate::error::{ErrorCode, ProtocolError};

// Which envelope metadata the host actually exposed; an absent value never matches.
#[derive(Clone, Copy, Debug)]
pub struct Correlation {
    pub request_sequence: Option<i64>,
    pub response_cmd: Option<i32>,
    pub response_sequence: Option<i64>,
}

fn invalid(message: &str) -> ProtocolError {
    ProtocolError::new(ErrorCode::InvalidArgument, message)
}

// cmd and inbox select the host SDK route; there is no local command whitelist.
pub fn validate_request(cmd: i32, inbox_type: i32, body_len: usize) -> Result<(), ProtocolError> {
    if cmd <= 0 || inbox_type < 0 || body_len > MAX_BODY_BYTES {
        return Err(invalid(
            "cmd must be positive, inbox nonnegative and RequestBody at most 4 MiB",
        ));
    }
    Ok(())
}

// Wraps a business message under an explicit RequestBody tag; the tag is never inferred from cmd.
pub fn wrap_payload(body_tag: i32, payload: &[u8]) -> Result<Vec<u8>, ProtocolError> {
    let number =
        u32::try_from(body_tag).map_err(|_| invalid("RequestBody tag must be positive"))?;
    encode(&[Field {
        number,
        value: Value::Bytes(payload.to_vec()),
    }])
}

// Matches envelope metadata without interpreting or limiting the business response body.
pub fn correlate(expected_cmd: i32, correlation: Correlation) -> Result<(), ProtocolError> {
    let failure = |message| ProtocolError::new(ErrorCode::ResponseDecodeFailed, message);
    if correlation.response_cmd != Some(expected_cmd) {
        return Err(failure("Response cmd does not match this request"));
    }
    match (correlation.request_sequence, correlation.response_sequence) {
        (Some(request), Some(response)) if request == response => Ok(()),
        _ => Err(failure("Response sequence does not match this request")),
    }
}
