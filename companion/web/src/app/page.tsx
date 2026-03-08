"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import Header from "./components/Header";
import { generateMnemonic, validateMnemonic } from "@/lib/crypto";

type Network = "testnet" | "mainnet" | "signet";

export default function Home() {
  const router = useRouter();
  const [network, setNetwork] = useState<Network>("testnet");
  const [importMode, setImportMode] = useState(false);
  const [mnemonicInput, setMnemonicInput] = useState("");
  const [error, setError] = useState("");

  function handleCreate() {
    const phrase = generateMnemonic(12);
    sessionStorage.setItem("bw_network", network);
    sessionStorage.setItem("bw_mnemonic", phrase);
    sessionStorage.setItem("bw_source", "generated");
    router.push("/wallet");
  }

  function handleImport() {
    const phrase = mnemonicInput.trim();
    const words = phrase.split(/\s+/);
    if (words.length !== 12 && words.length !== 24) {
      setError("Mnemonic must be 12 or 24 words.");
      return;
    }
    if (!validateMnemonic(phrase)) {
      setError("Invalid mnemonic. Check spelling and word order.");
      return;
    }
    setError("");
    sessionStorage.setItem("bw_network", network);
    sessionStorage.setItem("bw_mnemonic", phrase);
    sessionStorage.setItem("bw_source", "imported");
    router.push("/wallet");
  }

  return (
    <>
      <Header network={network} />

      <main>
        <h1>Burner Wallet Companion</h1>
        <p style={{ color: "#777", marginBottom: "1.5rem" }}>
          Air-gapped Bitcoin cold-storage wallet. This web companion handles
          chain access, PSBT construction, and broadcasting. The signer never
          touches the internet.
        </p>

        <div className="card">
          <h2>Network</h2>
          <div className="field">
            <label htmlFor="network-select">Select Bitcoin network</label>
            <select
              id="network-select"
              value={network}
              onChange={(e) => setNetwork(e.target.value as Network)}
            >
              <option value="testnet">Testnet</option>
              <option value="mainnet">Mainnet</option>
              <option value="signet">Signet</option>
            </select>
          </div>
        </div>

        {!importMode ? (
          <div className="card">
            <h2>Get Started</h2>
            <p style={{ color: "#777", marginBottom: "1rem" }}>
              Create a new wallet or import an existing mnemonic.
            </p>
            <div className="btn-group">
              <button className="btn btn-primary" onClick={handleCreate}>
                Create Wallet
              </button>
              <button className="btn" onClick={() => setImportMode(true)}>
                Import Wallet
              </button>
            </div>
          </div>
        ) : (
          <div className="card">
            <h2>Import Wallet</h2>
            <div className="field">
              <label htmlFor="mnemonic-input">
                BIP39 Mnemonic (12 or 24 words)
              </label>
              <textarea
                id="mnemonic-input"
                value={mnemonicInput}
                onChange={(e) => {
                  setMnemonicInput(e.target.value);
                  setError("");
                }}
                placeholder="abandon abandon abandon ..."
                rows={3}
              />
            </div>
            {error && (
              <p style={{ color: "#f44", marginBottom: "0.75rem" }}>{error}</p>
            )}
            <div className="btn-group">
              <button className="btn btn-primary" onClick={handleImport}>
                Import
              </button>
              <button
                className="btn"
                onClick={() => {
                  setImportMode(false);
                  setMnemonicInput("");
                  setError("");
                }}
              >
                Cancel
              </button>
            </div>
          </div>
        )}
      </main>
    </>
  );
}
