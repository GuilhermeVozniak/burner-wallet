"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Header from "../components/Header";
import SessionGuard from "../components/SessionGuard";
import QrDisplay from "../components/QrDisplay";
import {
  mnemonicToSeed,
  deriveKey,
  fetchUtxos,
  fetchTxHex,
  buildSendSummary,
  type Network,
} from "@/lib/crypto";
import {
  buildUnsignedPsbt,
  encodeFrames,
  validateRecipient,
  formatSats,
  type UnsignedPsbt,
} from "@/lib/psbt";
import { PENDING_PSBT_KEY, PENDING_TXID_KEY } from "@/lib/session";

type Step = "form" | "loading" | "review";

/** Refuse obviously wrong fee rates before they reach the signer. */
const MAX_FEE_RATE_SAT_VB = 1000;

interface TxSummary {
  inputs: { txid: string; vout: number; value: number }[];
  outputs: { address: string; value: number }[];
  fee: number;
  change: number;
}

export default function SendPage() {
  const router = useRouter();
  const [network, setNetwork] = useState<Network>("testnet");
  const [step, setStep] = useState<Step>("form");
  const [error, setError] = useState("");

  const [recipient, setRecipient] = useState("");
  const [amount, setAmount] = useState("");
  const [feeRate, setFeeRate] = useState("1");

  const [txSummary, setTxSummary] = useState<TxSummary | null>(null);
  const [unsigned, setUnsigned] = useState<UnsignedPsbt | null>(null);
  const [frames, setFrames] = useState<Uint8Array[]>([]);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    const net = sessionStorage.getItem("bw_network") as Network | null;
    setNetwork(net || "testnet");
  }, []);

  async function handleReview() {
    const addr = recipient.trim();
    if (!addr) {
      setError("Recipient address is required.");
      return;
    }
    const addrError = validateRecipient(addr, network);
    if (addrError) {
      setError(addrError);
      return;
    }
    // Number() rather than parseInt(): parseInt("1e5") silently yields 1.
    const sats = Number(amount);
    if (!Number.isSafeInteger(sats) || sats <= 0) {
      setError("Amount must be a whole, positive number of satoshis.");
      return;
    }
    const fee = Number(feeRate);
    if (!Number.isFinite(fee) || fee <= 0) {
      setError("Fee rate must be a positive number.");
      return;
    }
    if (fee > MAX_FEE_RATE_SAT_VB) {
      setError(`Fee rate ${fee} sat/vB looks wrong (max ${MAX_FEE_RATE_SAT_VB}).`);
      return;
    }

    setError("");
    setStep("loading");

    try {
      const mnemonic = sessionStorage.getItem("bw_mnemonic");
      if (!mnemonic) throw new Error("No wallet in this session");
      const net = (sessionStorage.getItem("bw_network") || "testnet") as Network;
      const seed = await mnemonicToSeed(mnemonic);
      const receiveKey = deriveKey(seed, net, false, 0);
      const changeKey = deriveKey(seed, net, true, 0);

      const utxos = await fetchUtxos(receiveKey.address, net);
      const summary = buildSendSummary({
        recipient: addr,
        amountSats: sats,
        feeRateSatVb: fee,
        changeAddress: changeKey.address,
        utxos,
        network: net,
      });

      if (summary.error) {
        setError(summary.error);
        setStep("form");
        return;
      }

      // The signer verifies every input against its previous transaction,
      // so fetch the raw hex of each funding transaction.
      const prevTxHexes = await Promise.all(
        summary.inputs.map((inp) => fetchTxHex(inp.txid, net))
      );

      // Build the real BIP174 PSBT the signer consumes. All selected UTXOs
      // belong to the first receive key, whose script goes into witness_utxo.
      const built = buildUnsignedPsbt({
        inputs: summary.inputs.map((inp, i) => ({ ...inp, prevTxHex: prevTxHexes[i] })),
        inputKey: receiveKey,
        outputs: summary.outputs,
        network: net,
      });

      // Remember what we built so the receive page can verify the signed
      // PSBT is for this exact transaction.
      sessionStorage.setItem(PENDING_TXID_KEY, built.txid);
      sessionStorage.setItem(PENDING_PSBT_KEY, built.psbtHex);

      setTxSummary({
        inputs: summary.inputs,
        outputs: summary.outputs,
        fee: Number(built.fee),
        change: summary.change,
      });
      setUnsigned(built);
      setFrames(encodeFrames(built.psbt));
      setCopied(false);
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
      setUnsigned(null);
      setFrames([]);
    } else {
      router.push("/wallet");
    }
  }

  async function copyHex() {
    if (!unsigned) return;
    try {
      await navigator.clipboard.writeText(unsigned.psbtHex);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  }

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
                step="1"
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
              Querying Esplora for available UTXOs and building the PSBT.
            </p>
          </div>
        )}

        {step === "review" && txSummary && unsigned && (
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
                      {recipient.trim()}
                    </td>
                  </tr>
                  <tr>
                    <td style={{ color: "#777", padding: "0.4rem 0" }}>Amount</td>
                    <td style={{ padding: "0.4rem 0" }}>
                      {formatSats(txSummary.outputs[0].value)} sats
                    </td>
                  </tr>
                  <tr>
                    <td style={{ color: "#777", padding: "0.4rem 0" }}>Fee</td>
                    <td style={{ padding: "0.4rem 0" }}>
                      {formatSats(txSummary.fee)} sats ({feeRate} sat/vB)
                    </td>
                  </tr>
                  <tr>
                    <td style={{ color: "#777", padding: "0.4rem 0" }}>Change</td>
                    <td style={{ padding: "0.4rem 0" }}>
                      {txSummary.change > 0
                        ? `${formatSats(txSummary.change)} sats`
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
                  <tr>
                    <td style={{ color: "#777", padding: "0.4rem 0" }}>Txid</td>
                    <td className="mono" style={{ padding: "0.4rem 0", wordBreak: "break-all", fontSize: "0.8rem" }}>
                      {unsigned.txid}
                    </td>
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
                    {formatSats(inp.value)} sats
                  </span>
                </div>
              ))}
            </div>

            <div className="card">
              <h2>Unsigned PSBT for Signer</h2>
              <p style={{ color: "#777", marginBottom: "0.5rem", fontSize: "0.85rem" }}>
                Scan with the air-gapped signer. {frames.length > 1
                  ? `The PSBT spans ${frames.length} QR frames; they cycle automatically.`
                  : "Single QR frame."}
              </p>
              <QrDisplay frames={frames} size={280} label="BIP174 PSBT (binary, multi-frame)" />
            </div>

            <div className="card">
              <h2>PSBT Hex (manual entry fallback)</h2>
              <p style={{ color: "#777", marginBottom: "0.5rem", fontSize: "0.85rem" }}>
                If the camera cannot read the QR, type this into the signer&apos;s
                manual entry screen. It must start with 70736274ff.
              </p>
              <div className="hex-display" style={{ fontSize: "0.75rem" }}>
                {unsigned.psbtHex}
              </div>
              <button className="btn" style={{ marginTop: "0.75rem" }} onClick={copyHex}>
                {copied ? "Copied" : "Copy PSBT hex"}
              </button>
            </div>

            <div className="btn-group">
              <button className="btn btn-primary" onClick={() => router.push("/receive")}>
                Next: Receive Signed PSBT
              </button>
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
