"use client";

import { useEffect, useRef, useState } from "react";
import { Html5Qrcode } from "html5-qrcode";

interface QrScannerProps {
  /**
   * Called for every decoded QR code. Return `true` when the payload is
   * complete to stop the camera; return `false`/nothing to keep scanning
   * (multi-frame payloads from the signer need several codes).
   */
  onScan: (data: string) => boolean | void;
  onError?: (error: string) => void;
  /** Optional progress text shown under the viewfinder while scanning. */
  status?: string;
}

/** Webcam-based QR code scanner using html5-qrcode. */
export default function QrScanner({ onScan, onError, status }: QrScannerProps) {
  const [scanning, setScanning] = useState(false);
  const scannerRef = useRef<Html5Qrcode | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const stoppingRef = useRef(false);
  // Always call the latest callback: html5-qrcode captures the one passed to
  // start(), which would otherwise see stale React state.
  const onScanRef = useRef(onScan);
  onScanRef.current = onScan;

  async function stopScanner() {
    const scanner = scannerRef.current;
    if (!scanner || stoppingRef.current) return;
    stoppingRef.current = true;
    try {
      await scanner.stop();
    } catch {
      // Already stopped
    }
    try {
      scanner.clear();
    } catch {
      // Element may already be gone
    }
    scannerRef.current = null;
    stoppingRef.current = false;
    setScanning(false);
  }

  useEffect(() => {
    return () => {
      // Cleanup on unmount
      const scanner = scannerRef.current;
      if (scanner) {
        scanner.stop().catch(() => {}).finally(() => {
          try {
            scanner.clear();
          } catch {
            // ignore
          }
        });
        scannerRef.current = null;
      }
    };
  }, []);

  async function startScanning() {
    if (!containerRef.current || scannerRef.current) return;

    const scannerId = "qr-scanner-container";
    containerRef.current.id = scannerId;

    try {
      const scanner = new Html5Qrcode(scannerId);
      scannerRef.current = scanner;
      setScanning(true);

      await scanner.start(
        { facingMode: "environment" },
        {
          fps: 10,
          qrbox: { width: 250, height: 250 },
        },
        (decodedText) => {
          if (stoppingRef.current) return;
          const done = onScanRef.current(decodedText);
          if (done === true) {
            void stopScanner();
          }
        },
        () => {
          // QR not found in this frame -- ignore
        }
      );
    } catch (err) {
      scannerRef.current = null;
      setScanning(false);
      const msg = err instanceof Error ? err.message : "Camera access denied";
      if (onError) onError(msg);
    }
  }

  return (
    <div>
      <div
        ref={containerRef}
        style={{
          width: "100%",
          maxWidth: "400px",
          margin: "0 auto",
          minHeight: scanning ? "300px" : "0",
          borderRadius: "8px",
          overflow: "hidden",
        }}
      />
      {scanning && status && (
        <p style={{ color: "#0ff", fontSize: "0.85rem", textAlign: "center", marginTop: "0.5rem" }}>
          {status}
        </p>
      )}
      <div style={{ textAlign: "center", marginTop: "0.75rem" }}>
        {!scanning ? (
          <button className="btn btn-primary" type="button" onClick={startScanning}>
            Start Camera Scan
          </button>
        ) : (
          <button className="btn" type="button" onClick={stopScanner}>
            Stop Scanning
          </button>
        )}
      </div>
      <p style={{ color: "#555", fontSize: "0.8rem", textAlign: "center", marginTop: "0.5rem" }}>
        Point your camera at the QR code(s) from the air-gapped signer. Keep
        scanning until every frame has been captured.
      </p>
    </div>
  );
}
