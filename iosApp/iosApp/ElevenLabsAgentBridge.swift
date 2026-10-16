import Combine
import ElevenLabs
import Foundation
import ComposeApp

final class ElevenLabsAgentBridge: NSObject, AgentBridge {
    private let lock = NSLock()
    private var latestId = 0
    private var tail: Task<Void, Never>?
    private var pendingStart: Task<Void, Never>?

    @MainActor private var session: Session?

    func start(token: String, plan: String, listener: AgentBridgeListener) {
        enqueue(isStart: true) { [self] id in
            await endCurrent()
            guard isLatest(id), !Task.isCancelled else { return }
            await connect(id: id, token: token, plan: plan, listener: listener)
        }
    }

    func stop(done: @escaping () -> Void) {
        enqueue(isStart: false) { [self] _ in
            await endCurrent()
            done()
        }
    }

    func sendContext(text: String) {
        withConversation { try await $0.updateContext(text) }
    }

    func sendUserText(text: String) {
        withConversation { try await $0.sendMessage(text) }
    }

    func setMicMuted(muted: Bool) {
        withConversation { try await $0.setMuted(muted) }
    }


    private func enqueue(isStart: Bool, _ operation: @escaping @MainActor (Int) async -> Void) {
        lock.lock()
        defer { lock.unlock() }
        latestId += 1
        let id = latestId
        pendingStart?.cancel()
        let previous = tail
        let task = Task { @MainActor in
            await previous?.value
            await operation(id)
        }
        tail = task
        pendingStart = isStart ? task : nil
    }

    private func isLatest(_ id: Int) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return latestId == id
    }

    @MainActor
    private func endCurrent() async {
        guard let current = session else { return }
        session = nil
        current.phase = .over
        current.cancellables.removeAll()
        if let conversation = current.conversation {
            await conversation.endConversation()
        }
        current.conversation = nil
    }

    @MainActor
    private func connect(id: Int, token: String, plan: String, listener: AgentBridgeListener) async {
        let current = Session(id: id, listener: listener)
        session = current
        listener.onStatus(status: .connecting)

        let config = ConversationConfig(
            dynamicVariables: ["plan": plan],
            onDisconnect: { [weak self, weak current] reason in
                Self.onMain {
                    guard let current else { return }
                    if reason == .agent { self?.reportRefusal(current) }
                    self?.reportDisconnected(current)
                }
            },
            onError: { [weak self, weak current] error in
                Self.onMain { if let current { self?.reportError(current, Self.describe(error)) } }
            },
            onAgentResponse: { [weak self, weak current] text, _ in
                Self.onMain {
                    current?.agentSpoke = true
                    self?.deliver(to: current) { $0.onAgentText(text: text) }
                }
            },
            onAgentResponseCorrection: { [weak self, weak current] _, corrected, _ in
                Self.onMain { self?.deliver(to: current) { $0.onAgentTextCorrected(text: corrected) } }
            },
            onUserTranscript: { [weak self, weak current] text, _ in
                Self.onMain { self?.deliver(to: current) { $0.onUserTranscript(text: text) } }
            },
            onVadScore: { [weak self, weak current] score in
                Self.onMain { self?.deliver(to: current) { $0.onVad(score: Float(score)) } }
            },
            onUnhandledClientToolCall: { [weak self, weak current] call in
                Self.onMain { if let current { self?.handleToolCall(call, in: current) } }
            }
        )
        let auth: ElevenLabsConfiguration = .conversationToken(token)

        let conversation: Conversation
        do {
            conversation = try await ElevenLabs.startConversation(auth: auth, config: config)
        } catch {
            if current.phase == .connecting, isCurrent(current), !(error is CancellationError) {
                reportError(current, Self.describe(error))
            }
            if session === current { session = nil }
            current.phase = .over
            return
        }

        current.conversation = conversation
        guard current.phase == .connecting, isCurrent(current), !Task.isCancelled else {
            current.phase = .over
            if session === current { session = nil }
            await conversation.endConversation()
            current.release()
            return
        }
        current.phase = .connected
        listener.onStatus(status: .connected)

        conversation.$state
            .sink { [weak self, weak current] state in
                guard let current else { return }
                switch state {
                case .ended, .error, .idle: self?.reportDisconnected(current)
                case .connecting, .active: break
                }
            }
            .store(in: &current.cancellables)
        conversation.$agentState
            .map { $0 == .speaking }
            .removeDuplicates()
            .sink { [weak self, weak current] speaking in
                if speaking { current?.agentSpoke = true }
                self?.deliver(to: current) { $0.onMode(speaking: speaking) }
            }
            .store(in: &current.cancellables)
    }


    @MainActor
    private func isCurrent(_ current: Session) -> Bool {
        current.phase != .over && isLatest(current.id)
    }

    @MainActor
    private func deliver(to current: Session?, _ event: (AgentBridgeListener) -> Void) {
        guard let current, isCurrent(current) else { return }
        event(current.listener)
    }

    @MainActor
    private func reportDisconnected(_ current: Session) {
        guard current.phase == .connected, isCurrent(current) else { return }
        current.phase = .over
        current.cancellables.removeAll()
        current.listener.onStatus(status: .disconnected)
        Task { @MainActor in current.release() }
    }

    @MainActor
    private func reportRefusal(_ current: Session) {
        guard isCurrent(current), !current.agentSpoke, !current.refused, current.phase != .failed else { return }
        current.refused = true
        current.listener.onRefused()
        if current.phase == .connecting { current.phase = .failed }
    }

    @MainActor
    private func reportError(_ current: Session, _ message: String) {
        guard isCurrent(current) else { return }
        if current.phase == .failed {
            NSLog("VP-Agent error after a failed or refused start (not forwarded): %@", message)
            return
        }
        current.listener.onError(message: message)
        if current.phase == .connecting {
            current.phase = .failed
            current.listener.onStatus(status: .disconnected)
        }
    }

    @MainActor
    private func handleToolCall(_ call: ClientToolCallEvent, in current: Session) {
        let json = String(data: call.parametersData, encoding: .utf8) ?? "{}"
        let deliver = isCurrent(current)
        let reply: @Sendable (KotlinBoolean, String) -> Void = { ok, message in
            Task { @MainActor in
                guard let conversation = current.conversation else { return }
                if call.expectsResponse {
                    try? await conversation.sendToolResult(
                        for: call.toolCallId, result: message, isError: !ok.boolValue
                    )
                } else {
                    conversation.markToolCallCompleted(call.toolCallId)
                }
            }
        }
        if deliver {
            current.listener.onToolCall(name: call.toolName, paramsJson: json, reply: reply)
        } else {
            reply(KotlinBoolean(bool: false), "session ended")
        }
    }


    private static func onMain(_ body: @escaping @MainActor () -> Void) {
        if Thread.isMainThread {
            MainActor.assumeIsolated(body)
        } else {
            Task { @MainActor in body() }
        }
    }

    private func withConversation(_ call: @escaping @MainActor (Conversation) async throws -> Void) {
        Task { @MainActor [self] in
            guard let conversation = session?.conversation, session?.phase == .connected else { return }
            try? await call(conversation)
        }
    }

    private static func describe(_ error: Error) -> String {
        (error as? LocalizedError)?.errorDescription ?? String(describing: error)
    }
}

@MainActor
private final class Session {
    enum Phase { case connecting, connected, failed, over }

    let id: Int
    let listener: AgentBridgeListener
    var conversation: Conversation?
    var phase = Phase.connecting
    var agentSpoke = false
    var refused = false
    var cancellables = Set<AnyCancellable>()

    init(id: Int, listener: AgentBridgeListener) {
        self.id = id
        self.listener = listener
    }

    func release() {
        cancellables.removeAll()
        conversation = nil
    }
}
