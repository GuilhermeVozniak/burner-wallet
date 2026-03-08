"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Header from "../components/Header";

type Network = "testnet" | "mainnet" | "signet";

export default function WalletPage() {
  const router = useRouter();
  const [network, setNetwork] = useState<Network>("testnet");
  const [mnemonic, setMnemonic] = useState("");
  const [source, setSource] = useState("");
  const [showMnemonic, setShowMnemonic] = useState(false);

  useEffect(() => {
    const net = sessionStorage.getItem("bw_network") as Network | null;
    const mn = sessionStorage.getItem("bw_mnemonic");
    const src = sessionStorage.getItem("bw_source");

    if (!mn) {
      router.push("/");
      return;
    }

    setNetwork(net || "testnet");
    setMnemonic(mn);
    setSource(src || "unknown");
  }, [router]);

  function handleLogout() {
    sessionStorage.removeItem("bw_network");
    sessionStorage.removeItem("bw_mnemonic");
    sessionStorage.removeItem("bw_source");
    router.push("/");
  }

  if (!mnemonic) {
    return null;
  }

  return (
    <>
      <Header network={network} />

      <main>
        <h1>Wallet Dashboard</h1>

        <div className="card">
          <h2>Mnemonic</h2>
          <p style={{ color: "#777", marginBottom: "0.5rem", fontSize: "0.85rem" }}>
            Source: {source === "generated" ? "Generated (placeholder)" : "Imported"}
          </p>
          {showMnemonic ? (
            <>
              <div className="hex-display">{mnemonic}</div>
              <button
                className="btn"
                style={{ marginTop: "0.75rem" }}
                onClick={() => setShowMnemonic(false)}
              >
                Hide Mnemonic
              </button>
            </>
          ) : (
            <button className="btn" onClick={() => setShowMnemonic(true)}>
              Reveal Mnemonic
            </button>
          )}
        </div>

        <div className="card">
          <h2>Receive Address</h2>
          <p className="placeholder-value">
            Connect WASM to derive address
          </p>
          <div className="note">
            Address derivation (BIP84) requires the companion core WASM bridge.
            Once integrated, this will show your first receiving address.
          </div>
        </div>

        <div className="card">
          <h2>Balance</h2>
          <p
            style={{
              fontSize: "1.5rem",
              fontWeight: 700,
              color: "#0ff",
            }}
          >
            <span className="placeholder-value" style={{ fontSize: "1rem", fontWeight: 400 }}>
              Sync to see balance
            </span>
          </p>
          <div className="note">
            Balance lookup requires Esplora API integration via the WASM bridge.
          </div>
        </div>

        <div className="card">
          <h2>Actions</h2>
          <div className="btn-group">
            <button className="btn btn-primary" onClick={() => router.push("/send")}>
              Send (PSBT)
            </button>
            <button className="btn" onClick={() => router.push("/receive")}>
              Receive Signed PSBT
            </button>
            <button className="btn" disabled>
              History
            </button>
            <button className="btn" disabled>
              Sync
            </button>
          </div>
          <div className="note">
            History and Sync require the WASM bridge and Esplora API connection.
          </div>
        </div>

        <div style={{ marginTop: "1.5rem" }}>
          <button className="btn btn-danger" onClick={handleLogout}>
            Logout
          </button>
        </div>
      </main>
    </>
  );
}
