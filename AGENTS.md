# NukeApp-OSS Development Conventions

These conventions apply to the NukeApp-OSS project and all its subdirectories. The user's current explicit request takes precedence over this file; for architecture and commands, the actual source, build configuration and latest verification results are authoritative.

The project is `NukeApp-OSS`. The module app uses `io.github.nuke.oss` as both applicationId and namespace; the standalone patcher app `NukePatcher` uses `io.github.nuke.oss.patcher`; the shared runtime uses sub-packages under `io.github.nuke.oss`. The standalone test host uses `io.github.nuke.oss.fixture`. Entry metadata, scope, R8 rules and test targets must stay consistent with these identities.

`CLAUDE.md` is a relative symlink to this file. Edit `AGENTS.md` only; never replace the link with a copy.

## 1 Working principles

- Read the relevant code and docs before changing anything. Do not implement from directory names, old reports or guesses.
- Deliver changes that build and can be verified against the current request. Preserve unrelated changes made by the user or other tasks.
- Keep going on authorized work. Make routine, reversible implementation choices yourself instead of repeatedly asking for confirmation.
- Prefer simple, explicit interfaces and dependencies. Do not build abstractions, plugin systems or generic frameworks before something uses them.
- Language: reply in the language the user writes in. `README.md` is maintained in Chinese. Code identifiers, code comments and commit messages are in English.

## 2 Git branches and commits

- The default development and integration branch is `dev`. Unless the user says otherwise, commit and deliver to `dev`, not `main` or `master`.
- When a separate branch is needed, branch from `dev` as `feat/<name>`, `fix/<name>`, `refactor/<name>` and so on; PRs target `dev` by default.
- Before changing or delivering anything, check `git status --short` and the current branch. If unrelated uncommitted work exists, leave it in place; use a separate worktree to switch baselines instead of forcing a branch switch.
- If there is no Git repository, no `dev` branch or not enough remote information, say so. Never invent commits, branches or push results, and never set a remote on your own.
- Do not run reset, checkout, clean, force push or history rewrites that would overwrite the user's work unless the user explicitly asks for that operation.
- Each commit covers one complete change. Stage by explicit path, review the staged diff and run `git diff --cached --check` before committing.
- After committing or pushing, report the actual result. For remote delivery, confirm the remote branch; a local commit is not a push.

Commits follow Conventional Commits, with title, body and footer in English:

```text
<type>(<scope>): <description>
```

`.githooks/commit-msg` and CI run `tools/check_commit_msg.py` on these rules; enable the hook with `git config core.hooksPath .githooks`.

Common types: `feat`, `fix`, `refactor`, `perf`, `test`, `docs`, `build`, `ci`, `chore`, `revert`. The scope is a real module or feature name and may be omitted when none fits. Breaking interface or behavior changes use `!` and explain them in a `BREAKING CHANGE:` footer.

```text
feat(libxposed102): add modern module entry
fix(hook): preserve in-flight callback pairing
refactor(runtime): isolate host initialization
docs(agents): document development conventions
```

## 3 Technology choices

- Android UI, platform APIs, lifecycle and JVM Hook adaptation use Kotlin first. New Android management screens and shared settings UI use Jetpack Compose with Material 3.
- Classic Views are for existing transitional screens, host View integration and cases that truly need direct View access. They are not the default for new standalone screens.
- New native business logic, algorithms, networking, protocol encoding, compression and archives, hashing, standalone file processing and any other capability that does not depend on Android/JVM is written in Rust first, not only when it is performance-sensitive or low-level. Core logic must be testable on its own; Kotlin only provides the needed platform, UI and JNI glue.
- C/C++ is kept to the Zygisk entry, third-party interfaces, JNI and thin adapters that must use that ABI. It is not a new default business language.
- Java is allowed for test targets, framework interop and necessary compatibility code. Other new logic chooses Kotlin or Rust by the platform and standalone-capability boundaries above.
- Gradle uses the Kotlin DSL, with dependency versions centralized in `gradle/libs.versions.toml`. Reuse existing dependencies; avoid dynamic versions and unnecessary new frameworks.
- Python is fine for development-machine checks, packaging and report tools. Do not ship development-only scripts or dependencies into host runtime.
- Do not rewrite stable third-party implementations, including APK signing and the patch engine, just to unify the stack. Reuse mature implementations behind a narrow adapter; new in-house capabilities follow the priorities above.

## 4 Comments and naming

- Do not add block comments to hand-written project code, including plain block comments, KDoc, Javadoc and Rust block doc comments.
- Kotlin, Java and C/C++ use `//` line comments. Rust uses `//` for ordinary comments and `///` or `//!` for doc comments.
- When editing existing code, convert block comments in the part you touch to line comments. Do not mass-rewrite unrelated files for this.
- Keep third-party source, license texts and generated code as published; do not strip copyright notices or hand-edit generated output to satisfy formatting rules.
- These limits apply to block comments only; annotations such as `@Composable` and `@Test` are unaffected.
- Comments explain reasons, constraints, threading and lifecycle; they do not narrate code line by line. Delete stale comments and TODOs that are no longer needed.
- Types, objects and Compose functions use UpperCamelCase; ordinary functions and properties use lowerCamelCase; true constants use `UPPER_SNAKE_CASE`.
- Default to the narrowest visibility and prefer immutable values. Avoid ownerless global state, redundant caches and pointless wrapper classes.

## 5 Modules and dependency boundaries

- Eight Gradle modules are registered: `:apps:module`, `:apps:patcher`, `:core:runtime`, `:core:script`, `:core:loader`, `:core:designsystem`, `:fixtures:host` and `:core:hook-ksp`. `:core:script` is a pure Kotlin/JVM module holding the QuickJS script engine and its built-in `nuke:*` modules in `io.github.nuke.oss.script`; it depends only on `:core:runtime` (and quickjs-kt, whose JVM variant runs its tests and whose Android variant the module app packages) and never on Android, a host, a loader or an app. `:core:loader` is an Android library holding the three entries and their backends; it depends only on `:core:runtime` and is the only module that sees the XP82 and libxposed102 APIs. `:core:designsystem` is an Android library holding the Nuke design system (tokens, shapes, press feedback and controls) in `io.github.nuke.oss.designsystem`; it depends on Compose Material 3 only and never on another project module, an app, a host or a framework API. The module app's production project dependencies are `:core:runtime`, `:core:script`, `:core:loader` and `:core:designsystem`; `:core:hook-ksp` is a build-time KSP processor (`io.github.nuke.oss.ksp`), reaches the module app only as a `ksp` dependency, links no project or Android class and is never packaged; the patcher app and the test host have no production project dependencies. The two apps produce independent APKs: the module app never depends on the patcher, and the patcher never bundles the module APK.
- The script SDK, the npm package `@nuke-oss/script-sdk`, lives in `sdk/script-sdk`: type declarations of the `nuke:*` modules, the package rules in JavaScript and the `nuke-script` command. It is never packaged into an APK. Its manifest fixtures are shared with the module's Rust package reader, and `ScriptApiContractTest` keeps its declarations equal to the modules a script session gets; change the three together. Publishing to npm needs the user's confirmation each time.
- The native CMake project lives in `apps/module/src/main/cpp`. Static installer files and Xposed metadata live in the default `apps/module/src/main/resources`. Do not add a top-level native or packaging directory, a template source set or small Gradle modules for these.
- Keep directories matching the actual implementation: no unused legacy module directories, empty `.gitkeep` files or placeholder READMEs. Host, code generation, script and similar capabilities are implemented inside the relevant module once something uses them. `artifacts` snapshots stay as they are and are not a substitute for the present state.
- `:core:runtime` is a pure JVM module holding the shared model, Hook contracts and scheduling, feature lifecycle and entry arbitration. It does not import Android, Compose, Xposed, DexKit, any concrete backend or any host implementation.
- XP82 types and calls appear only in the XP82 loader/backend; libxposed types and calls appear only in the modern loader/backend. The modern path must never fall back to the legacy API.
- Business features, scripts and host SDKs reach Hooks only through the shared HookService, capability interfaces and scopes. They never hold framework callbacks or handles.
- Neither app has product flavors; only Debug and Release build types exist. The module APK carries the XP82 loader/backend, the libxposed102 loader/backend, the Zygisk entry and the LSPlant backend together. Both framework APIs are `compileOnly` in `:core:loader` only and never reach the module app's compile classpath; API stubs and test frameworks must not be packaged. Entry metadata is centralized in the module's main source set, and each entry points only to itself. The module UI and the Zygisk bootstrap must not resolve or call optional Xposed entry types. The Zygisk payload reuses all `classes*.dex` of the same package.
- APK patching is a deployment method. It reuses the module APK's libxposed102 entry, backend and arbitration and adds no fourth Hook API. The patcher imports an external NukeApp-OSS module APK and a target APK separately through SAF, checks module identity and API 102 metadata, then embeds the selected module. It must not embed the patcher itself, rely on an installed module, or describe the package-name check as publisher authentication.
- The Rust crate for file import, archives and hash checks lives in `apps/patcher/src/main/rust` and is built by `tools/build_rust.py`. The module app has its own crate, `nuke-protocol-core` in `apps/module/src/main/rust`, for the TikTok IM packet protocol (wire codec, request wrapping, response correlation), the module APK signer check, the read-only device trust checks (`riskctl`: debugger, injection, Frida, executable-code integrity of the signature path, memory map, mounts, SELinux and kernel exposure, boot state, root and the like, each raising named flags of which a tripwire counts the ones that matter; observed and reported, never acted on) and the script package reader (validation and extraction of `.nks` packages, bound in `io.github.nuke.oss.app.shared.script`); it is packaged as `libnuke_protocol_core.so` and bound by JNI in `io.github.nuke.oss.app.tiktok.protocol` and `io.github.nuke.oss.app.shared.integrity`. The release certificate's SHA-256 is compiled into that library from the release signing config; a build without a release key enforces nothing. The two crates never share code or libraries. The mature LSPatch patching and signing implementation is called through a narrow adapter at a pinned version and goes only into the patcher APK.
- Patcher signing material lives in the private directory of `io.github.nuke.oss.patcher`. Do not assume the old integrated app's signing identity migrates automatically, and do not migrate app data automatically. The module UI keeps raw-byte export of the module APK, which also serves as the root module once renamed to `.zip`; it has no separate ZIP export and holds no patcher state or signing material.
- Native builds stay driven by `tools/build_native.py` through CMake, with third-party source caches in `build/native/deps` and outputs in `build/native/<abi>`. Third-party notices belong in each app's `src/main/resources/third-party`. The shared GPL text has a single authoritative copy in the module app and is copied into the patcher APK at build time; `apps/patcher/LICENSE` and `sdk/script-sdk/LICENSE` are checked-in copies that `tools/check_boundaries.py` keeps byte for byte the same as their sources (that GPL text and the root `LICENSE`); never edit a copy by hand.
- Zygisk installer files, payload and JNI libraries are assembled before APK signing. The same APK installs normally, and a copy renamed to `.zip` can be given to a root manager. Never modify the archive after signing, and never require a separate Manager APK to pair with it.
- Each app's `io.github.nuke.oss.assembly` package chooses the concrete HostAdapter and wires the runtime graph; `runtime` registers no host by default. Library code never depends back on an app. Each host lives in its own `io.github.nuke.oss.app.<name>` package and shares code only through `io.github.nuke.oss.app.shared`; host packages never reference each other, and only the assembly references a concrete host.
- The three entries and their backends live in `:core:loader` (`io.github.nuke.oss.loader` and `io.github.nuke.oss.backend`). The module app links none of them and no framework API: the entries reach application code only through `LoaderProfile`, which `io.github.nuke.oss.assembly.ApplicationLoaderProfile` implements and the loader finds by name, so keep that name in step with `LoaderProfiles` and the R8 rules. Keep the per-package boundary checks, the missing-API class-loading contract and the post-R8 artifact checks; the module app's R8 run sees the framework types only as unresolved references and relies on `-dontwarn` for them.
- The generic FakeHookBackend and ContractTargets live in `core/runtime/src/testFixtures`; framework-specific drivers and simulators live in app test source sets. Shared host test code forms no production dependency, and no test implementation may enter an APK.
- Utilities are grouped by responsibility (Hook, Dex, reflection, Android, resources, data). Do not put implementations with host state into a generic `utils`.
- Libraries do not read an app's BuildConfig or depend on app resources. Build identity, configuration, logging and platform services are passed in through explicit interfaces.
- Registries, entry metadata and JNI bindings each have one clear authoritative source. Never hand-edit generated files. Host features register through `@HookEntry` (`io.github.nuke.oss.app.shared.registry`) on an object implementing `FeatureEntry`, or on a feature class whose companion implements it; `:core:hook-ksp` generates each host package's `generatedHookEntries`, and the host adapter registers only the settings entry and the init by hand. Dex queries are `UPPER_SNAKE_CASE` `DexQuery` properties of `@DexQueries` objects (`io.github.nuke.oss.app.shared.dex`); the same processor names each `<Object>.<PROPERTY>` in the host package's `generatedDexQueries`, which is the resolver's catalog, so a query carries no hand-written ID or registration.

## 6 Hooks and lifecycle

- Within one backend instance, a target gets exactly one underlying entry; the shared scheduler manages all feature subscriptions on it.
- Preserve before/after enter-exit pairing, priority and snapshot semantics. Disabling a feature must not break calls already in flight.
- Distinguish Pending, a null return, a deliberate exception and a callback implementation error. Never use one null for several states.
- `proceed` means continuing the rest of the call chain; calling the original implementation is a different capability. Never emulate continuation with ORIGIN.
- A Hook frame is used only within the current synchronous callback. It never crosses threads, is never stored in a long-lived field and is never handed to a coroutine or script that runs later.
- Subscriptions and resources owned by a feature belong to its scope. Failed installs roll back; closing is idempotent; in-flight calls and physical unhooking are handled separately.
- When a capability is unavailable or unverified, report it explicitly. No placeholder success, silent fallback or catch-all exception handling that fakes compatibility.
- ClassLoaders, processes, accounts and code generations are all boundaries. Reflection caches are never keyed by class name alone, and Method, Class or framework objects are never persisted to disk.
- Framework package load, the final ClassLoader, the Application Context, host UI and account availability are different moments; never treat them as one startup point.
- The current scope is the non-isolated main process of `io.github.nuke.oss.fixture` and of the TikTok host, and for the TikTok host also its non-isolated child processes (named `<package>:<suffix>`, never `sandboxed_process*`; `HostScope.processOf` classifies them as main, mini-app or other, and a feature entry declares the kinds it installs in, the main process alone by default). The TikTok host is scoped to Douyin (`com.ss.android.ugc.aweme`) only and was added by explicit user request for migrating the legacy TikTok-module features. `HostScope` in `io.github.nuke.oss.assembly` is the single source; the entries, Xposed scope metadata, native Zygisk filter and APK checks follow it. Zygisk is built only for `arm64-v8a`. Never widen hosts or ABIs based on an upstream support list.
- Zygisk reads all canonically named `classes*.dex` of the same package through the API 27 `InMemoryDexClassLoader` ByteBuffer-array constructor. Total DEX size is capped at 64 MiB; the buffers and loader are kept until the process exits. Never skip a DEX or free its memory early.
- XP82, libxposed102 and Zygisk coordinate cross-ClassLoader entry through a synchronized String token claim on `System.getProperties()`. Whichever effective entry callback arrives first owns the process; each entry separately guards against duplicate and re-entrant events. Do not guess the backend from visible framework classes, and do not set a static modern-first priority. A failed owner keeps ownership until the process exits and never switches backends automatically. This arbitration is not a mutual-exclusion guarantee against third-party ART engines.

## 7 Compose and state

- The UI reads state and sends intents; the runtime and data layers own business state. Composables never install Hooks, start Dex scans or control native lifecycles.
- New screens use an explicit state model and unidirectional data flow. Choose a ViewModel by complexity; do not add layers to simple static screens.
- Side effects use Compose APIs with explicit keys and lifecycles. Release listeners and jobs when leaving a screen; avoid duplicate subscriptions and holding the Activity.
- Visible text lives in resources. In NukeApp-OSS, `values/` is the English default and `values-zh/` the Chinese translation; add every translatable string to both, mark product names and identifiers `translatable="false"`, and keep `res/xml/locales_config.xml` in step. `tools/check_boundaries.py` checks this. NukePatcher is not translated yet. Keep light and dark themes, readability and basic accessibility. Reuse the design system instead of per-screen visual parameters: the module app takes it from `:core:designsystem`, so add or change tokens and controls there, not in a screen. NukePatcher has no project dependency and keeps its own `patcher/ui/NukeTheme.kt`, which does not yet follow the Nuke design system.
- Show configured intent and actual runtime state separately. Disconnected, stale snapshots, awaiting adaptation and pending restart are never shown as in effect.

## 8 Rust and native boundaries

- Prefer safe Rust. Every new `unsafe` needs an adjacent `// SAFETY:` line comment covering pointer, length, ownership and threading assumptions.
- FFI/JNI uses narrow interfaces that define version, ABI, errors, memory ownership and release functions. Never let a panic or Rust unwind cross an external boundary that does not support unwinding.
- A JNIEnv is used only on its own thread. JNI global references and callback references have explicit owners. Never unload a library whose code backs a native callback that is still alive.
- Hook infrastructure and native business engines manage initialization, versions and lifecycle separately; a business update must never replace a Hook library in use.
- For Rust changes, run `cargo fmt --check`, `cargo clippy --all-targets -- -D warnings` and `cargo test`. Verify cross-compilation and Android linking for the actual targets in addition.
- Pin and record toolchain and third-party versions. Never claim an Android version or ABI is verified because an upstream support list includes it.
- The Zygisk native code only provides the entry, JNI and thin LSPlant/Dobby/xDL adapters, and may use C++. Standalone Rust capabilities join the existing app module that uses them; do not wrap thin adapters in Rust or split out Gradle modules for them.

## 9 Verification

- Use the project Gradle Wrapper and the JDK/SDK the build requires, never an ad-hoc system Gradle.
- Run module tests that are meaningful for the change. Fixes to Hook semantics, concurrency, exceptions, install rollback or lifecycle get matching regression tests.
- Do not add mirror tests for simple doc or low-impact formatting changes, and do not rerun a full build without reason after checks pass.
- Changes to loaders, backends, shared Hook contracts, the dependency graph, entry resources or R8 rules require the shared tests, Debug and R8 Release artifact checks of the unified APK, and the relevant three-entry selection and Zygisk/LSPlant tests. Confirm that all three entries and their metadata are present, framework API stubs are not packaged, each entry calls only its own adapter, and the APK root installer, the arm64-v8a library and the same-package multi-DEX payload are intact.
- Patcher and delivery-boundary changes verify both APKs. The module APK contains no patch engine, payload or Rust file core; the patcher APK contains no module Hook implementation, runtime, entry metadata, Zygisk installer or bundled module APK. Patch contracts use a standalone module APK and the test host and cover module identity and modern metadata, input rejection, signing and the final embedded bytes. After R8, check both the patcher JNI and the third-party configuration model.
- Pure doc changes only need content, link and diff checks; do not rebuild APKs for them.
- Gradle builds in this project run serially under the project lock so concurrent builds cannot interfere.

```bash
flock /tmp/nukeapp-gradle.lock ./gradlew verify --max-workers=1 --console=plain
flock /tmp/nukeapp-gradle.lock ./gradlew verifyRelease --max-workers=1 --console=plain
```

`verify` covers Rust format/Clippy/tests, the script SDK's tests (Node 22.2 or later), the patch round trip with the real fixture and a standalone module APK, current contract tests, Debug builds of the module, patcher and test host, and the boundary and APK checks. Use `verifyRelease` for R8, entry retention or release packaging changes; there is no need to run `verify` first.

- Never hard-code test counts as a long-term baseline. Report the passed, failed, errored and skipped counts from the current run.
- Report JVM simulation tests, compilation, APK signing, installation, launch and real ART/host behavior separately; one never substitutes for another.
- For device verification, check the connection first and install test builds with `adb install -r` to keep data. Never uninstall, clear data or widen the host scope on your own.
- A local x86_64 emulator is valid evidence for patch-mode flows (SAF, in-app patching, installing and launching the patched fixture). Report it as emulator evidence; it does not replace arm64-v8a device evidence, `tools/check_android_patcher.py`, or framework and root modes.
- Without a device or framework environment, keep reproducible artifacts, state which verification steps were not done, and never fabricate success records.

## 10 Delivery and sensitive material

- Licensing is per directory: `apps/patcher` is GPL-3.0 because it embeds LSPatch, and everything else is Apache-2.0 (`LICENSE`), with the copyright statement in `NOTICE`. Apache-2.0 code never depends on, imports from or copies from the patcher; code written in `apps/patcher` is GPL-3.0. Do not paste source from a project whose license does not fit the directory it would land in; reimplement from the public specification instead (the APK signer check follows the Android APK Signature Scheme documentation).
- Never commit `local.properties`, credentials, server secrets, signing private keys, keystores, real account data or user content.
- Never commit build directories, APKs, test logs, decompilation caches or temporary packaging files. The Gradle Wrapper JAR is a required build file and stays committed.
- Deliverables and reviewable reports may be kept in the ignored `artifacts` directory, with file links, verification results and the necessary checksums.
- Logs never record keys, account credentials, chat text or full method arguments by default. Diagnostics are bounded and identify the feature, process, target and stage.
- When interfaces, entries or verification flows change, update the README and the native notes. Docs never describe reserved capabilities as implemented.
- Final reports state what changed, what was verified and what real limits remain. Never present old reports' test counts or device state as current results.

## 11 Where to start reading

- `README.md`: building the module and patcher APKs, the three Hook modes, patching, verification status and the code layout.
- `apps/module/src/main/cpp/README.md`: the Zygisk bootstrap, the JNI contract, pinned native dependencies and the APK / ZIP installer.
- `sdk/script-sdk/README.md`: writing, checking and packaging scripts.
- `gradle/libs.versions.toml`, each module's build files and the source code: the authority for actual dependencies and behavior.
