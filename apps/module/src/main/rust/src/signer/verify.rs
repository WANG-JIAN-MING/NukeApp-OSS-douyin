//! Reads the signer of an APK and compares it with the release certificate this library was built for.
//!
//! An APK signed with APK Signature Scheme v2 or v3 keeps its signatures in the APK Signing Block, which sits between the
//! ZIP entries and the central directory; the layout is the one the platform documents for the two schemes. The block is
//! found from the end of the file: the end of central directory record names the central directory, and the block ends
//! where the directory begins. The signer's first certificate is read from the pair of the scheme and its SHA-256 is the
//! signer digest. Only the certificate is compared here. Checking the signature itself is the package manager's job, and
//! it refuses to install an APK whose signature does not verify.

use sha2::{Digest, Sha256};
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};

const END_RECORD_SIGNATURE: u32 = 0x0605_4b50;
const END_RECORD_LEN: u64 = 22;
const MAX_COMMENT_LEN: u64 = 0xffff;
const ZIP64_LOCATOR_SIGNATURE: u32 = 0x0706_4b50;
const ZIP64_LOCATOR_LEN: u64 = 20;
const DIRECTORY_SIGNATURE: u32 = 0x0201_4b50;
const BLOCK_MAGIC: &[u8; 16] = b"APK Sig Block 42";
// The block ends with its size (8 bytes) and the magic (16 bytes).
const BLOCK_FOOTER_LEN: u64 = 24;
// A signing block is a few KiB; anything far larger is not a real one and is not read into memory.
const MAX_BLOCK_LEN: u64 = 16 * 1024 * 1024;
const SCHEME_V3: u32 = 0xf053_68c0;
const SCHEME_V2: u32 = 0x7109_871a;
// v3 first: when both are present they name the same signer, and v3 is what current platforms read.
const SCHEMES_BY_PREFERENCE: [u32; 2] = [SCHEME_V3, SCHEME_V2];

/// The SHA-256 of the release certificate, given to the build as 64 hex digits; absent when the build had no release key.
const CONFIGURED: Option<&str> = option_env!("NUKE_MODULE_SIGNER_SHA256");

#[derive(Debug, PartialEq, Eq)]
pub enum SignerError {
    Io,
    NotZip,
    NoSigningBlock,
    Malformed,
    NoSigner,
}

#[derive(Debug, PartialEq, Eq)]
pub enum Verdict {
    Matches,
    Mismatch,
    /// The build carries no release certificate digest, so there is nothing to compare with.
    Unconfigured,
    Unreadable(SignerError),
}

/// The first certificate (DER) of the first signer of the v3 or, failing that, the v2 scheme.
pub fn signer_certificate(apk: &mut (impl Read + Seek)) -> Result<Vec<u8>, SignerError> {
    let directory = central_directory_offset(apk)?;
    let pairs = signing_block_pairs(apk, directory)?;
    for scheme in SCHEMES_BY_PREFERENCE {
        if let Some(value) = pair_value(&pairs, scheme)? {
            return first_certificate(value);
        }
    }
    Err(SignerError::NoSigner)
}

/// The SHA-256 of the signer's certificate.
pub fn signer_digest(apk: &mut (impl Read + Seek)) -> Result<[u8; 32], SignerError> {
    Ok(Sha256::digest(signer_certificate(apk)?).into())
}

/// Compares the signer of the APK at `path` with the configured release certificate.
pub fn verify(path: &str) -> Verdict {
    match CONFIGURED.and_then(parse_hex) {
        Some(expected) => verify_against(path, &expected),
        None => Verdict::Unconfigured,
    }
}

pub fn verify_against(path: &str, expected: &[u8; 32]) -> Verdict {
    let actual = File::open(path)
        .map_err(|_| SignerError::Io)
        .and_then(|mut file| signer_digest(&mut file));
    match actual {
        Ok(digest) if &digest == expected => Verdict::Matches,
        Ok(_) => Verdict::Mismatch,
        Err(error) => Verdict::Unreadable(error),
    }
}

pub fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

fn parse_hex(text: &str) -> Option<[u8; 32]> {
    let text = text.trim();
    if text.len() != 64 || !text.is_ascii() {
        return None;
    }
    let mut digest = [0u8; 32];
    for (slot, pair) in digest.iter_mut().zip(text.as_bytes().chunks(2)) {
        *slot = u8::from_str_radix(std::str::from_utf8(pair).ok()?, 16).ok()?;
    }
    Some(digest)
}

/// A cursor over bytes that never reads past their end: every read that would is `Malformed`, so the parsers below carry
/// no index arithmetic of their own.
struct Reader<'a> {
    rest: &'a [u8],
}

impl<'a> Reader<'a> {
    fn new(bytes: &'a [u8]) -> Self {
        Reader { rest: bytes }
    }

    fn is_empty(&self) -> bool {
        self.rest.is_empty()
    }

    fn take(&mut self, count: usize) -> Result<&'a [u8], SignerError> {
        if count > self.rest.len() {
            return Err(SignerError::Malformed);
        }
        let (head, tail) = self.rest.split_at(count);
        self.rest = tail;
        Ok(head)
    }

    fn u16(&mut self) -> Result<u16, SignerError> {
        let bytes = self.take(2)?;
        Ok(u16::from_le_bytes([bytes[0], bytes[1]]))
    }

    fn u32(&mut self) -> Result<u32, SignerError> {
        let bytes = self.take(4)?;
        Ok(u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
    }

    fn u64(&mut self) -> Result<u64, SignerError> {
        let bytes = self.take(8)?;
        let mut eight = [0u8; 8];
        eight.copy_from_slice(bytes);
        Ok(u64::from_le_bytes(eight))
    }

    /// A field that starts with its own length as a u32.
    fn length_prefixed(&mut self) -> Result<&'a [u8], SignerError> {
        let length = usize::try_from(self.u32()?).map_err(|_| SignerError::Malformed)?;
        self.take(length)
    }
}

fn read_at(apk: &mut (impl Read + Seek), offset: u64, length: u64) -> Result<Vec<u8>, SignerError> {
    let length = usize::try_from(length).map_err(|_| SignerError::Malformed)?;
    let mut bytes = vec![0u8; length];
    apk.seek(SeekFrom::Start(offset))
        .map_err(|_| SignerError::Io)?;
    apk.read_exact(&mut bytes).map_err(|_| SignerError::Io)?;
    Ok(bytes)
}

/// What the end of central directory record says about the central directory.
struct EndRecord {
    directory_size: u64,
    directory_offset: u64,
    comment_len: u64,
}

fn parse_end_record(bytes: &[u8]) -> Option<EndRecord> {
    let mut reader = Reader::new(bytes);
    if reader.u32().ok()? != END_RECORD_SIGNATURE {
        return None;
    }
    // Disk numbers and entry counts.
    reader.take(8).ok()?;
    Some(EndRecord {
        directory_size: u64::from(reader.u32().ok()?),
        directory_offset: u64::from(reader.u32().ok()?),
        comment_len: u64::from(reader.u16().ok()?),
    })
}

/// Where the central directory starts. The end record is the last 22 bytes of the file followed by a comment of up to
/// 64 KiB, so the file's tail is searched backwards for a record whose comment length reaches exactly to the end of the
/// file, which a copy of the signature inside the comment does not. A signed APK has its central directory directly in
/// front of that record and is not ZIP64, which the signature schemes do not allow.
fn central_directory_offset(apk: &mut (impl Read + Seek)) -> Result<u64, SignerError> {
    let size = apk.seek(SeekFrom::End(0)).map_err(|_| SignerError::Io)?;
    if size < END_RECORD_LEN {
        return Err(SignerError::NotZip);
    }
    let window = (END_RECORD_LEN + MAX_COMMENT_LEN).min(size);
    let window_start = size - window;
    let tail = read_at(apk, window_start, window)?;
    for from_end in 0..=(window - END_RECORD_LEN) {
        let position = window - END_RECORD_LEN - from_end;
        let Some(record) = parse_end_record(&tail[position as usize..]) else {
            continue;
        };
        if record.comment_len != from_end {
            continue;
        }
        let record_offset = window_start + position;
        if record_offset >= ZIP64_LOCATOR_LEN {
            let locator = read_at(apk, record_offset - ZIP64_LOCATOR_LEN, 4)?;
            if Reader::new(&locator).u32()? == ZIP64_LOCATOR_SIGNATURE {
                return Err(SignerError::Malformed);
            }
        }
        if record.directory_offset.checked_add(record.directory_size) != Some(record_offset) {
            return Err(SignerError::Malformed);
        }
        return Ok(record.directory_offset);
    }
    Err(SignerError::NotZip)
}

/// The pairs of the signing block in front of the central directory, without the block's sizes and magic.
fn signing_block_pairs(
    apk: &mut (impl Read + Seek),
    directory: u64,
) -> Result<Vec<u8>, SignerError> {
    let start_of_directory = read_at(apk, directory, 4)?;
    if Reader::new(&start_of_directory).u32()? != DIRECTORY_SIGNATURE {
        return Err(SignerError::NotZip);
    }
    if directory < BLOCK_FOOTER_LEN {
        return Err(SignerError::NoSigningBlock);
    }
    let footer = read_at(apk, directory - BLOCK_FOOTER_LEN, BLOCK_FOOTER_LEN)?;
    let mut reader = Reader::new(&footer);
    let size = reader.u64()?;
    if reader.take(BLOCK_MAGIC.len())? != BLOCK_MAGIC {
        return Err(SignerError::NoSigningBlock);
    }
    // The size counts everything after its own first copy: the pairs, the second copy and the magic.
    if !(BLOCK_FOOTER_LEN..=MAX_BLOCK_LEN).contains(&size) || size + 8 > directory {
        return Err(SignerError::Malformed);
    }
    let block = read_at(apk, directory - size - 8, size + 8)?;
    let mut reader = Reader::new(&block);
    if reader.u64()? != size {
        return Err(SignerError::Malformed);
    }
    let pairs_len = usize::try_from(size - BLOCK_FOOTER_LEN).map_err(|_| SignerError::Malformed)?;
    Ok(reader.take(pairs_len)?.to_vec())
}

/// The value of the pair with `id`, if there is one. Each pair is a u64 length, a u32 id and the value; the length counts
/// the id and the value.
fn pair_value(pairs: &[u8], id: u32) -> Result<Option<&[u8]>, SignerError> {
    let mut reader = Reader::new(pairs);
    while !reader.is_empty() {
        let length = usize::try_from(reader.u64()?).map_err(|_| SignerError::Malformed)?;
        let mut pair = Reader::new(reader.take(length)?);
        if pair.u32()? == id {
            return Ok(Some(pair.rest));
        }
    }
    Ok(None)
}

/// The first certificate of the first signer. Both schemes lay it out the same way: a sequence of signers, each holding
/// its signed data, which holds the digests and then the certificates.
fn first_certificate(value: &[u8]) -> Result<Vec<u8>, SignerError> {
    let signers = Reader::new(value).length_prefixed()?;
    let signer = Reader::new(signers).length_prefixed()?;
    let signed_data = Reader::new(signer).length_prefixed()?;
    let mut fields = Reader::new(signed_data);
    fields.length_prefixed()?;
    let certificates = fields.length_prefixed()?;
    let certificate = Reader::new(certificates).length_prefixed()?;
    if certificate.is_empty() {
        return Err(SignerError::NoSigner);
    }
    Ok(certificate.to_vec())
}

#[cfg(test)]
mod fixture {
    use super::{BLOCK_MAGIC, DIRECTORY_SIGNATURE, END_RECORD_SIGNATURE, ZIP64_LOCATOR_SIGNATURE};

    fn prefixed(content: &[u8]) -> Vec<u8> {
        let mut out = (content.len() as u32).to_le_bytes().to_vec();
        out.extend_from_slice(content);
        out
    }

    /// The value of a v2 or v3 pair for one signer whose first certificate is `certificate`.
    pub fn signer_value(certificate: &[u8]) -> Vec<u8> {
        let signed_data = [
            prefixed(&prefixed(b"digest")),
            prefixed(&prefixed(certificate)),
        ]
        .concat();
        let signer = prefixed(
            &[
                prefixed(&signed_data),
                prefixed(b"signatures"),
                prefixed(b"key"),
            ]
            .concat(),
        );
        prefixed(&signer)
    }

    /// A minimal APK layout: local data, a signing block, a central directory and the end record.
    pub struct Apk {
        pub pairs: Vec<(u32, Vec<u8>)>,
        pub comment: Vec<u8>,
        pub zip64: bool,
    }

    impl Apk {
        pub fn signed(scheme: u32, certificate: &[u8]) -> Self {
            Apk {
                pairs: vec![
                    (0x4242_4242, b"padding".to_vec()),
                    (scheme, signer_value(certificate)),
                ],
                comment: Vec::new(),
                zip64: false,
            }
        }

        pub fn with_pair(mut self, scheme: u32, certificate: &[u8]) -> Self {
            self.pairs.push((scheme, signer_value(certificate)));
            self
        }

        pub fn comment(mut self, comment: &[u8]) -> Self {
            self.comment = comment.to_vec();
            self
        }

        /// The offset of the end record in what `build` returns.
        pub fn end_record_at(&self, built: &[u8]) -> usize {
            built.len() - self.comment.len() - 22
        }

        pub fn build(&self) -> Vec<u8> {
            let mut pairs = Vec::new();
            for (id, value) in &self.pairs {
                pairs.extend_from_slice(&((value.len() + 4) as u64).to_le_bytes());
                pairs.extend_from_slice(&id.to_le_bytes());
                pairs.extend_from_slice(value);
            }
            let size = (pairs.len() + 24) as u64;
            let mut out = b"PK\x03\x04local file data".to_vec();
            out.extend_from_slice(&size.to_le_bytes());
            out.extend_from_slice(&pairs);
            out.extend_from_slice(&size.to_le_bytes());
            out.extend_from_slice(BLOCK_MAGIC);
            let directory = out.len() as u32;
            out.extend_from_slice(&DIRECTORY_SIGNATURE.to_le_bytes());
            out.extend_from_slice(&[0u8; 42]);
            if self.zip64 {
                out.extend_from_slice(&ZIP64_LOCATOR_SIGNATURE.to_le_bytes());
                out.extend_from_slice(&[0u8; 16]);
            }
            out.extend_from_slice(&END_RECORD_SIGNATURE.to_le_bytes());
            out.extend_from_slice(&[0u8; 8]);
            out.extend_from_slice(&46u32.to_le_bytes());
            out.extend_from_slice(&directory.to_le_bytes());
            out.extend_from_slice(&(self.comment.len() as u16).to_le_bytes());
            out.extend_from_slice(&self.comment);
            out
        }
    }
}

#[cfg(test)]
mod tests {
    use super::fixture::{signer_value, Apk};
    use super::*;
    use std::io::Cursor;

    fn certificate_of(apk: Vec<u8>) -> Result<Vec<u8>, SignerError> {
        signer_certificate(&mut Cursor::new(apk))
    }

    #[test]
    fn reads_the_certificate_of_the_v2_and_the_v3_scheme() {
        for scheme in SCHEMES_BY_PREFERENCE {
            let apk = Apk::signed(scheme, b"certificate").build();
            assert_eq!(certificate_of(apk), Ok(b"certificate".to_vec()));
        }
    }

    #[test]
    fn v3_is_read_before_v2_whatever_their_order_in_the_block() {
        let v2_first = Apk::signed(SCHEME_V2, b"old").with_pair(SCHEME_V3, b"new");
        assert_eq!(certificate_of(v2_first.build()), Ok(b"new".to_vec()));
        let v3_first = Apk::signed(SCHEME_V3, b"new").with_pair(SCHEME_V2, b"old");
        assert_eq!(certificate_of(v3_first.build()), Ok(b"new".to_vec()));
    }

    #[test]
    fn finds_the_end_record_behind_a_comment_that_contains_its_signature() {
        let apk = Apk::signed(SCHEME_V2, b"certificate")
            .comment(b"a comment PK\x05\x06 with a fake signature")
            .build();
        assert_eq!(certificate_of(apk), Ok(b"certificate".to_vec()));
    }

    #[test]
    fn finds_the_end_record_behind_the_longest_comment() {
        let apk = Apk::signed(SCHEME_V3, b"certificate")
            .comment(&vec![b'c'; 0xffff])
            .build();
        assert_eq!(certificate_of(apk), Ok(b"certificate".to_vec()));
    }

    #[test]
    fn the_digest_is_the_sha256_of_the_certificate() {
        let apk = Apk::signed(SCHEME_V3, b"certificate").build();
        let expected: [u8; 32] = Sha256::digest(b"certificate").into();
        assert_eq!(signer_digest(&mut Cursor::new(apk)), Ok(expected));
    }

    #[test]
    fn a_block_without_a_v2_or_v3_pair_has_no_signer() {
        let other_scheme = Apk::signed(0x1b93_ad61, b"certificate").build();
        assert_eq!(certificate_of(other_scheme), Err(SignerError::NoSigner));
        let mut empty = Apk::signed(SCHEME_V3, b"certificate");
        empty.pairs.clear();
        assert_eq!(certificate_of(empty.build()), Err(SignerError::NoSigner));
    }

    #[test]
    fn an_empty_certificate_is_no_signer() {
        let apk = Apk::signed(SCHEME_V3, b"").build();
        assert_eq!(certificate_of(apk), Err(SignerError::NoSigner));
    }

    #[test]
    fn files_that_are_not_zip_archives_are_reported() {
        assert_eq!(
            certificate_of(b"not a zip".to_vec()),
            Err(SignerError::NotZip)
        );
        assert_eq!(certificate_of(Vec::new()), Err(SignerError::NotZip));
        assert_eq!(certificate_of(vec![0u8; 100_000]), Err(SignerError::NotZip));
    }

    #[test]
    fn an_apk_without_a_signing_block_is_reported() {
        let mut unsigned = Apk::signed(SCHEME_V3, b"certificate").build();
        let footer = unsigned.windows(16).position(|w| w == BLOCK_MAGIC).unwrap();
        unsigned[footer] ^= 0xff;
        assert_eq!(certificate_of(unsigned), Err(SignerError::NoSigningBlock));
    }

    #[test]
    fn a_central_directory_too_close_to_the_start_has_no_room_for_a_block() {
        // 4 bytes of directory signature followed by 42 bytes and an end record, with nothing in front.
        let mut tiny = Vec::new();
        tiny.extend_from_slice(&DIRECTORY_SIGNATURE.to_le_bytes());
        tiny.extend_from_slice(&[0u8; 42]);
        tiny.extend_from_slice(&END_RECORD_SIGNATURE.to_le_bytes());
        tiny.extend_from_slice(&[0u8; 8]);
        tiny.extend_from_slice(&46u32.to_le_bytes());
        tiny.extend_from_slice(&0u32.to_le_bytes());
        tiny.extend_from_slice(&0u16.to_le_bytes());
        assert_eq!(certificate_of(tiny), Err(SignerError::NoSigningBlock));
    }

    #[test]
    fn block_sizes_that_disagree_or_do_not_fit_are_malformed() {
        let apk = Apk::signed(SCHEME_V3, b"certificate");
        let built = apk.build();
        let footer = built.windows(16).position(|w| w == BLOCK_MAGIC).unwrap();

        // The size at the front of the block differs from the one in the footer.
        let mut front = built.clone();
        let front_at =
            built.windows(7).position(|w| w == b"local f").unwrap() + b"local file data".len();
        front[front_at] ^= 0x01;
        assert_eq!(certificate_of(front), Err(SignerError::Malformed));

        // A footer that claims more than the whole file, and one larger than any real block.
        for claimed in [u64::MAX, 1 << 40, 5] {
            let mut footer_broken = built.clone();
            footer_broken[footer - 8..footer].copy_from_slice(&claimed.to_le_bytes());
            assert_eq!(
                certificate_of(footer_broken),
                Err(SignerError::Malformed),
                "{claimed}"
            );
        }
    }

    #[test]
    fn lengths_inside_the_block_never_read_out_of_bounds() {
        let built = Apk::signed(SCHEME_V3, b"certificate").build();
        let at = built.windows(11).position(|w| w == b"certificate").unwrap() - 4;
        let mut too_long = built.clone();
        too_long[at..at + 4].copy_from_slice(&u32::MAX.to_le_bytes());
        assert_eq!(certificate_of(too_long), Err(SignerError::Malformed));

        let mut short_pair = Apk::signed(SCHEME_V3, b"certificate");
        short_pair.pairs.insert(0, (0, Vec::new()));
        let mut bytes = short_pair.build();
        // Make the first pair's length 3, too short to hold its own id.
        let first = bytes
            .windows(8)
            .position(|w| w == 4u64.to_le_bytes())
            .unwrap();
        bytes[first..first + 8].copy_from_slice(&3u64.to_le_bytes());
        assert_eq!(certificate_of(bytes), Err(SignerError::Malformed));
    }

    #[test]
    fn a_signer_value_that_ends_early_is_malformed() {
        for cut in 0..signer_value(b"certificate").len() {
            let value = signer_value(b"certificate");
            assert!(first_certificate(&value[..cut]).is_err(), "cut at {cut}");
        }
        assert!(first_certificate(&signer_value(b"certificate")).is_ok());
    }

    #[test]
    fn zip64_apks_are_refused() {
        let mut apk = Apk::signed(SCHEME_V3, b"certificate");
        apk.zip64 = true;
        assert_eq!(certificate_of(apk.build()), Err(SignerError::Malformed));
    }

    #[test]
    fn the_central_directory_must_end_where_the_end_record_begins() {
        let apk = Apk::signed(SCHEME_V3, b"certificate");
        let mut built = apk.build();
        let record = apk.end_record_at(&built);
        // The size field of the end record, 12 bytes into it.
        built[record + 12..record + 16].copy_from_slice(&47u32.to_le_bytes());
        assert_eq!(certificate_of(built), Err(SignerError::Malformed));
    }

    #[test]
    fn a_file_can_be_read_from_disk_and_compared_with_a_digest() {
        let directory = std::env::temp_dir().join(format!("nuke-signer-{}", std::process::id()));
        std::fs::create_dir_all(&directory).unwrap();
        let path = directory.join("module.apk");
        std::fs::write(&path, Apk::signed(SCHEME_V3, b"certificate").build()).unwrap();
        let path = path.to_str().unwrap();
        let expected: [u8; 32] = Sha256::digest(b"certificate").into();
        assert_eq!(verify_against(path, &expected), Verdict::Matches);
        assert_eq!(verify_against(path, &[0u8; 32]), Verdict::Mismatch);
        assert_eq!(
            verify_against("/missing/module.apk", &expected),
            Verdict::Unreadable(SignerError::Io)
        );
        std::fs::remove_dir_all(&directory).unwrap();
    }

    #[test]
    fn hex_digits_round_trip_and_bad_text_is_refused() {
        let digest: [u8; 32] = Sha256::digest(b"certificate").into();
        assert_eq!(parse_hex(&hex(&digest)), Some(digest));
        assert_eq!(parse_hex(&format!("  {}\n", hex(&digest))), Some(digest));
        assert_eq!(parse_hex("xyz"), None);
        assert_eq!(parse_hex(&"g".repeat(64)), None);
        assert_eq!(parse_hex(&"é".repeat(32)), None);
        assert_eq!(parse_hex(&hex(&digest)[..62]), None);
    }
}
