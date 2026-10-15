// Canonical client tool configs (single source of truth; see agent/README.md).

export const TOOLS = [
  {
    "type": "client",
    "name": "compose_track",
    "description": "Generate a brand new track. Call it once, right after the producer confirms the brief. Replaces the current track.",
    "parameters": {
      "type": "object",
      "required": [
        "prompt",
        "instrumental"
      ],
      "properties": {
        "prompt": {
          "type": "string",
          "description": "Short English description of the sound: genre, mood, energy arc, tempo/BPM, key instruments, vocal timbre and delivery, what the lyrics are about, what to avoid. No artist, band, composer, album or song names, and no words like 'X-like' or 'in the style of X' - the service rejects them."
        },
        "instrumental": {
          "type": "boolean",
          "description": "True for no vocals."
        },
        "length_s": {
          "type": "number",
          "description": "Track length in seconds, 45 to 120. Only set it if the producer asked for a specific length; otherwise omit it (default 75). Never ask the producer for a length."
        }
      }
    },
    "pre_tool_speech": "off",
    "expects_response": true,
    "response_timeout_secs": 10
  },
  {
    "type": "client",
    "name": "edit_section",
    "description": "Regenerate one existing section of the current plan with changed styles. Section name must come from the current plan. Make the change clearly audible: use strong, concrete tags for what should change, and remove any existing tags that contradict it.",
    "parameters": {
      "type": "object",
      "required": [
        "name",
        "add_styles",
        "remove_styles",
        "context_adherence"
      ],
      "properties": {
        "name": {
          "type": "string",
          "description": "Exact section name from the current plan."
        },
        "add_styles": {
          "type": "array",
          "items": {
            "type": "string",
            "description": "Style tag"
          },
          "description": "Short, concrete English style tags for the change, strongest first, e.g. \"heavy distorted guitars\", \"driving drums\". No artist or song names."
        },
        "remove_styles": {
          "type": "array",
          "items": {
            "type": "string",
            "description": "Style tag"
          },
          "description": "Existing tags of this section (exact text from the plan) that contradict the change. Empty list if none."
        },
        "context_adherence": {
          "type": "string",
          "enum": [
            "high",
            "medium"
          ],
          "description": "\"high\": keep continuity with the neighbouring sections (same voice, mix, groove) - for changes within the sound: louder/softer, denser, an added layer, dynamics. \"medium\": more freedom, the seam may be more noticeable - for changes to the section's character: a replaced instrument, a different vocal delivery or groove, a sharp contrast."
        }
      }
    },
    "pre_tool_speech": "off",
    "expects_response": true,
    "response_timeout_secs": 10
  },
  {
    "type": "client",
    "name": "set_global",
    "description": "Changes the styles of the whole track and re-records all of it.",
    "parameters": {
      "type": "object",
      "required": [
        "add",
        "remove"
      ],
      "properties": {
        "add": {
          "type": "array",
          "items": {
            "type": "string",
            "description": "Style tag"
          },
          "description": "Short, concrete English style tags to add to the whole track, strongest first. No artist or song names. Empty list if none."
        },
        "remove": {
          "type": "array",
          "items": {
            "type": "string",
            "description": "Style tag"
          },
          "description": "Short English style tags to remove from the whole track. Empty list if none."
        }
      }
    },
    "pre_tool_speech": "off",
    "expects_response": true,
    "response_timeout_secs": 10
  },
  {
    "type": "client",
    "name": "undo_edit",
    "description": "Undo the last edit to the plan and restore the previous version.",
    "expects_response": true,
    "response_timeout_secs": 10
  }
];
