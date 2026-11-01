package io.github.nuke.oss.tools;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import kotlin.Unit;
import io.github.nuke.oss.patcher.patch.KeyMaterial;
import io.github.nuke.oss.patcher.patch.LocalPatchKey;
import io.github.nuke.oss.patcher.patch.NativePatchFiles;
import io.github.nuke.oss.patcher.patch.PatchEngine;
import org.json.JSONObject;

public final class AndroidPatchProbe {
    private static final Set<String> STAGES = new HashSet<>(Arrays.asList(
            "stage:READING", "stage:SIGNING", "stage:PACKING_SPLIT", "stage:REWRITING",
            "stage:INJECTING", "stage:EMBEDDING", "stage:WRITING", "stage:DONE", "stage:VERIFYING"));

    private AndroidPatchProbe() {}

    public static void main(String[] arguments) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            failure.printStackTrace(System.err);
            System.exit(1);
        });
        runProbe(arguments);
    }

    private static void runProbe(String[] arguments) throws Exception {
        System.out.println("probe:START");
        if (arguments.length != 1) throw new IllegalArgumentException("Expected the private probe directory");
        File root = new File(arguments[0]).getCanonicalFile();
        File input = new File(root, "fixture.apk");
        File module = new File(root, "module.apk");
        File nativeLibrary = new File(root, "libnuke_patch_core.so");
        File outputDirectory = new File(root, "output");
        File temporaryDirectory = new File(root, "temporary");
        if (!temporaryDirectory.mkdir()) throw new IllegalStateException("Cannot create private probe temporary directory");
        System.setProperty("java.io.tmpdir", temporaryDirectory.getAbsolutePath());
        System.load(nativeLibrary.getAbsolutePath());
        System.out.println("probe:NATIVE_LOADED");

        JSONObject moduleInspection = verified(NativePatchFiles.INSTANCE.inspectApk(module.getAbsolutePath()), "module inspection");
        JSONObject inputInspection = verified(NativePatchFiles.INSTANCE.inspectApk(input.getAbsolutePath()), "fixture inspection");
        System.out.println("probe:INPUTS_VERIFIED");
        File keyDirectory = new File(root, "private-key");
        KeyMaterial first = LocalPatchKey.INSTANCE.loadOrCreate(keyDirectory);
        KeyMaterial second = LocalPatchKey.INSTANCE.loadOrCreate(keyDirectory);
        if (!first.getCertificateSha256().equals(second.getCertificateSha256())
                || !first.getPassword().equals(second.getPassword())) {
            throw new IllegalStateException("Local signing identity changed while reloading");
        }
        String storeType = KeyStore.getDefaultType();
        if (!"BKS".equalsIgnoreCase(storeType)) throw new IllegalStateException("Expected Android's default BKS store");
        System.out.println("probe:KEY_RELOADED");
        final int[] stageCount = {0};
        File patched = new PatchEngine().patch(input, module, outputDirectory, first, stage -> {
            if (STAGES.contains(stage)) {
                stageCount[0]++;
                System.out.println(stage);
            }
            return Unit.INSTANCE;
        });
        JSONObject verification = verified(NativePatchFiles.INSTANCE.verifyPatched(
                input.getAbsolutePath(), module.getAbsolutePath(), patched.getAbsolutePath()), "patched APK verification");
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("execution", "Android ART app_process under shell UID; no application installed or launched");
        result.put("proof", "production PatchEngine signing/payload checks, Android BKS key reload, Rust JNI file verification");
        result.put("does_not_prove", "UI lifecycle, application sandbox behavior or runtime Hook activation");
        result.put("key_store_type", storeType);
        result.put("signing_certificate_sha256", first.getCertificateSha256());
        result.put("persistent_key_reload_matches", true);
        result.put("structured_stage_count", stageCount[0]);
        result.put("output_file", patched.getName());
        result.put("module", moduleInspection);
        result.put("fixture", inputInspection);
        result.put("patched", verification);
        try (FileOutputStream output = new FileOutputStream(new File(root, "verification.json"))) {
            output.write((result.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        System.out.println("Android patcher probe verification passed");
    }

    private static JSONObject verified(String json, String operation) throws Exception {
        JSONObject value = new JSONObject(json);
        if (!value.optBoolean("ok")) {
            JSONObject error = value.optJSONObject("error");
            throw new IllegalStateException(operation + " failed: " + (error == null ? "unknown" : error.optString("code", "unknown")));
        }
        return value;
    }
}
