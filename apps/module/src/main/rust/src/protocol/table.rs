use super::wire::{Field, Value, MAX_BODY_BYTES};
use crate::error::{ErrorCode, ProtocolError};

// Field table, all integers big-endian: repeated records of
//   u32 number, u8 wire type, then u64 value (wire types 0 and 1), u32 value (wire type 5),
//   or u32 length followed by the bytes (wire type 2).
// This is the only structured form that crosses JNI, so Kotlin needs no object graph marshalling.

fn invalid(message: &str) -> ProtocolError {
    ProtocolError::new(ErrorCode::InvalidArgument, message)
}

pub fn encode_table(fields: &[Field]) -> Result<Vec<u8>, ProtocolError> {
    let mut out = Vec::new();
    for field in fields {
        out.extend_from_slice(&field.number.to_be_bytes());
        match &field.value {
            Value::Varint(value) => {
                out.push(0);
                out.extend_from_slice(&value.to_be_bytes());
            }
            Value::Fixed64(value) => {
                out.push(1);
                out.extend_from_slice(&value.to_be_bytes());
            }
            Value::Bytes(bytes) => {
                out.push(2);
                out.extend_from_slice(&(bytes.len() as u32).to_be_bytes());
                out.extend_from_slice(bytes);
            }
            Value::Fixed32(value) => {
                out.push(5);
                out.extend_from_slice(&value.to_be_bytes());
            }
        }
        if out.len() > MAX_BODY_BYTES * 2 {
            return Err(invalid("Field table is too large"));
        }
    }
    Ok(out)
}

pub fn decode_table(table: &[u8]) -> Result<Vec<Field>, ProtocolError> {
    let mut offset = 0usize;
    let mut fields = Vec::new();
    while offset < table.len() {
        let number = u32::from_be_bytes(take(table, &mut offset, 4)?.try_into().unwrap());
        let wire = take(table, &mut offset, 1)?[0];
        let value = match wire {
            0 => Value::Varint(u64::from_be_bytes(
                take(table, &mut offset, 8)?.try_into().unwrap(),
            )),
            1 => Value::Fixed64(u64::from_be_bytes(
                take(table, &mut offset, 8)?.try_into().unwrap(),
            )),
            2 => {
                let length =
                    u32::from_be_bytes(take(table, &mut offset, 4)?.try_into().unwrap()) as usize;
                Value::Bytes(take(table, &mut offset, length)?.to_vec())
            }
            5 => Value::Fixed32(u32::from_be_bytes(
                take(table, &mut offset, 4)?.try_into().unwrap(),
            )),
            _ => return Err(invalid("Unsupported wire type in field table")),
        };
        fields.push(Field { number, value });
    }
    Ok(fields)
}

fn take<'a>(table: &'a [u8], offset: &mut usize, length: usize) -> Result<&'a [u8], ProtocolError> {
    let end = offset.checked_add(length).filter(|end| *end <= table.len());
    let end = end.ok_or_else(|| invalid("Truncated field table"))?;
    let slice = &table[*offset..end];
    *offset = end;
    Ok(slice)
}
