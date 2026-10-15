package com.voiceproducer.core.agent

enum class BridgeStatus { Connecting, Connected, Disconnected }

interface AgentBridgeListener {
    fun onStatus(status: BridgeStatus)

    fun onMode(speaking: Boolean)

    fun onVad(score: Float)

    fun onUserTranscript(text: String)

    fun onAgentText(text: String)

    fun onError(message: String)

    fun onRefused()

    fun onToolCall(
        name: String,
        paramsJson: String,
        reply: (Boolean, String) -> Unit,
    )
}

interface AgentBridge {
    fun start(
        token: String,
        plan: String,
        listener: AgentBridgeListener,
    )

    fun stop(done: () -> Unit)

    fun sendContext(text: String)

    fun sendUserText(text: String)

    fun setMicMuted(muted: Boolean)
}
