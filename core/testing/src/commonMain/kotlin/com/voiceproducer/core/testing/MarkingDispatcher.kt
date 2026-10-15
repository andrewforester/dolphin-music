package com.voiceproducer.core.testing

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext

class MarkingDispatcher(
    private val delegate: CoroutineDispatcher,
) : CoroutineDispatcher() {
    var running = false
        private set

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        delegate.dispatch(
            context,
            Runnable {
                val outer = running
                running = true
                try {
                    block.run()
                } finally {
                    running = outer
                }
            },
        )
    }
}
