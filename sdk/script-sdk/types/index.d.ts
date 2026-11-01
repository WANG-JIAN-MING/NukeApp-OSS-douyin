// Types of the NukeApp-OSS script API, level 1. The modules exist only inside the module's script engine; a contract
// test in the module checks that every export declared here is what the engine provides, and nothing more.

// Bytes go in as Uint8Array, a string (encoded as UTF-8) or an array of byte values where text makes sense.
type NukeBytesInput = Uint8Array | Int8Array | number[] | string;

declare function setTimeout<A extends unknown[]>(callback: (...args: A) => void, delay?: number, ...args: A): number;
declare function setInterval<A extends unknown[]>(callback: (...args: A) => void, delay?: number, ...args: A): number;
declare function clearTimeout(id: number | undefined): void;
declare function clearInterval(id: number | undefined): void;

declare module "nuke:runtime" {
  // The script API level the engine implements.
  export const apiVersion: number;
  export const script: Readonly<{ id: string; name: string; version: string; versionCode: number }>;
  // The permissions the script declared and was granted, such as "network", "java" or "douyin.messaging".
  export const permissions: readonly string[];
  export function hasPermission(name: string): boolean;
  // Every failure of a native call. code names the kind, such as INVALID_ARGUMENT, UNAVAILABLE or LIMIT_EXCEEDED.
  export class NukeError extends Error {
    readonly code: string;
  }
  // Calling something the manifest did not declare.
  export class PermissionError extends NukeError {}
  // A Java call threw; javaClass names the Java exception.
  export class JavaError extends NukeError {
    readonly javaClass: string;
  }
}

declare module "nuke:log" {
  export function debug(...values: unknown[]): void;
  export function info(...values: unknown[]): void;
  export function warn(...values: unknown[]): void;
  export function error(...values: unknown[]): void;
}

declare module "nuke:timers" {
  export const setTimeout: typeof globalThis.setTimeout;
  export const clearTimeout: typeof globalThis.clearTimeout;
  export const setInterval: typeof globalThis.setInterval;
  export const clearInterval: typeof globalThis.clearInterval;
  export function sleep(millis: number): Promise<void>;
}

declare module "nuke:config" {
  type ConfigValue = string | number | boolean;
  // The current settings, frozen; keys the user never set have their schema default.
  export function values(): Readonly<Record<string, ConfigValue>>;
  export function has(key: string): boolean;
  export function get<T extends ConfigValue = ConfigValue>(key: string, fallback?: T): T | undefined;
  // Called after the user saves new settings; returns a function that removes the listener.
  export function onChange(listener: (values: Readonly<Record<string, ConfigValue>>) => void): () => void;
}

declare module "nuke:encoding" {
  export function encodeUtf8(text: string): Uint8Array;
  export function decodeUtf8(bytes: Uint8Array | Int8Array | number[]): string;
  export function encodeBase64(data: NukeBytesInput, options?: { url?: boolean }): string;
  export function decodeBase64(text: string): Uint8Array;
  export function encodeHex(data: NukeBytesInput, uppercase?: boolean): string;
  export function decodeHex(text: string): Uint8Array;
}

declare module "nuke:crypto" {
  export function randomBytes(length: number): Uint8Array;
  export function randomUUID(): string;
  export function digest(algorithm: "MD5" | "SHA-1" | "SHA-256" | "SHA-512", data: NukeBytesInput): Uint8Array;
  export function hmac(algorithm: "SHA-1" | "SHA-256" | "SHA-512", key: NukeBytesInput, data: NukeBytesInput): Uint8Array;
  export function timingSafeEqual(left: Uint8Array | number[], right: Uint8Array | number[]): boolean;
  interface AesGcmOptions {
    // 16, 24 or 32 bytes.
    key: Uint8Array | number[];
    // 12 to 16 bytes.
    iv: Uint8Array | number[];
    data: NukeBytesInput;
    aad?: NukeBytesInput;
  }
  // The ciphertext followed by the 16-byte tag.
  export function aesGcmEncrypt(options: AesGcmOptions): Uint8Array;
  export function aesGcmDecrypt(options: AesGcmOptions): Uint8Array;
}

declare module "nuke:java" {
  // Strings, numbers and booleans cross as values; every other Java object as a JavaObject.
  type JavaValue = JavaObject | string | number | boolean | null;
  // What can be passed to Java: values, Java objects, byte arrays and arrays of these for Java array parameters.
  type JavaArgument = JavaValue | undefined | Uint8Array | JavaArgument[] | { [key: string]: JavaArgument };

  // A reference to a Java object. Requires the java permission.
  export class JavaObject {
    private constructor();
    // The object's runtime class name.
    readonly className: string;
    // Calls an instance method; the overload is chosen from the arguments.
    call(name: string, ...args: JavaArgument[]): JavaValue;
    // Calls an explicit method from JavaClass.method on this object.
    invoke(member: JavaObject, ...args: JavaArgument[]): JavaValue;
    get(field: string): JavaValue;
    set(field: string, value: JavaArgument): void;
    instanceOf(className: string): boolean;
    getClass(): JavaClass;
    equals(other: unknown): boolean;
    hashCode(): number;
    toString(): string;
    // The elements of a Java array or Iterable.
    toArray(): JavaValue[];
    // The contents of a byte[].
    toBytes(): Uint8Array;
  }

  // A Java class: static members, constructors and member lookup.
  export class JavaClass extends JavaObject {
    // The class's name, such as java.lang.String.
    readonly name: string;
    new(...args: JavaArgument[]): JavaObject;
    // Calls a static method.
    call(name: string, ...args: JavaArgument[]): JavaValue;
    invoke(member: JavaObject, ...args: JavaArgument[]): JavaValue;
    // Reads a static field.
    get(field: string): JavaValue;
    set(field: string, value: JavaArgument): void;
    // A declared or inherited method; parameter types are needed when the name is overloaded.
    method(name: string, parameterTypes?: (string | JavaClass)[]): JavaObject;
    ctor(parameterTypes?: (string | JavaClass)[]): JavaObject;
    isInstance(value: unknown): boolean;
  }

  // Loads a class from the host's ClassLoader, or from loader. Names like "int" and "java.lang.String[]" work too.
  export function use(name: string, loader?: JavaObject | null): JavaClass;
  export function array(componentType: string | JavaClass, values: Iterable<JavaArgument>): JavaObject;
  export function isJava(value: unknown): value is JavaObject;
}

declare module "nuke:hook" {
  import type { JavaObject } from "nuke:java";
  type JavaValue = JavaObject | string | number | boolean | null;

  interface CallSnapshot {
    readonly phase: "before" | "after";
    readonly method: JavaObject;
    readonly thisObject: JavaValue;
    // The arguments; setArg also updates this array.
    readonly args: JavaValue[];
  }
  interface BeforeCall extends CallSnapshot {
    readonly phase: "before";
    setArg(index: number, value: unknown): void;
    // Skips the original method and the rest of the chain with this result.
    returnEarly(value: unknown): void;
    // Throws in the caller: a Java Throwable as is, anything else as a ScriptThrownException with its message.
    throwEarly(error: unknown): void;
  }
  interface AfterCall extends CallSnapshot {
    readonly phase: "after";
    readonly result: JavaValue;
    // The Java exception the call threw, or null.
    readonly error: JavaObject | null;
    setArg(index: number, value: unknown): void;
    setResult(value: unknown): void;
    setError(error: unknown): void;
  }
  interface HookOptions {
    // Must return synchronously; the host thread waits for the answer.
    before?(call: BeforeCall): void;
    after?(call: AfterCall): void;
    // Higher runs first.
    priority?: number;
    // How long the host thread waits for an answer, 1 to 1000 ms, 100 by default. A late answer is dropped and the
    // call goes on unchanged; three in a row remove the hook.
    timeoutMs?: number;
    // Receive calls without the host thread waiting; the call cannot be changed.
    observe?: boolean;
  }
  // Requires the java and hook permissions. Closed automatically when the script stops.
  export function hook(member: JavaObject, options: HookOptions): { close(): void };
}

declare module "nuke:http" {
  export class Response {
    private constructor();
    readonly status: number;
    readonly ok: boolean;
    // The final URL after redirects.
    readonly url: string;
    // Lower-cased header names; repeated headers are joined with ", ".
    readonly headers: Readonly<Record<string, string>>;
    bytes(): Uint8Array;
    text(): string;
    json<T = unknown>(): T;
  }
  interface FetchOptions {
    method?: "GET" | "HEAD" | "POST" | "PUT" | "PATCH" | "DELETE" | "OPTIONS";
    headers?: Record<string, string | number | boolean>;
    body?: Uint8Array | Int8Array | number[] | string;
    // 1 to 60000 ms, 15000 by default.
    timeoutMs?: number;
  }
  // Only the origins in permissions.network; private addresses need privateNetwork. Bodies are limited to 8 MiB.
  export function fetch(url: string, options?: FetchOptions): Promise<Response>;
}

declare module "nuke:fs" {
  interface FileInfo {
    name: string;
    directory: boolean;
    size: number;
    modified: number;
  }
  // Paths are relative to the root with forward slashes.
  interface FileRoot {
    exists(path: string): Promise<boolean>;
    stat(path: string): Promise<FileInfo | null>;
    list(path?: string): Promise<FileInfo[]>;
    readText(path: string): Promise<string>;
    readBytes(path: string): Promise<Uint8Array>;
    writeText(path: string, text: string): Promise<void>;
    writeBytes(path: string, bytes: Uint8Array | Int8Array | number[]): Promise<void>;
    appendText(path: string, text: string): Promise<void>;
    mkdir(path: string): Promise<void>;
    remove(path: string): Promise<void>;
  }
  // The script's own directory, kept across updates and removed with the script; 64 MiB at most.
  export const data: FileRoot;
  // The host app's private directory; requires permissions.filesystem.host.
  export const host: FileRoot;
}

declare module "nuke:ui" {
  // Shown with the script's name in front.
  export function toast(message: string, options?: { long?: boolean }): Promise<void>;
  export function alert(options: string | { title?: string; message: string }): Promise<void>;
  export function confirm(options: string | { title?: string; message: string; confirmText?: string; cancelText?: string }): Promise<boolean>;
}

declare module "nuke:douyin/account" {
  type Account =
    | { state: "signedIn"; uid: string; nickname: string }
    | { state: "signedOut" }
    | { state: "unavailable" };
  // Requires the douyin.account host permission.
  export function current(): Account;
}

declare module "nuke:douyin/im" {
  type SendResult =
    | { status: "sent"; detail: string; clientMessageId: string | null; serverMessageId: number | null }
    | { status: "failed" | "unknown"; detail: string };
  // Sends through Douyin's own sender; requires the douyin.messaging host permission. "unknown" means no answer in
  // time, never that the message went out.
  export function sendText(
    target: { uid: string } | { conversationId: string },
    text: string,
    options?: { timeoutMs?: number },
  ): Promise<SendResult>;
}
