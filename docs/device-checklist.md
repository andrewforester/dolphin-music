# Device checklist

Manual voice run on a real phone. Each generation spends ElevenLabs Music credits, so run the whole list once and note the results.

## Setup

1. `local.properties` contains `ACCESS_CODE` (or enter the code in the sheet on first start).
2. `./gradlew :androidApp:installDebug` (with `ANDROID_SERIAL` set if more than one device is attached).
3. Optional, for evidence: `adb logcat -c`, then `adb logcat --pid=$(adb shell pidof com.voiceproducer.android) > run.log` in a second terminal.
4. Use a quiet room. Run the "echo" step on the phone speaker (no headphones).

## Steps

| # | Step | How | Pass when |
|---|------|-----|-----------|
| 1 | Create a track by voice | Tap the composer orb (grant the mic permission on first use). Say e.g. "Make a chill lo-fi beat with a catchy chorus, about a minute." | The agent confirms, the status shows Generating, then the track loops and the section labels and timeline appear. Note the **time from your request to the first sound**. |
| 2 | Edit: "chorus heavier" | While it plays, say "Make the chorus heavier." | Only the chorus label pulses (regenerating). Playback never stops. The **next** chorus pass sounds new (heavier), with no click or gap at its edges, and the other sections are unchanged. |
| 3 | Tap a label | Tap a section label (e.g. Verse). | Playback jumps to the start of that section and the playhead follows. |
| 4 | Undo | Tap Undo (or say "undo that"). | The previous chorus comes back on its next pass, with no click. Undo is disabled when there is nothing left to undo. |
| 5 | Ducking while talking | While music plays, talk to the agent, then let it answer. | The music volume drops smoothly while you or the agent speak, then comes back about a second after the speech stops, with no pumping or clicks. |
| 6 | Echo with music playing | With the agent connected and music playing on the speaker, stay **silent for 30 s**. Repeat with a track that has vocals if possible. | The agent does not "hear" the music: no phantom "You: ..." transcripts and no spurious replies or edits. |
| 7 | Restart restore | Swipe the app away (or `adb shell am force-stop com.voiceproducer.android`), then reopen it. | The last track version is restored (title, sections, and the audio plays on Play) and Undo history matches. |

## Results

Device: ______ &nbsp;·&nbsp; Build: ______ &nbsp;·&nbsp; Date: ______ &nbsp;·&nbsp; Tester: ______

| # | Step | Result (PASS / FAIL / PARTIAL) | Measurement / notes |
|---|------|--------------------------------|---------------------|
| 1 | Create a track by voice | | Time to first sound: ___ s |
| 2 | Edit "chorus heavier" | | Time until the new chorus was heard: ___ s; click at the edges? |
| 3 | Tap a label | | |
| 4 | Undo | | |
| 5 | Ducking while talking | | |
| 6 | Echo, 30 s of silence (no vocals / with vocals) | | Phantom transcripts: ___ |
| 7 | Restart restore | | |
