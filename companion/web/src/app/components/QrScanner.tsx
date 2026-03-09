"use client";

import { useEffect, useRef, useState } from "react";
import { Html5Qrcode } from "html5-qrcode";

interface QrScannerProps {
  onScan: (data: string) => void;
  onError?: (error: string) => void;
}

/** Webcam-based QR code scanner using html5-qrcode. */
export default function QrScanner({ onScan, onError }: QrScannerProps) {
  const [scanning, setScanning] = useState(false);
  const [started, setStarted] = useState(false);
  const scannerRef = useRef<Html5Qrcode | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    return () => {
      // Cleanup on unmount
      if (scannerRef.current) {
        scannerRef.current.stop().catch(() => {});
        scannerRef.current.clear();
      }
    };
  }, []);

  async function startScanning() {
    if (!containerRef.current) return;

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
          onScan(decodedText);
          scanner.stop().catch(() => {});
          setScanning(false);
          setStarted(false);
        },
        () => {
          // QR not found in this frame -- ignore
        }
      );
      setStarted(true);
    } catch (err) {
      setScanning(false);
      const msg = err instanceof Error ? err.message : "Camera access denied";
      if (onError) onError(msg);
    }
  }

  async function stopScanning() {
    if (scannerRef.current && started) {
      await scannerRef.current.stop().catch(() => {});
      setScanning(false);
      setStarted(false);
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
      <div style={{ textAlign: "center", marginTop: "0.75rem" }}>
        {!scanning ? (
          <button className="btn btn-primary" onClick={startScanning}>
            Start Camera Scan
          </button>
        ) : (
          <button className="btn" onClick={stopScanning}>
            Stop Scanning
          </button>
        )}
      </div>
      <p style={{ color: "#555", fontSize: "0.8rem", textAlign: "center", marginTop: "0.5rem" }}>
        Point your camera at a QR code from the air-gapped signer.
      </p>
    </div>
  );
}
