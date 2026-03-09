import { contextBridge } from "electron";

// Expose a minimal API to the renderer process.
// All crypto is handled in the renderer via inline JS (Web Crypto + pure JS libs).
// The preload only provides fetch-based network access for balance queries.
contextBridge.exposeInMainWorld("burnerAPI", {
  platform: process.platform,
  electronVersion: process.versions.electron,
  fetchBalance: async (address: string, baseUrl: string): Promise<{ confirmed: number; unconfirmed: number }> => {
    const res = await fetch(`${baseUrl}/address/${address}`);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json();
    const confirmed = (data.chain_stats?.funded_txo_sum ?? 0) - (data.chain_stats?.spent_txo_sum ?? 0);
    const unconfirmed = (data.mempool_stats?.funded_txo_sum ?? 0) - (data.mempool_stats?.spent_txo_sum ?? 0);
    return { confirmed, unconfirmed };
  },
});
