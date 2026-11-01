package io.github.nuke.oss.hook.runtime

import io.github.nuke.oss.common.closeAll
import io.github.nuke.oss.hook.api.*
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class TargetSnapshot(val target: String, val subscriptions: Int, val inFlight: Int)

// One backend attachment per target; callbacks run against immutable subscription snapshots.
class DefaultHookService(
    private val backend: HookBackend,
    private val failureSink: HookFailureSink = HookFailureSink {},
) : HookService, AutoCloseable {
    private val lock = Any()
    private val targets = LinkedHashMap<Executable, TargetEntry>()
    private var closed = false
    private val sinkFailures = AtomicInteger()

    val diagnosticSinkFailures: Int get() = sinkFailures.get()

    override fun install(owner: HookOwner, target: Executable, spec: HookSpec): HookHandle = synchronized(lock) {
        check(!closed) { "Hook service is closed" }
        require(!Modifier.isAbstract(target.modifiers)) { "Abstract targets have no implementation" }
        if (target is Constructor<*>) {
            require(spec.before == null) { "Constructors only support successful after observation" }
            requireAvailable(target, HookCapability.CONSTRUCTOR_OBSERVE_AFTER)
        } else {
            requireAvailable(target, HookCapability.METHOD_BEFORE_AFTER)
        }
        spec.requiredCapabilities.forEach { requireAvailable(target, it) }
        val key = SubscriptionKey(owner, spec.id)
        val entry = targets[target] ?: TargetEntry(target).also {
            // Do not publish a successful registration if backend installation fails.
            it.binding = backend.attach(target, it)
            targets[target] = it
        }
        require(entry.subscriptions.none { it.key == key }) { "Duplicate Hook subscription: $key" }
        entry.subscriptions = (entry.subscriptions + Subscription(key, spec)).sortedWith(
            compareByDescending<Subscription> { it.spec.priority }
                .thenBy { it.key.owner.featureId }
                .thenBy { it.key.owner.generationId }
                .thenBy { it.key.id },
        )
        object : HookHandle {
            override val id = spec.id
            override val owner = owner
            private val released = AtomicBoolean()
            override fun close() {
                if (!released.compareAndSet(false, true)) return
                synchronized(lock) {
                    entry.subscriptions = entry.subscriptions.filterNot { it.key == key }
                }
            }
        }
    }

    fun snapshot(): List<TargetSnapshot> = synchronized(lock) {
        targets.values.map { TargetSnapshot(it.target.toString(), it.subscriptions.size, it.calls.get()) }
    }

    override fun close() {
        val bindings = synchronized(lock) {
            if (closed) return
            closed = true
            targets.values.forEach { it.subscriptions = emptyList() }
            targets.values.map { entry -> AutoCloseable { entry.binding.retire() } }
        }
        bindings.asReversed().closeAll()
    }

    private fun requireAvailable(target: Executable, capability: HookCapability) {
        val support = backend.support(target, capability)
        require(support.level == SupportLevel.AVAILABLE) {
            "${backend.id}: $capability is ${support.level}: ${support.reason.orEmpty()}"
        }
    }

    private data class SubscriptionKey(val owner: HookOwner, val id: String)
    private data class Subscription(val key: SubscriptionKey, val spec: HookSpec)

    private inner class TargetEntry(val target: Executable) : BackendCallbacks {
        lateinit var binding: BackendBinding
        @Volatile var subscriptions: List<Subscription> = emptyList()
        val calls = AtomicInteger()
        val capabilities = HookCapability.entries.associateWith { backend.support(target, it) }

        override fun enter(frame: BackendFrame): InvocationToken {
            require(frame.target == target)
            check(frame.state == CallState.Pending) { "Backend entered a completed call" }
            CallValidation.arguments(target, frame.arguments)
            val token = Token(this, frame)
            calls.incrementAndGet()
            try {
                val snapshot = subscriptions
                for (subscription in snapshot) {
                    token.entered += subscription
                    subscription.spec.before?.let { callback ->
                        val view = BeforeView(token)
                        protectedCall(subscription, "before", token, view) { callback(view) }
                    }
                    if (frame.state is CallState.Completed) break
                }
                token.submittedArguments = frame.arguments.copyOf()
                return token
            } catch (error: Throwable) {
                calls.decrementAndGet()
                throw error
            }
        }

        override fun exit(frame: BackendFrame, token: InvocationToken) {
            val invocation = token as? Token ?: error("Foreign invocation token")
            check(invocation.entry === this && invocation.frame === frame) { "Mismatched invocation token" }
            check(invocation.thread === Thread.currentThread()) { "Call crossed threads" }
            check(invocation.finished.compareAndSet(false, true)) { "Call already finished" }
            try {
                check(frame.state is CallState.Completed) { "Backend exited without an outcome" }
                val failedConstructor = target is Constructor<*> &&
                    (frame.state as CallState.Completed).outcome is CallOutcome.Thrown
                if (!failedConstructor) for (subscription in invocation.entered.asReversed()) {
                    subscription.spec.after?.let { callback ->
                        val view = AfterView(invocation)
                        protectedCall(subscription, "after", invocation, view) { callback(view) }
                    }
                }
            } finally {
                calls.decrementAndGet()
            }
        }

        private fun protectedCall(
            subscription: Subscription,
            phase: String,
            token: Token,
            view: ViewGuard,
            callback: () -> Unit,
        ) {
            val savedArguments = token.frame.arguments.copyOf()
            val savedState = token.frame.state
            try {
                callback()
            } catch (error: Throwable) {
                token.frame.arguments = savedArguments
                token.frame.state = savedState
                error.rethrowIfFatal()
                val failure = HookFailure(subscription.key.owner, subscription.key.id, target, phase, error)
                try {
                    failureSink.report(failure)
                } catch (sinkError: Throwable) {
                    sinkError.rethrowIfFatal()
                    sinkFailures.incrementAndGet()
                }
            } finally {
                view.invalidate()
            }
        }
    }

    private inner class Token(val entry: TargetEntry, val frame: BackendFrame) : InvocationToken {
        val thread: Thread = Thread.currentThread()
        val entered = ArrayList<Subscription>()
        var submittedArguments: Array<Any?> = emptyArray()
        val finished = AtomicBoolean()
    }

    private abstract inner class ViewGuard(val token: Token) {
        private var valid = true
        fun invalidate() { valid = false }
        fun checkValid() {
            check(valid && token.thread === Thread.currentThread()) { "Hook frame is no longer valid on this thread" }
        }
        fun requireCapability(capability: HookCapability) {
            checkValid()
            check(token.entry.capabilities.getValue(capability).level == SupportLevel.AVAILABLE) {
                "${backend.id} does not provide $capability"
            }
        }
    }

    private inner class BeforeView(token: Token) : ViewGuard(token), BeforeCall {
        override val target: Executable get() { checkValid(); return token.frame.target }
        override val receiver: Any? get() { checkValid(); return token.frame.receiver }
        override val argumentCount: Int get() { checkValid(); return token.frame.arguments.size }
        override fun argument(index: Int): Any? { checkValid(); return token.frame.arguments[index] }
        override fun setArgument(index: Int, value: Any?) {
            requireCapability(HookCapability.ARGUMENT_REWRITE)
            CallValidation.argument(target, index, value)
            token.frame.arguments[index] = value
        }
        override fun returnEarly(value: Any?) {
            requireCapability(HookCapability.EARLY_RETURN)
            CallValidation.returned(target, value)
            token.frame.state = CallState.Completed(CallOutcome.Returned(value))
        }
        override fun throwEarly(error: Throwable) {
            requireCapability(HookCapability.EARLY_RETURN)
            check(target !is Constructor<*>) { "Constructors are observe-only" }
            token.frame.state = CallState.Completed(CallOutcome.Thrown(error))
        }
    }

    private inner class AfterView(token: Token) : ViewGuard(token), AfterCall {
        override val target: Executable get() { checkValid(); return token.frame.target }
        override val receiver: Any? get() { checkValid(); return token.frame.receiver }
        override val outcome: CallOutcome get() {
            checkValid()
            return (token.frame.state as CallState.Completed).outcome
        }
        override fun argument(index: Int): Any? { checkValid(); return token.submittedArguments[index] }
        override fun returnValue(value: Any?) {
            requireCapability(HookCapability.OUTCOME_REWRITE)
            CallValidation.returned(target, value)
            token.frame.state = CallState.Completed(CallOutcome.Returned(value))
        }
        override fun throwError(error: Throwable) {
            requireCapability(HookCapability.OUTCOME_REWRITE)
            check(target !is Constructor<*>) { "Constructors are observe-only" }
            token.frame.state = CallState.Completed(CallOutcome.Thrown(error))
        }
    }
}
