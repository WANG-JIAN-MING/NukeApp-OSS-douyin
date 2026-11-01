# riskctl

模块 Rust 核心（`nuke-protocol-core`）里的设备可信检测：只读地看一眼这台手机和当前进程的状态，把结果交给界面显示。

## 先说清楚

**riskctl 的代码仅供娱乐。** 它跑在用户自己的设备上，读的是用户自己的进程和系统，用户随时可以改它、绕过它或者让它说谎，所以它的结果不能当成安全保证，也不能拿来当做任何决定的唯一依据。

**服务端是否拒收日志，由服务端决定。** 客户端这边的检测只是把观察到的情况摆出来（对话框里给人看，将来发日志时作为随附的参考信息），不会替服务端拒收、放行或打折任何东西；服务端怎么看待一份日志，是服务端的事，不以这里的结果为准。

它也不会做任何反应：不拦截功能、不改行为、不伪造数据、不上报到任何地方。旧的原生引擎里“检测到就伪造签名”之类的反应没有搬过来。

## 做什么

一次 `scan()` 按固定顺序跑 17 项检测，每项给出一个级别和一句不超过 120 个字符的说明：

| 检测 | 看什么 |
| --- | --- |
| `debugger` | `/proc/self/status` 里的 `TracerPid` |
| `injection` | 环境里有没有 `LD_PRELOAD`、`LD_AUDIT` |
| `frida` | 内存映射、线程名、文件描述符里有没有 Frida 的痕迹 |
| `descriptors` | 有没有调试服务器或内存转储相关的描述符 |
| `signature_path` | 保护模块签名校验的库和 libc，可执行代码在内存里和磁盘文件里是否一致 |
| `maps_tools` | 内存映射里点名的 Xposed 系、Riru/Zygisk、Magisk、KernelSU/APatch、其他 Hook 库、ARM 转译层 |
| `maps_memory` | 可写又可执行的映射、来自 memfd 或已删除文件的可执行内存（JIT 自己的不算）、模块库来自临时位置或两个路径 |
| `network` | Frida 默认端口在监听、带 Frida 名字的 socket、环境里的代理 |
| `root` | 常见路径下有没有 `su` |
| `bootloader` | bootloader 锁定状态和验证启动状态 |
| `selinux` | SELinux 的强制/宽容模式 |
| `selinux_policy` | `/sys/fs/selinux/status` 里的策略计数器是不是和正常启动一致 |
| `kernel_exposure` | 内核符号地址、`/proc/kcore` 对应用是否可读 |
| `mounts` | root 工具的挂载、系统分区是 overlay 或可写 |
| `build` | 可调试构建、`ro.secure` 被关、test-keys |
| `emulator` | 模拟器或 ARM 转译层 |
| `adbd` | adbd 是否在运行 |

级别有五种：`ok`、`info`（常见于会用这个模块的人，比如 root、bootloader 解锁，只提示）、`warn`（削弱了系统的某项保护）、`alert`（进程里有调试器、注入、插桩工具，或者代码被改过）、`unknown`（读不到，不等于没问题）。

## 标志位和绊线

每项检测会置位相关的标志，一共 29 个，按类分：

- **硬证据（6）：** `DEBUGGER`、`GDB_PTRACE`、`FRIDA`、`LINKER_ENV`、`FD_SUSPICIOUS`、`EXEC_INTEGRITY`
- **弱证据（10）：** `SIGNATURE_HOOK`、`PROC_MAPS`、`HOOK_SURFACE`、`MEMFD_DELETED`、`WX_MAPPING`、`NATIVE_PATH`、`MULTI_MAPPING`、`DUMP_SURFACE`、`OTHER_HOOK`、`NETWORK_SURFACE`
- **设备状态（9）：** `XPOSED`、`ZYGISK`、`MAGISK`、`ROOT_TOOL`、`SU_BINARY`、`BOOT_UNLOCKED`、`SELINUX_PERMISSIVE`、`KERNEL_TAMPER`、`DEBUGGABLE_BUILD`
- **备注（4）：** `NATIVE_BRIDGE`、`ADBD_RUNNING`、`TEST_KEYS`、`PROXY`

绊线沿用旧原生引擎的规则：**硬标志自己就触发；弱标志和 `KERNEL_TAMPER` 只有在出现硬标志时才一起计入。** 没有硬标志，什么都不触发。所以一台 root 过、bootloader 解锁的手机不会触发绊线，而同样的表面出现在调试器或 Frida 旁边就会。

## 已知的局限

- **读数可以被骗。** 所有读数都走普通的系统调用和 libc，能 Hook libc 或系统的工具可以让结果失真。
- **`root` 只看常见路径。** KernelSU 之类会对普通应用隐藏 `su`，所以干净的结果不等于没有 root。
- **`selinux_policy` 是经验值。** 序号 4 且加载 1 次、或序号 0 且加载 0 次算正常，这是旧原生引擎的经验，别的厂商的手机启动方式不同时可能报警告，它只是提示。
- **`signature_path` 依赖可读的映射。** 读内存之前先用 `/proc/self/maps` 确认这段地址可读，遇到只执行不可读的映射就记为“读不到”，而不是去读。
- **系统属性只在 Android 上有。** 其他平台（比如开发机上跑测试）这些检测读不到，记为 `unknown`。

## 文件

| 文件 | 内容 |
| --- | --- |
| `mod.rs` | 级别、检测、报告，以及把报告编码成文本 |
| `classify.rs` | 所有判断：文本进、结果出的纯函数，不碰系统 |
| `flags.rs` | 标志位清单和绊线 |
| `image.rs` | ELF 解析、CRC32C、已加载库的代码和磁盘文件对比 |
| `source.rs` | 读进程和系统，把各项检测串起来 |
| `bridge.rs` | JNI 绑定，`NativeRisk.scan()` |

## 输出格式

`scan()` 的结果编码成一行一条的文本，字段用制表符分隔：

```text
check<TAB>id<TAB>level<TAB>detail
flag<TAB>NAME<TAB>class<TAB>1 或 0
```

`flag` 行只写被置位的标志，最后一列是 1 表示它触发了绊线。说明里的控制字符会换成空格，并截到 120 个字符，一行不会被拆开。Kotlin 一侧在 `io.github.nuke.oss.app.shared.integrity.DeviceTrust` 解析，界面在设置页头像的对话框里。

## 测试

```bash
cd apps/module/src/main/rust
cargo fmt --check
cargo clippy --all-targets -- -D warnings
cargo test
```

测试在 `tests/riskctl.rs`。判断逻辑都是纯函数，用样本文本测；`image.rs` 用手工拼的 ELF 测解析，并在 Linux 开发机上用 glibc 对比一次真实的已加载库；`scan()` 另外测了检测的顺序和输出格式。

## 还没做

- **发送详细日志。** 现在报告只显示在对话框里，不会发送。将来发日志时要以一次现取的报告为基础，代码里留了 `TODO(log-sending)`（`DeviceTrust.kt` 和 `FailureReport.kt`）。
- 旧原生引擎里的内核时序侧信道、`maps` 里匿名可执行段的细分判断没有搬，前者复杂，后者在 Android 10 以上会因为 JIT 对每台设备误报。
