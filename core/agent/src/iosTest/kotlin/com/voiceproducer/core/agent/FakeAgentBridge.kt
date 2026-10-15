package com.voiceproducer.core.agent

class FakeAgentBridge : AgentBridge {
    data class Start(
        val token: String,
        val plan: String,
    )

    val starts = mutableListOf<Start>()
    val listeners = mutableListOf<AgentBridgeListener>()
    var stops = 0

    var answerStop = true
    val contexts = mutableListOf<String>()
    val userTexts = mutableListOf<String>()
    val mutes = mutableListOf<Boolean>()

    val listener: AgentBridgeListener get() = listeners.last()

    override fun start(
        token: String,
        plan: String,
        listener: AgentBridgeListener,
    ) {
        starts += Start(token, plan)
        listeners += listener
    }

    override fun stop(done: () -> Unit) {
        stops++
        if (answerStop) done()
    }

    override fun sendContext(text: String) {
        contexts += text
    }

    override fun sendUserText(text: String) {
        userTexts += text
    }

    override fun setMicMuted(muted: Boolean) {
        mutes += muted
    }
}
