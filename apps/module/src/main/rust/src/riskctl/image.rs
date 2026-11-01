//! Whether the executable code of a loaded library is still the code of its file. A library's executable segments are
//! hashed with CRC32C twice, once from the file on disk and once from the memory they were loaded into; an inline hook, a
//! patch or a trampoline written over the code makes the two differ. It is run for the library that holds the module's
//! signature check and for libc, since a patched libc is the way to make a file or a clock read lie. CRC32C is a quick
//! probe for tampering, not a signature, and the memory is read from the current mapping, not from the instruction cache.

const ELF64_HEADER_LEN: usize = 64;
const ELF64_PROGRAM_HEADER_LEN: usize = 56;
const PT_LOAD: u32 = 1;
const PF_X: u32 = 1;
const CRC32C_POLYNOMIAL: u32 = 0x82f6_3b78;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ImageResult {
    /// The executable code in memory is the code of the file.
    Match,
    /// The segments or their bytes differ.
    Mismatch,
    /// The image is loaded but its file or its memory could not be read.
    Unavailable,
    /// No loaded image has that file name.
    NotLoaded,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ExecSegment {
    pub offset: u64,
    pub vaddr: u64,
    pub filesz: u64,
}

const fn crc32c_table() -> [u32; 256] {
    let mut table = [0u32; 256];
    let mut index = 0usize;
    while index < table.len() {
        let mut value = index as u32;
        let mut bit = 0;
        while bit < 8 {
            value = if value & 1 != 0 {
                (value >> 1) ^ CRC32C_POLYNOMIAL
            } else {
                value >> 1
            };
            bit += 1;
        }
        table[index] = value;
        index += 1;
    }
    table
}

const CRC32C_TABLE: [u32; 256] = crc32c_table();

fn crc32c_update(mut state: u32, bytes: &[u8]) -> u32 {
    for byte in bytes {
        let index = ((state ^ u32::from(*byte)) & 0xff) as usize;
        state = CRC32C_TABLE[index] ^ (state >> 8);
    }
    state
}

/// The CRC32C (Castagnoli) of `bytes`.
pub fn crc32c(bytes: &[u8]) -> u32 {
    !crc32c_update(u32::MAX, bytes)
}

fn read_u16(bytes: &[u8], offset: usize) -> Option<u16> {
    let value = bytes.get(offset..offset.checked_add(2)?)?;
    Some(u16::from_le_bytes(value.try_into().ok()?))
}

fn read_u32(bytes: &[u8], offset: usize) -> Option<u32> {
    let value = bytes.get(offset..offset.checked_add(4)?)?;
    Some(u32::from_le_bytes(value.try_into().ok()?))
}

fn read_u64(bytes: &[u8], offset: usize) -> Option<u64> {
    let value = bytes.get(offset..offset.checked_add(8)?)?;
    Some(u64::from_le_bytes(value.try_into().ok()?))
}

/// Where the program header table of a little-endian 64-bit ELF file is: its offset, entry size and entry count. `None`
/// for anything else, and for a table that does not fit in the file.
pub fn parse_elf_header(header: &[u8], file_len: u64) -> Option<(u64, usize, usize)> {
    if header.len() < ELF64_HEADER_LEN
        || header.get(0..4)? != b"\x7fELF"
        || header[4] != 2
        || header[5] != 1
    {
        return None;
    }
    let phoff = read_u64(header, 0x20)?;
    let phentsize = usize::from(read_u16(header, 0x36)?);
    let phnum = usize::from(read_u16(header, 0x38)?);
    if phentsize < ELF64_PROGRAM_HEADER_LEN || phnum == 0 || phnum > 1024 {
        return None;
    }
    let table_len = phentsize.checked_mul(phnum)?;
    let table_end = phoff.checked_add(table_len as u64)?;
    if table_len > 1024 * 1024 || table_end > file_len {
        return None;
    }
    Some((phoff, phentsize, phnum))
}

/// The loadable executable segments of a program header table. `None` when the table is malformed, a segment reaches past
/// the end of the file, or there is no executable segment at all.
pub fn parse_program_headers(
    table: &[u8],
    phentsize: usize,
    phnum: usize,
    file_len: u64,
) -> Option<Vec<ExecSegment>> {
    if table.len() != phentsize.checked_mul(phnum)? {
        return None;
    }
    let mut segments = Vec::new();
    for index in 0..phnum {
        let base = index.checked_mul(phentsize)?;
        let entry = table.get(base..base.checked_add(phentsize)?)?;
        if read_u32(entry, 0)? != PT_LOAD || read_u32(entry, 4)? & PF_X == 0 {
            continue;
        }
        let offset = read_u64(entry, 8)?;
        let vaddr = read_u64(entry, 16)?;
        let filesz = read_u64(entry, 32)?;
        if filesz == 0 || offset.checked_add(filesz)? > file_len {
            return None;
        }
        segments.push(ExecSegment {
            offset,
            vaddr,
            filesz,
        });
    }
    if segments.is_empty() {
        None
    } else {
        Some(segments)
    }
}

/// The executable segments of a whole ELF file held in memory.
pub fn parse_exec_segments(elf: &[u8]) -> Option<Vec<ExecSegment>> {
    let header = elf.get(..ELF64_HEADER_LEN)?;
    let (phoff, phentsize, phnum) = parse_elf_header(header, elf.len() as u64)?;
    let table_len = phentsize.checked_mul(phnum)?;
    let start = usize::try_from(phoff).ok()?;
    let table = elf.get(start..start.checked_add(table_len)?)?;
    parse_program_headers(table, phentsize, phnum, elf.len() as u64)
}

/// Whether every byte of `start..end` lies in a readable mapping of a `/proc/self/maps` text. The code of a library is
/// read straight from memory, and some systems map system code execute-only; a read there would crash the process.
pub fn readable(maps: &str, start: u64, end: u64) -> bool {
    let mut ranges: Vec<(u64, u64)> = maps
        .lines()
        .filter_map(|line| {
            let mut fields = line.split_whitespace();
            let (low, high) = fields.next()?.split_once('-')?;
            if !fields.next()?.starts_with('r') {
                return None;
            }
            Some((
                u64::from_str_radix(low, 16).ok()?,
                u64::from_str_radix(high, 16).ok()?,
            ))
        })
        .collect();
    ranges.sort_unstable();
    let mut cursor = start;
    while cursor < end {
        match ranges
            .iter()
            .find(|(low, high)| *low <= cursor && cursor < *high)
        {
            Some((_, high)) => cursor = *high,
            None => return false,
        }
    }
    true
}

#[cfg(all(unix, target_pointer_width = "64"))]
mod loaded {
    use super::{
        crc32c_update, parse_elf_header, parse_program_headers, readable, ExecSegment, ImageResult,
        ELF64_HEADER_LEN, PF_X, PT_LOAD,
    };
    use std::ffi::{c_char, c_int, c_void, CStr};
    use std::fs::File;
    use std::os::unix::fs::FileExt;
    use std::slice;

    const IO_CHUNK_LEN: usize = 64 * 1024;

    #[repr(C)]
    struct ProgramHeader {
        p_type: u32,
        p_flags: u32,
        p_offset: u64,
        p_vaddr: u64,
        p_paddr: u64,
        p_filesz: u64,
        p_memsz: u64,
        p_align: u64,
    }

    // The leading fields of `struct dl_phdr_info`, which bionic and glibc share; later fields are never read.
    #[repr(C)]
    struct DlPhdrInfo {
        dlpi_addr: usize,
        dlpi_name: *const c_char,
        dlpi_phdr: *const ProgramHeader,
        dlpi_phnum: u16,
    }

    struct LoadedImage {
        base: usize,
        path: String,
        exec_segments: Vec<ExecSegment>,
    }

    extern "C" {
        fn dl_iterate_phdr(
            callback: Option<unsafe extern "C" fn(*mut DlPhdrInfo, usize, *mut c_void) -> c_int>,
            data: *mut c_void,
        ) -> c_int;
    }

    unsafe extern "C" fn collect_image(
        info: *mut DlPhdrInfo,
        _size: usize,
        data: *mut c_void,
    ) -> c_int {
        if info.is_null() || data.is_null() {
            return 0;
        }
        // SAFETY: the loader passes a valid `dl_phdr_info` for the duration of the callback, and `info` was checked for null.
        let info = unsafe { &*info };
        if info.dlpi_name.is_null()
            || info.dlpi_phdr.is_null()
            || info.dlpi_phnum == 0
            || info.dlpi_phnum > 1024
        {
            return 0;
        }
        // SAFETY: `dlpi_name` is a NUL-terminated string owned by the loader and valid during the callback.
        let path = unsafe { CStr::from_ptr(info.dlpi_name) }
            .to_string_lossy()
            .into_owned();
        if path.is_empty() {
            return 0;
        }
        // SAFETY: the loader guarantees `dlpi_phnum` program headers at `dlpi_phdr` during the callback.
        let headers =
            unsafe { slice::from_raw_parts(info.dlpi_phdr, usize::from(info.dlpi_phnum)) };
        let exec_segments = headers
            .iter()
            .filter(|header| header.p_type == PT_LOAD && header.p_flags & PF_X != 0)
            .filter(|header| header.p_filesz != 0)
            .map(|header| ExecSegment {
                offset: header.p_offset,
                vaddr: header.p_vaddr,
                filesz: header.p_filesz,
            })
            .collect();
        // SAFETY: `data` is the `Vec<LoadedImage>` that `loaded_images` lends to this call, alive and not aliased.
        let images = unsafe { &mut *(data.cast::<Vec<LoadedImage>>()) };
        images.push(LoadedImage {
            base: info.dlpi_addr,
            path,
            exec_segments,
        });
        0
    }

    fn loaded_images() -> Vec<LoadedImage> {
        let mut images: Vec<LoadedImage> = Vec::new();
        // SAFETY: the callback only reads what the loader hands it and pushes into `images`, which outlives the call.
        unsafe {
            dl_iterate_phdr(
                Some(collect_image),
                (&mut images as *mut Vec<LoadedImage>).cast::<c_void>(),
            );
        }
        images
    }

    fn read_exact_at(file: &File, mut offset: u64, mut out: &mut [u8]) -> std::io::Result<()> {
        while !out.is_empty() {
            let read = file.read_at(out, offset)?;
            if read == 0 {
                return Err(std::io::Error::from(std::io::ErrorKind::UnexpectedEof));
            }
            offset = offset
                .checked_add(read as u64)
                .ok_or_else(|| std::io::Error::from(std::io::ErrorKind::InvalidData))?;
            out = &mut out[read..];
        }
        Ok(())
    }

    fn file_exec_segments(file: &File) -> Option<Vec<ExecSegment>> {
        let file_len = file.metadata().ok()?.len();
        let mut header = [0u8; ELF64_HEADER_LEN];
        read_exact_at(file, 0, &mut header).ok()?;
        let (phoff, phentsize, phnum) = parse_elf_header(&header, file_len)?;
        let mut table = vec![0u8; phentsize.checked_mul(phnum)?];
        read_exact_at(file, phoff, &mut table).ok()?;
        parse_program_headers(&table, phentsize, phnum, file_len)
    }

    fn file_range_crc32c(file: &File, offset: u64, len: u64) -> Option<u32> {
        let mut state = u32::MAX;
        let mut cursor = offset;
        let mut remaining = len;
        let mut buffer = vec![0u8; IO_CHUNK_LEN];
        while remaining != 0 {
            let count = usize::try_from(remaining.min(buffer.len() as u64)).ok()?;
            read_exact_at(file, cursor, &mut buffer[..count]).ok()?;
            state = crc32c_update(state, &buffer[..count]);
            cursor = cursor.checked_add(count as u64)?;
            remaining -= count as u64;
        }
        Some(!state)
    }

    fn memory_range_crc32c(base: usize, segment: ExecSegment, maps: &str) -> Option<u32> {
        let start = (base as u64).checked_add(segment.vaddr)?;
        let end = start.checked_add(segment.filesz)?;
        if start == 0 || segment.filesz == 0 || !readable(maps, start, end) {
            return None;
        }
        let address = usize::try_from(start).ok()?;
        let len = usize::try_from(segment.filesz).ok()?;
        // SAFETY: `start..end` was just found inside readable mappings of this process, and the library whose code this
        // is stays loaded while the process runs; the bytes are only read.
        let bytes = unsafe { slice::from_raw_parts(address as *const u8, len) };
        let mut state = u32::MAX;
        for chunk in bytes.chunks(IO_CHUNK_LEN) {
            state = crc32c_update(state, chunk);
        }
        Some(!state)
    }

    fn check_image(image: &LoadedImage, maps: &str) -> ImageResult {
        let Ok(file) = File::open(&image.path) else {
            return ImageResult::Unavailable;
        };
        let Some(disk_segments) = file_exec_segments(&file) else {
            return ImageResult::Unavailable;
        };
        if disk_segments != image.exec_segments {
            return ImageResult::Mismatch;
        }
        for segment in disk_segments {
            let Some(disk) = file_range_crc32c(&file, segment.offset, segment.filesz) else {
                return ImageResult::Unavailable;
            };
            let Some(memory) = memory_range_crc32c(image.base, segment, maps) else {
                return ImageResult::Unavailable;
            };
            if disk != memory {
                return ImageResult::Mismatch;
            }
        }
        ImageResult::Match
    }

    pub fn check(file_name: &str, maps: Option<&str>) -> ImageResult {
        let mut result = ImageResult::NotLoaded;
        for image in loaded_images() {
            if image.path.rsplit('/').next() != Some(file_name) {
                continue;
            }
            let status = match maps {
                Some(maps) => check_image(&image, maps),
                None => ImageResult::Unavailable,
            };
            result = match (result, status) {
                (ImageResult::Mismatch, _) | (_, ImageResult::Mismatch) => ImageResult::Mismatch,
                (ImageResult::Unavailable, _) | (_, ImageResult::Unavailable) => {
                    ImageResult::Unavailable
                }
                _ => ImageResult::Match,
            };
        }
        result
    }
}

/// Compares the executable code of the loaded library called `file_name` with its file. `maps` is the text of
/// `/proc/self/maps`, which tells which memory may be read.
#[cfg(all(unix, target_pointer_width = "64"))]
pub fn check_loaded(file_name: &str, maps: Option<&str>) -> ImageResult {
    loaded::check(file_name, maps)
}

/// Libraries are only inspected on 64-bit Unix systems, which is every supported Android target.
#[cfg(not(all(unix, target_pointer_width = "64")))]
pub fn check_loaded(_file_name: &str, _maps: Option<&str>) -> ImageResult {
    ImageResult::Unavailable
}
