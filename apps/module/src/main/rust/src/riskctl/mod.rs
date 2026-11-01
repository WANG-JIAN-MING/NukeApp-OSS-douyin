//! Device trust checks: what this device and this process look like from the inside. `classify` turns what was read into
//! a verdict per check and holds all the judgement, `flags` names the flags the checks raise and decides which of them
//! trip the wire, `image` compares the code of loaded libraries with their files, `source` reads the process and the
//! system, and `bridge` is the JNI binding. The checks only observe: nothing here changes behavior, blocks anything or
//! reports anywhere, and a hook in libc can mislead the readings, so a result is evidence for a person to read and not
//! proof for a program to rely on.

mod bridge;
mod classify;
mod flags;
mod image;
mod source;

pub use classify::*;
pub use flags::*;
pub use image::{
    check_loaded, crc32c, parse_elf_header, parse_exec_segments, parse_program_headers, readable,
    ExecSegment, ImageResult,
};
pub use source::scan;

/// How much a finding matters. `Info` is a common state among people who run this module (root, an unlocked
/// bootloader), reported and not held against the device; `Warn` weakens a protection of the platform; `Alert` is the
/// presence of a debugger, an injection or an instrumentation tool in this process, or modified code.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Level {
    Ok,
    Info,
    Warn,
    Alert,
    /// The state could not be read, which is not the same as it being fine.
    Unknown,
}

impl Level {
    pub fn code(self) -> &'static str {
        match self {
            Level::Ok => "ok",
            Level::Info => "info",
            Level::Warn => "warn",
            Level::Alert => "alert",
            Level::Unknown => "unknown",
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Check {
    /// A fixed name for the check; the Java side maps it to a label.
    pub id: &'static str,
    pub level: Level,
    /// A short plain-text note: no paths of the process, no addresses, no values of properties beyond a state word.
    pub detail: String,
    /// The flags this finding raises, a set of the bits in `flags`.
    pub flags: u64,
}

impl Check {
    pub(crate) fn new(id: &'static str, level: Level, detail: impl Into<String>) -> Self {
        Check {
            id,
            level,
            detail: detail.into(),
            flags: 0,
        }
    }

    /// For tests outside the crate, which cannot reach `new`.
    #[doc(hidden)]
    pub fn new_for_test(id: &'static str, level: Level, detail: &str) -> Self {
        Check::new(id, level, detail)
    }

    pub(crate) fn flagged(mut self, flags: u64) -> Self {
        self.flags |= flags;
        self
    }
}

/// The checks of one run, every flag they raised, and the part of those flags that trips the wire.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Report {
    pub checks: Vec<Check>,
    pub flags: u64,
    pub tripwire: u64,
}

impl Report {
    pub fn new(checks: Vec<Check>) -> Self {
        let flags = checks.iter().fold(0, |all, check| all | check.flags);
        Report {
            tripwire: tripwire(flags),
            flags,
            checks,
        }
    }
}

const MAX_DETAIL_CHARS: usize = 120;

fn clean(detail: &str) -> String {
    detail
        .chars()
        .map(|letter| if letter.is_control() { ' ' } else { letter })
        .take(MAX_DETAIL_CHARS)
        .collect()
}

/// The report as lines. A check is `check<TAB>id<TAB>level<TAB>detail`; every flag that is set is
/// `flag<TAB>NAME<TAB>class<TAB>1 if it trips the wire else 0`. Control characters in a detail become spaces and a detail
/// is cut to 120 characters, so a line can never be split or smuggle another field in.
pub fn encode(report: &Report) -> String {
    let mut text = String::new();
    for check in &report.checks {
        text.push_str("check\t");
        text.push_str(check.id);
        text.push('\t');
        text.push_str(check.level.code());
        text.push('\t');
        text.push_str(&clean(&check.detail));
        text.push('\n');
    }
    for flag in named(report.flags) {
        text.push_str("flag\t");
        text.push_str(flag.name);
        text.push('\t');
        text.push_str(flag.class.code());
        text.push('\t');
        text.push(if report.tripwire & flag.bit != 0 {
            '1'
        } else {
            '0'
        });
        text.push('\n');
    }
    text
}
