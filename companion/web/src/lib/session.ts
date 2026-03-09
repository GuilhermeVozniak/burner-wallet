const SESSION_TIMEOUT_MS = 5 * 60 * 1000; // 5 minutes
const TIMESTAMP_KEY = "bw_last_active";

/** Update the last-active timestamp. Call on every user interaction. */
export function touchSession(): void {
  sessionStorage.setItem(TIMESTAMP_KEY, Date.now().toString());
}

/** Check if the session has expired. Returns true if expired. */
export function isSessionExpired(): boolean {
  const last = sessionStorage.getItem(TIMESTAMP_KEY);
  if (!last) return false; // No session started yet
  return Date.now() - parseInt(last, 10) > SESSION_TIMEOUT_MS;
}

/** Clear all wallet data from sessionStorage. */
export function clearSession(): void {
  sessionStorage.removeItem("bw_mnemonic");
  sessionStorage.removeItem("bw_network");
  sessionStorage.removeItem("bw_source");
  sessionStorage.removeItem(TIMESTAMP_KEY);
}

/** Initialize session — set timestamp when wallet is created/imported. */
export function startSession(): void {
  touchSession();
}
