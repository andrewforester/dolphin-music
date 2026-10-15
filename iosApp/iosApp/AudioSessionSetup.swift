import AVFoundation
import LiveKit

enum AudioSessionSetup {
    static func configure() {
        let manager = AudioManager.shared
        manager.audioSession.isAutomaticConfigurationEnabled = false
        if #available(iOS 17, *) {
            manager.duckingLevel = .min
        }
        manager.isAdvancedDuckingEnabled = false

        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(
                .playAndRecord,
                mode: .default,
                options: [.defaultToSpeaker, .mixWithOthers, .allowBluetoothA2DP]
            )
            try session.setActive(true)
        } catch {
            NSLog("VP-Audio session setup failed: %@", error.localizedDescription)
        }
    }
}
