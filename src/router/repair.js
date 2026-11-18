// A parameter error is a verdict about the BODY, not about the model.
//
// This gateway serves model-scoped argument errors through HTTP 400. Live
// 2026-11-17/18: `grok/grok-4.7` answered `invalid-argument`, "Invalid
// reasoning effort." 116 times and each refused decision burned all 5 attempts
// of a single-member chain. Retrying the same body five times is not a
// failover, it is a loop with a budget — and it ends 400 either way.
//
// So: when a 400 NAMES a field we chose to send, drop that field and give the
// candidate exactly one more try before branding it refused. The signal is
// upstream's own status and message, which is data; a per-model table of
// "quirks" would be a belief, and it rots the moment a seller rotates.
//
// Deliberately narrow: one repair per candidate per request, matching one
// field. A gateway that rejects the repaired body too is a real failure.

const FIELD_RULES = [
  // Both wire spellings: flat `reasoning_effort` (<OA> chat) and the nested
  // `reasoning` object used by newer clients.
  { fields: ["reasoning_effort", "reasoning"], pattern: /reasoning[ _-]?effort|invalid reasoning/i }
];

/**
 * Return a repaired request body when an upstream 400 names a parameter we
 * sent, otherwise null.
 *
 * @param {object} requestBody body as the caller sent it (never mutated)
 * @param {number} status upstream HTTP status
 * @param {string} reason upstream error text
 * @returns {object|null} body without the offending field, or null
 */
export function repairBodyFor(requestBody, status, reason) {
  if (status !== 400) return null;
  if (!requestBody || typeof requestBody !== "object") return null;
  const text = String(reason || "");
  if (!text) return null;
  for (const rule of FIELD_RULES) {
    if (!rule.pattern.test(text)) continue;
    const present = rule.fields.filter((f) => f in requestBody);
    if (present.length === 0) continue;
    const repaired = { ...requestBody };
    for (const field of present) delete repaired[field];
    return repaired;
  }
  return null;
}
