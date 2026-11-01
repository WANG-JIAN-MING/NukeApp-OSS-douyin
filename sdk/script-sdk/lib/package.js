import { deflateRawSync, crc32 } from "node:zlib";
import { readdir, readFile, stat } from "node:fs/promises";
import { join, relative, sep } from "node:path";
import {
  API_VERSION, MAX_ENTRIES, MAX_FILE_BYTES, MAX_ICON_BYTES, MAX_TOTAL_BYTES, PackageError, checkPath,
  validateConfigSchema, validateManifest,
} from "./manifest.js";

const PNG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

// Checks a set of package files ({ path: Buffer }) the way the module's reader does and returns the normalized
// manifest.
export function checkFiles(files, { maxApi = API_VERSION } = {}) {
  const names = Object.keys(files);
  if (names.length > MAX_ENTRIES) throw new PackageError("INVALID_PACKAGE", `More than ${MAX_ENTRIES} entries`);
  let total = 0;
  const lower = new Set();
  for (const name of names) {
    checkPath(name);
    const size = files[name].length;
    if (size > MAX_FILE_BYTES) throw new PackageError("TOO_LARGE", `${name} is larger than 8 MiB`);
    total += size;
    lower.add(name.toLowerCase());
  }
  if (total > MAX_TOTAL_BYTES) throw new PackageError("TOO_LARGE", "The files add up to more than 16 MiB");
  if (lower.size !== names.length) throw new PackageError("INVALID_PACKAGE", "Two entries differ only in letter case");
  if (!files["manifest.json"]) throw new PackageError("INVALID_PACKAGE", "manifest.json is missing");
  const manifest = validateManifest(utf8(files, "manifest.json"), { maxApi, files: new Set(names) });
  utf8(files, manifest.entry);
  if (manifest.configSchema !== null) validateConfigSchema(utf8(files, manifest.configSchema));
  if (manifest.readme !== null) utf8(files, manifest.readme);
  if (manifest.icon !== null) {
    const icon = files[manifest.icon];
    if (icon.length > MAX_ICON_BYTES || !icon.subarray(0, 8).equals(PNG)) {
      throw new PackageError("INVALID_PACKAGE", `${manifest.icon} must be a PNG of at most 512 KiB`);
    }
  }
  return manifest;
}

function utf8(files, path) {
  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(files[path]);
  } catch {
    throw new PackageError("INVALID_PACKAGE", `${path} is not UTF-8`);
  }
}

// Reads every file below directory, with package paths relative to it; dot files and directories are left out, as
// the reader would reject them.
export async function readDirectory(directory) {
  const files = {};
  async function walk(current) {
    for (const entry of await readdir(current, { withFileTypes: true })) {
      if (entry.name.startsWith(".")) continue;
      const path = join(current, entry.name);
      if (entry.isDirectory()) await walk(path);
      else if (entry.isFile()) files[relative(directory, path).split(sep).join("/")] = await readFile(path);
    }
  }
  if (!(await stat(directory)).isDirectory()) throw new Error(`${directory} is not a directory`);
  await walk(directory);
  return files;
}

// Builds the .nks archive: a plain ZIP, deflated, with fixed timestamps so the same files give the same bytes.
export function createPackage(files, options = {}) {
  const manifest = checkFiles(files, options);
  const locals = [];
  const central = [];
  let offset = 0;
  for (const name of Object.keys(files).sort()) {
    const data = files[name];
    const nameBytes = Buffer.from(name, "utf8");
    const compressed = deflateRawSync(data, { level: 9 });
    const stored = compressed.length >= data.length;
    const body = stored ? data : compressed;
    const crc = crc32(data);
    const header = Buffer.alloc(30);
    header.writeUInt32LE(0x04034b50, 0);
    header.writeUInt16LE(20, 4);
    header.writeUInt16LE(0x0800, 6);
    header.writeUInt16LE(stored ? 0 : 8, 8);
    header.writeUInt16LE(0, 10);
    header.writeUInt16LE(0x21, 12);
    header.writeUInt32LE(crc, 14);
    header.writeUInt32LE(body.length, 18);
    header.writeUInt32LE(data.length, 22);
    header.writeUInt16LE(nameBytes.length, 26);
    header.writeUInt16LE(0, 28);
    locals.push(header, nameBytes, body);
    const entry = Buffer.alloc(46);
    entry.writeUInt32LE(0x02014b50, 0);
    entry.writeUInt16LE(0x0314, 4);
    entry.writeUInt16LE(20, 6);
    entry.writeUInt16LE(0x0800, 8);
    entry.writeUInt16LE(stored ? 0 : 8, 10);
    entry.writeUInt16LE(0, 12);
    entry.writeUInt16LE(0x21, 14);
    entry.writeUInt32LE(crc, 16);
    entry.writeUInt32LE(body.length, 20);
    entry.writeUInt32LE(data.length, 24);
    entry.writeUInt16LE(nameBytes.length, 28);
    entry.writeUInt32LE((0o100644 << 16) >>> 0, 38);
    entry.writeUInt32LE(offset, 42);
    central.push(entry, nameBytes);
    offset += header.length + nameBytes.length + body.length;
  }
  const directory = Buffer.concat(central);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(Object.keys(files).length, 8);
  end.writeUInt16LE(Object.keys(files).length, 10);
  end.writeUInt32LE(directory.length, 12);
  end.writeUInt32LE(offset, 16);
  return { manifest, bytes: Buffer.concat([...locals, directory, end]) };
}
