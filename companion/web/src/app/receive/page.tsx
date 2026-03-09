"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Header from "../components/Header";
import SessionGuard from "../components/SessionGuard";
import QrScanner from "../components/QrScanner";
import { broadcastTx } from "@/lib/crypto";

type Network = "testnet" | "mainnet" | "signet";

export default function ReceivePage() {
  const router = useRouter();
  const [network, setNetwork] = useState<Network>("testnet");
  const [psbtHex, setPsbtHex] = useState("");
  const [parsed, setParsed] = useState(false);
  const [inputMode, setInputMode] = useState<"paste" | "scan">("paste");
  const [broadcasting, setBroadcasting] = useState(false);
  const [txid, setTxid] = useState<string | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    const net = sessionStorage.getItem("bw_network") as Network | null;
    setNetwork(net || "testnet");
  }, []);

  function handleParse() {
    const hex = psbtHex.trim();
    if (!hex) {
      setError("Please paste the signed transaction hex.");
      return;
    }
    if (!/^[0-9a-fA-F]+$/.test(hex)) {
      setError("Invalid hex string.");
      return;
    }
    if (hex.length < 20) {
      setError("Hex is too short to be a valid transaction.");
      return;
    }
    setError("");
    setParsed(true);
  }

  async function handleBroadcast() {
    setBroadcasting(true);
    setError("");
    try {
      const id = await broadcastTx(psbtHex.trim(), network);
      setTxid(id);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Broadcast failed");
    } finally {
      setBroadcasting(false);
    }
  }

  function handleReset() {
    setPsbtHex("");
    setParsed(false);
    setTxid(null);
    setError("");
    setInputMode("paste");
  }

  return (
    <SessionGuard>
      <Header network={network} />

      <main>
        <h1>Receive Signed PSBT</h1>
        <p style={{ color: "#777", marginBottom: "1.5rem" }}>
          Get the signed transaction from your air-gapped signer via QR scan or
          paste, then broadcast it.
        </p>

        {!parsed ? (
          <div className="card">
            <div className="btn-group" style={{ marginBottom: "1rem" }}>
              <button
                className={`btn ${inputMode === "paste" ? "btn-primary" : ""}`}
                onClick={() => setInputMode("paste")}
              >
                Paste Hex
              </button>
              <button
                className={`btn ${inputMode === "scan" ? "btn-primary" : ""}`}
                onClick={() => setInputMode("scan")}
              >
                Scan QR
              </button>
            </div>

            {inputMode === "paste" ? (
              <>
                <h2>Transaction Hex</h2>
                <div className="field">
                  <label htmlFor="psbt-input">
                    Paste the raw transaction hex from your signer
                  </label>
                  <textarea
                    id="psbt-input"
                    value={psbtHex}
                    onChange={(e) => {
                      setPsbtHex(e.target.value);
                      setError("");
                    }}
                    placeholder="0200000001..."
                    rows={6}
                  />
                </div>
                <div className="btn-group">
                  <button className="btn btn-primary" onClick={handleParse}>
                    Parse Transaction
                  </button>
                  <button className="btn" onClick={() => router.push("/wallet")}>
                    Back
                  </button>
                </div>
              </>
            ) : (
              <>
                <h2>Scan QR from Signer</h2>
                <QrScanner
                  onScan={(data) => {
                    setPsbtHex(data);
                    setError("");
                    setParsed(true);
                  }}
                  onError={(msg) => setError(msg)}
                />
                <div className="btn-group" style={{ marginTop: "1rem" }}>
                  <button className="btn" onClick={() => router.push("/wallet")}>
                    Back
                  </button>
                </div>
              </>
            )}

            {error && (
              <p style={{ color: "#f44", marginTop: "0.75rem" }}>{error}</p>
            )}
          </div>
        ) : (
          <>
            <div className="card">
              <h2>Transaction Details</h2>
              <table
                style={{ width: "100%", borderCollapse: "collapse", marginBottom: "1rem" }}
              >
                <tbody>
                  <tr>
                    <td style={{ color: "#777", padding: "0.4rem 0" }}>Size</td>
                    <td style={{ padding: "0.4rem 0" }}>
                      {Math.floor(psbtHex.trim().length / 2)} bytes
                    </td>
                  </tr>
                  <tr>
                    <td style={{ color: "#777", padding: "0.4rem 0" }}>Network</td>
                    <td style={{ padding: "0.4rem 0" }}>{network}</td>
                  </tr>
                  <tr>
                    <td style={{ color: "#777", padding: "0.4rem 0" }}>Status</td>
                    <td style={{ padding: "0.4rem 0", color: txid ? "#4f4" : "#ff0" }}>
                      {txid ? "Broadcast" : "Ready to broadcast"}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>

            <div className="card">
              <h2>Raw Hex</h2>
              <div className="hex-display">{psbtHex.trim()}</div>
            </div>

            {txid && (
              <div className="card">
                <h2>Broadcast Successful</h2>
                <p style={{ color: "#4f4", marginBottom: "0.5rem" }}>Transaction ID:</p>
                <div className="hex-display">{txid}</div>
              </div>
            )}

            {error && (
              <div className="card">
                <p style={{ color: "#f44" }}>{error}</p>
              </div>
            )}

            <div className="btn-group">
              {!txid && (
                <button
                  className="btn btn-primary"
                  onClick={handleBroadcast}
                  disabled={broadcasting}
                >
                  {broadcasting ? "Broadcasting..." : "Broadcast Transaction"}
                </button>
              )}
              <button className="btn" onClick={handleReset}>
                New Transaction
              </button>
              <button className="btn" onClick={() => router.push("/wallet")}>
                Back to Dashboard
              </button>
            </div>
          </>
        )}
      </main>
    </SessionGuard>
  );
}
