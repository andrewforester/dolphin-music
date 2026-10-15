// Canonical Composer agent config (single source of truth; see agent/README.md).
// Only the fields listed as owned in supabase/README.md are synced to the live agent.
import { PROMPT } from "./prompt.ts";

export const AGENT = {
  "name": "Composer",
  "conversation_config": {
    "agent": {
      "first_message": "Hello, I'm your composer. Let's put a new track together — tell me what you have in mind.",
      "language": "en",
      "dynamic_variables": {
        "dynamic_variable_placeholders": {
          "plan": "No track yet."
        }
      },
      "prompt": {
        "prompt": PROMPT,
        "llm": "claude-sonnet-5-5",
        "tool_ids": [],
        "built_in_tools": {
          "skip_turn": {
            "type": "system",
            "name": "skip_turn",
            "description": "Stay silent instead of replying. Use when the producer asks you to be quiet, says they are listening, thinking, or not addressing you, or when there is nothing useful to add (e.g. after a [system] 'Track ready' notice while the producer listens).",
            "response_timeout_secs": 20,
            "params": {
              "system_tool_type": "skip_turn"
            }
          }
        }
      }
    },
    "turn": {
      "turn_timeout": -1,
      "silence_end_call_timeout": 30,
      "turn_eagerness": "patient",
      "speculative_turn": true
    },
    "conversation": {
      "client_events": [
        "audio",
        "interruption",
        "user_transcript",
        "tentative_user_transcript",
        "agent_response",
        "agent_chat_response_part",
        "agent_response_correction",
        "client_tool_call",
        "vad_score",
        "agent_tool_response"
      ]
    },
    "tts": {
      "model_id": "eleven_v4_turbo",
      "voice_id": "CwhRBWXzGAHq8TQ4Fs17",
      "stability": 0.65,
      "speed": 0.95,
      "expressive_mode": false
    }
  },
  "platform_settings": {
    "auth": {
      "enable_auth": true
    },
    "overrides": {
      "conversation_config_override": {
        "conversation": {
          "text_only": true
        },
        "agent": {
          "first_message": true
        }
      }
    }
  }
};
