import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdir, mkdtemp, readFile, readdir, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { main } from "../lib/cli.js";
import { PackageError, checkPath, createPackage, readDirectory, validateConfigSchema, validateManifest } from "../lib/index.js";

const fixtures = fileURLToPath(new URL("./fixtures/", import.meta.url));

async function jsonFiles(directory) {
  return (await readdir(directory)).filter((name) => name.endsWith(".json")).map((name) => join(directory, name));
}

// Reads an archive back with Python's zipfile, which checks every CRC, so the writer is not checked against itself.
function unzip(bytes) {
  const script = "import sys,zipfile,io,json,base64\n" +
    "z=zipfile.ZipFile(io.BytesIO(sys.stdin.buffer.read()))\n" +
    "assert z.testzip() is None\n" +
    "print(json.dumps({i.filename: base64.b64encode(z.read(i)).decode() for i in z.infolist()}))";
  const out = JSON.parse(execFileSync("python3", ["-I", "-c", script], { input: bytes }).toString());
  return Object.fromEntries(Object.entries(out).map(([name, data]) => [name, Buffer.from(data, "base64")]));
}

test("the shared valid manifests pass", async () => {
  for (const file of await jsonFiles(join(fixtures, "manifests/valid"))) {
    const manifest = JSON.parse(await readFile(file, "utf8"));
    assert.doesNotThrow(() => validateManifest(manifest), file);
  }
});

test("the shared invalid manifests fail", async () => {
  for (const file of await jsonFiles(join(fixtures, "manifests/invalid"))) {
    const text = await readFile(file, "utf8");
    assert.throws(() => validateManifest(text), PackageError, file);
  }
});

test("manifests are normalized", () => {
  const manifest = validateManifest({
    manifestVersion: 1, apiVersion: 1, id: "a.b", name: "N", version: "1", versionCode: 1,
    permissions: { network: { origins: ["HTTPS://*.Example.com"] }, host: ["x.y", "x.y"] },
  });
  assert.equal(manifest.entry, "main.js");
  assert.deepEqual(manifest.permissions, {
    network: { any: false, origins: ["https://*.example.com"], privateNetwork: false },
    java: false,
    hook: false,
    filesystem: { external: false, host: false, reason: null },
    host: ["x.y"],
  });
  assert.equal(validateManifest({ manifestVersion: 1, apiVersion: 2, id: "a.b", name: "N", version: "1", versionCode: 1 }, { maxApi: 2 }).apiVersion, 2);
  assert.throws(() => validateManifest({ manifestVersion: 1, apiVersion: 2, id: "a.b", name: "N", version: "1", versionCode: 1 }), { code: "UNSUPPORTED_API" });
});

test("settings schemas are checked", () => {
  validateConfigSchema({
    fields: [
      { key: "name", type: "string", title: "Name", default: "hi", maxLength: 5 },
      { key: "count", type: "integer", title: "Count", min: 1, max: 3, default: 2 },
      { key: "mode", type: "select", title: "Mode", options: [{ value: "a", label: "A" }], default: "a" },
    ],
  });
  for (const fields of [
    [{ key: "a", type: "color", title: "A" }],
    [{ key: "a", type: "string", title: "A" }, { key: "a", type: "string", title: "B" }],
    [{ key: "a", type: "integer", title: "A", max: 3, default: 4 }],
    [{ key: "a", type: "boolean", title: "A", min: 1 }],
    [{ key: "a", type: "select", title: "A", options: [] }],
    [{ key: "a", type: "string" }],
  ]) {
    assert.throws(() => validateConfigSchema({ fields }), { code: "INVALID_CONFIG_SCHEMA" }, JSON.stringify(fields));
  }
});

test("unsafe paths are refused", () => {
  for (const path of ["/a.js", "../a.js", "a/../b.js", ".hidden", "a\\b.js", "naïve.js", ""]) assert.throws(() => checkPath(path), PackageError, path);
  for (const path of ["main.js", "lib/util-1.js"]) checkPath(path);
});

test("a package holds exactly the checked files", async () => {
  const files = await readDirectory(join(fixtures, "packages/hello"));
  const { manifest, bytes } = createPackage(files);
  assert.equal(manifest.readme, "README.md");
  assert.deepEqual(unzip(bytes), files);
  assert.deepEqual(createPackage(files).bytes, bytes, "the same files give the same bytes");
});

test("the committed sample package still matches its sources", async () => {
  const files = await readDirectory(join(fixtures, "packages/hello"));
  assert.deepEqual(unzip(await readFile(join(fixtures, "packages/hello.nks"))), files);
});

test("packages the reader would refuse are not written", () => {
  const manifest = Buffer.from('{"manifestVersion":1,"apiVersion":1,"id":"a.b","name":"N","version":"1","versionCode":1}');
  assert.throws(() => createPackage({ "manifest.json": manifest }), { code: "INVALID_MANIFEST" });
  assert.throws(() => createPackage({ "main.js": Buffer.from("") }), { code: "INVALID_PACKAGE" });
  assert.throws(() => createPackage({ "manifest.json": manifest, "main.js": Buffer.from(""), "Main.js": Buffer.from("") }), { code: "INVALID_PACKAGE" });
  assert.throws(() => createPackage({ "manifest.json": manifest, "main.js": Buffer.from([0xff, 0xfe]) }), { code: "INVALID_PACKAGE" });
});

test("the command line creates, checks and packs a project", async () => {
  const directory = await mkdtemp(join(tmpdir(), "nuke-script-"));
  const lines = [];
  const log = (line) => lines.push(line);
  assert.equal(await main(["init", directory, "--id", "com.example.cli", "--name", "Cli"], log), 0);
  assert.equal(await main(["check", directory], log), 0);
  assert.match(lines.at(-1), /com\.example\.cli 1\.0\.0 \(1\) is valid/);
  assert.equal(await main(["init", directory], log), 1);

  const out = join(directory, "out.nks");
  const staged = join(directory, "staged");
  await mkdir(staged);
  await writeFile(join(staged, "manifest.json"), await readFile(join(directory, "manifest.json")));
  await writeFile(join(staged, "main.js"), "export {};\n");
  assert.equal(await main(["pack", staged, "--out", out], log), 0);
  assert.deepEqual(Object.keys(unzip(await readFile(out))).sort(), ["main.js", "manifest.json"]);

  await writeFile(join(staged, "main.js"), "");
  await writeFile(join(staged, "manifest.json"), '{"manifestVersion":1}');
  assert.equal(await main(["pack", staged], log), 1);
  assert.match(lines.at(-1), /^INVALID_MANIFEST: /);
  assert.equal(await main(["nonsense"], log), 1);
});
