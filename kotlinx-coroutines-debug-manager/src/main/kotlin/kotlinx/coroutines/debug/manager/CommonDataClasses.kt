package kotlinx.coroutines.debug.manager

/**
 * @author Kirill Timofeev
 */

data class MethodInfo(
        val isAnonymous: Boolean = false,
        val isSuspend: Boolean = false,
        val isDoResume: Boolean = false,
        val isStateMachine: Boolean = false)

data class MethodId private constructor(val name: String, val owner: String, val desc: String) {
    override fun toString() = "$owner.$name $desc"

    companion object {
        fun build(name: String, owner: String, desc: String) = MethodId(name, owner.replace('/', '.'), desc)
    }
}

data class MethodIdWithInfo(val method: MethodId, val info: MethodInfo, private val pretty: String = "") {
    override fun toString() = if (pretty.isNotEmpty()) pretty else "$method" + info
}

data class CallPosition(val file: String, val line: Int) {
    companion object {
        val UNKNOWN = CallPosition("unknown", -1)
    }
}

data class DoResumeForSuspend(
        val doResume: MethodIdWithInfo,
        val suspend: SuspendFunction,
        val doResumeCallPosition: CallPosition? = null) {
    val doResumeForItself = doResume.method == suspend.method
}

data class MethodCall(val method: MethodId, val position: CallPosition, val fromMethod: MethodId? = null) {
    override fun toString() = "$method at ${position.file}:${position.line}"

    val stackTraceElement by lazy { StackTraceElement(method.owner, method.name, position.file, position.line) }
}

sealed class SuspendFunction(open val method: MethodId)

data class AnonymousSuspendFunction(override val method: MethodId) : SuspendFunction(method)

data class NamedSuspendFunction(override val method: MethodId) : SuspendFunction(method)