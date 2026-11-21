import { commands } from "@/bindings";
import { createInputInitializer } from "./inputInitialization";

/** Process-wide coalesced input initializer shared by App and onboarding TryIt. */
export const initializeInputSystem = createInputInitializer(commands);
