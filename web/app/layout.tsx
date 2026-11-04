import type { Metadata } from "next";
import Link from "next/link";
import "./globals.css";

export const metadata: Metadata = {
  title: "pretium — what sending money really costs",
  description:
    "Provider-by-provider costs of sending money abroad, from open data, with the evidence for every number.",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <header className="top">
          <Link href="/" className="brand">
            pretium
          </Link>
          <nav>
            <Link href="/">Compare</Link>
            <Link href="/ops">Operations</Link>
            <Link href="/about">Method &amp; sources</Link>
          </nav>
        </header>
        <main>{children}</main>
        <footer>
          Provider prices: The World Bank, Remittance Prices Worldwide, available at{" "}
          <a href="http://remittanceprices.worldbank.org">remittanceprices.worldbank.org</a> (CC BY 4.0). Reference
          rates: Source: ECB. No endorsement by either is implied.
        </footer>
      </body>
    </html>
  );
}
