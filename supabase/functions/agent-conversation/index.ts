import { defaultDeps } from "../_shared/elevenlabs.ts";
import { handler } from "./handler.ts";

Deno.serve((r) => handler(r, defaultDeps));
