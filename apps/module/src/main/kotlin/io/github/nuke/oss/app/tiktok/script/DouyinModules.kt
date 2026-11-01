package io.github.nuke.oss.app.tiktok.script

import io.github.nuke.oss.app.shared.dex.DexQueryUnavailable
import io.github.nuke.oss.app.shared.settings.HostAccount
import io.github.nuke.oss.app.tiktok.sdk.chat.ContentSender
import io.github.nuke.oss.app.tiktok.sdk.chat.SendResult
import io.github.nuke.oss.app.tiktok.sdk.contact.DouyinAccountReader
import io.github.nuke.oss.script.ScriptException
import io.github.nuke.oss.script.ScriptModule
import io.github.nuke.oss.script.ScriptModuleContext
import io.github.nuke.oss.script.longAt
import io.github.nuke.oss.script.optionalStringAt
import io.github.nuke.oss.script.stringAt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Host capabilities a script declares under permissions.host; both need the user's grant.
internal const val DOUYIN_ACCOUNT = "douyin.account"
internal const val DOUYIN_MESSAGING = "douyin.messaging"

// nuke:douyin/account: the signed-in account, read through the host's account service like the settings header.
internal class DouyinAccountModule(private val reader: DouyinAccountReader) : ScriptModule {
    override val name = "nuke:douyin/account"
    override val source = """
export function current() { return __nuke.check(globalThis.__nk_douyin_account()); }
"""

    override fun bind(context: ScriptModuleContext) {
        context.function("douyin_account") {
            context.requirePermission(DOUYIN_ACCOUNT)
            when (val account = reader.read()) {
                is HostAccount.SignedIn -> mapOf("state" to "signedIn", "uid" to account.userId, "nickname" to account.nickname)
                HostAccount.SignedOut -> mapOf("state" to "signedOut")
                HostAccount.Unavailable -> mapOf("state" to "unavailable")
            }
        }
    }
}

// nuke:douyin/im: sends text through the host's own content sender, to a user or to an existing conversation. Sent
// means the server acknowledged it; no answer in time is unknown, never assumed sent.
internal class DouyinImModule(
    private val sender: ContentSender,
    private val text: (String) -> Any,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ScriptModule {
    override val name = "nuke:douyin/im"
    override val source = """
export async function sendText(target, text, options) {
  const to = target || {};
  return __nuke.check(await globalThis.__nk_douyin_send_text(
    to.uid === undefined || to.uid === null ? null : String(to.uid),
    to.conversationId === undefined || to.conversationId === null ? null : String(to.conversationId),
    String(text),
    options && options.timeoutMs !== undefined ? options.timeoutMs : null,
  ));
}
"""

    override fun bind(context: ScriptModuleContext) {
        context.asyncFunction("douyin_send_text") { args ->
            context.requirePermission(DOUYIN_MESSAGING)
            val uid = args.optionalStringAt(0, "uid")
            val conversation = args.optionalStringAt(1, "conversationId")
            if ((uid == null) == (conversation == null)) throw ScriptException.argument("Name exactly one of uid and conversationId")
            val text = args.stringAt(2, "text")
            if (text.isEmpty() || text.length > MAX_TEXT) throw ScriptException.argument("text must be 1 to $MAX_TEXT characters")
            val timeout = if (args.getOrNull(3) == null) DEFAULT_TIMEOUT else args.longAt(3, "timeoutMs")
            if (timeout !in 1..MAX_TIMEOUT) throw ScriptException.argument("timeoutMs must be between 1 and $MAX_TIMEOUT")
            withContext(io) {
                val result = try {
                    val content = this@DouyinImModule.text(text)
                    if (uid != null) sender.send(uid, content, timeout) else sender.sendToConversation(conversation!!, content, timeout)
                } catch (error: DexQueryUnavailable) {
                    throw ScriptException(ScriptException.UNAVAILABLE, "Sending is not adapted to this Douyin build: ${error.message}")
                }
                when (result) {
                    is SendResult.Sent -> mapOf(
                        "status" to "sent", "detail" to result.detail,
                        "clientMessageId" to result.clientMessageId, "serverMessageId" to result.serverMessageId,
                    )
                    is SendResult.Failed -> mapOf("status" to "failed", "detail" to result.detail)
                    is SendResult.Unknown -> mapOf("status" to "unknown", "detail" to result.detail)
                }
            }
        }
    }

    private companion object {
        const val MAX_TEXT = 5_000
        const val DEFAULT_TIMEOUT = 10_000L
        const val MAX_TIMEOUT = 30_000L
    }
}
