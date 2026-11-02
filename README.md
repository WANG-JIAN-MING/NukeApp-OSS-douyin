# NukeApp-OSS

NukeApp-OSS 是给**抖音**（`com.ss.android.ugc.aweme`）用的 Android Hook 模块。装好以后，抖音的设置页里会多出一行「NukeApp-OSS」，点进去是一百多项功能开关，改动在抖音重启后生效。

> **状态：早期、实验性。** 目前没有发布页，APK 需要自己构建（见[给开发者](#给开发者)）。大多数功能还没有在真机的抖音各版本上逐项验证过，详见[已知限制](#已知限制)。请只在自己的设备上、自担风险使用。

## 能做什么

设置页按分类列出功能，只有「禁用双击点赞」默认开启，其余都默认关闭，用哪个开哪个。

| 分区 | 分类 |
| --- | --- |
| 视频 | 推荐流、播放控制、画质与缓存、视频页面、手势与操作、弹幕、广告 |
| 社交 | 评论、消息、聊天玩法、分享与转发、个人主页、隐私 |
| 应用 | 布局与显示、使用时长、应用与系统、其他 |

举几个例子：不上报已读状态（可只对黑名单或白名单里的会话生效）、自定义火花天数（只有你自己看得到）、自动续火、自动抢红包、聊天消息复读（气泡旁边的「+1」）、去除转发数量限制。每一项在设置页里都有一句说明。

除了内置功能，还能在设置页的「脚本」页导入 `.nks` 脚本包，用 JavaScript 写自己的功能。脚本怎么写、怎么打包见[脚本 SDK](sdk/script-sdk/README.md)。

## 需要什么

- Android 8.1（API 27）及以上。
- 抖音（不含国际版 TikTok）。
- 下面三种方式里选一种让模块进入抖音。三种方式的功能一样，区别只是怎么把模块放进去：

| 方式 | 适合谁 | 要求 |
| --- | --- | --- |
| Xposed | 已经在用 LSPosed 之类的框架 | 支持经典 Xposed（API 82）或现代 libxposed（API 102）的框架 |
| Zygisk | 有 root，用 Magisk、KernelSU 等 | 开启 Zygisk，设备是 `arm64-v8a` |
| 修补 | 没有 root | 用 NukePatcher 给抖音打补丁，Android 9 及以上，4 KiB 页大小 |

同时启用了多种方式也没关系：同一个进程里只有最先到达的那个入口生效，不会重复 Hook。

## 安装

### 第一步：拿到模块 APK

用 Xposed 或 Zygisk 方式时，要把 NukeApp-OSS 的 APK 装到手机上，设置页本身就在这个 APK 里。用修补方式时不用安装它，只要把这个 APK 作为文件选进 NukePatcher。

### 方式一：Xposed

1. 在框架里启用 NukeApp-OSS，作用域选抖音。
2. 彻底重启抖音（强行停止后再打开）。

### 方式二：Zygisk

1. 把模块 APK 改名为 `.zip`（也可以在模块首页「导出模块 APK」，再改名）。**这个 zip 必须和已安装的 APK 是同一个构建。**
2. 在 root 管理器里「从本地安装」这个 zip。
3. 确认 Zygisk 已开启，并且抖音**不在**排除列表（DenyList）里，否则模块可能不会被加载进抖音。
4. 重启手机。

### 方式三：修补（没有 root）

1. 安装 NukePatcher，打开「修补」页。
2. 分别选一个 NukeApp-OSS 模块 APK 和一个完整的单 APK 抖音安装包。
3. 校验通过后开始修补，签名并核对嵌入内容后导出。
4. 自己安装导出的 APK。

需要知道：

- 产物用修补器自己的密钥签名，所以**不能覆盖更新原来签名的应用**，要先卸载原版再装。修补器不会替你安装、卸载或清除数据。
- 不支持拆分 APK、APKS、XAPK，也不支持已经被修补过的应用；16 KiB 页大小的设备会被拒绝。
- 修补器的签名密钥保存在 `io.github.nuke.oss.patcher` 的私有目录，不会跨应用迁移。
- 修补器界面目前只有中文，可以离线使用。

## 使用

1. 打开抖音，进入设置，找到「NukeApp-OSS」。
2. 按分类找到想要的功能，打开开关。有额外选项的功能点进去设置。
3. **强行停止抖音再重新打开**，改动才会生效。

模块应用首页的运行状态目前还接不上宿主，会一直显示「未连接」，这不代表没有生效，以抖音设置页里能看到入口为准。

点设置页顶部的头像，会弹出「设备可信」对话框：只读地检查这台手机和本应用自己的进程，共 17 项，包括调试器、库注入、Frida、调试与转储句柄、签名校验路径（模块签名校验所在的库和 libc 的代码在内存里是否和磁盘上的文件一致，用来发现内联 Hook）、内存映射里的 Hook 与 root 工具和异常的可执行映射、网络面、root、bootloader 与验证启动、SELinux 模式和策略状态、内核符号与 `/proc/kcore` 的暴露、挂载、系统构建、模拟器、adbd。对话框先给总判断，再列出「绊线」状态和被置位的标志位（触发了绊线的标红），最后按严重程度列出每一项。检查在本机完成，结果不会发送到任何地方。

## 出问题时

**抖音里没有「NukeApp-OSS」这一行**

- Xposed：检查作用域里勾了抖音，并且彻底重启过抖音。
- Zygisk：依次检查 Zygisk 是否开启、模块是否启用、抖音是不是在 DenyList 里、有没有重启手机、设备是不是 `arm64-v8a`。然后在电脑上看日志（需要 adb）：

  ```bash
  adb logcat -c
  adb shell am force-stop com.ss.android.ugc.aweme
  adb shell monkey -p com.ss.android.ugc.aweme -c android.intent.category.LAUNCHER 1
  adb logcat -d -s 'NukeApp-OSS/Zygisk'
  ```

  看到 `zygisk_started` 说明运行时起来了。抖音进程里有时看不到这条日志，这时可以读抖音数据目录里的 `cache/nukeapp-zygisk.txt`（需要 root）：里面记着这次启动有没有走到模块、`bootstrap` 是 `returned` 还是 `failed`，失败时还写着停在哪一步。
- 修补：确认装的是修补后的那个包，而不是原版。

**某个功能没效果**：功能开关改完要重启抖音。抖音更新后，部分功能依赖的内部代码可能变了，这样的功能会失效，需要等模块跟进。

**想确认模块在工作**：安装 Hook Fixture（测试应用），它的说明文字会显示当前入口 `backend=xposed82`、`libxposed102` 或 `lsplant`。输入 `Alice`：开启改写时结果是 `hooked:original:NukeApp`，关闭时是 `original:Alice`。这个开关只影响当前进程，重启后又会打开。

## 已知限制

- **验证有限。** 契约、调度和打包有 JVM 测试和 APK 检查覆盖，但测试用的是模拟传输，不会跑真实的 ART。Zygisk 方式在一台 Android 16 设备（ReSukiSU + ReZygisk）上跑通过运行时启动；Magisk 自带的 Zygisk、其他设备和系统版本没有验证。
- **功能效果没有逐项核对。** 设置入口、DexKit 匹配和每个功能的实际效果都没有对照真实的抖音各版本验证过。
- **Zygisk 的 native 库随 zip 提供**，不再要求同时装着同一个构建的 APK 才能加载它们；但这一点还没有在设备上单独验证过，也没有做过「不装 APK」的运行。设置页本身、宿主里显示的模块字符串和图标，以及模块签名检查仍然依赖已安装的 APK（没有 APK 时签名检查记为「未检查」并继续运行）。
- **设备可信检测只是参考。** 它通过系统调用读取本机状态，能 Hook 系统的工具可以让结果失真；root 一项只看常见路径下有没有 `su`，KernelSU 这类管理器会对普通应用隐藏 `su`，所以结果干净不等于设备没有 root。root、bootloader 解锁、adbd 在运行这类常见状态只作提示，不影响总体结论。「SELinux 策略」一项沿用旧原生引擎的经验值（序号 4 且加载 1 次，或序号 0 且加载 0 次算正常），别的厂商的手机启动方式不同时也可能报警告，只是提示，不是定论。
- **还没做：发送详细日志。** 现在日志只能导出成文件。发送详细日志要以设备可信检测为基础，代码里留了 `TODO(log-sending)`，写明了打算怎么接。
- **脚本**：引擎、包读取和脚本页的模型在 JVM 上测过；脚本页、导入、对话框和各个脚本模块没有在设备上的抖音里跑过。
- **模块签名**：模块会检查自己的 APK 是否用发布证书签名；没有发布密钥的构建不做任何强制。在宿主里签名不符会让运行时不启动。
- **Zygisk 的隐藏 API 豁免**：少数框架类通过 LSPass（LSPosed 的 AndroidHiddenApiBypass）豁免隐藏 API 限制，某个 Android 版本是否接受，还没有逐个核对。

## 组成

三个应用互相独立：

| 名称 | 应用 ID | 作用 |
| --- | --- | --- |
| NukeApp-OSS（模块） | `io.github.nuke.oss` | 三种入口都带上的模块，加设置管理界面（中英文）。可以把模块 APK 按原字节导出。 |
| NukePatcher | `io.github.nuke.oss.patcher` | 把模块嵌进抖音的修补器，用固定版本的 [LSPatch](https://github.com/JingMatrix/LSPatch)。不依赖模块，也不要求设备上装了模块。 |
| Hook Fixture | `io.github.nuke.oss.fixture` | 测试应用，用来确认一个 Hook 是否生效。 |

Hook 只会装在两处：Hook Fixture，以及抖音的主进程和少数功能声明过的非隔离子进程（小程序、代码热更新）。名单固定在源码的 `HostScope` 里。

## 给开发者

### 构建

需要：JDK 21、Android SDK 37、NDK 29.0.13113456、CMake 4.1.2、Python 3、Git，以及 Rust 1.90.0（含 rustfmt、clippy 和 `aarch64-linux-android`、`x86_64-linux-android` 两个 target）。把 SDK 路径写进不提交的 `local.properties`（`sdk.dir=...`）。

```bash
rustup toolchain install 1.90.0 --profile minimal --component rustfmt --component clippy \
  --target aarch64-linux-android --target x86_64-linux-android
python3 tools/build_rust.py --check
```

可以直接用系统的 rustup，也可以在执行官方 [rustup 安装命令](https://rust-lang.github.io/rustup/installation/index.html)前先设置项目内的隔离目录（不要改系统 HOME）：

```bash
export CARGO_HOME="$PWD/build/rust-toolchain/cargo"
export RUSTUP_HOME="$PWD/build/rust-toolchain/rustup"
export PATH="$CARGO_HOME/bin:$PATH"
```

第一次构建会下载固定版本的 native 源码、LSPatch JAR 和 Cargo 依赖，之后复用缓存。LSPatch 的准备（核对官方 JAR 的大小和 SHA-256，去掉其中的公开测试私钥和旧签名信息）也可以单独运行：

```bash
python3 tools/prepare_lspatch.py            # 缺少缓存时下载
python3 tools/prepare_lspatch.py --offline  # 缺少资源就失败，不联网
```

```bash
./gradlew verify --max-workers=1 --console=plain          # Debug 构建、测试和各项检查
./gradlew verifyRelease --max-workers=1 --console=plain   # 另加 R8 Release 构建与检查
```

`verify` 会跑：共享的调度、生命周期、仲裁和后端契约测试，用真实 fixture 做的修补往返，Rust 的格式、Clippy 和测试，三个应用的 Debug 构建，脚本 SDK 的 Node 测试（需要 Node 22.2 及以上），以及入口、根目录安装文件、native ABI 和两个 APK 之间隔离的检查。改动 R8 或打包时再用 `verifyRelease`。

签名是可选的。要用自己的密钥给模块和 NukePatcher（Debug 与 Release 都一样）签名，在 `local.properties` 里加上 `signing.storeFile`、`signing.storePassword`、`signing.keyAlias` 和 `signing.keyPassword`，密钥库放在仓库之外。四项不全时，Debug 构建用默认调试密钥，Release 构建不签名。fixture 始终用调试密钥。

| 产物 | 路径 |
| --- | --- |
| 模块，Debug | `apps/module/build/outputs/apk/debug/module-debug.apk` |
| 模块，R8 Release | `apps/module/build/outputs/apk/release/module-release.apk`（没有密钥时是 `module-release-unsigned.apk`） |
| NukePatcher，Debug | `apps/patcher/build/outputs/apk/debug/patcher-debug.apk` |
| NukePatcher，R8 Release | `apps/patcher/build/outputs/apk/release/patcher-release.apk`（没有密钥时是 `patcher-release-unsigned.apk`） |
| Hook Fixture | `fixtures/host/build/outputs/apk/debug/host-debug.apk` |

### 三种入口怎么协调

| 入口 | 需要什么 | 如何进入 |
| --- | --- | --- |
| XP82 | 经典 Xposed 框架（API 82） | 框架的包加载回调 |
| libxposed API 102 | 实现了现代 API 的框架 | 框架的模块加载回调 |
| Zygisk + LSPlant | 启用 Zygisk 的 Magisk，`arm64-v8a` | Native 引导加载模块的 DEX |

三个入口共用一套调度器和一套功能定义：**一个进程里只有最先到达的入口生效，同一个 Hook 不会装两次。** 失败的拥有者在进程结束前一直保有所有权，不会自动切换；这只协调本模块自己的入口，并不保证与其他 ART Hook 模块互斥。项目没有 product flavor；同一个 Debug 或 Release APK 可直接安装，改名为 `.zip` 的副本就是 root 模块。

现代适配器只调用现代 API，绝不回退到传统 API。修补方式嵌入的模块 targetApi 为 102，所以修补后的应用只进入模块已有的 `Libxposed102Entry`；LSPatch 对 API 101 及以上只读取现代入口，不会把同一个模块再按 XP82 加载一遍。LSPatch 固定为 [JingMatrix v1.2-487](https://github.com/JingMatrix/LSPatch/releases/tag/v1.2)，只存在于修补器 APK 里。模块身份校验只检查包名和元数据，不证明文件由谁发布。

Zygisk 的 native 引导细节（JNI 契约、固定依赖、库怎么随 zip 安装并交给载荷）见 [Native 与安装资源说明](apps/module/src/main/cpp/README.md)。

### 参与贡献

提交信息用英文，遵循 Conventional Commits（`feat(scope): description`，详见 `AGENTS.md`）。每个克隆启用一次本地检查：

```bash
git config core.hooksPath .githooks
```

GitHub Actions（`.github/workflows/ci.yml`）对推送和 PR 里的提交做同样的检查；向 `dev`、`master` 推送或提 PR 时还会跑 `./gradlew verifyRelease`（Debug 加 R8 Release）并上传 Release APK。推送时用仓库 secrets `SIGN_KEYSTORE_BASE64`、`SIGN_KEYSTORE_PASSWORD`、`SIGN_KEY_ALIAS`、`SIGN_KEY_PASSWORD` 里的密钥签名，缺任何一项都会失败；PR 拿不到密钥，产物是未签名的 Release。推送成功后，还会用 `TELEGRAM_BOT_TOKEN` 和 `TELEGRAM_CHAT_ID` 把已签名的模块和 NukePatcher APK 连同提交信息发到 Telegram 频道。

### 代码布局

```text
apps/module/       三入口模块应用：界面、装配、宿主适配器、native（CMake）和根目录安装文件
apps/patcher/      独立修补器：界面、修补引擎适配、签名和 Rust 文件核心
core/runtime/      纯 JVM 运行时：Hook 契约、调度、功能生命周期、入口仲裁
core/script/       纯 JVM 脚本引擎（基于 QuickJS）及其内置的 nuke:* 模块
core/loader/       三个入口及其后端；唯一能看到框架 API 的模块
core/designsystem/ 模块应用用的 Nuke 设计系统：token、形状和 Compose 控件
core/hook-ksp/     构建期 KSP 处理器，生成各宿主的 @HookEntry 注册表和 Dex 查询目录
fixtures/host/     独立测试宿主
sdk/script-sdk/    给脚本作者的 npm 包：类型、包检查和 nuke-script 命令
tools/             构建与验证脚本（只在开发机上用）
```

依赖只朝一个方向：模块应用依赖 `core:runtime`、`core:script`、`core:loader` 和 `core:designsystem`；`core:loader` 与 `core:script` 只依赖 `core:runtime`；`core:designsystem`（Compose UI 库）不依赖任何项目模块；修补器和 fixture 没有生产环境的项目依赖。宿主支持放在互不引用的 `io.github.nuke.oss.app.<name>` 包里，只有 `io.github.nuke.oss.assembly` 选择具体宿主。`tools/check_boundaries.py` 强制这些规则。

新增一个宿主需要：一个新包、一个在 `RuntimeAssembly` 里注册的适配器，以及 `HostScope` 里的一项；Xposed 作用域、native 过滤器和 APK 检查都跟着 `HostScope` 走。

抖音里的目标方法用 [DexKit](https://github.com/LuckyPray/DexKit) 查找，结果按宿主构建缓存。

### 更多资料

- [Native 与安装资源说明](apps/module/src/main/cpp/README.md)：Zygisk 引导、JNI 契约、固定依赖和 APK / ZIP 双用安装。
- [脚本 SDK](sdk/script-sdk/README.md)：脚本包的写法、校验和打包。
- [riskctl 设备可信检测](apps/module/src/main/rust/src/riskctl/README.md)：设置页头像对话框背后的检测、标志位和绊线，以及它的局限。
- [AGENTS.md](AGENTS.md)：开发约定（`CLAUDE.md` 是指向它的符号链接）。

## 许可证

仓库按目录分两种许可证：

| 目录 | 许可证 | 原因 |
| --- | --- | --- |
| `apps/patcher`（NukePatcher） | [GPL-3.0](apps/patcher/LICENSE) | 它内置 LSPatch（GPL-3.0） |
| 其余：模块、`core`、`sdk`、`fixtures`、`tools` | [Apache-2.0](LICENSE) | |

版权归 dartcv，见 [NOTICE](NOTICE)。两部分之间没有代码依赖：模块和修补器是各自独立的 APK，修补器只是把模块当文件嵌进目标应用，这属于并列分发，不改变各自的许可证。`tools/check_boundaries.py` 会检查这一点，以及各份许可证文本是否和来源一致。

模块的原生库静态链接了 LSPlant 和 DexBuilder（LGPL-3.0）。按 LGPL 的要求，你可以替换这两个库并重新链接：`python3 tools/build_native.py --export-relink <目录>` 会把重新链接所需的目标文件和脚本导出到那个目录，细节见 [native 说明](apps/module/src/main/cpp/README.md)。其余第三方组件的许可证文本在各应用的 `src/main/resources/third-party` 目录。

## 免责声明

本项目与抖音、字节跳动没有任何关联，也未获其认可。修改应用可能违反其服务条款，请只在自己的设备上、自担风险使用。

## 致谢

NukeApp-OSS 站在这些开源项目之上，感谢它们的作者：

- 界面与设计：[QAuxiliary](https://github.com/cinit/QAuxiliary)（cinit）。模块主界面的设计参考了它。
- 注入与 Hook：[LSPlant](https://github.com/LSPosed/LSPlant) 和 [DexBuilder](https://github.com/LSPosed/DexBuilder)（LSPosed）、[Dobby](https://github.com/jmpews/Dobby)（jmpews）、[xDL](https://github.com/hexhacking/xDL)（hexhacking）、[Zygisk 示例模块](https://github.com/topjohnwu/zygisk-module-sample)的 API 头文件（topjohnwu）、Xposed API 与 libxposed。
- 修补：[LSPatch](https://github.com/JingMatrix/LSPatch)（JingMatrix 的分支）。
- 查找与反射：[DexKit](https://github.com/LuckyPray/DexKit)（LuckyPray）、[KavaRef](https://github.com/HighCapable/KavaRef)（HighCapable）。
- 脚本：[QuickJS](https://bellard.org/quickjs/) 和 [quickjs-kt](https://github.com/dokar3/quickjs-kt)（dokar3）。
- 其他：[parallel-hashmap](https://github.com/greg7mdp/parallel-hashmap)（greg7mdp）、Silk-Codec-Android、AndroidHiddenApiBypass（LSPosed，文中的 LSPass）、Jetpack Compose。
