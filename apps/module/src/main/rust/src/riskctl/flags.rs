//! The flags the checks raise and the tripwire that decides which of them count. A flag is one bit of a 64-bit set; each
//! has a name, which the report carries, and a class that says how much it counts for on its own:
//!
//! - `Hard` is evidence of a debugger, an injection or an instrumentation tool in this process, or of modified code.
//! - `Weak` is a surface that such tools use but ordinary software also leaves (a writable and executable mapping, a
//!   readable kernel file); alone it means little.
//! - `Env` is the state of the device itself, such as root, an unlocked bootloader or a changed SELinux policy.
//! - `Note` is a fact worth showing and nothing more.
//!
//! The tripwire follows the older native engine this was taken from: a hard flag trips on its own, and the weak flags and
//! the kernel-tamper flag are only counted when a hard flag is present, so a rooted phone or a JIT's memory does not trip
//! it, while the same surfaces next to a debugger or Frida do.

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Class {
    Hard,
    Weak,
    Env,
    Note,
}

impl Class {
    pub fn code(self) -> &'static str {
        match self {
            Class::Hard => "hard",
            Class::Weak => "weak",
            Class::Env => "env",
            Class::Note => "note",
        }
    }
}

pub struct FlagInfo {
    pub name: &'static str,
    pub bit: u64,
    pub class: Class,
}

macro_rules! flags {
    ($($name:ident = $bit:expr, $class:ident;)*) => {
        $(pub const $name: u64 = 1 << $bit;)*
        /// Every flag, in bit order.
        pub const CATALOGUE: &[FlagInfo] = &[$(FlagInfo { name: stringify!($name), bit: $name, class: Class::$class }),*];
    };
}

flags! {
    DEBUGGER = 0, Hard;
    GDB_PTRACE = 1, Hard;
    FRIDA = 2, Hard;
    LINKER_ENV = 3, Hard;
    FD_SUSPICIOUS = 4, Hard;
    EXEC_INTEGRITY = 5, Hard;
    SIGNATURE_HOOK = 6, Weak;
    PROC_MAPS = 7, Weak;
    HOOK_SURFACE = 8, Weak;
    MEMFD_DELETED = 9, Weak;
    WX_MAPPING = 10, Weak;
    NATIVE_PATH = 11, Weak;
    MULTI_MAPPING = 12, Weak;
    DUMP_SURFACE = 13, Weak;
    OTHER_HOOK = 14, Weak;
    NETWORK_SURFACE = 15, Weak;
    XPOSED = 16, Env;
    ZYGISK = 17, Env;
    MAGISK = 18, Env;
    ROOT_TOOL = 19, Env;
    SU_BINARY = 20, Env;
    BOOT_UNLOCKED = 21, Env;
    SELINUX_PERMISSIVE = 22, Env;
    KERNEL_TAMPER = 23, Env;
    DEBUGGABLE_BUILD = 24, Env;
    NATIVE_BRIDGE = 25, Note;
    ADBD_RUNNING = 26, Note;
    TEST_KEYS = 27, Note;
    PROXY = 28, Note;
}

fn mask(class: Class) -> u64 {
    CATALOGUE
        .iter()
        .filter(|flag| flag.class == class)
        .fold(0, |all, flag| all | flag.bit)
}

pub fn hard_mask() -> u64 {
    mask(Class::Hard)
}

pub fn weak_mask() -> u64 {
    mask(Class::Weak)
}

/// The flags of `flags` that trip the wire: every hard flag, and, when there is one, every weak flag and the
/// kernel-tamper flag with them. Without a hard flag nothing trips.
pub fn tripwire(flags: u64) -> u64 {
    let hard = flags & hard_mask();
    if hard == 0 {
        return 0;
    }
    hard | (flags & weak_mask()) | (flags & KERNEL_TAMPER)
}

/// The catalogue entries whose bit is set in `flags`, in bit order.
pub fn named(flags: u64) -> impl Iterator<Item = &'static FlagInfo> {
    CATALOGUE.iter().filter(move |flag| flags & flag.bit != 0)
}
