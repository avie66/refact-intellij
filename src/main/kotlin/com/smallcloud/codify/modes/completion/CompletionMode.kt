package com.smallcloud.codify.modes.completion

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.*
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.smallcloud.codify.Resources.defaultContrastUrlSuffix
import com.smallcloud.codify.io.ConnectionStatus
import com.smallcloud.codify.io.InferenceGlobalContext
import com.smallcloud.codify.io.RequestJob
import com.smallcloud.codify.io.inferenceFetch
import com.smallcloud.codify.modes.EditorTextHelper
import com.smallcloud.codify.modes.Mode
import com.smallcloud.codify.struct.SMCPrediction
import com.smallcloud.codify.struct.SMCRequest
import com.smallcloud.codify.struct.SMCRequestBody
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

data class EditorState(
    val modificationStamp: Long,
    val offset: Int,
    val text: String
)

class CompletionMode : Mode(), CaretListener {
    var needToRender: Boolean = true
    private val scope: String = "completion"
    private val app = ApplicationManager.getApplication()
    private val scheduler = AppExecutorUtil.createBoundedScheduledExecutorService("CompletionScheduler", 1)
    private var processTask: Future<*>? = null
    private var completionLayout: CompletionLayout? = null
    private val logger = Logger.getInstance("CompletionMode")

    private var autocompleteSymbolBefore: String = ""
    private var hasOneLineCompletionBefore: Boolean = false

    override fun beforeDocumentChangeNonBulk(event: DocumentEvent?, editor: Editor) {
        logger.info("beforeDocumentChangeNonBulk cancelOrClose request")
        cancelOrClose(editor)
    }

    private fun willHaveMoreEvents(event: DocumentEvent, editor: Editor): Boolean {
        if (event.offset == 0 || event.offset >= editor.document.text.length) return false
        val ch = editor.document.text[event.offset]
        val beforeCh = editor.document.text[event.offset - 1]
        if (beforeCh == ':' && ch == '\n') return true
        return false
    }

    private fun autocompletedSymbolsInfo(event: DocumentEvent, editor: Editor): Pair<Boolean, Int> {
        val startAutocompleteStrings = setOf("(", "\"", "{", "[", "'", "\"")
        val endAutocompleteStrings = setOf(")", "\"", "\'", "}", "]", "'''", "\"\"\"")
        val startToStopSymbols = mapOf(
            "(" to setOf(")"), "{" to setOf("}"), "[" to setOf("]"),
            "'" to setOf("'", "'''"), "\"" to setOf("\"", "\"\"\"")
        )
        val newStr = event.newFragment.toString()
        if (startToStopSymbols[autocompleteSymbolBefore]?.contains(newStr) == true && newStr in endAutocompleteStrings) {
            logger.info("Fixing completion request for the autocomplete symbol: $newStr")
            autocompleteSymbolBefore = ""
            return true to -1
        }
        if (newStr.isNotEmpty() && "${newStr.last()}" in startAutocompleteStrings) {
            autocompleteSymbolBefore = "${newStr.last()}"
            logger.info("Skipped completion request: started autocomplete symbol: $autocompleteSymbolBefore")
            return false to 0
        }

        autocompleteSymbolBefore = ""
        return true to 0
    }

    override fun onTextChange(event: DocumentEvent?, editor: Editor, force: Boolean) {
        val document = editor.document
        val fileName = getActiveFile(document) ?: return
        val state: EditorState
        val debounceMs: Long
        if (!force) {
            if (event == null) return
            if (event.offset + event.newLength > editor.document.text.length) return
            if (event.newLength + event.oldLength <= 0) return
            if (willHaveMoreEvents(event, editor)) return
            val (valid, offset) = autocompletedSymbolsInfo(event, editor)
            if (!valid) return
            state = EditorState(
                editor.document.modificationStamp,
                event.offset + event.newLength + offset,
                editor.document.text
            )

            val completionData = CompletionCache.getCompletion(state.text, state.offset)
            if (completionData != null) {
                processTask = scheduler.submit {
                    app.invokeLater { renderCompletion(editor, state, completionData) }
                }
                return
            }

            if (shouldIgnoreChange(event, editor, state.offset)) {
                return
            }

            debounceMs = CompletionTracker.calcDebounceTime(editor)
            CompletionTracker.updateLastCompletionRequestTime(editor)
            logger.info("Debounce time: $debounceMs")
        } else {
            state = EditorState(
                editor.document.modificationStamp,
                editor.caretModel.offset,
                editor.document.text
            )
            debounceMs = 0
        }

        val request = makeRequest(fileName, state.text, state.offset) ?: return
        request.uri = request.uri.resolve(defaultContrastUrlSuffix)
        val editorHelper = EditorTextHelper(editor, state.offset)

        processTask = scheduler.schedule({
            process(request, editor, state, editorHelper, force)
        }, debounceMs, TimeUnit.MILLISECONDS)
    }

    private fun makeRequest(fileName: String, text: String, offset: Int): SMCRequest? {
        val requestBody = SMCRequestBody(
            mapOf(fileName to text),
            "Infill",
            "infill",
            fileName,
            offset, offset,
            50,
            1,
            listOf("\n\n")
        )
        val req = InferenceGlobalContext.makeRequest(requestBody)
        if (req != null) {
            req.scope = scope
        }
        return req
    }

    private fun renderCompletion(
        editor: Editor,
        state: EditorState,
        completionData: Completion,
    ) {
        val invalidStamp = state.modificationStamp != editor.document.modificationStamp
        val invalidOffset = state.offset != editor.caretModel.offset
        if (invalidStamp || invalidOffset) {
            logger.info("Completion is droppped: invalidStamp || invalidOffset")
            logger.info(
                "state_offset: ${state.offset}," +
                        " state_modificationStamp: ${state.modificationStamp}"
            )
            logger.info(
                "editor_offset: ${editor.caretModel.offset}," +
                        " editor_modificationStamp: ${editor.document.modificationStamp}"
            )
            return
        }
        if (processTask == null) {
            logger.info("Completion is droppped: there is no active processTask is left")
            return
        }
        logger.info(
            "Completion rendering: offset: ${state.offset}," +
                    " modificationStamp: ${state.modificationStamp}"
        )
        logger.info("Completion data: ${completionData.completion}")
        try {
            val completion = CompletionLayout(editor, completionData)
            if (needToRender) completion.render()
            completionLayout = completion
            editor.caretModel.addCaretListener(this)
        } catch (ex: Exception) {
            logger.warn("Exception while rendering completion", ex)
            logger.info("Exception while rendering completion cancelOrClose request")
            cancelOrClose(editor)
        }
    }

    fun hideCompletion() {
        logger.info("hideCompletion")
        if (!isInActiveState()) return
        scheduler.submit {
            try {
                processTask?.get()
            } catch (_: CancellationException) {
            } finally {
                app.invokeAndWait { completionLayout?.hide() }
            }
        }
    }

    fun showCompletion() {
        logger.info("showCompletion")
        if (isInActiveState()) return
        scheduler.submit {
            try {
                processTask?.get()
            } catch (_: CancellationException) {
            } finally {
                app.invokeAndWait { completionLayout?.show() }
            }
        }
    }

    private fun process(
        request: SMCRequest,
        editor: Editor,
        state: EditorState,
        editorHelper: EditorTextHelper,
        force: Boolean,
    ) {
        var maybeLastReqJob: RequestJob? = null
        try {
            val completionState = CompletionState(editorHelper, force = force)
            if (!force && !completionState.readyForCompletion) return
            if (!force && !completionState.multiline && hasOneLineCompletionBefore) return
            if (force) {
                request.body.maxTokens = 512
            }

            request.body.stopTokens = completionState.stopTokens

            if (InferenceGlobalContext.status == ConnectionStatus.CONNECTED) {
                InferenceGlobalContext.status = ConnectionStatus.PENDING
            }
            maybeLastReqJob = inferenceFetch(request)
            val lastReqJob = maybeLastReqJob ?: return

            logger.info("Making a completion request: multiline=${completionState.multiline}")
            val prediction = lastReqJob.future.get() as SMCPrediction

            if (prediction.status == null) {
                InferenceGlobalContext.status = ConnectionStatus.ERROR
                InferenceGlobalContext.lastErrorMsg = "Parameters are not correct"
                return
            }

            val predictedText = prediction.choices?.firstOrNull()?.files?.get(request.body.cursorFile)
            val finishReason = prediction.choices?.firstOrNull()?.finishReason
            if (predictedText == null || finishReason == null) {
                InferenceGlobalContext.status = ConnectionStatus.CONNECTED
                InferenceGlobalContext.lastErrorMsg = "Request was succeeded but there is no predicted data"
                return
            } else {
                InferenceGlobalContext.status = ConnectionStatus.CONNECTED
                InferenceGlobalContext.lastErrorMsg = null
            }

            val completionData = completionState.difference(predictedText, finishReason) ?: return
            if (!completionData.isMakeSense()) return
            synchronized(this) {
                CompletionCache.addCompletion(completionData)
            }
            app.invokeAndWait {
                renderCompletion(editor, state, completionData)
            }
        } catch (_: InterruptedException) {
            maybeLastReqJob?.let {
                maybeLastReqJob.request?.abort()
                logger.info("lastReqJob abort")
            }
        } catch (e: ExecutionException) {
            catchNetExceptions(e.cause)
        } catch (e: Exception) {
            InferenceGlobalContext.status = ConnectionStatus.CONNECTED
            InferenceGlobalContext.lastErrorMsg = e.message
            logger.warn("Exception while completion request processing", e)
        }
    }

    private fun catchNetExceptions(e: Throwable?) {
        InferenceGlobalContext.status = ConnectionStatus.ERROR
        InferenceGlobalContext.lastErrorMsg = e?.message
        logger.warn("Exception while completion request processing", e)
    }

    override fun onTabPressed(editor: Editor, caret: Caret?, dataContext: DataContext) {
        completionLayout?.apply {
            applyPreview(caret ?: editor.caretModel.currentCaret)
            dispose()
            hasOneLineCompletionBefore = completionData.isSingleLineComplete
        }
        completionLayout = null
        editor.caretModel.removeCaretListener(this)
    }

    override fun onEscPressed(editor: Editor, caret: Caret?, dataContext: DataContext) {
        logger.info("onEscPressed cancelOrClose request")
        cancelOrClose(editor)
    }

    override fun caretPositionChanged(event: CaretEvent) {
        logger.info("caretPositionChanged cancelOrClose request")
        cancelOrClose(event.editor)
    }

    override fun isInActiveState(): Boolean = completionLayout != null && completionLayout!!.rendered && needToRender

    private fun shouldIgnoreChange(event: DocumentEvent?, editor: Editor, offset: Int): Boolean {
        if (event == null) return false
        val document = event.document

        if (editor.editorKind != EditorKind.MAIN_EDITOR && !app.isUnitTestMode) {
            return true
        }
        if (!EditorModificationUtil.checkModificationAllowed(editor)
            || document.getRangeGuard(offset, offset) != null
        ) {
            document.fireReadOnlyModificationAttempt()
            return true
        }
        return false
    }

    private fun getActiveFile(document: Document): String? {
        if (!app.isDispatchThread) return null
        val file = FileDocumentManager.getInstance().getFile(document)
        return file?.presentableName
    }

    fun cancelOrClose(editor: Editor) {
        try {
            processTask?.cancel(true)
            processTask?.get()
        } catch (_: CancellationException) {
        } finally {
            if (InferenceGlobalContext.status != ConnectionStatus.DISCONNECTED) {
                InferenceGlobalContext.status = ConnectionStatus.CONNECTED
            }
            processTask = null
            completionLayout?.dispose()
            completionLayout = null
            hasOneLineCompletionBefore = false
            editor.caretModel.removeCaretListener(this)
        }
    }
}
