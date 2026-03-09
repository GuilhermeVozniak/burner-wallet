"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Header from "../components/Header";
import SessionGuard from "../components/SessionGuard";
import QrDisplay from "../components/QrDisplay";
import {
  mnemonicToSeed,
  deriveAddress,
  deriveChangeAddress,
  fetchUtxos,
  buildSendSummary,
} from "@/lib/crypto";

type Network = "testnet" | "mainnet" | "signet";
type Step = "form" | "loading" | "review";

export default function SendPage() {
  const router = useRouter();
  const [network, setNetwork] = useState<Network>("testnet");
  const [step, setStep] = useState<Step>("form");
  const [error, setError] = useState("");

  const [recipient, setRecipient] = useState("");
  const [amount, setAmount] = useState("");
  const [feeRate, setFeeRate] = useState("1");

  // Transaction details from UTXO selection
  const [txSummary, setTxSummary] = useState<{
    inputs: { txid: string; vout: number; value: number }[];
    outputs: { address: string; value: number }[];
    fee: number;
    change: number;
  } | null>(null);

  useEffect(() => {
    const net = sessionStorage.getItem("bw_network") as Network | null;
    setNetwork(net || "testnet");
  }, []);

  async function handleReview() {
    if (!recipient.trim()) {
      setError("Recipient address is required.");
      return;
    }
    // Validate bech32 address format
    const addr = recipient.trim();
    const validPrefixes = network === "mainnet" ? ["bc1q", "bc1p"] : ["tb1q", "tb1p"];
    if (!validPrefixes.some((p) => addr.toLowerCase().startsWith(p))) {
      setError(
        `Invalid address for ${network}. Expected ${validPrefixes.join(" or ")} prefix.`
      );
      return;
    }
    const sats = parseInt(amount, 10);
    if (isNaN(sats) || sats <= 0) {
      setError("Amount must be a positive number of satoshis.");
      return;
    }
    const fee = parseFloat(feeRate);
    if (isNaN(fee) || fee <= 0) {
      setError("Fee rate must be a positive number.");
      return;
    }

    setError("");
    setStep("loading");

    try {
      const mnemonic = sessionStorage.getItem("bw_mnemonic")!;
      const net = (sessionStorage.getItem("bw_network") || "testnet") as Network;
      const seed = await mnemonicToSeed(mnemonic);
      const address = deriveAddress(seed, net, 0, 0);
      const changeAddr = deriveChangeAddress(seed, net, 0, 0);

      const utxos = await fetchUtxos(address, net);
      const summary = buildSendSummary({
        recipient: recipient.trim(),
        amountSats: sats,
        feeRateSatVb: fee,
        changeAddress: changeAddr,
        utxos,
        network: net,
      });

      if (summary.error) {
        setError(summary.error);
        setStep("form");
        return;
      }

      setTxSummary(summary);
      setStep("review");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to build transaction");
      setStep("form");
    }
  }

  function handleBack() {
    if (step === "review") {
      setStep("form");
      setTxSummary(null);
    } else {
      router.push("/wallet");
    }
  }

  // Build QR payload for the signer
  const qrPayload = txSummary
    ? JSON.stringify({
        inputs: txSummary.inputs,
        outputs: txSummary.outputs,
        network,
      })
    : "";

  return (
    <SessionGuard>
      <Header network={network} />

      <main>
        <h1>Send Bitcoin</h1>

        {step === "form" && (
          <div className="card">
            <h2>Transaction Details</h2>

            <div className="field">
              <label htmlFor="recipient">Recipient Address</label>
              <input
                id="recipient"
                type="text"
                value={recipient}
                onChange={(e) => {
                  setRecipient(e.target.value);
                  setError("");
                }}
                placeholder={network === "mainnet" ? "bc1q..." : "tb1q..."}
              />
            </div>

            <div className="field">
              <label htmlFor="amount">Amount (sats)</label>
              <input
                id="amount"
                type="number"
                min="1"
                value={amount}
                onChange={(e) => {
                  setAmount(e.target.value);
                  setError("");
                }}
                placeholder="10000"
              />
            </div>

            <div className="field">
              <label htmlFor="fee-rate">Fee Rate (sat/vB)</label>
              <input
                id="fee-rate"
                type="number"
                min="1"
                step="0.1"
                value={feeRate}
                onChange={(e) => {
                  setFeeRate(e.target.value);
                  setError("");
                }}
                placeholder="1"
              />
            </div>

            {error && (
              <p style={{ color: "#f44", marginBottom: "0.75rem" }}>{error}</p>
            )}

            <div className="btn-group">
              <button className="btn btn-primary" onClick={handleReview}>
                Build Transaction
              </button>
              <button className="btn" onClick={handleBack}>
                Back
              </button>
            </div>
          </div>
        )}

        {step === "loading" && (
          <div className="card">
            <h2>Fetching UTXOs...</h2>
            <p style={{ color: "#777" }}>
              Querying Esplora for available UTXOs and building the transaction.
            </p>
          </div>
        )}

        {step === "review" && txSummary && (
          <>
            <div className="card">
              <h2>Transaction Summary</h2>
              <table
                style={{ width: "100%", borderCollapse: "collapse", marginBottom: "1rem" }}
              >
                <tbody>
                  <tr>
                    <td style={{ color: "#777", padding: "0.4rem 0" }}>To</td>
                    <td className="mono" style={{ padding: "0.4rem 0", wordBreak: "break-all" }}>
                      {recipient}
                    </td>
                  </tr>
                  <tr>
                    <td style={{ color: "#777", padding: "0.4rem 0" }}>Amount</td>
                    <td style={{ padding: "0.4rem 0" }}>
                      {parseInt(amount, 10).toLocaleString()} sats
                    </td>
                  </tr>
                  <tr>
                    <td style={{ color: "#777", padding: "0.4rem 0" }}>Fee</td>
                    <td style={{ padding: "0.4rem 0" }}>
                      {txSummary.fee.toLocaleString()} sats ({feeRate} sat/vB)
                    </td>
                  </tr>
                  <tr>
                    <td style={{ color: "#777", padding: "0.4rem 0" }}>Change</td>
                    <td style={{ padding: "0.4rem 0" }}>
                      {txSummary.change > 0
                        ? `${txSummary.change.toLocaleString()} sats`
                        : "none (dust absorbed into fee)"}
                    </td>
                  </tr>
                  <tr>
                    <td style={{ color: "#777", padding: "0.4rem 0" }}>Inputs</td>
                    <td style={{ padding: "0.4rem 0" }}>
                      {txSummary.inputs.length} UTXO{txSummary.inputs.length !== 1 ? "s" : ""}
                    </td>
                  </tr>
                  <tr>
                    <td style={{ color: "#777", padding: "0.4rem 0" }}>Network</td>
                    <td style={{ padding: "0.4rem 0" }}>{network}</td>
                  </tr>
                </tbody>
              </table>
            </div>

            <div className="card">
              <h2>Inputs</h2>
              {txSummary.inputs.map((inp, i) => (
                <div key={i} style={{ marginBottom: "0.5rem" }}>
                  <span className="mono" style={{ color: "#777", fontSize: "0.8rem" }}>
                    {inp.txid.slice(0, 8)}...:{inp.vout}
                  </span>
                  <span style={{ marginLeft: "0.5rem" }}>
                    {inp.value.toLocaleString()} sats
                  </span>
                </div>
              ))}
            </div>

            <div className="card">
              <h2>QR Code for Signer</h2>
              <p style={{ color: "#777", marginBottom: "0.5rem", fontSize: "0.85rem" }}>
                Scan this with your air-gapped signer to construct and sign the
                transaction.
              </p>
              <QrDisplay
                data={qrPayload}
                size={280}
                label="Transaction data for signer"
              />
            </div>

            <div className="card">
              <h2>Raw Transaction Data</h2>
              <div className="hex-display" style={{ fontSize: "0.75rem" }}>
                {qrPayload}
              </div>
            </div>

            <div className="btn-group">
              <button className="btn" onClick={handleBack}>
                Edit Transaction
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
