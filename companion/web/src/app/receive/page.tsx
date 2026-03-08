"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Header from "../components/Header";
import { broadcastTx } from "@/lib/crypto";

type Network = "testnet" | "mainnet" | "signet";

export default function ReceivePage() {
  const router = useRouter();
  const [network, setNetwork] = useState<Network>("testnet");
  const [psbtHex, setPsbtHex] = useState("");
  const [parsed, setParsed] = useState(false);
  const [broadcasting, setBroadcasting] = useState(false);
  const [txid, setTxid] = useState<string | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    const net = sessionStorage.getItem("bw_network") as Network | null;
    const mn = sessionStorage.getItem("bw_mnemonic");

    if (!mn) {
      router.push("/");
      return;
    }

    setNetwork(net || "testnet");
  }, [router]);

  function handleParse() {
    const hex = psbtHex.trim();
    if (!hex) {
      setError("Please paste the signed PSBT or raw transaction hex.");
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
  }

  return (
    <>
      <Header network={network} />

      <main>
        <h1>Receive Signed PSBT</h1>
        <p style={{ color: "#777", marginBottom: "1.5rem" }}>
          Paste the signed transaction hex from your air-gapped signer to
          broadcast it to the Bitcoin network.
        </p>

        {!parsed ? (
          <div className="card">
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
            {error && (
              <p style={{ color: "#f44", marginBottom: "0.75rem" }}>{error}</p>
            )}
            <div className="btn-group">
              <button className="btn btn-primary" onClick={handleParse}>
                Parse Transaction
              </button>
              <button className="btn" onClick={() => router.push("/wallet")}>
                Back
              </button>
            </div>
          </div>
        ) : (
          <>
            <div className="card">
              <h2>Transaction Details</h2>
              <table style={{ width: "100%", borderCollapse: "collapse", marginBottom: "1rem" }}>
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
                <h2>Error</h2>
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
    </>
  );
}
