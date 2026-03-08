"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Header from "../components/Header";

type Network = "testnet" | "mainnet" | "signet";

export default function ReceivePage() {
  const router = useRouter();
  const [network, setNetwork] = useState<Network>("testnet");
  const [psbtHex, setPsbtHex] = useState("");
  const [parsed, setParsed] = useState(false);
  const [broadcasting, setBroadcasting] = useState(false);
  const [broadcastResult, setBroadcastResult] = useState<string | null>(null);

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
      alert("Please paste the signed PSBT hex.");
      return;
    }
    // Basic hex validation
    if (!/^[0-9a-fA-F]+$/.test(hex)) {
      alert("Invalid hex string. PSBT hex must contain only hexadecimal characters.");
      return;
    }
    if (hex.length < 20) {
      alert("PSBT hex is too short to be valid.");
      return;
    }
    setParsed(true);
  }

  function handleBroadcast() {
    setBroadcasting(true);
    // Simulate broadcast attempt
    setTimeout(() => {
      setBroadcasting(false);
      setBroadcastResult(
        "Broadcasting requires Esplora API integration via the WASM bridge. " +
          "This is a placeholder. Once integrated, the signed transaction will " +
          "be finalized and broadcast to the Bitcoin network."
      );
    }, 1000);
  }

  function handleReset() {
    setPsbtHex("");
    setParsed(false);
    setBroadcastResult(null);
  }

  return (
    <>
      <Header network={network} />

      <main>
        <h1>Receive Signed PSBT</h1>
        <p style={{ color: "#777", marginBottom: "1.5rem" }}>
          Paste the signed PSBT hex from your air-gapped signer to finalize and
          broadcast the transaction.
        </p>

        {!parsed ? (
          <div className="card">
            <h2>Signed PSBT Hex</h2>
            <div className="field">
              <label htmlFor="psbt-input">
                Paste the signed PSBT hex from your signer
              </label>
              <textarea
                id="psbt-input"
                value={psbtHex}
                onChange={(e) => setPsbtHex(e.target.value)}
                placeholder="70736274ff01..."
                rows={6}
              />
            </div>
            <div className="btn-group">
              <button className="btn btn-primary" onClick={handleParse}>
                Parse PSBT
              </button>
              <button className="btn" onClick={() => router.push("/wallet")}>
                Back to Dashboard
              </button>
            </div>
          </div>
        ) : (
          <>
            <div className="card">
              <h2>Transaction Details</h2>
              <table
                style={{
                  width: "100%",
                  borderCollapse: "collapse",
                  marginBottom: "1rem",
                }}
              >
                <tbody>
                  <tr>
                    <td style={{ color: "#777", padding: "0.4rem 0" }}>
                      PSBT Length
                    </td>
                    <td style={{ padding: "0.4rem 0" }}>
                      {psbtHex.trim().length / 2} bytes
                    </td>
                  </tr>
                  <tr>
                    <td style={{ color: "#777", padding: "0.4rem 0" }}>
                      Network
                    </td>
                    <td style={{ padding: "0.4rem 0" }}>{network}</td>
                  </tr>
                  <tr>
                    <td style={{ color: "#777", padding: "0.4rem 0" }}>
                      Status
                    </td>
                    <td style={{ padding: "0.4rem 0", color: "#4f4" }}>
                      Signed (ready to broadcast)
                    </td>
                  </tr>
                </tbody>
              </table>
              <div className="note">
                Full PSBT parsing and transaction detail extraction require the
                WASM bridge. This shows basic metadata only.
              </div>
            </div>

            <div className="card">
              <h2>Raw PSBT</h2>
              <div className="hex-display">{psbtHex.trim()}</div>
            </div>

            {broadcastResult && (
              <div className="card">
                <h2>Broadcast Result</h2>
                <p style={{ color: "#777" }}>{broadcastResult}</p>
              </div>
            )}

            <div className="btn-group">
              {!broadcastResult && (
                <button
                  className="btn btn-primary"
                  onClick={handleBroadcast}
                  disabled={broadcasting}
                >
                  {broadcasting ? "Broadcasting..." : "Broadcast Transaction"}
                </button>
              )}
              <button className="btn" onClick={handleReset}>
                Paste Another PSBT
              </button>
              <button className="btn" onClick={() => router.push("/wallet")}>
                Back to Dashboard
              </button>
            </div>

            <div className="note" style={{ marginTop: "1rem" }}>
              Broadcasting requires Esplora API integration. The signed PSBT
              will be finalized (witnesses extracted) and the raw transaction
              broadcast to the Bitcoin network.
            </div>
          </>
        )}
      </main>
    </>
  );
}
