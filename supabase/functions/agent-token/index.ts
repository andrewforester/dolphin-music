import { defaultDeps } from "../_shared/elevenlabs.ts";
import { createAgentSync } from "./agentSync.ts";
import { handler } from "./handler.ts";

const agentSync = createAgentSync(defaultDeps);
Deno.serve((r) => handler(r, { ...defaultDeps, agentSync }));
