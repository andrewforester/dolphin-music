package com.voiceproducer.core.agent

import android.media.AudioManager
import android.telephony.TelephonyManager
import com.voiceproducer.core.agent.AudioModeDecision.RESTORE
import com.voiceproducer.core.agent.AudioModeDecision.SKIP_CALL_ACTIVE
import com.voiceproducer.core.agent.AudioModeDecision.SKIP_CALL_STATE_UNKNOWN
import com.voiceproducer.core.agent.AudioModeDecision.SKIP_NOT_IN_COMMUNICATION
import com.voiceproducer.core.agent.AudioModeDecision.SKIP_SESSION_ACTIVE
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioModeRestoreTest {
    private val inCommunication = AudioManager.MODE_IN_COMMUNICATION
    private val idle = TelephonyManager.CALL_STATE_IDLE
    private val otherModes =
        listOf(
            AudioManager.MODE_NORMAL,
            AudioManager.MODE_RINGTONE,
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_CALL_SCREENING,
            AudioManager.MODE_CALL_REDIRECT,
            AudioManager.MODE_COMMUNICATION_REDIRECT,
            AudioManager.MODE_INVALID,
            AudioManager.MODE_CURRENT,
        )
    private val sdks = listOf(26, 29, 30, 31, 34, 36)

    private fun decide(
        sdk: Int,
        mode: Int,
        callState: Int?,
        active: Boolean = false,
    ) = decideAudioModeRestore(sdkInt = sdk, currentMode = mode, callState = callState, sessionActive = active)

    @Test fun api31PlusRestoresOurLeftoverCommunicationModeWithoutCallState() {
        listOf(31, 34, 36).forEach { assertEquals("sdk $it", RESTORE, decide(it, inCommunication, callState = null)) }
    }

    @Test fun api31PlusIgnoresCallStateBecauseModesArePerClient() {
        assertEquals(RESTORE, decide(31, inCommunication, TelephonyManager.CALL_STATE_OFFHOOK))
    }

    @Test fun api26To30RestoresOnlyWhenNoPhoneCall() {
        listOf(26, 29, 30).forEach { assertEquals("sdk $it", RESTORE, decide(it, inCommunication, idle)) }
    }

    @Test fun api26To30SkipsDuringRingingOrOffhookCall() {
        listOf(26, 30).forEach { sdk ->
            assertEquals(SKIP_CALL_ACTIVE, decide(sdk, inCommunication, TelephonyManager.CALL_STATE_RINGING))
            assertEquals(SKIP_CALL_ACTIVE, decide(sdk, inCommunication, TelephonyManager.CALL_STATE_OFFHOOK))
        }
    }

    @Test fun api26To30SkipsWhenCallStateCannotBeRead() {
        listOf(26, 30).forEach { assertEquals("sdk $it", SKIP_CALL_STATE_UNKNOWN, decide(it, inCommunication, callState = null)) }
    }

    @Test fun neverTouchesAnyOtherMode() {
        sdks.forEach { sdk ->
            otherModes.forEach { mode ->
                assertEquals("sdk $sdk mode $mode", SKIP_NOT_IN_COMMUNICATION, decide(sdk, mode, idle))
                assertEquals("sdk $sdk mode $mode", SKIP_NOT_IN_COMMUNICATION, decide(sdk, mode, callState = null))
            }
        }
    }

    @Test fun neverTouchesTheModeWhileASessionIsActive() {
        sdks.forEach { sdk ->
            (otherModes + inCommunication).forEach { mode ->
                listOf(null, idle, TelephonyManager.CALL_STATE_OFFHOOK).forEach { callState ->
                    assertEquals("sdk $sdk mode $mode call $callState", SKIP_SESSION_ACTIVE, decide(sdk, mode, callState, active = true))
                }
            }
        }
    }
}
