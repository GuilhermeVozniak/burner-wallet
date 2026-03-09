"use client";

import { useEffect, useState, useCallback, useRef } from "react";
import { useRouter } from "next/navigation";
import { isSessionExpired, clearSession, touchSession } from "@/lib/session";

const CHECK_INTERVAL_MS = 30_000; // Check every 30 seconds
const WARNING_BEFORE_MS = 30_000; // Show warning 30s before expiry
const SESSION_TIMEOUT_MS = 5 * 60 * 1000; // Must match session.ts

export default function SessionGuard({
  children,
}: {
  children: React.ReactNode;
}) {
  const router = useRouter();
  const [warning, setWarning] = useState(false);
  const warningRef = useRef(false);

  const handleExpiry = useCallback(() => {
    clearSession();
    router.push("/");
  }, [router]);

  const handleActivity = useCallback(() => {
    touchSession();
    setWarning(false);
    warningRef.current = false;
  }, []);

  useEffect(() => {
    // Check on mount
    if (isSessionExpired()) {
      handleExpiry();
      return;
    }

    // Periodic check
    const interval = setInterval(() => {
      if (isSessionExpired()) {
        handleExpiry();
        return;
      }

      // Check if we should show the warning
      const last = sessionStorage.getItem("bw_last_active");
      if (last) {
        const elapsed = Date.now() - parseInt(last, 10);
        const remaining = SESSION_TIMEOUT_MS - elapsed;
        if (remaining <= WARNING_BEFORE_MS && remaining > 0) {
          setWarning(true);
          warningRef.current = true;
        } else {
          setWarning(false);
          warningRef.current = false;
        }
      }
    }, CHECK_INTERVAL_MS);

    // Reset timer on user interaction
    const events = ["click", "keydown"] as const;
    for (const event of events) {
      window.addEventListener(event, handleActivity);
    }

    return () => {
      clearInterval(interval);
      for (const event of events) {
        window.removeEventListener(event, handleActivity);
      }
    };
  }, [handleExpiry, handleActivity]);

  return (
    <>
      {warning && (
        <div
          role="alert"
          style={{
            position: "fixed",
            top: 0,
            left: 0,
            right: 0,
            padding: "0.75rem 1rem",
            background: "#442200",
            color: "#ffcc00",
            textAlign: "center",
            zIndex: 9999,
            fontSize: "0.9rem",
            borderBottom: "1px solid #664400",
          }}
        >
          Session expires in 30s — click to stay active
        </div>
      )}
      {children}
    </>
  );
}
