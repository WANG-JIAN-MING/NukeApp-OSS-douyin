use crate::error::{ErrorCode, ProtocolError};

pub const MAX_BODY_BYTES: usize = 4 * 1024 * 1024;
const MAX_FIELD_NUMBER: u32 = 0x1fff_ffff;

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Value {
    Varint(u64),
    Fixed64(u64),
    Bytes(Vec<u8>),
    Fixed32(u32),
}

impl Value {
    fn wire_type(&self) -> u64 {
        match self {
            Value::Varint(_) => 0,
            Value::Fixed64(_) => 1,
            Value::Bytes(_) => 2,
            Value::Fixed32(_) => 5,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Field {
    pub number: u32,
    pub value: Value,
}

fn decode_error(message: &str) -> ProtocolError {
    ProtocolError::new(ErrorCode::ResponseDecodeFailed, message)
}

fn encode_error(message: &str) -> ProtocolError {
    ProtocolError::new(ErrorCode::SerializeFailed, message)
}

fn check_number(number: u32) -> bool {
    (1..=MAX_FIELD_NUMBER).contains(&number)
}

// Keeps every field in wire order, including repeated and unknown ones; no schema is applied.
pub fn decode(bytes: &[u8]) -> Result<Vec<Field>, ProtocolError> {
    if bytes.len() > MAX_BODY_BYTES {
        return Err(decode_error("Protobuf body is too large"));
    }
    let mut reader = Reader {
        source: bytes,
        offset: 0,
    };
    let mut fields = Vec::new();
    while reader.remaining() > 0 {
        let tag = reader.varint()?;
        if tag == 0 || tag > u64::from(u32::MAX) {
            return Err(decode_error("Invalid protobuf tag"));
        }
        let number = (tag >> 3) as u32;
        if !check_number(number) {
            return Err(decode_error("Invalid protobuf field number"));
        }
        let value = match tag & 7 {
            0 => Value::Varint(reader.varint()?),
            1 => Value::Fixed64(reader.fixed(8)?),
            2 => {
                let length = reader.varint()?;
                if length > reader.remaining() as u64 {
                    return Err(decode_error("Truncated protobuf payload"));
                }
                Value::Bytes(reader.take(length as usize).to_vec())
            }
            5 => Value::Fixed32(reader.fixed(4)? as u32),
            _ => return Err(decode_error("Unsupported protobuf wire type")),
        };
        fields.push(Field { number, value });
    }
    Ok(fields)
}

pub fn encode(fields: &[Field]) -> Result<Vec<u8>, ProtocolError> {
    let mut out = Vec::new();
    for field in fields {
        if !check_number(field.number) {
            return Err(encode_error("Invalid protobuf field number"));
        }
        write_varint(
            &mut out,
            (u64::from(field.number) << 3) | field.value.wire_type(),
        );
        match &field.value {
            Value::Varint(value) => write_varint(&mut out, *value),
            Value::Fixed64(value) => out.extend_from_slice(&value.to_le_bytes()),
            Value::Fixed32(value) => out.extend_from_slice(&value.to_le_bytes()),
            Value::Bytes(bytes) => {
                if bytes.len() > MAX_BODY_BYTES {
                    return Err(encode_error("Protobuf field is too large"));
                }
                write_varint(&mut out, bytes.len() as u64);
                out.extend_from_slice(bytes);
            }
        }
        if out.len() > MAX_BODY_BYTES {
            return Err(encode_error("Protobuf body is too large"));
        }
    }
    Ok(out)
}

fn write_varint(out: &mut Vec<u8>, mut value: u64) {
    while value >= 0x80 {
        out.push((value as u8 & 0x7f) | 0x80);
        value >>= 7;
    }
    out.push(value as u8);
}

struct Reader<'a> {
    source: &'a [u8],
    offset: usize,
}

impl<'a> Reader<'a> {
    fn remaining(&self) -> usize {
        self.source.len() - self.offset
    }

    fn take(&mut self, length: usize) -> &'a [u8] {
        let slice = &self.source[self.offset..self.offset + length];
        self.offset += length;
        slice
    }

    fn varint(&mut self) -> Result<u64, ProtocolError> {
        let mut result = 0u64;
        for index in 0..10 {
            if self.remaining() == 0 {
                return Err(decode_error("Truncated varint"));
            }
            let next = self.take(1)[0];
            if index == 9 && next > 1 {
                return Err(decode_error("Overflowing varint"));
            }
            result |= u64::from(next & 0x7f) << (index * 7);
            if next & 0x80 == 0 {
                return Ok(result);
            }
        }
        Err(decode_error("Overflowing varint"))
    }

    fn fixed(&mut self, length: usize) -> Result<u64, ProtocolError> {
        if self.remaining() < length {
            return Err(decode_error("Truncated fixed field"));
        }
        Ok(self
            .take(length)
            .iter()
            .enumerate()
            .fold(0u64, |acc, (index, byte)| {
                acc | (u64::from(*byte) << (index * 8))
            }))
    }
}
