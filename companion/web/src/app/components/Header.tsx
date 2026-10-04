"use client";

import Link from "next/link";
import Image from "next/image";
import type { Network } from "@/lib/crypto";

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
      <Link
        href="/"
        style={{ display: "inline-flex", alignItems: "center", gap: "0.65rem", textDecoration: "none" }}
      >
        <Image
          src="/brand/mark.svg"
          alt=""
          width={40}
          height={40}
          unoptimized
          style={{ flexShrink: 0 }}
        />
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
