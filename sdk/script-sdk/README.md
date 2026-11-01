# @nuke-oss/script-sdk

Types, validation and packaging for NukeApp-OSS scripts. Scripts run in NukeApp-OSS's QuickJS engine inside the
host app's main process (currently Douyin only) and reach the host through built-in `nuke:*` modules. This package
does not contain those modules; it describes them for your editor and turns a project into a `.nks` package the
module's scripts page can import.

The SDK's major version is the script API level. Version 1 targets API 1.

## Getting started

```sh
npx @nuke-oss/script-sdk init hello --id com.example.hello --name "Hello"
cd hello
npm install --save-dev @nuke-oss/script-sdk esbuild
npx nuke-script build
```

`build` bundles `src/main.ts` (or `src/main.js`) and everything it imports into one ES module, leaves the `nuke:*`
imports alone, and writes `com.example.hello-1.0.0.nks`. Import that file from Douyin: NukeApp-OSS settings →
Scripts → the upload button. A new script starts switched off; switching it on asks for its sensitive permissions.

Other commands:

- `nuke-script check [directory]` validates `manifest.json` and the settings schema it names.
- `nuke-script pack <directory> [--out file.nks]` packages a directory as it is, after the same checks the module
  runs on import.

The library exports the same checks: `validateManifest`, `validateConfigSchema`, `checkFiles`, `createPackage` and
`readDirectory`.

## The package

A `.nks` file is a ZIP archive:

| Path | |
| --- | --- |
| `manifest.json` | required |
| the entry, `main.js` by default | one ES module; `.js` or `.mjs` |
| the settings schema | optional, named by `configSchema` |
| `README.md` | optional; shown as Markdown on the scripts page |
| `icon.png` | optional; at most 512 KiB |

At most 64 files, 8 MiB each and 16 MiB together. Paths are relative, use `/`, contain only letters, digits, `.`,
`_` and `-`, and no segment starts with a dot. The module rejects a package that breaks any rule.

## manifest.json

```json
{
  "manifestVersion": 1,
  "apiVersion": 1,
  "id": "com.example.hello",
  "name": "Hello",
  "version": "1.0.0",
  "versionCode": 1,
  "description": "Says hello.",
  "author": "you",
  "homepage": "https://example.com",
  "hosts": ["com.ss.android.ugc.aweme"],
  "configSchema": "config.schema.json",
  "permissions": {
    "network": { "origins": ["https://api.example.com", "https://*.example.org"], "privateNetwork": false },
    "java": true,
    "hook": true,
    "filesystem": { "host": true, "reason": "Reads the draft cache" },
    "host": ["douyin.messaging", "douyin.account"]
  }
}
```

- `id`: two or more dot-separated lowercase segments; it identifies the script across updates.
- `versionCode`: a positive integer; a higher one is an update, a lower one a downgrade.
- `hosts`: the host packages the script is made for; leave it out to allow any.
- Unknown fields and wrongly typed values are errors.

Permissions:

| Permission | Grants | Needs the user's grant |
| --- | --- | --- |
| `network` | `nuke:http` to the listed origins (`true` for any); `privateNetwork` for local addresses | no, shown |
| `filesystem.external` | files the user picks (not offered yet) | no, shown |
| `java` | `nuke:java` reflection over the host | yes |
| `hook` | `nuke:hook` (also needs `java` to name the method) | yes |
| `filesystem.host` | `nuke:fs` `host` root, the host's private directory | yes |
| `host: ["douyin.account"]` | `nuke:douyin/account` | yes |
| `host: ["douyin.messaging"]` | `nuke:douyin/im` | yes |

A grant covers the set of sensitive permissions; an update asking for a different set asks again. Calling something
not declared throws `PermissionError`.

## Settings schema

```json
{
  "fields": [
    { "key": "greeting", "type": "string", "title": "Greeting", "default": "hi", "maxLength": 40 },
    { "key": "count", "type": "integer", "title": "Count", "min": 1, "max": 10, "default": 3 },
    { "key": "mode", "type": "select", "title": "Mode", "options": [{ "value": "a", "label": "A" }], "default": "a" },
    { "key": "enabled", "type": "boolean", "title": "Enabled", "default": true }
  ]
}
```

Types are `string`, `text` (multi-line), `number`, `integer`, `boolean` and `select`. The scripts page renders the
form; the script reads the values with `nuke:config` and hears about changes through `onChange`.

## Writing scripts

Add `"types": ["@nuke-oss/script-sdk"]` to `tsconfig.json` (the `init` template does) to get the module types.

```ts
import { info } from "nuke:log";
import { get, onChange } from "nuke:config";
import { use } from "nuke:java";
import { hook } from "nuke:hook";

info("greeting is", get("greeting"));
onChange((values) => info("now", values.greeting));

const Target = use("com.example.Target");
hook(Target.method("load", ["java.lang.String"]), {
  before(call) {
    if (call.args[0] === "blocked") call.returnEarly(null);
  },
});
```

| Module | |
| --- | --- |
| `nuke:runtime` | API level, script info, permissions, error classes |
| `nuke:log` | `debug`, `info`, `warn`, `error`; shown on the scripts page |
| `nuke:timers` | `sleep` and the timer functions, which are also globals |
| `nuke:config` | settings values and changes |
| `nuke:encoding` | UTF-8, Base64, hex |
| `nuke:crypto` | random bytes and UUIDs, MD5 and SHA digests, HMAC, AES-GCM |
| `nuke:java` | classes, objects, methods and fields of the host |
| `nuke:hook` | before and after callbacks on host methods |
| `nuke:http` | `fetch` |
| `nuke:fs` | the script's `data` directory and the host's `host` directory |
| `nuke:ui` | `toast`, `alert`, `confirm` |
| `nuke:douyin/account` | the signed-in account |
| `nuke:douyin/im` | sending text |

How scripts run:

- One thread per script. Events, timers and Promise callbacks never run concurrently with each other.
- A task that runs for more than 3 seconds without yielding, or a script that runs out of its 64 MiB of memory, is
  stopped. So is a script with an unhandled Promise rejection: catch what you start.
- Hook callbacks must return synchronously. The host thread waits for the answer (100 ms by default, `timeoutMs` up
  to 1000); a late answer is dropped and the call goes on unchanged, and three in a row remove the hook. A hook that
  fires on the script's own thread, because the script called the hooked method itself, is skipped. Use
  `observe: true` when you only need to see calls.
- Java objects are handles released when their JavaScript wrapper is garbage collected; strings, numbers and booleans
  cross as values. Java `long` values beyond 2^53 lose precision.
- Everything a script installs is released when it stops; there is no stop callback.
- A script that stops with an error is locked like any other failing feature and must be unlocked on the debug page.

Not offered yet: receiving Douyin messages, picked external files, implementing Java interfaces in JavaScript, and
calling a hooked method's original implementation.
