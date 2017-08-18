package kotlinx.coroutines.debug.transformer

import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.commons.InstructionAdapter
import org.jetbrains.org.objectweb.asm.tree.AbstractInsnNode
import org.jetbrains.org.objectweb.asm.tree.InsnList
import org.jetbrains.org.objectweb.asm.tree.MethodNode

/**
 * @author Kirill Timofeev
 */

private val COMPLETION_WRAPPER_CLASS_NAME = "kotlinx/coroutines/debug/manager/WrappedCompletion"
private val WRAP_COMPLETION = "maybeWrapCompletionAndCreateNewCoroutine"
private val MANAGER_CLASS_NAME = "kotlinx/coroutines/debug/manager/InstrumentedCodeEventsHandler"
private val AFTER_SUSPEND_CALL = "handleAfterSuspendCall"
private val DO_RESUME_ENTER = "handleDoResumeEnter"

private inline fun code(block: InstructionAdapter.() -> Unit): InsnList =
        MethodNode().apply { block(InstructionAdapter(this)) }.instructions

fun generateNewWrappedCompletion(completionIndex: Int) =
        code {
            load(completionIndex, CONTINUATION_TYPE)
            visitMethodInsn(Opcodes.INVOKESTATIC, COMPLETION_WRAPPER_CLASS_NAME, WRAP_COMPLETION,
                    "(${CONTINUATION_TYPE.descriptor})${CONTINUATION_TYPE.descriptor}", false)
            store(completionIndex, CONTINUATION_TYPE)
        }

/**
 * Generate call of [kotlinx.coroutines.debug.manager.InstrumentedCodeEventsHandler.handleAfterSuspendCall] with continuation
 * and index of function call from [kotlinx.coroutines.debug.manager.allSuspendCalls] list
 */
fun generateAfterSuspendCall(continuationVarIndex: Int, functionCallIndex: Int) =
        code {
            dup()
            load(continuationVarIndex, CONTINUATION_TYPE)
            aconst(functionCallIndex)
            visitMethodInsn(Opcodes.INVOKESTATIC, MANAGER_CLASS_NAME, AFTER_SUSPEND_CALL,
                    "(${OBJECT_TYPE.descriptor}${CONTINUATION_TYPE.descriptor}I)V", false)
        }

/**
 * Generate call of [kotlinx.coroutines.debug.manager.InstrumentedCodeEventsHandler.handleDoResumeEnter] with continuation
 * and index of doResume function in [kotlinx.coroutines.debug.manager.doResumeToSuspendFunctions] list
 */
fun generateHandleDoResumeCallEnter(continuationVarIndex: Int, doResumeIndex: Int) =
        code {
            load(0, COROUTINE_IMPL_TYPE)
            getfield(COROUTINE_IMPL_TYPE.descriptor, "completion", CONTINUATION_TYPE.descriptor)
            load(continuationVarIndex, CONTINUATION_TYPE)
            aconst(doResumeIndex)
            visitMethodInsn(Opcodes.INVOKESTATIC, MANAGER_CLASS_NAME, DO_RESUME_ENTER,
                    "(${CONTINUATION_TYPE.descriptor}${CONTINUATION_TYPE.descriptor}I)V", false)
        }

fun insertPrintln(text: String, instructions: InsnList, insertAfter: AbstractInsnNode? = null) {
    code {
        getstatic("java/lang/System", "out", "Ljava/io/PrintStream;")
        aconst(text)
        visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(${STRING_TYPE.descriptor})V", false)
    }.apply {
        if (insertAfter != null)
            instructions.insert(insertAfter, this)
        else
            instructions.insert(this)
    }
}