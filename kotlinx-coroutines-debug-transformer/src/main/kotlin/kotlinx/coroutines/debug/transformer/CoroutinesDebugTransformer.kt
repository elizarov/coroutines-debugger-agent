package kotlinx.coroutines.debug.transformer

import kotlinx.coroutines.debug.manager.*
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LocalVariableNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain

/**
 * @author Kirill Timofeev
 */

private sealed class WithContextVarIndex(open val index: Int)

private data class CoroutineImplVar(override val index: Int) : WithContextVarIndex(index)
private data class ContinuationVar(override val index: Int) : WithContextVarIndex(index)

private fun isCoroutineImplOrSubType(variable: LocalVariableNode): Boolean { //fiXME should we really check the type?
    if (Type.getType(variable.desc) == COROUTINE_IMPL_TYPE) {
        return true
    }
    val extends = typesInfo[Type.getType(variable.desc).internalName]?.extends ?: return false
    return extends == COROUTINE_IMPL_TYPE.internalName
}

private fun argumentVarIndex(argumentTypes: Array<Type>, argumentIndex: Int)
        = argumentTypes.take(argumentIndex).map { it.size }.sum()

private fun getContinuationVarIndex(method: MethodNode): WithContextVarIndex {
    //FIXME use getfield instruction instead
    val thisVar = method.localVariables?.map { it as LocalVariableNode }?.firstOrNull { it.name == "this" }
    if (method.name.contains("doResume")) {
        println("this: ${thisVar?.name}, ${thisVar?.desc}")
    }
    if (thisVar != null && isCoroutineImplOrSubType(thisVar)) {
        return CoroutineImplVar(thisVar.index)
    }
    val continuationIndex = continuationArgumentIndex(method)
            ?: throw IllegalArgumentException("Can't find Continuation in ${method.desc}")
    return ContinuationVar(argumentVarIndex(Type.getArgumentTypes(method.desc), continuationIndex))
}

private data class ExtendsImplements(val extends: String?, val implements: List<String>)

private val typesInfo = mutableMapOf<String, ExtendsImplements>() //FIXME

private fun addSuspendCallHandlers(continuationVarIndex: Int, method: MethodNode, classNode: ClassNode) {
    val lines = methodCallLineNumber(method.instructions)
    val iter = method.instructions.iterator()
    while (iter.hasNext()) {
        val i = iter.next()
        if (i is MethodInsnNode && i.isSuspend()) {
            println("instrument call ${i.owner}.${i.name} from ${classNode.name}.${method.name} at ${classNode.sourceFile}:${lines[i]}")
            method.instructions.insert(i, generateAfterSuspendCall(i, continuationVarIndex, method.name, classNode.sourceFile, lines[i] ?: -1))
        }
    }
}

private fun transformMethod(method: MethodNode, classNode: ClassNode): DoResumeForSuspend? {
    val isStateMachine = isStateMachineForAnonymousSuspendFunction(method)
    val isDoResume = method.isDoResume()
    val isSuspend = method.isSuspend()
    if (isSuspend || isStateMachine || isDoResume) {
        val continuation = getContinuationVarIndex(method)
        println(">>in method ${classNode.name}.${method.name} with description: ${method.desc}\ncont: $continuation")
        if (isSuspend || isStateMachine) {
            addSuspendCallHandlers(continuation.index, method, classNode)
        }
        if (isDoResume) {
            val function = if (isStateMachine)
                AnonymousSuspendFunction(MethodId(method.name, classNode.name, method.desc))
            else
                correspondingSuspendFunctionForDoResume(method)
            method.instructions.insert(generateHandleDoResumeCall(continuation.index, function.method))
            return DoResumeForSuspend(buildMethodId(method, classNode)
                    .copy(info = MethodInfo(isStateMachine, isSuspend, isDoResume, isStateMachine)), function)
        }
    }
    return null
}


class CoroutinesDebugTransformer : ClassFileTransformer {
    override fun transform(loader: ClassLoader?, className: String?, classBeingRedefined: Class<*>?,
                           protectionDomain: ProtectionDomain?, classfileBuffer: ByteArray?): ByteArray {
        val reader = ClassReader(classfileBuffer)
        val classNode = ClassNode()
        reader.accept(classNode, 0)

        typesInfo[classNode.name] = ExtendsImplements(classNode.superName, classNode.interfaces.map { it as String })
        for (method in classNode.methods.map { it as MethodNode }) {
            try {
                val doResumeForSuspend = transformMethod(method, classNode)
                val oldSz = doResumeToSuspendFunction.size
                if (doResumeForSuspend != null) {
                    updateDoResumeToSuspendFunctionMap(doResumeForSuspend)
                } else {
                    val m = buildMethodId(method, classNode)
                    val info = m.info
                    if (info != null && (info.isSuspend || info.isDoResume || info.isStateMachine)) {
                        updateDoResumeToSuspendFunctionMap(m)
                    }
                }
                if (doResumeToSuspendFunction.size != oldSz) {
                    println("doResumeToSuspendFunction:")
                    for ((k, v) in doResumeToSuspendFunction) {
                        println("$k : $v")
                    }
                }
            } catch (e: Exception) {
                val trace = StringWriter()
                e.printStackTrace(PrintWriter(trace));
                println("while instrumenting $className.${method.name} with desc: ${method.desc} exception : $trace")
            }
        }
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        classNode.accept(writer)
        return writer.toByteArray()
    }
}