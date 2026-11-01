package io.github.nuke.oss.app.tiktok.query

import io.github.nuke.oss.app.shared.dex.DexQueries
import io.github.nuke.oss.app.shared.dex.DexQuery
import io.github.nuke.oss.app.shared.dex.field
import io.github.nuke.oss.app.shared.dex.method
import io.github.nuke.oss.app.shared.dex.uniqueField
import io.github.nuke.oss.app.shared.dex.uniqueMethod
import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.matchers.MethodMatcher

// Dex queries for downloading and saving media (videos, image sets, comment media), located the same way as
// FeedQueries.
@DexQueries
internal object DownloadQueries {
    private const val AWEME = "com.ss.android.ugc.aweme.feed.model.Aweme"
    private const val VIDEO = "com.ss.android.ugc.aweme.feed.model.Video"
    private const val SERIALIZED = "com.google.gson.annotations.SerializedName"
    private const val SAVE_ITEM = "com.ss.android.ugc.aweme.comment.manager.longclickaction.actions.SaveImageActionItem"
    private const val COMMENT = "com.ss.android.ugc.aweme.comment.model.Comment"
    private const val COMMENT_IMAGE = "com.ss.android.ugc.aweme.comment.model.CommentImageStruct"
    private const val PRIVACY = "com.ss.android.ugc.aweme.feed.share.video.SharePrivacyVideoApi\$PrivacyVideoResponse"
    private const val AB_TEST = "com.ss.android.ugc.aweme.services.external.IABTestService"
    private const val STORY = "com.ss.android.ugc.aweme.services.external.ui.IStoryService"

    // The video's playable address: the no-argument getter of the address model that reads both play_addr and
    // play_addr_265 but not the ratio field.
    val VIDEO_PLAY = DexQuery { bridge ->
        val candidates = bridge.findMethod {
            matcher {
                declaredClass = VIDEO
                paramCount = 0
                returnType = "com.ss.android.ugc.aweme.feed.model.VideoUrlModel"
                usingFields {
                    listOf("play_addr", "play_addr_265").forEach { key ->
                        add { field { annotations { add { type = SERIALIZED; usingEqStrings(key) } } } }
                    }
                }
            }
        }.filter { data ->
            data.usingFields.none { usage ->
                usage.field.annotations.any { annotation ->
                    annotation.typeName == SERIALIZED && annotation.elements.any { it.value.stringValue() == "ratio" }
                }
            }
        }
        check(candidates.size == 1) { "expected one method, found ${candidates.size}" }
        candidates.single().descriptor
    }

    private fun videoField(serialized: String) = field {
        matcher { declaredClass = VIDEO; annotations { add { type = SERIALIZED; usingEqStrings(serialized) } } }
    }

    val VIDEO_DOWNLOAD = videoField("download_addr")
    val VIDEO_WATERMARK = videoField("has_watermark")
    val VIDEO_SUFFIX_WATERMARK = videoField("has_download_suffix_logo_addr")

    // The place the download address of a video is chosen.
    val VIDEO_ADDRESS = method {
        matcher {
            returnType = "com.ss.android.ugc.aweme.feed.model.VideoUrlModel"
            paramTypes(AWEME)
            invokeMethods {
                add { descriptor = "Lcom/google/gson/Gson;-><init>()V" }
                add { descriptor = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;->getVideo()Lcom/ss/android/ugc/aweme/feed/model/Video;" }
            }
        }
    }

    private fun DexKitBridge.imageDownloadOwner() = findMethod {
        matcher { returnType = "void"; paramTypes("com.ss.android.ugc.aweme.sharer.ui.SharePackage"); usingEqStrings("downloadImage") }
    }.also { check(it.size == 1) { "expected one image download method, found ${it.size}" } }.single()

    val IMAGE_DOWNLOAD = DexQuery { bridge -> bridge.imageDownloadOwner().descriptor }

    // The Aweme the image download step works on.
    val DOWNLOAD_AWEME = DexQuery { bridge ->
        val owner = bridge.imageDownloadOwner().declaredClassName
        bridge.uniqueField { matcher { declaredClass = owner; type = AWEME } }
    }

    val IMAGE_EXECUTOR = method {
        matcher { returnType = "boolean"; paramCount = 1; usingEqStrings("/douyin", "share_", ".png", "DownLoadExecutor") }
    }

    val LIVE_EXECUTOR = method {
        matcher { returnType = "boolean"; paramCount = 1; usingEqStrings("DownloadLiveExecutor", "encode live photo isFinish: ") }
    }

    val TASK_PATHS = method {
        matcher {
            declaredClass = "com.ss.android.ugc.aweme.share.business.camera.publish.infer.AbsTask"
            returnType = "java.util.List"
            paramCount = 0
        }
    }

    val LOCAL_MASK = method {
        matcher { returnType = "boolean"; paramCount = 0; usingEqStrings("save_image_to_video_local_water_mask_enable") }
    }

    // The live photo mask switch: the implementation, in the class that also reads the local mask switch, of the
    // interface method the live photo exporter calls.
    val LIVE_MASK = DexQuery { bridge ->
        val api = bridge.findMethod {
            matcher {
                declaredClass = AB_TEST
                returnType = "boolean"
                paramCount = 0
                callerMethods { add { usingEqStrings("_with_watermark.mp4") } }
            }
        }.also { check(it.size == 1) { "expected one interface method, found ${it.size}" } }.single()
        val implementation = bridge.findClass {
            matcher {
                interfaces { add { className = AB_TEST } }
                usingEqStrings("save_image_to_video_local_water_mask_enable")
            }
        }.also { check(it.size == 1) { "expected one implementation, found ${it.size}" } }.single()
        bridge.uniqueMethod {
            matcher { declaredClass = implementation.name; name = api.name; returnType = "boolean"; paramCount = 0 }
        }
    }

    val STORY_SINGLE = method {
        matcher {
            returnType = "void"
            usingEqStrings("asve")
            declaredClass { interfaces { add { className = STORY } } }
        }
    }

    val STORY_MULTI = method {
        matcher {
            returnType = "void"
            paramTypes(
                "android.app.Activity", "androidx.lifecycle.LifecycleOwner", "java.util.List",
                "com.ss.android.ugc.aweme.music.model.Music", "boolean", "boolean", "java.lang.String", "boolean",
                "kotlin.jvm.functions.Function1",
            )
            declaredClass { interfaces { add { className = STORY } }; usingEqStrings("asve") }
            invokeMethods { add { name = "<init>"; declaredClass { usingEqStrings("feed_save_local_images_use_slides") } } }
        }
    }

    // A public final method of an abstract class with one parameter that reads the NORMAL, GRAYED and HIDDEN states.
    private fun permission(matcher: MethodMatcher) = matcher.apply {
        modifiers = Modifier.PUBLIC or Modifier.FINAL
        declaredClass { modifiers = Modifier.ABSTRACT }
        paramCount = 1
        usingFields {
            listOf("NORMAL", "GRAYED", "HIDDEN").forEach { symbol ->
                add { field { name = symbol; type { superClass = "java.lang.Enum"; usingEqStrings("NORMAL", "GRAYED", "HIDDEN") } } }
            }
        }
    }

    private fun DexKitBridge.permissionMethod() = findMethod { matcher { permission(this) } }
        .also { check(it.size == 1) { "expected one permission method, found ${it.size}" } }.single()

    val PERMISSION = DexQuery { bridge -> bridge.permissionMethod().descriptor }

    // The status enum field of the permission result.
    val PERMISSION_STATUS = DexQuery { bridge ->
        val method = bridge.permissionMethod()
        bridge.uniqueField {
            matcher {
                declaredClass = method.returnTypeName
                type { superClass = "java.lang.Enum"; usingEqStrings("NORMAL", "GRAYED", "HIDDEN") }
            }
        }
    }

    // Which action the permission checker belongs to (DOWNLOAD, DUET and so on).
    val CHECKER_KIND = DexQuery { bridge ->
        val owner = bridge.permissionMethod().declaredClassName
        bridge.uniqueMethod {
            matcher { declaredClass = owner; paramCount = 0; returnType { superClass = "java.lang.Enum"; usingEqStrings("DOWNLOAD", "DUET") } }
        }
    }

    val GALLERY_DOWNLOAD = method {
        matcher {
            returnType = "void"
            paramTypes(AWEME, "java.lang.String")
            invokeMethods { listOf("getVideo", "isAwemeFromXiGua", "getDownloadStatus", "getAid").forEach { n -> add { declaredClass = AWEME; name = n } } }
        }
    }

    // The int field Aweme.getDownloadStatus reads; its name is obfuscated.
    val AWEME_DOWNLOAD_STATUS = field {
        matcher {
            declaredClass = "com.ss.android.ugc.aweme.feed.model.AwemeStatus"
            type = "int"
            readMethods { add { declaredClass = AWEME; name = "getDownloadStatus"; returnType = "int"; paramCount = 0 } }
        }
    }

    val PRIVACY_DOWNLOAD = method {
        matcher {
            returnType = "io.reactivex.Observable"
            paramTypes("java.lang.String")
            modifiers = Modifier.PUBLIC or Modifier.STATIC or Modifier.FINAL
            declaredClass { usingEqStrings("https://www.snssdk.com") }
        }
    }

    val PRIVACY_STATUS = field {
        matcher { declaredClass = PRIVACY; annotations { add { type = SERIALIZED; usingEqStrings("status") } } }
    }

    val PRIVACY_MESSAGE = field {
        matcher { declaredClass = PRIVACY; annotations { add { type = SERIALIZED; usingEqStrings("msg") } } }
    }

    // Comment media.
    val COMMENT_IMAGE_DOWNLOAD = method {
        matcher {
            declaredClass = COMMENT_IMAGE
            paramCount = 0
            returnType = "com.ss.android.ugc.aweme.base.model.UrlModel"
            usingFields { add { field { annotations { add { type = SERIALIZED; usingEqStrings("download_url") } } } } }
        }
    }

    val COMMENT_IMAGE_ORIGIN = field {
        matcher { declaredClass = COMMENT_IMAGE; annotations { add { type = SERIALIZED; usingEqStrings("origin_url") } } }
    }

    val COMMENT_AUDIO_CONTENT = field {
        matcher {
            declaredClass = "com.ss.android.ugc.aweme.comment.model.CommentAudioStruct"
            annotations { add { type = SERIALIZED; usingEqStrings("content") } }
        }
    }

    val SAVE_VISIBLE = method {
        matcher { declaredClass = SAVE_ITEM; returnType = "boolean"; paramCount = 0 }
    }

    val SAVE_CLICK = method {
        matcher { declaredClass = SAVE_ITEM; returnType = "void"; paramTypes("int"); usingEqStrings("bpea-comment_save_image_to_album") }
    }

    private fun DexKitBridge.whiteListMethod() = findMethod {
        matcher { returnType = "java.util.Set"; paramCount = 1; usingEqStrings("custom", "default"); modifiers = Modifier.PUBLIC or Modifier.STATIC }
    }.also { check(it.size == 1) { "expected one white list method, found ${it.size}" } }.single()

    val COMMENT_WHITE_LIST = DexQuery { bridge -> bridge.whiteListMethod().descriptor }

    // The field of the save action item that holds the menu parameters.
    val SAVE_PARAMS = DexQuery { bridge ->
        val params = bridge.whiteListMethod().paramTypes.single()
        bridge.uniqueField { matcher { declaredClass = SAVE_ITEM; type = params.name } }
    }

    // The field of the menu parameters that holds the comment.
    val PARAMS_COMMENT = DexQuery { bridge ->
        val params = bridge.whiteListMethod().paramTypes.single()
        bridge.uniqueField { matcher { declaredClass = params.name; type = COMMENT } }
    }

    // The "Listen to Douyin" local content filter.
    val LISTEN_FILTER = method {
        matcher { returnType = "boolean"; paramTypes(AWEME, "java.lang.String"); usingEqStrings("listen_video_status") }
    }
}
