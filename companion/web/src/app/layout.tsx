import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Burner Wallet Companion",
  description:
    "Air-gapped Bitcoin cold-storage wallet companion — web interface",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <body>
        <div className="container">{children}</div>
      </body>
    </html>
  );
}
