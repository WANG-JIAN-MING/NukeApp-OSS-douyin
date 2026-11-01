package io.github.nuke.oss.app.shared.activity

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import java.io.File
import java.io.IOException
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicReference

// Starts ACTION_OPEN_DOCUMENT from the Activity in front with a request code of this picker's own and takes the answer
// out of Activity.dispatchActivityResult before the host sees it, whichever host Activity gets it (a recreated one
// included). That method is framework-internal but has kept its name and its (requestCode, resultCode, Intent) order;
// a framework without it fails the install. The chosen file is copied on a background thread into directory, which
// holds only the latest pick.
internal class AndroidFilePicker(
    private val directory: File,
    private val logger: Logger,
    private val background: (() -> Unit) -> Unit = { task -> Thread(task, "nuke-file-picker").start() },
) : FilePicker {
    private val activities = ResumedActivity()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val pending = AtomicReference<((PickResult) -> Unit)?>()
    private var featureId = "file-picker"
    private var requestCode = BASE_REQUEST
    @Volatile private var maxBytes = 0L

    // Where one overload keeps the request code, the result code and the data.
    private class Delivery(val method: Method, val request: Int, val result: Int, val data: Int)

    override fun install(scope: FeatureScope, featureId: String) {
        this.featureId = featureId
        // Lower 16 bits only: androidx Activities reject larger request codes.
        requestCode = BASE_REQUEST + (featureId.hashCode() and 0xFF)
        val deliveries = Activity::class.java.declaredMethods.filter { it.name == "dispatchActivityResult" }.mapNotNull { method ->
            val ints = method.parameterTypes.withIndex().filter { it.value == Int::class.javaPrimitiveType }.map { it.index }
            val data = method.parameterTypes.indexOf(Intent::class.java)
            if (ints.size >= 2 && data >= 0) Delivery(method.apply { isAccessible = true }, ints[0], ints[1], data) else null
        }
        check(deliveries.isNotEmpty()) { "Activity.dispatchActivityResult(…, int, int, Intent, …) is unavailable" }
        activities.install(scope, featureId)
        scope.own(AutoCloseable { pending.getAndSet(null)?.invoke(PickResult.Failed("closed")) })
        deliveries.forEachIndexed { index, delivery ->
            scope.hooks.install(delivery.method, HookSpec(
                id = "$featureId:Picked$index",
                requiredCapabilities = setOf(HookCapability.EARLY_RETURN),
                before = { call ->
                    if (call.argument(delivery.request) != requestCode) return@HookSpec
                    val done = pending.getAndSet(null) ?: return@HookSpec
                    call.returnEarly(null)
                    val activity = call.receiver as? Activity
                    val uri = (call.argument(delivery.data) as? Intent)?.data
                    when {
                        call.argument(delivery.result) != Activity.RESULT_OK || uri == null -> background { done(PickResult.Cancelled) }
                        activity == null -> background { done(PickResult.Failed("no activity")) }
                        else -> {
                            val resolver = activity.contentResolver
                            background { done(copy(resolver, uri, maxBytes)) }
                        }
                    }
                },
            ))
        }
    }

    override fun pick(mimeTypes: List<String>, maxBytes: Long, done: (PickResult) -> Unit) {
        main.post {
            val activity = activities.get() ?: return@post background { done(PickResult.Failed("no activity in front")) }
            if (!pending.compareAndSet(null, done)) return@post background { done(PickResult.Failed("another pick is open")) }
            this.maxBytes = maxBytes
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mimeTypes.singleOrNull() ?: "*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
            try {
                activity.startActivityForResult(intent, requestCode)
            } catch (failure: ActivityNotFoundException) {
                pending.set(null)
                logger.log("feature=$featureId picker_unavailable ${failure.javaClass.simpleName}")
                background { done(PickResult.Failed("no document picker")) }
            }
        }
    }

    private fun copy(resolver: ContentResolver, uri: Uri, maxBytes: Long): PickResult = try {
        val name = displayName(resolver, uri)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create the pick directory")
        directory.listFiles()?.forEach { it.delete() }
        val extension = name?.substringAfterLast('.', "")?.filter { it.isLetterOrDigit() }?.take(8).orEmpty()
        val target = File(directory, "picked-${System.currentTimeMillis()}" + if (extension.isEmpty()) "" else ".$extension")
        val input = resolver.openInputStream(uri) ?: throw IOException("The provider gave no stream")
        input.use { source ->
            target.outputStream().use { sink ->
                val buffer = ByteArray(BUFFER)
                var total = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) throw IOException("The file is larger than ${maxBytes / 1024 / 1024} MiB")
                    sink.write(buffer, 0, read)
                }
            }
        }
        PickResult.Picked(target, name)
    } catch (failure: IOException) {
        logger.log("feature=$featureId pick_copy_failed ${failure.javaClass.simpleName}")
        PickResult.Failed(failure.message ?: failure.javaClass.simpleName)
    } catch (failure: SecurityException) {
        logger.log("feature=$featureId pick_copy_failed ${failure.javaClass.simpleName}")
        PickResult.Failed(failure.javaClass.simpleName)
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private companion object {
        const val BASE_REQUEST = 0x4E00
        const val BUFFER = 64 * 1024
    }
}
