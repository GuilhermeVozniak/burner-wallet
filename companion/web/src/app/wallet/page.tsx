"use client";

import { useEffect, useState, useCallback } from "react";
import { useRouter } from "next/navigation";
import Header from "../components/Header";
import { mnemonicToSeed, deriveAddress, fetchBalance } from "@/lib/crypto";

type Network = "testnet" | "mainnet" | "signet";

export default function WalletPage() {
  const router = useRouter();
  const [network, setNetwork] = useState<Network>("testnet");
  const [mnemonic, setMnemonic] = useState("");
  const [source, setSource] = useState("");
  const [showMnemonic, setShowMnemonic] = useState(false);
  const [address, setAddress] = useState("");
  const [balance, setBalance] = useState<{ confirmed: number; unconfirmed: number } | null>(null);
  const [syncing, setSyncing] = useState(false);
  const [syncError, setSyncError] = useState("");

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

    // Derive address
    mnemonicToSeed(mn).then((seed) => {
      const addr = deriveAddress(seed, net || "testnet", 0, 0);
      setAddress(addr);
    });
  }, [router]);

  const handleSync = useCallback(async () => {
    if (!address) return;
    setSyncing(true);
    setSyncError("");
    try {
      const bal = await fetchBalance(address, network);
      setBalance(bal);
    } catch (e) {
      setSyncError(e instanceof Error ? e.message : "Sync failed");
    } finally {
      setSyncing(false);
    }
  }, [address, network]);

  function handleLogout() {
    sessionStorage.removeItem("bw_network");
    sessionStorage.removeItem("bw_mnemonic");
    sessionStorage.removeItem("bw_source");
    router.push("/");
  }

  if (!mnemonic) {
    return null;
  }

  const totalSats = balance ? balance.confirmed + balance.unconfirmed : null;

  return (
    <>
      <Header network={network} />

      <main>
        <h1>Wallet Dashboard</h1>

        <div className="card">
          <h2>Mnemonic</h2>
          <p style={{ color: "#777", marginBottom: "0.5rem", fontSize: "0.85rem" }}>
            Source: {source === "generated" ? "Generated" : "Imported"}
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
          {address ? (
            <div className="hex-display">{address}</div>
          ) : (
            <p className="placeholder-value">Deriving...</p>
          )}
          <p style={{ color: "#555", fontSize: "0.8rem", marginTop: "0.5rem" }}>
            BIP84 path: m/84&apos;/{network === "mainnet" ? "0" : "1"}&apos;/0&apos;/0/0
          </p>
        </div>

        <div className="card">
          <h2>Balance</h2>
          {totalSats !== null ? (
            <p style={{ fontSize: "1.5rem", fontWeight: 700, color: "#0ff" }}>
              {totalSats.toLocaleString()} sats
            </p>
          ) : (
            <p className="placeholder-value">
              {syncing ? "Syncing..." : "Press Sync to fetch balance"}
            </p>
          )}
          {balance && balance.unconfirmed !== 0 && (
            <p style={{ color: "#777", fontSize: "0.85rem" }}>
              Confirmed: {balance.confirmed.toLocaleString()} / Unconfirmed: {balance.unconfirmed.toLocaleString()}
            </p>
          )}
          {syncError && (
            <p style={{ color: "#f44", fontSize: "0.85rem" }}>{syncError}</p>
          )}
        </div>

        <div className="card">
          <h2>Actions</h2>
          <div className="btn-group">
            <button
              className="btn btn-primary"
              onClick={handleSync}
              disabled={syncing || !address}
            >
              {syncing ? "Syncing..." : "Sync"}
            </button>
            <button className="btn" onClick={() => router.push("/send")}>
              Send (PSBT)
            </button>
            <button className="btn" onClick={() => router.push("/receive")}>
              Receive Signed PSBT
            </button>
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
