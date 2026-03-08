import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Burner Wallet Companion",
  description: "Air-gapped Bitcoin wallet companion",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <body style={{ backgroundColor: "#111", color: "#eee", fontFamily: "system-ui, sans-serif", margin: 0, padding: "2rem" }}>
        {children}
      </body>
    </html>
  );
}
