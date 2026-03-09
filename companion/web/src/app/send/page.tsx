"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Header from "../components/Header";
import QrDisplay from "../components/QrDisplay";

type Network = "testnet" | "mainnet" | "signet";
type Step = "form" | "review";

export default function SendPage() {
  const router = useRouter();
  const [network, setNetwork] = useState<Network>("testnet");
  const [step, setStep] = useState<Step>("form");

  const [recipient, setRecipient] = useState("");
  const [amount, setAmount] = useState("");
  const [feeRate, setFeeRate] = useState("1");

  useEffect(() => {
    const net = sessionStorage.getItem("bw_network") as Network | null;
    const mn = sessionStorage.getItem("bw_mnemonic");

    if (!mn) {
      router.push("/");
      return;
    }

    setNetwork(net || "testnet");
  }, [router]);

  function handleReview() {
    if (!recipient.trim()) {
      alert("Recipient address is required.");
      return;
    }
    const sats = parseInt(amount, 10);
    if (isNaN(sats) || sats <= 0) {
      alert("Amount must be a positive number of satoshis.");
      return;
    }
    const fee = parseFloat(feeRate);
    if (isNaN(fee) || fee <= 0) {
      alert("Fee rate must be a positive number.");
      return;
    }
    setStep("review");
  }

  function handleBack() {
    if (step === "review") {
      setStep("form");
    } else {
      router.push("/wallet");
    }
  }

  // Build a simple payload for QR display (not a real PSBT yet,
  // but structured so the signer can read it)
  const txPayload = JSON.stringify({
    to: recipient,
    amount: parseInt(amount, 10) || 0,
    fee_rate: parseFloat(feeRate) || 1,
    network,
  });

  return (
    <>
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
                onChange={(e) => setRecipient(e.target.value)}
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
                onChange={(e) => setAmount(e.target.value)}
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
                onChange={(e) => setFeeRate(e.target.value)}
                placeholder="1"
              />
            </div>

            <div className="btn-group">
              <button className="btn btn-primary" onClick={handleReview}>
                Review Transaction
              </button>
              <button className="btn" onClick={handleBack}>
                Back
              </button>
            </div>
          </div>
        )}

        {step === "review" && (
          <>
            <div className="card">
              <h2>Review Transaction</h2>
              <table
                style={{
                  width: "100%",
                  borderCollapse: "collapse",
                  marginBottom: "1rem",
                }}
              >
                <tbody>
                  <tr>
                    <td style={{ color: "#777", padding: "0.4rem 0" }}>To</td>
                    <td className="mono" style={{ padding: "0.4rem 0" }}>
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
                    <td style={{ color: "#777", padding: "0.4rem 0" }}>Fee Rate</td>
                    <td style={{ padding: "0.4rem 0" }}>{feeRate} sat/vB</td>
                  </tr>
                  <tr>
                    <td style={{ color: "#777", padding: "0.4rem 0" }}>Network</td>
                    <td style={{ padding: "0.4rem 0" }}>{network}</td>
                  </tr>
                </tbody>
              </table>
            </div>

            <div className="card">
              <h2>QR Code for Signer</h2>
              <p style={{ color: "#777", marginBottom: "0.5rem", fontSize: "0.85rem" }}>
                Scan this QR code with your air-gapped signer to sign the
                transaction.
              </p>
              <QrDisplay
                data={txPayload}
                size={280}
                label="Transaction payload for signer"
              />
              <div className="note">
                Full PSBT construction requires UTXO data from Esplora.
                This QR shows the transaction parameters for the signer.
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
    </>
  );
}
