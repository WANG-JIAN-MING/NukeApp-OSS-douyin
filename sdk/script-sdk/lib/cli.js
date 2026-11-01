import { access, copyFile, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { basename, dirname, join, resolve } from "node:path";
import { parseArgs } from "node:util";
import { PackageError, validateConfigSchema, validateManifest } from "./manifest.js";
import { createPackage, readDirectory } from "./package.js";

const USAGE = `nuke-script <command> [directory] [options]

Commands:
  init [directory]   Create a script project (--id com.example.hello --name "Hello")
  check [directory]  Validate manifest.json and the settings schema it names
  build [directory]  Bundle src/main.ts or src/main.js into dist/ and write the .nks package
                     (--entry <file>, --out <file.nks>, --no-pack)
  pack <directory>   Package a directory as it is (--out <file.nks>)

A project keeps manifest.json, and optionally README.md, icon.png and its settings schema, at its root and its
source under src/. Built-in modules are imported as "nuke:..."; everything else is bundled into the entry file.`;

export async function main(argv, log = console.log) {
  const { values, positionals } = parseArgs({
    args: argv,
    allowPositionals: true,
    options: {
      id: { type: "string" },
      name: { type: "string" },
      entry: { type: "string" },
      out: { type: "string" },
      "no-pack": { type: "boolean" },
      help: { type: "boolean", short: "h" },
    },
  });
  const [command, target] = positionals;
  if (!command || values.help) {
    log(USAGE);
    return command ? 0 : 1;
  }
  const directory = resolve(target ?? ".");
  try {
    switch (command) {
      case "init": return await init(directory, values, log);
      case "check": return await check(directory, log);
      case "build": return await build(directory, values, log);
      case "pack": return await pack(directory, values.out, log);
      default:
        log(`Unknown command: ${command}\n\n${USAGE}`);
        return 1;
    }
  } catch (error) {
    if (error instanceof PackageError) {
      log(`${error.code}: ${error.message}`);
      return 1;
    }
    throw error;
  }
}

async function exists(path) {
  try {
    await access(path);
    return true;
  } catch {
    return false;
  }
}

async function readManifest(directory) {
  const path = join(directory, "manifest.json");
  if (!(await exists(path))) throw new PackageError("INVALID_PACKAGE", `No manifest.json in ${directory}`);
  return validateManifest(await readFile(path, "utf8"));
}

async function check(directory, log) {
  const manifest = await readManifest(directory);
  if (manifest.configSchema !== null) validateConfigSchema(await readFile(join(directory, manifest.configSchema), "utf8"));
  log(`${manifest.id} ${manifest.version} (${manifest.versionCode}) is valid`);
  return 0;
}

async function build(directory, options, log) {
  const manifest = await readManifest(directory);
  let esbuild;
  try {
    esbuild = await import("esbuild");
  } catch {
    log("build needs esbuild: npm install --save-dev esbuild");
    return 1;
  }
  const entry = options.entry
    ? resolve(directory, options.entry)
    : (await exists(join(directory, "src/main.ts"))) ? join(directory, "src/main.ts") : join(directory, "src/main.js");
  const dist = join(directory, "dist");
  await rm(dist, { recursive: true, force: true });
  await esbuild.build({
    entryPoints: [entry],
    outfile: join(dist, manifest.entry),
    bundle: true,
    format: "esm",
    platform: "neutral",
    mainFields: ["module", "main"],
    target: "es2023",
    external: ["nuke:*"],
    charset: "utf8",
    logLevel: "warning",
  });
  for (const extra of ["manifest.json", "README.md", "icon.png", manifest.configSchema, manifest.readme, manifest.icon]) {
    if (extra === null || !(await exists(join(directory, extra)))) continue;
    await mkdir(dirname(join(dist, extra)), { recursive: true });
    await copyFile(join(directory, extra), join(dist, extra));
  }
  log(`Built ${join("dist", manifest.entry)}`);
  if (options["no-pack"]) return 0;
  return pack(dist, options.out ?? join(directory, `${manifest.id}-${manifest.version}.nks`), log);
}

async function pack(directory, out, log) {
  const { manifest, bytes } = createPackage(await readDirectory(directory));
  const target = resolve(out ?? `${manifest.id}-${manifest.version}.nks`);
  await writeFile(target, bytes);
  log(`Packed ${basename(target)} (${bytes.length} bytes)`);
  return 0;
}

async function init(directory, options, log) {
  const id = options.id ?? `com.example.${basename(directory).toLowerCase().replace(/[^a-z0-9_-]/g, "") || "script"}`;
  const name = options.name ?? basename(directory);
  if (await exists(join(directory, "manifest.json"))) {
    log(`${directory} already has a manifest.json`);
    return 1;
  }
  const manifest = {
    manifestVersion: 1,
    apiVersion: 1,
    id,
    name,
    version: "1.0.0",
    versionCode: 1,
    hosts: ["com.ss.android.ugc.aweme"],
    permissions: {},
  };
  validateManifest(manifest);
  const files = {
    "manifest.json": JSON.stringify(manifest, null, 2) + "\n",
    "README.md": `# ${name}\n\nWhat this script does and how to use it.\n`,
    "src/main.ts": `import { info } from "nuke:log";\nimport { script } from "nuke:runtime";\n\ninfo(\`\${script.name} \${script.version} started\`);\n`,
    "tsconfig.json": JSON.stringify({
      compilerOptions: {
        target: "ES2023",
        module: "ESNext",
        moduleResolution: "Bundler",
        strict: true,
        noEmit: true,
        types: ["@nuke-oss/script-sdk"],
      },
      include: ["src"],
    }, null, 2) + "\n",
    "package.json": JSON.stringify({
      name: id.split(".").pop(),
      private: true,
      type: "module",
      scripts: { build: "nuke-script build", check: "nuke-script check" },
    }, null, 2) + "\n",
  };
  for (const [path, content] of Object.entries(files)) {
    await mkdir(dirname(join(directory, path)), { recursive: true });
    await writeFile(join(directory, path), content);
  }
  log(`Created ${id} in ${directory}. Install the tools with: npm install --save-dev @nuke-oss/script-sdk esbuild`);
  return 0;
}
