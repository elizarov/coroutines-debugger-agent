package kotlinx.coroutines.debug.manager

import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.experimental.Continuation
import kotlin.coroutines.experimental.CoroutineContext
import kotlin.coroutines.experimental.intrinsics.COROUTINE_SUSPENDED

/**
 * @author Kirill Timofeev
 */

val doResumeToSuspendFunctions = mutableListOf<DoResumeForSuspend>()

val suspendCalls = mutableListOf<FunctionCall>()

val stacks = ConcurrentHashMap<CoroutineContext, CoroutineStack>()

private fun emptyStack(context: CoroutineContext)
        = CoroutineStackImpl(context, { Logger.default.data { prettyPrint() } })

//functions called from instrumented(user) code
object Manager {
    @JvmStatic
    fun afterSuspendCall(result: Any, continuation: Continuation<*>, functionCallIndex: Int) {
        val suspended = result === COROUTINE_SUSPENDED
        val call = suspendCalls[functionCallIndex]
        Logger.default.debug {
            "suspend call of ${call.function} at ${call.position.file}:${call.position.line} from ${call.fromFunction}, with " +
                    "${continuation.hashCode()} : ${if (suspended) "suspended" else "result = $result"}"
        }
        try {
            if (suspended) {
                val context = continuation.context
                val stack = stacks.getOrPut(context, { emptyStack(context) })
                stack.handleSuspendFunctionReturn(continuation, call)
            }
        } catch (e: Exception) {
            Logger.default.error {
                "afterSuspendCall($result, ${continuation.toStringSafe()}, ${call.function} " +
                        "at ${call.position.file}:${call.position.line}) exception: ${e.stackTraceToString()}"
            }
        }
    }

    @JvmStatic
    fun handleDoResume(continuation: Continuation<*>, doResumeIndex: Int) { //FIXME
        val func = doResumeToSuspendFunctions[doResumeIndex]
        Logger.default.debug { "called doResume ($func) : cont: ${continuation.hashCode()}, context: ${continuation.context}" }
        val context = continuation.context
        val stack = stacks.getOrPut(context, { emptyStack(context) })
        stack.handleDoResume(continuation, func)
    }
}