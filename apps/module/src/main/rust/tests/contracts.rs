use nuke_protocol_core::*;

fn bytes(number: u32, payload: &[u8]) -> Field {
    Field {
        number,
        value: Value::Bytes(payload.to_vec()),
    }
}

#[test]
fn round_trips_every_wire_type_in_order() {
    let fields = vec![
        Field {
            number: 1,
            value: Value::Varint(u64::MAX),
        },
        bytes(2, b"hello"),
        Field {
            number: 3,
            value: Value::Fixed64(0x0102_0304_0506_0708),
        },
        Field {
            number: 3,
            value: Value::Fixed32(0xdead_beef),
        },
        bytes(2, b""),
    ];
    let wire = encode(&fields).unwrap();
    assert_eq!(decode(&wire).unwrap(), fields);
    assert_eq!(
        decode_table(&encode_table(&fields).unwrap()).unwrap(),
        fields
    );
}

#[test]
fn encodes_known_vectors() {
    let wire = encode(&[Field {
        number: 1,
        value: Value::Varint(150),
    }])
    .unwrap();
    assert_eq!(wire, [0x08, 0x96, 0x01]);
    assert_eq!(wrap_payload(5, &[1, 2, 3]).unwrap(), [0x2a, 3, 1, 2, 3]);
}

#[test]
fn rejects_malformed_wire() {
    for bad in [
        &[0x08][..],         // missing varint value
        &[0x00, 0x01],       // tag zero
        &[0x0a, 0x05, 0x01], // truncated bytes field
        &[0x0b],             // deprecated start-group wire type
        &[0x09, 1, 2, 3],    // truncated fixed64
        &[
            0x08, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x7f,
        ], // overflowing varint
    ] {
        let error = decode(bad).unwrap_err();
        assert_eq!(error.code, ErrorCode::ResponseDecodeFailed, "{bad:?}");
    }
}

#[test]
fn enforces_size_and_field_number_limits() {
    assert_eq!(
        encode(&[bytes(0, b"x")]).unwrap_err().code,
        ErrorCode::SerializeFailed
    );
    assert_eq!(
        encode(&[bytes(0x2000_0000, b"x")]).unwrap_err().code,
        ErrorCode::SerializeFailed
    );
    let big = vec![0u8; MAX_BODY_BYTES + 1];
    assert!(encode(&[bytes(1, &big)]).is_err());
    assert!(decode(&big).is_err());
    assert!(wrap_payload(0, b"x").is_err());
    assert!(wrap_payload(-1, b"x").is_err());
}

#[test]
fn rejects_corrupt_field_tables() {
    assert!(decode_table(&[0, 0, 0, 1]).is_err());
    assert!(decode_table(&[0, 0, 0, 1, 9]).is_err());
    assert!(decode_table(&[0, 0, 0, 1, 2, 0, 0, 0, 9, 1]).is_err());
}

#[test]
fn validates_requests() {
    assert!(validate_request(1, 0, 0).is_ok());
    assert!(validate_request(0, 1, 0).is_err());
    assert!(validate_request(1, -1, 0).is_err());
    assert!(validate_request(1, 1, MAX_BODY_BYTES).is_ok());
    assert!(validate_request(1, 1, MAX_BODY_BYTES + 1).is_err());
}

#[test]
fn correlation_requires_every_value_to_match() {
    let ok = Correlation {
        request_sequence: Some(7),
        response_cmd: Some(3),
        response_sequence: Some(7),
    };
    assert!(correlate(3, ok).is_ok());
    assert!(correlate(4, ok).is_err());
    assert!(correlate(
        3,
        Correlation {
            response_sequence: Some(8),
            ..ok
        }
    )
    .is_err());
    assert!(correlate(
        3,
        Correlation {
            request_sequence: None,
            ..ok
        }
    )
    .is_err());
    assert!(correlate(
        3,
        Correlation {
            response_sequence: None,
            ..ok
        }
    )
    .is_err());
    assert!(correlate(
        3,
        Correlation {
            response_cmd: None,
            ..ok
        }
    )
    .is_err());
}
