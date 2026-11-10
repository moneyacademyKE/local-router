/** Detect a Codex billing refusal before treating provider text as an answer. */
const PREFIX = "[Codex error: credit_balance_exhausted";
const BILLING = /(?:You have no credits remaining|credit balance exhausted)/i;
const MAX_PROBE_CHARS = 512;

export function isCodexCreditRefusal(text) {
  if (typeof text !== "string") return false;
  const trimmed = text.trim();
  return trimmed.startsWith(PREFIX) && BILLING.test(trimmed) && /^\[Codex error:[\s\S]*\]\s*$/.test(trimmed);
}

/** Buffer only a possible error prefix; ordinary text is released immediately. */
export function createQuotaFrameGate() {
  let text = "";
  let held = [];
  let decided = false;
  return {
    accept(frame) {
      if (decided) return { frames: [frame] };
      let event;
      try {
        const data = frame.split(/\r?\n/).filter(line => line.startsWith("data:"))
          .map(line => line.slice(5).trimStart()).join("\n");
        if (!data) return { frames: [frame] };
        if (data === "[DONE]") {
          const frames = [...held, frame];
          held = [];
          decided = true;
          return { frames };
        }
        event = JSON.parse(data);
      } catch { return { frames: [frame] }; }
      const parts = (event.choices || []).map(choice => choice?.delta?.content)
        .filter(content => typeof content === "string" && content.length > 0);
      if (!parts.length) {
        if (held.length && /"(?:tool_calls|function_call)"/.test(frame)) {
          const frames = [...held, frame];
          held = []; decided = true;
          return { frames };
        }
        if (held.length && !frame.trimStart().startsWith(":")) { held.push(frame); return { frames: [] }; }
        return { frames: [frame] };
      }
      text += parts.join("");
      held.push(frame);
      const trimmed = text.trimStart();
      if (isCodexCreditRefusal(trimmed)) {
        held = [];
        return { frames: [], quota: true };
      }
      const plausible = PREFIX.startsWith(trimmed) || trimmed.startsWith(PREFIX);
      if (plausible && text.length < MAX_PROBE_CHARS) return { frames: [] };
      decided = true;
      const frames = held;
      held = [];
      return { frames };
    },
    finish() {
      const frames = held;
      held = [];
      decided = true;
      return frames;
    }
  };
}
