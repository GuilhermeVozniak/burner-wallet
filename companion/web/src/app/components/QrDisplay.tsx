"use client";

import { useEffect, useRef } from "react";
import QRCode from "qrcode";

interface QrDisplayProps {
  data: string;
  size?: number;
  label?: string;
}

/** Renders a QR code to a canvas element. */
export default function QrDisplay({ data, size = 256, label }: QrDisplayProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    if (!canvasRef.current || !data) return;

    QRCode.toCanvas(canvasRef.current, data, {
      width: size,
      margin: 2,
      color: {
        dark: "#000000",
        light: "#ffffff",
      },
      errorCorrectionLevel: "L",
    }).catch(() => {
      // If data is too long for a single QR, show error
      const ctx = canvasRef.current?.getContext("2d");
      if (ctx) {
        ctx.fillStyle = "#333";
        ctx.fillRect(0, 0, size, size);
        ctx.fillStyle = "#f44";
        ctx.font = "14px system-ui";
        ctx.textAlign = "center";
        ctx.fillText("Data too large for QR", size / 2, size / 2);
      }
    });
  }, [data, size]);

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
      {label && (
        <p style={{ color: "#777", fontSize: "0.8rem", marginTop: "0.5rem" }}>
          {label}
        </p>
      )}
    </div>
  );
}
