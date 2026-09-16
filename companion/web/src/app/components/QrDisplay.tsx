"use client";

import { useEffect, useRef, useState } from "react";
import QRCode from "qrcode";

/** Auto-advance interval for multi-frame payloads (matches the signer). */
const FRAME_INTERVAL_MS = 2000;

interface QrDisplayProps {
  /** Text payload (single QR). Ignored when `frames` is given. */
  data?: string;
  /**
   * Binary multi-frame payload (see lib/psbt.ts encodeFrames). Each frame is
   * rendered in QR byte mode, exactly as the signer's QrDecoder expects, and
   * the display cycles through frames automatically.
   */
  frames?: Uint8Array[];
  size?: number;
  label?: string;
}

/** Renders a QR code (or a cycling set of binary QR frames) to a canvas. */
export default function QrDisplay({ data, frames, size = 256, label }: QrDisplayProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [frameIdx, setFrameIdx] = useState(0);
  const [renderError, setRenderError] = useState("");

  const frameCount = frames ? frames.length : 0;

  // Reset to the first frame whenever the payload changes.
  useEffect(() => {
    setFrameIdx(0);
  }, [frames, data]);

  // Auto-advance for multi-frame payloads.
  useEffect(() => {
    if (frameCount <= 1) return;
    const timer = setInterval(() => {
      setFrameIdx((i) => (i + 1) % frameCount);
    }, FRAME_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [frameCount]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    let payload: string | { data: Uint8Array; mode: "byte" }[];
    if (frames && frames.length > 0) {
      const frame = frames[Math.min(frameIdx, frames.length - 1)];
      payload = [{ data: frame, mode: "byte" }];
    } else if (data) {
      payload = data;
    } else {
      return;
    }

    setRenderError("");
    QRCode.toCanvas(canvas, payload, {
      width: size,
      margin: 2,
      color: {
        dark: "#000000",
        light: "#ffffff",
      },
      // ECC level L matches the signer's own QR output and keeps versions small.
      errorCorrectionLevel: "L",
    }).catch((e: unknown) => {
      setRenderError(e instanceof Error ? e.message : "Data too large for QR");
      const ctx = canvas.getContext("2d");
      if (ctx) {
        ctx.fillStyle = "#333";
        ctx.fillRect(0, 0, size, size);
        ctx.fillStyle = "#f44";
        ctx.font = "14px system-ui";
        ctx.textAlign = "center";
        ctx.fillText("Data too large for QR", size / 2, size / 2);
      }
    });
  }, [data, frames, frameIdx, size]);

  return (
    <div style={{ textAlign: "center", margin: "1rem 0" }}>
      <canvas
        ref={canvasRef}
        style={{
          borderRadius: "8px",
          background: "#fff",
          padding: "8px",
        }}
      />
      {frameCount > 1 && (
        <div style={{ marginTop: "0.5rem" }}>
          <button
            className="btn"
            type="button"
            onClick={() => setFrameIdx((i) => (i - 1 + frameCount) % frameCount)}
          >
            &lt;
          </button>
          <span style={{ color: "#aaa", margin: "0 0.75rem", fontSize: "0.85rem" }}>
            Frame {frameIdx + 1} / {frameCount}
          </span>
          <button
            className="btn"
            type="button"
            onClick={() => setFrameIdx((i) => (i + 1) % frameCount)}
          >
            &gt;
          </button>
        </div>
      )}
      {label && (
        <p style={{ color: "#777", fontSize: "0.8rem", marginTop: "0.5rem" }}>
          {label}
        </p>
      )}
      {renderError && (
        <p style={{ color: "#f44", fontSize: "0.8rem", marginTop: "0.5rem" }}>{renderError}</p>
      )}
    </div>
  );
}
