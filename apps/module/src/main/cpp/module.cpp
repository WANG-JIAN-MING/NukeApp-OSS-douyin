#include <android/log.h>
#include <dirent.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <atomic>
#include <algorithm>
#include <cctype>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <iterator>
#include <mutex>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#include "dobby.h"
#include "lsplant.hpp"
#include "xdl.h"
#include "zygisk.hpp"

namespace {
// Keep in step with io.github.nuke.oss.assembly.HostScope; tools/check_apks.py compares both lists.
constexpr const char *kHostProcesses[] = {"io.github.nuke.oss.fixture", "com.ss.android.ugc.aweme"};

// Douyin's non-isolated child processes are named <package>:<suffix>; sandboxed ones are never entered. Keep in step
// with HostScope.processOf.
constexpr const char kChildHost[] = "com.ss.android.ugc.aweme";
constexpr const char kSandboxPrefix[] = "sandboxed_process";

bool is_child_process(const char *name) {
    const size_t length = sizeof(kChildHost) - 1;
    if (std::strncmp(name, kChildHost, length) != 0 || name[length] != ':' || name[length + 1] == '\0') return false;
    return std::strncmp(name + length + 1, kSandboxPrefix, sizeof(kSandboxPrefix) - 1) != 0;
}

bool is_host_process(const char *name) {
    return std::any_of(std::begin(kHostProcesses), std::end(kHostProcesses),
                       [name](const char *host) { return std::strcmp(name, host) == 0; }) || is_child_process(name);
}
constexpr size_t kMaxPayloadBytes = 64 * 1024 * 1024;
constexpr size_t kMaxNativeBytes = 32 * 1024 * 1024;

// These objects, payload bytes, callbacks and the native image live until process exit.
JavaVM *java_vm = nullptr;
std::vector<std::vector<unsigned char>> payloads;

// The native libraries the module's code loads, read while the process can still read the module directory.
struct NativeLibrary {
    std::string name;
    std::vector<unsigned char> bytes;
};
std::vector<NativeLibrary> natives;
jobject payload_loader = nullptr;
void *art_handle = nullptr;
std::mutex initialize_mutex;
std::atomic<bool> initialized{false};
bool initialization_attempted = false;

constexpr const char *kLogTag = "NukeApp-OSS/Zygisk";
constexpr const char *kNotReached = "the host process was not reached";

// The step the last failed bootstrap stopped at, for the marker file written beside the log.
std::string last_failure;
// What LSPlant's initialization could not find or install: ART symbols that did not resolve, symbols it asked to be
// matched by prefix, inline hooks that were refused. Written to the marker file when initialization fails.
std::string init_diag;

void note_init(const std::string &entry) {
    if (init_diag.size() < 1500) init_diag += entry + ";";
}

void log_error(const char *stage) {
    __android_log_write(ANDROID_LOG_ERROR, kLogTag, stage);
}

// Logs the step that failed and reports failure, so each early return of bootstrap names itself.
bool fail(const char *step) {
    last_failure = step;
    log_error(step);
    return false;
}

// Writes what this process went through to cache/nukeapp-zygisk.txt in the host's data directory. Logcat drops the
// early native lines on some devices, and a file shows whether postAppSpecialize ran at all. Only the host process gets
// one, and only after specialization, when the process may write its own directory.
void write_marker(JNIEnv *env, jstring data_directory, const std::string &text) {
    if (data_directory == nullptr) return;
    const char *directory = env->GetStringUTFChars(data_directory, nullptr);
    if (directory == nullptr) {
        env->ExceptionClear();
        return;
    }
    std::string path = std::string(directory) + "/cache/nukeapp-zygisk.txt";
    env->ReleaseStringUTFChars(data_directory, directory);
    int fd = open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd < 0) return;
    size_t offset = 0;
    while (offset < text.size()) {
        ssize_t count = write(fd, text.data() + offset, text.size() - offset);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) break;
        offset += static_cast<size_t>(count);
    }
    close(fd);
}

// Clears the pending exception and returns its toString and those of up to three causes, cut to a length a marker file
// can hold. Used where logcat may not show the Java side's own report.
std::string describe_exception(JNIEnv *env) {
    jthrowable error = env->ExceptionOccurred();
    env->ExceptionClear();
    std::string text;
    for (int depth = 0; error != nullptr && depth < 4; ++depth) {
        jclass type = env->GetObjectClass(error);
        jmethodID to_string = env->GetMethodID(type, "toString", "()Ljava/lang/String;");
        jmethodID get_cause = env->GetMethodID(type, "getCause", "()Ljava/lang/Throwable;");
        env->DeleteLocalRef(type);
        if (to_string == nullptr || get_cause == nullptr) {
            env->ExceptionClear();
            break;
        }
        auto description = static_cast<jstring>(env->CallObjectMethod(error, to_string));
        if (!env->ExceptionCheck() && description != nullptr) {
            const char *chars = env->GetStringUTFChars(description, nullptr);
            if (chars != nullptr) {
                if (!text.empty()) text += " <- caused by ";
                text += chars;
                env->ReleaseStringUTFChars(description, chars);
            }
        }
        env->ExceptionClear();
        auto cause = static_cast<jthrowable>(env->CallObjectMethod(error, get_cause));
        env->ExceptionClear();
        error = cause;
    }
    for (char &letter : text) if (letter == '\n' || letter == '\r') letter = ' ';
    if (text.size() > 700) text.resize(700);
    return text.empty() ? "no description" : text;
}

bool clear_exception(JNIEnv *env, const char *stage) {
    if (!env->ExceptionCheck()) return false;
    log_error(stage);
    env->ExceptionClear();
    return true;
}

// Reads one regular file of at least `minimum` bytes, counting it against `limit` together with what `total` already holds.
bool read_file(int directory, const char *filename, size_t minimum, size_t limit, size_t &total, std::vector<unsigned char> &bytes) {
    int fd = openat(directory, filename, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return false;
    struct stat status {};
    if (fstat(fd, &status) != 0 || !S_ISREG(status.st_mode) || status.st_size < static_cast<off_t>(minimum) ||
        static_cast<uint64_t>(status.st_size) > limit - total) {
        close(fd);
        return false;
    }
    bytes.assign(static_cast<size_t>(status.st_size), 0);
    size_t offset = 0;
    while (offset < bytes.size()) {
        ssize_t count = read(fd, bytes.data() + offset, bytes.size() - offset);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) break;
        offset += static_cast<size_t>(count);
    }
    close(fd);
    return offset == bytes.size();
}

bool read_dex(int directory, const char *filename, size_t &total) {
    std::vector<unsigned char> bytes;
    if (!read_file(directory, filename, 112, kMaxPayloadBytes, total, bytes) || std::memcmp(bytes.data(), "dex\n", 4) != 0) return false;
    total += bytes.size();
    payloads.push_back(std::move(bytes));
    return true;
}

unsigned dex_index(const std::string &name) {
    if (name == "classes.dex") return 1;
    if (!name.starts_with("classes") || !name.ends_with(".dex")) return 0;
    std::string digits = name.substr(7, name.size() - 11);
    if (digits.empty() || digits.front() == '0') return 0;
    unsigned index = 0;
    for (char digit : digits) {
        if (digit < '0' || digit > '9' || index > 128) return 0;
        index = index * 10 + static_cast<unsigned>(digit - '0');
    }
    return index >= 2 && index <= 128 ? index : 0;
}

bool read_payload(int directory) {
    int fd = openat(directory, "payload", O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return false;
    DIR *listing = fdopendir(fd);
    if (listing == nullptr) {
        close(fd);
        return false;
    }
    std::vector<std::pair<unsigned, std::string>> files;
    bool valid = true;
    errno = 0;
    while (dirent *entry = readdir(listing)) {
        std::string name(entry->d_name);
        if (name == "." || name == "..") continue;
        unsigned index = dex_index(name);
        if (index == 0 || files.size() >= 128) {
            valid = false;
            break;
        }
        files.emplace_back(index, std::move(name));
    }
    if (errno != 0 || files.empty()) valid = false;
    std::sort(files.begin(), files.end());
    size_t total = 0;
    payloads.reserve(files.size());
    for (size_t position = 0; valid && position < files.size(); ++position) {
        valid = files[position].first == position + 1 &&
            read_dex(fd, files[position].second.c_str(), total);
    }
    closedir(listing);
    if (!valid) payloads.clear();
    return valid;
}

bool native_name_valid(const std::string &name) {
    if (name.size() < 7 || name.size() > 64 || !name.starts_with("lib") || !name.ends_with(".so")) return false;
    return std::all_of(name.begin(), name.end(), [](char letter) {
        return std::isalnum(static_cast<unsigned char>(letter)) || letter == '_' || letter == '.' || letter == '-' || letter == '+';
    });
}

// Reads the module's natives directory: plain lib*.so files that start with the ELF magic, 32 MiB at most together. A
// module without one is valid, and nothing is read then. Any other problem rejects the whole directory.
bool read_natives(int directory) {
    int fd = openat(directory, "natives", O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return errno == ENOENT;
    DIR *listing = fdopendir(fd);
    if (listing == nullptr) {
        close(fd);
        return false;
    }
    bool valid = true;
    size_t total = 0;
    errno = 0;
    while (dirent *entry = readdir(listing)) {
        std::string name(entry->d_name);
        if (name == "." || name == "..") continue;
        NativeLibrary library{name, {}};
        if (!native_name_valid(name) || natives.size() >= 64 ||
            !read_file(fd, name.c_str(), 64, kMaxNativeBytes, total, library.bytes) ||
            std::memcmp(library.bytes.data(), "\x7f" "ELF", 4) != 0) {
            valid = false;
            break;
        }
        total += library.bytes.size();
        natives.push_back(std::move(library));
    }
    if (errno != 0) valid = false;
    closedir(listing);
    if (!valid) natives.clear();
    return valid;
}

// A digest of the library names and bytes, so a different build gets its own directory and never reads another's files.
uint64_t native_digest() {
    uint64_t hash = 14695981039346656037ULL;
    auto mix = [&hash](const unsigned char *data, size_t size) {
        for (size_t index = 0; index < size; ++index) hash = (hash ^ data[index]) * 1099511628211ULL;
    };
    for (const NativeLibrary &library : natives) {
        mix(reinterpret_cast<const unsigned char *>(library.name.data()), library.name.size());
        uint64_t size = library.bytes.size();
        mix(reinterpret_cast<const unsigned char *>(&size), sizeof(size));
        mix(library.bytes.data(), library.bytes.size());
    }
    return hash;
}

bool write_whole_file(const std::string &path, const std::vector<unsigned char> &bytes) {
    int fd = open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd < 0) return false;
    size_t offset = 0;
    while (offset < bytes.size()) {
        ssize_t count = write(fd, bytes.data() + offset, bytes.size() - offset);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) break;
        offset += static_cast<size_t>(count);
    }
    close(fd);
    return offset == bytes.size();
}

// Deletes the library directories of other builds from the host's code_cache.
void remove_stale_natives(const std::string &cache, const std::string &keep) {
    DIR *listing = opendir(cache.c_str());
    if (listing == nullptr) return;
    while (dirent *entry = readdir(listing)) {
        std::string name(entry->d_name);
        std::string path = cache + "/" + name;
        if (!name.starts_with("nuke-natives-") || path == keep) continue;
        if (DIR *stale = opendir(path.c_str())) {
            while (dirent *file = readdir(stale)) {
                std::string file_name(file->d_name);
                if (file_name != "." && file_name != "..") unlink((path + "/" + file_name).c_str());
            }
            closedir(stale);
            rmdir(path.c_str());
        }
    }
    closedir(listing);
}

// Writes the libraries into the host's own code_cache after specialization, when the process may write there and the
// host's linker namespace may load from it. Returns the directory, or an empty string when there are none or any step
// failed; the Java side then reports the libraries as unavailable instead of loading a partial set.
std::string install_natives(JNIEnv *env, jstring data_directory) {
    if (natives.empty() || data_directory == nullptr) return "";
    const char *chars = env->GetStringUTFChars(data_directory, nullptr);
    if (chars == nullptr) {
        env->ExceptionClear();
        return "";
    }
    std::string cache = std::string(chars) + "/code_cache";
    env->ReleaseStringUTFChars(data_directory, chars);
    char digest[17];
    std::snprintf(digest, sizeof(digest), "%016llx", static_cast<unsigned long long>(native_digest()));
    std::string directory = cache + "/nuke-natives-" + digest;
    if (mkdir(directory.c_str(), 0700) != 0 && errno != EEXIST) return "";
    for (const NativeLibrary &library : natives) {
        std::string path = directory + "/" + library.name;
        struct stat status {};
        if (stat(path.c_str(), &status) == 0 && S_ISREG(status.st_mode) &&
            static_cast<size_t>(status.st_size) == library.bytes.size()) continue;
        // Written under another name and renamed, so a process that starts at the same time never loads half a file.
        std::string temporary = path + ".tmp" + std::to_string(getpid());
        if (!write_whole_file(temporary, library.bytes) || rename(temporary.c_str(), path.c_str()) != 0) {
            unlink(temporary.c_str());
            return "";
        }
    }
    remove_stale_natives(cache, directory);
    return directory;
}

jboolean native_initialize(JNIEnv *, jclass) {
    std::lock_guard guard(initialize_mutex);
    if (initialization_attempted) return initialized.load() ? JNI_TRUE : JNI_FALSE;
    initialization_attempted = true;
    if (java_vm == nullptr) return JNI_FALSE;

    // A fresh attached thread has no payload Java caller frame. LSPlant's Init performs
    // private ART JNI lookups and requires this unrestricted native-call context.
    // Native code does not change the process hidden-API policy (the Java bootstrap exempts a few framework classes with
    // LSPass) and never retries a partially failed Init.
    std::thread worker([] {
        JNIEnv *env = nullptr;
        if (java_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        art_handle = xdl_open("libart.so", XDL_DEFAULT);
        if (art_handle == nullptr) note_init("libart_open_failed");
        if (art_handle != nullptr) {
            lsplant::InitInfo info{
                .inline_hooker = [](void *target, void *replacement) -> void * {
                    dobby_dummy_func_t backup = nullptr;
                    int status = DobbyHook(target, reinterpret_cast<dobby_dummy_func_t>(replacement), &backup);
                    if (status != 0) note_init("hook_refused=" + std::to_string(status));
                    return status == 0 ? reinterpret_cast<void *>(backup) : nullptr;
                },
                .inline_unhooker = [](void *target) { return DobbyDestroy(target) == 0; },
                .art_symbol_resolver = [](std::string_view name) -> void * {
                    std::string terminated(name);
                    void *symbol = xdl_sym(art_handle, terminated.c_str(), nullptr);
                    if (symbol == nullptr) symbol = xdl_dsym(art_handle, terminated.c_str(), nullptr);
                    if (symbol == nullptr) note_init("missing=" + terminated);
                    return symbol;
                },
                // LSPlant's Init rejects an empty resolver although its header calls it optional. It is only asked
                // after an exact lookup failed, and null is the documented answer for a symbol that is not found, so
                // this reports each such request instead of scanning for a prefix.
                .art_symbol_prefix_resolver = [](std::string_view prefix) -> void * {
                    note_init("prefix_requested=" + std::string(prefix));
                    return nullptr;
                },
                // Trampolines come from Dobby's own allocation, so LSPlant gets no executable-memory hooks.
                .executable_memory_allocator = nullptr,
                .executable_memory_recycler = nullptr,
            };
            bool ready = lsplant::Init(env, info);
            if (clear_exception(env, "LSPlant initialization raised a JNI exception")) ready = false;
            initialized.store(ready);
        }
        java_vm->DetachCurrentThread();
    });
    worker.join();
    if (!initialized.load()) log_error("LSPlant initialization failed; hooks remain disabled");
    return initialized.load() ? JNI_TRUE : JNI_FALSE;
}

jobject native_hook(JNIEnv *env, jclass, jobject target, jobject callback, jobject method) {
    if (!initialized.load() || target == nullptr || callback == nullptr || method == nullptr) return nullptr;
    return lsplant::Hook(env, target, callback, method);
}

jboolean native_unhook(JNIEnv *env, jclass, jobject target) {
    if (!initialized.load() || target == nullptr) return JNI_FALSE;
    return lsplant::UnHook(env, target) ? JNI_TRUE : JNI_FALSE;
}

void throw_illegal_argument(JNIEnv *env, const char *message) {
    jclass type = env->FindClass("java/lang/IllegalArgumentException");
    if (type != nullptr) env->ThrowNew(type, message);
}

// Reads one boxed primitive; a null or a wrong box throws on the Java side instead of reading garbage.
bool unbox(JNIEnv *env, jobject value, jchar kind, jvalue *out) {
    struct Box { jchar kind; const char *type; const char *getter; const char *signature; };
    static constexpr Box kBoxes[] = {
        {'Z', "java/lang/Boolean", "booleanValue", "()Z"}, {'B', "java/lang/Byte", "byteValue", "()B"},
        {'C', "java/lang/Character", "charValue", "()C"}, {'S', "java/lang/Short", "shortValue", "()S"},
        {'I', "java/lang/Integer", "intValue", "()I"}, {'J', "java/lang/Long", "longValue", "()J"},
        {'F', "java/lang/Float", "floatValue", "()F"}, {'D', "java/lang/Double", "doubleValue", "()D"},
    };
    if (kind == 'L') {
        out->l = value;
        return true;
    }
    const Box *box = std::find_if(std::begin(kBoxes), std::end(kBoxes), [kind](const Box &it) { return it.kind == kind; });
    if (box == std::end(kBoxes)) {
        throw_illegal_argument(env, "Unknown parameter kind");
        return false;
    }
    jclass type = env->FindClass(box->type);
    if (type == nullptr) return false;
    if (value == nullptr || !env->IsInstanceOf(value, type)) {
        env->DeleteLocalRef(type);
        throw_illegal_argument(env, "Primitive constructor argument is null or of the wrong type");
        return false;
    }
    jmethodID getter = env->GetMethodID(type, box->getter, box->signature);
    env->DeleteLocalRef(type);
    if (getter == nullptr) return false;
    switch (kind) {
        case 'Z': out->z = env->CallBooleanMethod(value, getter); break;
        case 'B': out->b = env->CallByteMethod(value, getter); break;
        case 'C': out->c = env->CallCharMethod(value, getter); break;
        case 'S': out->s = env->CallShortMethod(value, getter); break;
        case 'I': out->i = env->CallIntMethod(value, getter); break;
        case 'J': out->j = env->CallLongMethod(value, getter); break;
        case 'F': out->f = env->CallFloatMethod(value, getter); break;
        default: out->d = env->CallDoubleMethod(value, getter); break;
    }
    return !env->ExceptionCheck();
}

// Runs a constructor's LSPlant backup on an instance that is already allocated, which reflection cannot do. kinds holds
// one JVM type letter per parameter (Z B C S I J F D, L for references). An exception the constructor throws stays
// pending and reaches the Java caller as itself.
void native_invoke_constructor(JNIEnv *env, jclass, jobject backup, jclass type, jobject receiver, jobjectArray arguments, jcharArray kinds) {
    if (!initialized.load() || backup == nullptr || type == nullptr || receiver == nullptr || arguments == nullptr || kinds == nullptr) {
        throw_illegal_argument(env, "Constructor backup call is incomplete");
        return;
    }
    jmethodID method = env->FromReflectedMethod(backup);
    jsize count = env->GetArrayLength(kinds);
    if (method == nullptr || count != env->GetArrayLength(arguments)) {
        throw_illegal_argument(env, "Constructor backup arguments do not match");
        return;
    }
    if (env->PushLocalFrame(count + 8) != JNI_OK) return;
    std::vector<jchar> letters(static_cast<size_t>(count));
    env->GetCharArrayRegion(kinds, 0, count, letters.data());
    std::vector<jvalue> values(static_cast<size_t>(count));
    for (jsize index = 0; index < count; ++index) {
        jobject value = env->GetObjectArrayElement(arguments, index);
        if (env->ExceptionCheck() || !unbox(env, value, letters[static_cast<size_t>(index)], &values[static_cast<size_t>(index)])) {
            env->PopLocalFrame(nullptr);
            return;
        }
    }
    env->CallNonvirtualVoidMethodA(receiver, type, method, values.data());
    env->PopLocalFrame(nullptr);
}

jclass load_class(JNIEnv *env, jobject loader, jmethodID method, const char *name) {
    jstring class_name = env->NewStringUTF(name);
    if (class_name == nullptr) return nullptr;
    auto type = static_cast<jclass>(env->CallObjectMethod(loader, method, class_name));
    env->DeleteLocalRef(class_name);
    return type;
}

bool bootstrap(JNIEnv *env, const std::string &process_name, const std::string &native_directory) {
    jclass class_loader = env->FindClass("java/lang/ClassLoader");
    if (class_loader == nullptr) return fail("Bootstrap: java/lang/ClassLoader not found");
    jmethodID get_system = env->GetStaticMethodID(class_loader, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
    jmethodID load = env->GetMethodID(class_loader, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    if (get_system == nullptr || load == nullptr) return fail("Bootstrap: ClassLoader methods not found");
    jobject parent = env->CallStaticObjectMethod(class_loader, get_system);
    if (env->ExceptionCheck() || parent == nullptr) return fail("Bootstrap: system class loader unavailable");
    jclass memory_loader = env->FindClass("dalvik/system/InMemoryDexClassLoader");
    if (memory_loader == nullptr) return fail("Bootstrap: InMemoryDexClassLoader not found");
    jmethodID constructor = env->GetMethodID(memory_loader, "<init>", "([Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
    if (constructor == nullptr) return fail("Bootstrap: InMemoryDexClassLoader constructor not found");
    jclass byte_buffer = env->FindClass("java/nio/ByteBuffer");
    if (byte_buffer == nullptr) return fail("Bootstrap: ByteBuffer not found");
    jobjectArray buffers = env->NewObjectArray(static_cast<jsize>(payloads.size()), byte_buffer, nullptr);
    if (buffers == nullptr) return fail("Bootstrap: ByteBuffer array not allocated");
    for (size_t index = 0; index < payloads.size(); ++index) {
        auto &dex = payloads[index];
        jobject bytes = env->NewDirectByteBuffer(dex.data(), static_cast<jlong>(dex.size()));
        if (bytes == nullptr) return fail("Bootstrap: DEX ByteBuffer not allocated");
        env->SetObjectArrayElement(buffers, static_cast<jsize>(index), bytes);
        env->DeleteLocalRef(bytes);
        if (env->ExceptionCheck()) return fail("Bootstrap: storing a DEX ByteBuffer raised an exception");
    }
    jobject loader = env->NewObject(memory_loader, constructor, buffers, parent);
    if (env->ExceptionCheck() || loader == nullptr) return fail("Bootstrap: InMemoryDexClassLoader construction failed");
    payload_loader = env->NewGlobalRef(loader);
    if (payload_loader == nullptr) return fail("Bootstrap: payload loader global reference failed");

    jclass driver = load_class(env, loader, load, "io.github.nuke.oss.backend.lsplant.JniLsplantDriver");
    if (env->ExceptionCheck() || driver == nullptr) return fail("Bootstrap: JniLsplantDriver not found in the payload");
    JNINativeMethod methods[] = {
        {const_cast<char *>("nativeInitialize"), const_cast<char *>("()Z"), reinterpret_cast<void *>(native_initialize)},
        {const_cast<char *>("nativeHook"), const_cast<char *>("(Ljava/lang/reflect/Executable;Ljava/lang/Object;Ljava/lang/reflect/Method;)Ljava/lang/reflect/Executable;"), reinterpret_cast<void *>(native_hook)},
        {const_cast<char *>("nativeUnhook"), const_cast<char *>("(Ljava/lang/reflect/Executable;)Z"), reinterpret_cast<void *>(native_unhook)},
        {const_cast<char *>("nativeInvokeConstructor"), const_cast<char *>("(Ljava/lang/reflect/Executable;Ljava/lang/Class;Ljava/lang/Object;[Ljava/lang/Object;[C)V"), reinterpret_cast<void *>(native_invoke_constructor)},
    };
    if (env->RegisterNatives(driver, methods, std::size(methods)) != JNI_OK) return fail("Bootstrap: RegisterNatives failed");
    jclass entry = load_class(env, loader, load, "io.github.nuke.oss.loader.zygisk.ZygiskEntry");
    if (env->ExceptionCheck() || entry == nullptr) return fail("Bootstrap: ZygiskEntry not found in the payload");
    jmethodID start = env->GetStaticMethodID(entry, "bootstrap", "(Ljava/lang/String;Ljava/lang/String;)V");
    if (start == nullptr) return fail("Bootstrap: ZygiskEntry.bootstrap not found");
    // /proc/self/cmdline still names the zygote here, so Java receives the name checked in preAppSpecialize.
    jstring name = env->NewStringUTF(process_name.c_str());
    if (name == nullptr) return fail("Bootstrap: process name string not allocated");
    // Java claims process ownership before calling nativeInitialize or touching ART.
    // No libraries to hand over is passed as null, which the Java side reports.
    jstring natives_path = native_directory.empty() ? nullptr : env->NewStringUTF(native_directory.c_str());
    if (!native_directory.empty() && natives_path == nullptr) return fail("Bootstrap: native directory string not allocated");
    env->CallStaticVoidMethod(entry, start, name, natives_path);
    if (!env->ExceptionCheck()) return true;
    std::string reason = "Bootstrap: ZygiskEntry.bootstrap threw " + describe_exception(env);
    return fail(reason.c_str());
}

class NukeModule final : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        api_ = api;
        env_ = env;
        env->GetJavaVM(&java_vm);
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        int app_id = args->uid % 100000;
        if (app_id < 10000 || app_id > 19999 || args->nice_name == nullptr || args->app_data_dir == nullptr) {
            unload();
            return;
        }
        const char *name = env_->GetStringUTFChars(args->nice_name, nullptr);
        if (name == nullptr) {
            clear_exception(env_, "Process name unavailable");
            unload();
            return;
        }
        bool target = is_host_process(name);
        std::string name_copy(name);
        env_->ReleaseStringUTFChars(args->nice_name, name);
        if (!target) {
            unload();
            return;
        }
        const char *data_directory = env_->GetStringUTFChars(args->app_data_dir, nullptr);
        if (data_directory == nullptr) {
            clear_exception(env_, "Application data directory unavailable");
            unload();
            return;
        }
        // A different app may request the same process name. Require the data path's
        // final package segment as a second independent identity check before ART Init.
        const char *package_segment = std::strrchr(data_directory, '/');
        // A child process is named <package>:<suffix>, and the data directory is the package's.
        const std::string package_name = name_copy.substr(0, name_copy.find(':'));
        bool host_directory = package_segment != nullptr &&
            std::strcmp(package_segment + 1, package_name.c_str()) == 0;
        env_->ReleaseStringUTFChars(args->app_data_dir, data_directory);
        if (!host_directory) {
            unload();
            return;
        }
        int directory = api_->getModuleDir();
        if (directory >= 0) {
            selected_ = read_payload(directory);
            if (selected_ && !read_natives(directory)) native_note_ = "natives directory was not valid";
            close(directory);
            if (!selected_) note_ = "payload directory was not valid";
        } else {
            note_ = "getModuleDir returned no directory";
        }
        // Even failures in the selected process keep this image mapped. No hook code
        // or JNI callback can ever point at an explicitly unloaded module.
        if (!selected_) {
            // Logging before specialization does not reach logcat on every device, so postAppSpecialize repeats this.
            log_error("Payload DEX directory could not be read before specialization");
            return;
        }
        process_name_ = std::move(name_copy);
        __android_log_print(ANDROID_LOG_INFO, kLogTag, "Payload selected for %s with %zu DEX",
                            process_name_.c_str(), payloads.size());
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs *args) override {
        bool host = selected_ || note_ != kNotReached;
        if (host) {
            write_marker(env_, args->app_data_dir, "post_called pid=" + std::to_string(getpid()) + " selected=" +
                (selected_ ? "yes" : "no") + " note=" + note_ + "\n");
        }
        if (!selected_) {
            __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Payload not selected for the host process: %s", note_.c_str());
            return;
        }
        __android_log_print(ANDROID_LOG_INFO, kLogTag, "Bootstrapping %s with %zu DEX", process_name_.c_str(), payloads.size());
        if (env_->PushLocalFrame(64) != JNI_OK) {
            clear_exception(env_, "Unable to allocate JNI local frame");
            return;
        }
        std::string native_directory = install_natives(env_, args->app_data_dir);
        bool ready = bootstrap(env_, process_name_, native_directory);
        clear_exception(env_, "Payload bootstrap raised a JNI exception");
        write_marker(env_, args->app_data_dir, "post_called pid=" + std::to_string(getpid()) + " bootstrap=" +
            (ready ? "returned" : "failed") + " last_failure=" + (last_failure.empty() ? "none" : last_failure) +
            " natives=" + (native_directory.empty() ? (natives.empty() ? "none" : "not_written") : "ready") +
            (native_note_.empty() ? "" : " native_note=" + native_note_) +
            (init_diag.empty() ? "" : " init=" + init_diag) + "\n");
        if (ready) {
            __android_log_write(ANDROID_LOG_INFO, kLogTag, "Payload bootstrap returned");
        } else {
            log_error("Payload bootstrap failed; host runtime is inactive");
        }
        env_->PopLocalFrame(nullptr);
    }

    void preServerSpecialize(zygisk::ServerSpecializeArgs *) override { unload(); }

private:
    void unload() { api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY); }
    zygisk::Api *api_ = nullptr;
    JNIEnv *env_ = nullptr;
    bool selected_ = false;
    std::string process_name_;
    // Why the payload was not selected, for postAppSpecialize to log.
    std::string note_ = kNotReached;
    // Empty unless the module's natives directory was rejected.
    std::string native_note_;
};
}

REGISTER_ZYGISK_MODULE(NukeModule)
