"use client";

import Link from "next/link";

type Network = "testnet" | "mainnet" | "signet";

interface HeaderProps {
  network: Network;
}

function badgeClass(network: Network): string {
  switch (network) {
    case "mainnet":
      return "badge badge-mainnet";
    case "signet":
      return "badge badge-signet";
    default:
      return "badge badge-testnet";
  }
}

export default function Header({ network }: HeaderProps) {
  return (
    <header
      style={{
        display: "flex",
        alignItems: "center",
        justifyContent: "space-between",
        borderBottom: "1px solid #333",
        paddingBottom: "1rem",
        marginBottom: "1.5rem",
        flexWrap: "wrap",
        gap: "0.5rem",
      }}
    >
      <Link href="/" style={{ textDecoration: "none" }}>
        <span
          style={{
            color: "#0ff",
            fontSize: "1.1rem",
            fontWeight: 700,
            letterSpacing: "0.02em",
          }}
        >
          Burner Wallet
        </span>
      </Link>

      <nav style={{ display: "flex", alignItems: "center", gap: "1rem" }}>
        <Link href="/wallet">Dashboard</Link>
        <Link href="/send">Send</Link>
        <Link href="/receive">Receive</Link>
        <span className={badgeClass(network)}>{network}</span>
      </nav>
    </header>
  );
}
