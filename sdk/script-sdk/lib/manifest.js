// Manifest and settings schema rules of NukeApp-OSS script packages. They mirror the module's package reader
// (apps/module/src/main/rust/src/script/package.rs); the fixtures under test/fixtures are checked by both, so a rule
// changed in one place and not the other fails a test.

export const MANIFEST_VERSION = 1;
export const API_VERSION = 1;
export const MAX_ENTRIES = 64;
export const MAX_FILE_BYTES = 8 * 1024 * 1024;
export const MAX_TOTAL_BYTES = 16 * 1024 * 1024;
export const MAX_ICON_BYTES = 512 * 1024;
const MAX_PATH = 200;
const MAX_SAFE = Number.MAX_SAFE_INTEGER;

export class PackageError extends Error {
  constructor(code, message) {
    super(message);
    this.name = "PackageError";
    this.code = code;
  }
}

const manifestError = (message) => new PackageError("INVALID_MANIFEST", message);
const schemaError = (message) => new PackageError("INVALID_CONFIG_SCHEMA", message);
const packageError = (message) => new PackageError("INVALID_PACKAGE", message);

// Relative, forward-slashed, portable names that can never leave the install directory.
export function checkPath(name) {
  const fail = () => packageError(`Invalid entry path: ${JSON.stringify(name)}`);
  if (typeof name !== "string" || name.length === 0 || name.length > MAX_PATH || name.startsWith("/")) throw fail();
  for (const segment of name.split("/")) {
    if (segment.length === 0 || segment === "." || segment === ".." || segment.startsWith(".")) throw fail();
    if (!/^[A-Za-z0-9._-]+$/.test(segment)) throw fail();
  }
}

function isObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function fields(object, place, error) {
  const charCount = (text) => [...text].length;
  return {
    only(allowed) {
      const unknown = Object.keys(object).filter((key) => !allowed.includes(key));
      if (unknown.length > 0) throw error(`${place} has unknown fields: ${JSON.stringify(unknown.sort())}`);
    },
    text(key, min, max) {
      const value = object[key];
      if (value === undefined || value === null) return null;
      if (typeof value !== "string") throw error(`${place}.${key} must be a string`);
      if (charCount(value.trim()) < min || charCount(value) > max) throw error(`${place}.${key} must be ${min} to ${max} characters`);
      return value;
    },
    integer(key) {
      const value = object[key];
      if (value === undefined || value === null) return null;
      if (!Number.isSafeInteger(value) || Math.abs(value) > MAX_SAFE) throw error(`${place}.${key} must be a safe integer`);
      return value;
    },
    number(key) {
      const value = object[key];
      if (value === undefined || value === null) return null;
      if (typeof value !== "number" || !Number.isFinite(value)) throw error(`${place}.${key} must be a number`);
      return value;
    },
    boolean(key) {
      const value = object[key];
      if (value === undefined || value === null) return null;
      if (typeof value !== "boolean") throw error(`${place}.${key} must be a boolean`);
      return value;
    },
  };
}

function strings(value, place, max, error) {
  if (!Array.isArray(value) || value.length > max) throw error(`${place} must be an array of at most ${max} strings`);
  return value.map((item) => {
    if (typeof item !== "string" || item.length === 0 || item.length > 253) throw error(`${place} must contain non-empty strings`);
    return item;
  });
}

// Lowercase dot-separated segments starting with a letter; package names may also use upper case and underscores.
function isDotted(text, packageName) {
  const segments = text.split(".");
  const segment = packageName ? /^[A-Za-z][A-Za-z0-9_]*$/ : /^[a-z][a-z0-9_-]*$/;
  return segments.length >= 2 && segments.every((part) => segment.test(part));
}

// Accepts scheme://host[:port] with an optional leading *. and returns it in lower case.
export function originOf(origin) {
  const fail = () => manifestError(`Invalid network origin: ${origin}`);
  const lower = origin.toLowerCase();
  const match = /^(https?):\/\/(.*)$/.exec(lower);
  if (!match) throw fail();
  let host = match[2];
  const colon = host.lastIndexOf(":");
  if (colon >= 0) {
    const port = host.slice(colon + 1);
    if (!/^\d+$/.test(port) || Number(port) < 1 || Number(port) > 65535) throw fail();
    host = host.slice(0, colon);
  }
  const bare = host.startsWith("*.") ? host.slice(2) : host;
  const label = /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/;
  if (bare.length === 0 || bare.length > 253 || !bare.split(".").every((part) => label.test(part))) throw fail();
  return lower;
}

function permissionsOf(object) {
  const permission = fields(object, "permissions", manifestError);
  permission.only(["network", "java", "hook", "filesystem", "host"]);
  let network = null;
  const rawNetwork = object.network;
  if (rawNetwork === true) {
    network = { any: true, origins: [], privateNetwork: false };
  } else if (isObject(rawNetwork)) {
    const net = fields(rawNetwork, "permissions.network", manifestError);
    net.only(["origins", "privateNetwork"]);
    if (rawNetwork.origins === undefined) throw manifestError("permissions.network needs origins or must be true");
    const origins = strings(rawNetwork.origins, "permissions.network.origins", 64, manifestError);
    if (origins.length === 0) throw manifestError("permissions.network.origins must not be empty");
    network = { any: false, origins: origins.map(originOf), privateNetwork: net.boolean("privateNetwork") ?? false };
  } else if (rawNetwork !== undefined && rawNetwork !== false) {
    throw manifestError("permissions.network must be a boolean or an object");
  }
  let filesystem = { external: false, host: false, reason: null };
  if (object.filesystem !== undefined) {
    if (!isObject(object.filesystem)) throw manifestError("permissions.filesystem must be an object");
    const files = fields(object.filesystem, "permissions.filesystem", manifestError);
    files.only(["external", "host", "reason"]);
    filesystem = { external: files.boolean("external") ?? false, host: files.boolean("host") ?? false, reason: files.text("reason", 1, 512) };
  }
  let host = [];
  if (object.host !== undefined) {
    host = strings(object.host, "permissions.host", 32, manifestError);
    for (const name of host) if (!isDotted(name, false)) throw manifestError(`Invalid host permission: ${name}`);
    host = [...new Set(host)].sort();
  }
  return { network, java: permission.boolean("java") ?? false, hook: permission.boolean("hook") ?? false, filesystem, host };
}

// Validates a manifest and returns it normalized. With files, the paths it names must be in the package and README.md
// and icon.png are picked up by their default names.
export function validateManifest(input, { maxApi = API_VERSION, files = null } = {}) {
  let root = input;
  if (typeof input === "string") {
    try {
      root = JSON.parse(input.replace(/^﻿/, ""));
    } catch (error) {
      throw manifestError(`Not JSON: ${error.message}`);
    }
  }
  if (!isObject(root)) throw manifestError("The manifest must be a JSON object");
  const field = fields(root, "manifest", manifestError);
  field.only([
    "manifestVersion", "apiVersion", "id", "name", "version", "versionCode", "entry", "description", "author",
    "homepage", "hosts", "configSchema", "readme", "icon", "permissions",
  ]);
  const manifestVersion = field.integer("manifestVersion");
  if (manifestVersion === null) throw manifestError("manifestVersion is required");
  if (manifestVersion !== MANIFEST_VERSION) throw manifestError(`Unsupported manifestVersion ${manifestVersion}`);
  const apiVersion = field.integer("apiVersion");
  if (apiVersion === null) throw manifestError("apiVersion is required");
  if (apiVersion < 1 || apiVersion > maxApi) {
    throw new PackageError("UNSUPPORTED_API", `The script needs API ${apiVersion}; this module supports API 1 to ${maxApi}`);
  }
  const id = field.text("id", 1, 128);
  if (id === null) throw manifestError("id is required");
  if (!isDotted(id, false)) throw manifestError("id must be at least two dot-separated lowercase segments, such as com.example.hello");
  const name = field.text("name", 1, 64);
  if (name === null) throw manifestError("name is required");
  const version = field.text("version", 1, 32);
  if (version === null) throw manifestError("version is required");
  const versionCode = field.integer("versionCode");
  if (versionCode === null) throw manifestError("versionCode is required");
  if (versionCode < 1) throw manifestError("versionCode must be positive");
  const entry = field.text("entry", 1, MAX_PATH) ?? "main.js";
  if (!entry.endsWith(".js") && !entry.endsWith(".mjs")) throw manifestError("entry must be a .js or .mjs file");
  const configSchema = field.text("configSchema", 1, MAX_PATH);
  if (configSchema !== null && !configSchema.endsWith(".json")) throw manifestError("configSchema must be a .json file");
  let readme = field.text("readme", 1, MAX_PATH);
  let icon = field.text("icon", 1, MAX_PATH);
  if (icon !== null && !icon.endsWith(".png")) throw manifestError("icon must be a .png file");
  const homepage = field.text("homepage", 1, 512);
  if (homepage !== null && !homepage.startsWith("https://")) throw manifestError("homepage must be an https URL");
  const hosts = root.hosts === undefined ? [] : strings(root.hosts, "hosts", 16, manifestError).map((host) => {
    if (!isDotted(host, true)) throw manifestError(`Invalid host package name: ${host}`);
    return host;
  });
  if (files !== null) {
    for (const [name, path] of [["entry", entry], ["configSchema", configSchema], ["readme", readme], ["icon", icon]]) {
      if (path === null) continue;
      try {
        checkPath(path);
      } catch {
        throw manifestError(`${name} is not a valid package path`);
      }
      if (!files.has(path)) throw manifestError(`${name} names ${path}, which is not in the package`);
    }
    if (readme === null && files.has("README.md")) readme = "README.md";
    if (icon === null && files.has("icon.png")) icon = "icon.png";
  }
  let permissions;
  if (root.permissions === undefined) permissions = permissionsOf({});
  else if (isObject(root.permissions)) permissions = permissionsOf(root.permissions);
  else throw manifestError("permissions must be an object");
  return {
    manifestVersion, apiVersion, id, name, version, versionCode, entry,
    description: field.text("description", 1, 2048), author: field.text("author", 1, 128), homepage,
    hosts, configSchema, readme, icon, permissions,
  };
}

const TYPES = ["string", "text", "number", "integer", "boolean", "select"];
const EXTRAS = { number: ["min", "max"], integer: ["min", "max"], string: ["maxLength"], text: ["maxLength"], select: ["options"] };

// Validates a settings schema: { "fields": [...] }, each field one setting the settings page renders.
export function validateConfigSchema(input) {
  let root = input;
  if (typeof input === "string") {
    try {
      root = JSON.parse(input.replace(/^﻿/, ""));
    } catch (error) {
      throw schemaError(`Not JSON: ${error.message}`);
    }
  }
  if (!isObject(root)) throw schemaError("The schema must be a JSON object");
  fields(root, "schema", schemaError).only(["fields"]);
  if (!Array.isArray(root.fields)) throw schemaError("fields must be an array");
  if (root.fields.length > 64) throw schemaError("More than 64 fields");
  const keys = new Set();
  root.fields.forEach((field, index) => {
    const where = `fields[${index}]`;
    if (!isObject(field)) throw schemaError(`${where} must be an object`);
    const f = fields(field, where, schemaError);
    f.only(["key", "type", "title", "description", "default", "options", "min", "max", "maxLength"]);
    const key = f.text("key", 1, 64);
    if (key === null) throw schemaError(`${where}.key is required`);
    if (!/^[A-Za-z][A-Za-z0-9_]*$/.test(key) || keys.has(key)) throw schemaError(`${where}.key is invalid or repeated: ${key}`);
    keys.add(key);
    if (f.text("title", 1, 64) === null) throw schemaError(`${where}.title is required`);
    f.text("description", 1, 512);
    const type = f.text("type", 1, 16);
    if (type === null) throw schemaError(`${where}.type is required`);
    if (!TYPES.includes(type)) throw schemaError(`${where}.type ${type} is not one of ${TYPES.join(", ")}`);
    for (const extra of ["options", "min", "max", "maxLength"]) {
      if (extra in field && !(EXTRAS[type] ?? []).includes(extra)) throw schemaError(`${where}.${extra} does not apply to type ${type}`);
    }
    const min = f.number("min");
    const max = f.number("max");
    if (min !== null && max !== null && min > max) throw schemaError(`${where}.min is above max`);
    const maxLength = f.integer("maxLength");
    if (maxLength !== null && (maxLength < 1 || maxLength > 10000)) throw schemaError(`${where}.maxLength must be between 1 and 10000`);
    const options = [];
    if (type === "select") {
      if (!Array.isArray(field.options) || field.options.length === 0 || field.options.length > 64) {
        throw schemaError(`${where}.options must list 1 to 64 choices`);
      }
      field.options.forEach((choice, choiceIndex) => {
        const place = `${where}.options[${choiceIndex}]`;
        if (!isObject(choice)) throw schemaError(`${place} must be an object`);
        const c = fields(choice, place, schemaError);
        c.only(["value", "label"]);
        const value = c.text("value", 1, 128);
        if (value === null) throw schemaError(`${place}.value is required`);
        if (c.text("label", 1, 64) === null) throw schemaError(`${place}.label is required`);
        if (options.includes(value)) throw schemaError(`${where}.options repeats ${value}`);
        options.push(value);
      });
    }
    if ("default" in field) {
      const value = field.default;
      let fits;
      if (type === "boolean") fits = typeof value === "boolean";
      else if (type === "string" || type === "text") fits = typeof value === "string" && (maxLength === null || [...value].length <= maxLength);
      else if (type === "select") fits = typeof value === "string" && options.includes(value);
      else {
        const number = type === "integer" ? (Number.isSafeInteger(value) ? value : null) : (typeof value === "number" ? value : null);
        fits = number !== null && (min === null || number >= min) && (max === null || number <= max);
      }
      if (!fits) throw schemaError(`${where}.default does not fit the field`);
    }
  });
  return root;
}
