package com.smallcloud.codify.modes.completion

import com.smallcloud.codify.modes.completion.structs.Completion
import com.smallcloud.codify.modes.completion.structs.CompletionHash


object CompletionCache : LinkedHashMap<CompletionHash, Completion>() {
    private fun cleanup(maxSize: Int = 1600) {
        if (size < maxSize || size == 0) {
            return
        }
        remove(minByOrNull { it.value.createdTs }?.key)
    }

    fun addCompletion(completion: Completion, maxSize: Int = 1600) {
        cleanup(maxSize)

        for (i in 0 until completion.completion.length) {
            val newCompletion = completion.copy(
                originalText = completion.originalText.substring(0, completion.startIndex) +
                        completion.completion.substring(0, i) +
                        completion.originalText.substring(completion.startIndex),
                completion = completion.completion.substring(i),
                startIndex = completion.startIndex + i,
                endIndex = completion.endIndex + i,
                firstLineEndIndex = completion.firstLineEndIndex + i,
                leftSymbolsToSkip = if (completion.leftSymbolsToRemove > 0) i else 0,
                isFromCache = true
            )
            this[CompletionHash(newCompletion.originalText, newCompletion.startIndex)] = newCompletion
        }

        val beforeLeft = completion.symbolsBeforeLeftCursorReversed()
        for (i in beforeLeft.indices) {
            if ((beforeLeft[i] != ' ' && beforeLeft[i] != '\t')
                || beforeLeft.reversed().substring(1 + i).length < completion.leftSymbolsToRemove) {
                break
            }
            val completionText = if (completion.leftSymbolsToRemove - i > 0) {
                (beforeLeft.substring(0, i).reversed() + completion.completion)
            } else {
                beforeLeft.substring(0, i).reversed() + completion.completion
            }

            val newCompletion = completion.copy(
                originalText = completion.originalText.substring(0, completion.startIndex - i) +
                        completion.originalText.substring(completion.startIndex),
                completion = completionText,
                startIndex = completion.startIndex - i,
                endIndex = completion.endIndex - i,
                firstLineEndIndex = completion.firstLineEndIndex - i,
                isFromCache = true
            )
            this[CompletionHash(newCompletion.originalText, newCompletion.startIndex)] = newCompletion
        }
    }

    fun getCompletion(text: String, offset: Int): Completion? = getOrDefault(CompletionHash(text, offset), null)
}
