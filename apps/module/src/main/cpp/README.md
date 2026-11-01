# NukeApp-OSS Native 与安装资源

Native CMake 工程位于应用 `src/main/cpp`，静态安装资源直接位于同一主 source set 的 [resources](../resources)。它们都归属 `:apps:module`，不另设 Gradle 模块、顶层源码工程或模板源集。

这是仅适用于自有 `io.github.nuke.oss.fixture` 主进程的最小入口。APK 安装器将当前 APK 的 `classes.dex`、`classes2.dex` 等按原名保存到模块目录的 `payload/`，并将本库安装为 `zygisk/<abi>.so`。

`preAppSpecialize` 严格检查进程名、应用数据目录最后一段包名与常规应用 UID 范围，目录为空或没有斜杠时拒绝，排除系统、隔离和 SDK sandbox 进程；使用 `getModuleDir` 与 `openat` 在降权前按数字序读取总计最大 64 MiB、最多 128 个连续编号的原始 DEX，拒绝符号链接与非普通文件。其他进程设置 `DLCLOSE_MODULE_LIBRARY`。目标进程的内存、classloader、ART 依赖与本库保持到进程结束，不进行库隐藏、挂载规避或检测规避。

`postAppSpecialize` 使用系统 ClassLoader 作为父加载器使用 API 27 的 `ByteBuffer[]` 构造器创建 `InMemoryDexClassLoader`，先绑定 `JniLsplantDriver` 的三个 native 方法，再调用 `ZygiskEntry.bootstrap(进程名, 库目录)`。库目录来自 `customize.sh` 放进模块目录 `natives/` 的 `lib*.so`（只收 ELF、最多 32 MiB、64 个）：`preAppSpecialize` 还能读模块目录时读入内存，`postAppSpecialize` 降权后写进宿主自己的 `code_cache/nuke-natives-<摘要>/`（先写临时名再改名，旧构建的同名前缀目录会删掉），Java 入口再用 `BaseDexClassLoader.addNativePath` 把它加给载荷加载器。没有这个目录时按“没有 native 库”如实报告。Java 入口先完成跨加载器进程所有权仲裁，取得所有权后才调用 `nativeInitialize()`；Native 不抢先修改 ART。

LSPlant 要求初始化线程允许访问 ART 的私有 JNI 成员。这里在全新 AttachCurrentThread 的 Native 工作线程内执行一次 Init 并等待结果，避免 payload Java 调用帧作为初始化查找的调用者；不修改进程的隐藏 API 策略。失败后保持 Hook 禁用并报告阶段，不重试部分失败的初始化。此策略仍须在目标 Android/ART 环境实测，构建成功不代表该初始化已经在设备通过。

## APK / ZIP 双用安装

[resources](../resources) 中的 `module.prop`、`customize.sh`、`META-INF/com/google/android/*` 与第三方许可证通过默认 Java resources 源集进入 APK root，不做模板替换，也不需要额外资源目录注册。现代 Xposed 元数据仍位于该源集的 `META-INF/xposed`。Native 库通过 JNI libraries 在 APK 签名前一起装配，Debug 和 R8 Release 均携带三种入口，没有 flavor。

安装器只提取当前 ABI 的 `libnuke_zygisk.so` 与同一 APK 的全部 `classes*.dex`，不下载其他载荷。APK 原字节复制并改扩展名为 ZIP 后可交给 Magisk 模块安装器；应用内导出同样只复制原字节，不能在签名后改写归档。安装器的非 Root 临时目录测试由仓库根目录的 `tools/check_zygisk_installer.py` 执行，不代表已经在设备上安装。

三个入口的总体说明和各入口的限制见仓库根目录的 [README](../../../../../README.md)。

## JNI 契约

`io.github.nuke.oss.backend.lsplant.JniLsplantDriver`：

```text
nativeInitialize()Z
nativeHook(Ljava/lang/reflect/Method;Ljava/lang/Object;Ljava/lang/reflect/Method;)Ljava/lang/reflect/Method;
nativeUnhook(Ljava/lang/reflect/Method;)Z
```

callback 必须是传入对象的 `public Object invoke(Object[])` 方法。实例方法的数组首项是 receiver，静态方法没有 receiver 占位；返回的 backup 由 Kotlin 层显式调用。LSPlant 在生成类的静态字段中持有 callback，并为 backup 持有全局引用。公共调度器负责 Hook 订阅与在途调用；JNI 驱动关闭后保留物理 Hook 与 backup 到进程结束，避免晚到 trampoline 与物理卸载竞态。`nativeUnhook` 仅保留底层接口，不用于生产会话的逻辑关闭。

## 固定依赖与构建

权威版本见 [dependencies.lock.json](dependencies.lock.json)。使用 LSPlant（v6.4 之后的上游提交 d8b5d1db，2026-09-09，含 Android 16 的修复；上游没有再打标签，更晚的提交要求外部 libc++ 模块包，暂不采用）、对应 DexBuilder/parallel-hashmap，以及 Dobby 官方 Android 兼容修复提交，xDL 和 Zygisk API 4。上游源缓存在仓库根目录被忽略的 `build/native/deps`；不修改第三方源码。Dobby 的旧版 logger 在函数内包含 Android 日志头，因此 CMake 对该文件预包含此头以兼容 NDK 28 的 inline 函数。

在仓库根目录执行：

```bash
python3 tools/build_native.py
python3 tools/build_native.py --offline
python3 tools/build_native.py --offline --export-relink artifacts/zygisk-relink
```

脚本读取 `local.properties`、Android SDK 环境变量或 `--sdk`，固定 NDK `29.0.13113456`、CMake `4.1.2`、API 27，串行构建 arm64-v8a，输出 `build/native/<abi>/libnuke_zygisk.so`。所有依赖与 C++ 运行库静态链接；动态依赖仅为 Android 系统库。脚本检查 16 KiB ELF LOAD 对齐、Zygisk 导出符号与没有 `libc++_shared.so`，写入 `build/native/verification.json`。这不覆盖实际 Zygisk 加载、SELinux、ART 私有符号版本、真实 Hook 或 16 KiB 设备运行。

源码目录变化时，脚本根据 `CMakeCache.txt` 的源目录记录丢弃对应 ABI 的工具生成输出并重新配置，保留共享依赖缓存。依赖缓存迁到 `build/native/deps`；旧工作区中仅由工具生成的 `native/zygisk/build/deps` 可移到这里复用，迁移后删除旧的空源码目录。

## 第三方源码与重新链接

应用资源中的 [third-party](../resources/third-party) 是许可证的单一权威源，随 APK 一起分发，不在 CMake 源码目录重复维护。[该目录](../resources/third-party) 保留上游许可证原文：LSPlant 与 DexBuilder 为 LGPL-3.0，Dobby 与 parallel-hashmap 为 Apache-2.0，xDL 为 MIT，Zygisk 头为 ISC。此目录没有为整个 NukeApp-OSS 仓库指定许可证。

对外分发静态链接的库时同时保留锁定版本的全部对应源码、许可证及重链接输入。使用 `--export-relink` 导出每个 ABI 的自有入口对象文件、四个静态归档、链接参数与 `relink.py`；按锁文件重新构建修改后的 LSPlant/DexBuilder 后替换对应 `.a`，运行 `python3 relink.py /path/to/Android/Sdk` 即可重链接。更改公开接口时还需按同一源码工程重新编译入口。依赖源码可由锁文件中的 Git URL 和 revision 获取，并应随交付打包存档，不能只提供易失的本机缓存路径。
