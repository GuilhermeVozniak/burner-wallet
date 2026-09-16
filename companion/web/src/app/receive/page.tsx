"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import type { Transaction } from "@scure/btc-signer";
import Header from "../components/Header";
import SessionGuard from "../components/SessionGuard";
import QrScanner from "../components/QrScanner";
import { broadcastTx, type Network } from "@/lib/crypto";
import {
  MultiFrameAssembler,
  decodePsbtHex,
  finalizeToTxHex,
  formatSats,
  hasPsbtMagic,
  parseSignedPsbt,
  qrTextToFrame,
  type ParsedPsbt,
} from "@/lib/psbt";
import { PENDING_PSBT_KEY, PENDING_TXID_KEY } from "@/lib/session";

type Stage = "input" | "review";

export default function ReceivePage() {
  const router = useRouter();
  const [network, setNetwork] = useState<Network>("testnet");
  const [stage, setStage] = useState<Stage>("input");
  const [inputMode, setInputMode] = useState<"paste" | "scan">("paste");
  const [pasteHex, setPasteHex] = useState("");
  const [scanStatus, setScanStatus] = useState("Waiting for first frame...");

  const [parsed, setParsed] = useState<ParsedPsbt | null>(null);
  const [expectedTxid, setExpectedTxid] = useState<string | null>(null);
  const [finalHex, setFinalHex] = useState<string | null>(null);
  const [finalizeError, setFinalizeError] = useState("");

  const [broadcasting, setBroadcasting] = useState(false);
  const [txid, setTxid] = useState<string | null>(null);
  const [error, setError] = useState("");

  const txRef = useRef<Transaction | null>(null);
  const assemblerRef = useRef(new MultiFrameAssembler());

  useEffect(() => {
    const net = sessionStorage.getItem("bw_network") as Network | null;
    setNetwork(net || "testnet");
    setExpectedTxid(sessionStorage.getItem(PENDING_TXID_KEY));
  }, []);

  /** Parse, cross-check, and finalize a signed PSBT. */
  function acceptSignedPsbt(bytes: Uint8Array) {
    setError("");
    setFinalizeError("");
    setFinalHex(null);
    txRef.current = null;

    let result;
    try {
      result = parseSignedPsbt(bytes, network);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to parse PSBT");
      return;
    }

    // Refuse a signed PSBT that is not the transaction built in this
    // session: a substituted PSBT on the return channel must not reach
    // broadcast without the user noticing.
    const pending = sessionStorage.getItem(PENDING_TXID_KEY);
    if (pending && pending !== result.info.txid) {
      setError(
        `Signed PSBT does not match the transaction built in this session ` +
          `(expected txid ${pending}, got ${result.info.txid}). Rejected.`
      );
      return;
    }

    if (result.info.signedInputs === 0) {
      setError("This PSBT carries no signatures. Sign it on the signer first.");
      return;
    }

    txRef.current = result.tx;
    setParsed(result.info);

    // finalizeToTxHex verifies each partial signature (SIGHASH_ALL, matching
    // key, valid ECDSA over the BIP143 digest) before building the witness.
    try {
      setFinalHex(finalizeToTxHex(result.tx));
    } catch (e) {
      setFinalizeError(
        e instanceof Error ? e.message : "Could not finalize (missing or invalid signature)"
      );
    }
    setStage("review");
  }

  function handleParsePaste() {
    let bytes: Uint8Array;
    try {
      bytes = decodePsbtHex(pasteHex);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Invalid PSBT hex");
      return;
    }
    acceptSignedPsbt(bytes);
  }

  /** Feed one scanned QR code into the multi-frame assembler. */
  function handleScan(text: string): boolean {
    const assembler = assemblerRef.current;
    const frame = qrTextToFrame(text, assembler.totalFrames);
    if (!frame) {
      setScanStatus("Unrecognized QR code (not a signer frame)");
      return false;
    }
    assembler.addFrame(frame);
    setScanStatus(`Frame ${assembler.receivedCount} / ${assembler.totalFrames} received`);
    if (!assembler.isComplete()) return false;

    const payload = assembler.assemble();
    assembler.reset();
    if (!hasPsbtMagic(payload)) {
      setError(
        "Assembled payload is not a PSBT. The camera may have mis-decoded a " +
          "binary frame; try again or use the hex paste fallback."
      );
      setScanStatus("Waiting for first frame...");
      return true;
    }
    acceptSignedPsbt(payload);
    return true;
  }

  async function handleBroadcast() {
    if (!finalHex) return;
    setBroadcasting(true);
    setError("");
    try {
      const id = await broadcastTx(finalHex, network);
      setTxid(id);
      sessionStorage.removeItem(PENDING_TXID_KEY);
      sessionStorage.removeItem(PENDING_PSBT_KEY);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Broadcast failed");
    } finally {
      setBroadcasting(false);
    }
  }

  function handleReset() {
    setPasteHex("");
    setParsed(null);
    setFinalHex(null);
    setFinalizeError("");
    setTxid(null);
    setError("");
    setStage("input");
    setInputMode("paste");
    setScanStatus("Waiting for first frame...");
    assemblerRef.current.reset();
    txRef.current = null;
  }

  const rowLabel = { color: "#777", padding: "0.4rem 0", verticalAlign: "top" as const };
  const rowValue = { padding: "0.4rem 0" };

  return (
    <SessionGuard>
      <Header network={network} />

      <main>
        <h1>Receive Signed PSBT</h1>
        <p style={{ color: "#777", marginBottom: "1.5rem" }}>
          Get the signed PSBT back from your air-gapped signer via QR scan or
          hex paste. It is verified, finalized, and then broadcast.
        </p>

        {expectedTxid ? (
          <p style={{ color: "#777", fontSize: "0.85rem", marginBottom: "1rem" }}>
            Pending transaction from this session:{" "}
            <span className="mono" style={{ wordBreak: "break-all" }}>{expectedTxid}</span>
          </p>
        ) : (
          <p style={{ color: "#ff0", fontSize: "0.85rem", marginBottom: "1rem" }}>
            No transaction was built in this session. Review the details below
            carefully before broadcasting.
          </p>
        )}

        {stage === "input" ? (
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
                <h2>Signed PSBT Hex</h2>
                <div className="field">
                  <label htmlFor="psbt-input">
                    Paste the signed PSBT hex (starts with 70736274ff)
                  </label>
                  <textarea
                    id="psbt-input"
                    value={pasteHex}
                    onChange={(e) => {
                      setPasteHex(e.target.value);
                      setError("");
                    }}
                    placeholder="70736274ff0100..."
                    rows={6}
                  />
                </div>
                <div className="btn-group">
                  <button className="btn btn-primary" onClick={handleParsePaste}>
                    Verify PSBT
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
                  onScan={handleScan}
                  onError={(msg) => setError(msg)}
                  status={scanStatus}
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
          parsed && (
            <>
              <div className="card">
                <h2>Transaction Details</h2>
                <table style={{ width: "100%", borderCollapse: "collapse", marginBottom: "1rem" }}>
                  <tbody>
                    <tr>
                      <td style={rowLabel}>Txid</td>
                      <td className="mono" style={{ ...rowValue, wordBreak: "break-all", fontSize: "0.8rem" }}>
                        {parsed.txid}
                      </td>
                    </tr>
                    <tr>
                      <td style={rowLabel}>Inputs</td>
                      <td style={rowValue}>
                        {parsed.inputs.length} ({parsed.signedInputs} signed)
                      </td>
                    </tr>
                    <tr>
                      <td style={rowLabel}>Outputs</td>
                      <td style={rowValue}>
                        {parsed.outputs.map((o, i) => (
                          <div key={i} style={{ marginBottom: "0.35rem" }}>
                            <span className="mono" style={{ wordBreak: "break-all", fontSize: "0.8rem" }}>
                              {o.address}
                            </span>
                            <span style={{ marginLeft: "0.5rem" }}>{formatSats(o.value)} sats</span>
                          </div>
                        ))}
                      </td>
                    </tr>
                    <tr>
                      <td style={rowLabel}>Total out</td>
                      <td style={rowValue}>{formatSats(parsed.totalOut)} sats</td>
                    </tr>
                    <tr>
                      <td style={rowLabel}>Fee</td>
                      <td style={rowValue}>
                        {parsed.fee !== null ? `${formatSats(parsed.fee)} sats` : "unknown (no witness_utxo)"}
                      </td>
                    </tr>
                    <tr>
                      <td style={rowLabel}>Network</td>
                      <td style={rowValue}>{network}</td>
                    </tr>
                    <tr>
                      <td style={rowLabel}>Status</td>
                      <td style={{ ...rowValue, color: txid ? "#4f4" : finalHex ? "#ff0" : "#f44" }}>
                        {txid
                          ? "Broadcast"
                          : finalHex
                            ? "Signatures verified, ready to broadcast"
                            : "Not finalizable"}
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>

              {finalizeError && (
                <div className="card">
                  <h2>Cannot Finalize</h2>
                  <p style={{ color: "#f44" }}>{finalizeError}</p>
                  <p style={{ color: "#777", fontSize: "0.85rem", marginTop: "0.5rem" }}>
                    The PSBT is missing a valid signature for at least one input.
                    Make sure the signer signed every input.
                  </p>
                </div>
              )}

              {finalHex && (
                <div className="card">
                  <h2>Final Transaction Hex</h2>
                  <div className="hex-display">{finalHex}</div>
                </div>
              )}

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
                {!txid && finalHex && (
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
          )
        )}
      </main>
    </SessionGuard>
  );
}
