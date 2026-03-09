import "react-native-get-random-values";

import React, { useState, useCallback } from "react";
import {
  StyleSheet,
  Text,
  View,
  TextInput,
  TouchableOpacity,
  ScrollView,
  ActivityIndicator,
  SafeAreaView,
  StatusBar,
} from "react-native";
import {
  generateMnemonic,
  validateMnemonic,
  mnemonicToSeed,
  deriveAddress,
  fetchBalance,
} from "./src/lib/crypto";

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

type Screen = "home" | "import" | "wallet";
type Network = "mainnet" | "testnet" | "signet";

interface WalletState {
  mnemonic: string;
  address: string;
  balance: { confirmed: number; unconfirmed: number } | null;
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function satsToBtc(sats: number): string {
  return (sats / 1e8).toFixed(8);
}

// ---------------------------------------------------------------------------
// Home Screen
// ---------------------------------------------------------------------------

function HomeScreen({
  network,
  setNetwork,
  onCreateWallet,
  onGoImport,
}: {
  network: Network;
  setNetwork: (n: Network) => void;
  onCreateWallet: () => void;
  onGoImport: () => void;
}) {
  const networks: Network[] = ["testnet", "signet", "mainnet"];

  return (
    <View style={styles.centered}>
      <Text style={styles.logo}>Burner Wallet</Text>
      <Text style={styles.subtitle}>Mobile Companion</Text>

      <Text style={[styles.label, { marginTop: 32 }]}>Network</Text>
      <View style={styles.networkRow}>
        {networks.map((n) => (
          <TouchableOpacity
            key={n}
            style={[
              styles.networkBtn,
              n === network && styles.networkBtnActive,
            ]}
            onPress={() => setNetwork(n)}
          >
            <Text
              style={[
                styles.networkBtnText,
                n === network && styles.networkBtnTextActive,
              ]}
            >
              {n}
            </Text>
          </TouchableOpacity>
        ))}
      </View>

      <TouchableOpacity style={styles.primaryBtn} onPress={onCreateWallet}>
        <Text style={styles.primaryBtnText}>Create Wallet</Text>
      </TouchableOpacity>

      <TouchableOpacity style={styles.secondaryBtn} onPress={onGoImport}>
        <Text style={styles.secondaryBtnText}>Import Wallet</Text>
      </TouchableOpacity>
    </View>
  );
}

// ---------------------------------------------------------------------------
// Import Screen
// ---------------------------------------------------------------------------

function ImportScreen({
  onImport,
  onBack,
}: {
  onImport: (mnemonic: string) => void;
  onBack: () => void;
}) {
  const [input, setInput] = useState("");
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = useCallback(() => {
    const trimmed = input.trim().replace(/\s+/g, " ");
    if (!validateMnemonic(trimmed)) {
      setError("Invalid mnemonic. Please check your words and try again.");
      return;
    }
    setError(null);
    onImport(trimmed);
  }, [input, onImport]);

  return (
    <View style={styles.screen}>
      <TouchableOpacity onPress={onBack}>
        <Text style={styles.backBtn}>{"< Back"}</Text>
      </TouchableOpacity>

      <Text style={styles.heading}>Import Wallet</Text>
      <Text style={styles.label}>Enter your 12 or 24-word mnemonic</Text>

      <TextInput
        style={styles.textArea}
        multiline
        numberOfLines={4}
        placeholder="abandon ability able about above absent ..."
        placeholderTextColor="#555"
        value={input}
        onChangeText={setInput}
        autoCapitalize="none"
        autoCorrect={false}
      />

      {error && <Text style={styles.error}>{error}</Text>}

      <TouchableOpacity style={styles.primaryBtn} onPress={handleSubmit}>
        <Text style={styles.primaryBtnText}>Import</Text>
      </TouchableOpacity>
    </View>
  );
}

// ---------------------------------------------------------------------------
// Wallet Screen
// ---------------------------------------------------------------------------

function WalletScreen({
  wallet,
  network,
  onSync,
  syncing,
  onBack,
}: {
  wallet: WalletState;
  network: Network;
  onSync: () => void;
  syncing: boolean;
  onBack: () => void;
}) {
  const [showMnemonic, setShowMnemonic] = useState(false);

  return (
    <ScrollView
      style={styles.screen}
      contentContainerStyle={{ paddingBottom: 40 }}
    >
      <TouchableOpacity onPress={onBack}>
        <Text style={styles.backBtn}>{"< Back"}</Text>
      </TouchableOpacity>

      <Text style={styles.heading}>Wallet</Text>

      <Text style={styles.label}>Network</Text>
      <Text style={styles.value}>{network}</Text>

      <Text style={[styles.label, { marginTop: 16 }]}>Address</Text>
      <Text style={styles.mono} selectable>
        {wallet.address}
      </Text>

      <Text style={[styles.label, { marginTop: 16 }]}>Balance</Text>
      {wallet.balance ? (
        <View>
          <Text style={styles.value}>
            {satsToBtc(wallet.balance.confirmed)} BTC (confirmed)
          </Text>
          <Text style={styles.valueMuted}>
            {satsToBtc(wallet.balance.unconfirmed)} BTC (unconfirmed)
          </Text>
        </View>
      ) : (
        <Text style={styles.valueMuted}>Not yet synced</Text>
      )}

      <TouchableOpacity
        style={[styles.primaryBtn, syncing && styles.disabledBtn]}
        onPress={onSync}
        disabled={syncing}
      >
        {syncing ? (
          <ActivityIndicator color="#111" />
        ) : (
          <Text style={styles.primaryBtnText}>Sync Balance</Text>
        )}
      </TouchableOpacity>

      <TouchableOpacity
        style={styles.secondaryBtn}
        onPress={() => setShowMnemonic((v) => !v)}
      >
        <Text style={styles.secondaryBtnText}>
          {showMnemonic ? "Hide Mnemonic" : "Reveal Mnemonic"}
        </Text>
      </TouchableOpacity>

      {showMnemonic && (
        <View style={styles.mnemonicBox}>
          <Text style={styles.mono} selectable>
            {wallet.mnemonic}
          </Text>
        </View>
      )}
    </ScrollView>
  );
}

// ---------------------------------------------------------------------------
// App Root
// ---------------------------------------------------------------------------

export default function App() {
  const [screen, setScreen] = useState<Screen>("home");
  const [network, setNetwork] = useState<Network>("testnet");
  const [wallet, setWallet] = useState<WalletState | null>(null);
  const [syncing, setSyncing] = useState(false);

  const loadWallet = useCallback(
    async (mnemonic: string) => {
      const seed = await mnemonicToSeed(mnemonic);
      const address = deriveAddress(seed, network);
      setWallet({ mnemonic, address, balance: null });
      setScreen("wallet");
    },
    [network]
  );

  const handleCreate = useCallback(async () => {
    const mnemonic = generateMnemonic(12);
    await loadWallet(mnemonic);
  }, [loadWallet]);

  const handleImport = useCallback(
    async (mnemonic: string) => {
      await loadWallet(mnemonic);
    },
    [loadWallet]
  );

  const handleSync = useCallback(async () => {
    if (!wallet) return;
    setSyncing(true);
    try {
      const balance = await fetchBalance(wallet.address, network);
      setWallet((prev) => (prev ? { ...prev, balance } : prev));
    } catch {
      // Silently ignore sync errors for now
    } finally {
      setSyncing(false);
    }
  }, [wallet, network]);

  const handleBack = useCallback(() => {
    setWallet(null);
    setScreen("home");
  }, []);

  return (
    <SafeAreaView style={styles.safeArea}>
      <StatusBar barStyle="light-content" backgroundColor="#111" />
      {screen === "home" && (
        <HomeScreen
          network={network}
          setNetwork={setNetwork}
          onCreateWallet={handleCreate}
          onGoImport={() => setScreen("import")}
        />
      )}
      {screen === "import" && (
        <ImportScreen
          onImport={handleImport}
          onBack={() => setScreen("home")}
        />
      )}
      {screen === "wallet" && wallet && (
        <WalletScreen
          wallet={wallet}
          network={network}
          onSync={handleSync}
          syncing={syncing}
          onBack={handleBack}
        />
      )}
    </SafeAreaView>
  );
}

// ---------------------------------------------------------------------------
// Styles
// ---------------------------------------------------------------------------

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: "#111",
  },
  centered: {
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
    padding: 24,
  },
  screen: {
    flex: 1,
    padding: 24,
    paddingTop: 16,
  },
  logo: {
    color: "#0ff",
    fontSize: 28,
    fontWeight: "bold",
  },
  subtitle: {
    color: "#888",
    fontSize: 16,
    marginTop: 4,
  },
  heading: {
    color: "#0ff",
    fontSize: 22,
    fontWeight: "bold",
    marginBottom: 16,
    marginTop: 8,
  },
  label: {
    color: "#888",
    fontSize: 13,
    textTransform: "uppercase",
    letterSpacing: 1,
    marginBottom: 4,
  },
  value: {
    color: "#eee",
    fontSize: 16,
  },
  valueMuted: {
    color: "#888",
    fontSize: 14,
    marginTop: 2,
  },
  mono: {
    color: "#0ff",
    fontSize: 13,
    fontFamily: "monospace",
  },
  networkRow: {
    flexDirection: "row",
    marginBottom: 32,
    marginTop: 8,
    gap: 8,
  },
  networkBtn: {
    paddingVertical: 8,
    paddingHorizontal: 16,
    borderRadius: 6,
    borderWidth: 1,
    borderColor: "#333",
  },
  networkBtnActive: {
    borderColor: "#0ff",
    backgroundColor: "#0ff22",
  },
  networkBtnText: {
    color: "#888",
    fontSize: 14,
  },
  networkBtnTextActive: {
    color: "#0ff",
  },
  primaryBtn: {
    backgroundColor: "#0ff",
    paddingVertical: 14,
    paddingHorizontal: 32,
    borderRadius: 8,
    alignItems: "center",
    marginTop: 16,
    width: "100%",
  },
  primaryBtnText: {
    color: "#111",
    fontWeight: "bold",
    fontSize: 16,
  },
  secondaryBtn: {
    borderWidth: 1,
    borderColor: "#0ff",
    paddingVertical: 12,
    paddingHorizontal: 32,
    borderRadius: 8,
    alignItems: "center",
    marginTop: 12,
    width: "100%",
  },
  secondaryBtnText: {
    color: "#0ff",
    fontSize: 16,
  },
  disabledBtn: {
    opacity: 0.6,
  },
  backBtn: {
    color: "#0ff",
    fontSize: 16,
    marginBottom: 12,
  },
  textArea: {
    backgroundColor: "#1a1a1a",
    color: "#eee",
    borderWidth: 1,
    borderColor: "#333",
    borderRadius: 8,
    padding: 12,
    fontSize: 15,
    minHeight: 100,
    textAlignVertical: "top",
    marginTop: 8,
    width: "100%",
  },
  error: {
    color: "#f55",
    fontSize: 14,
    marginTop: 8,
  },
  mnemonicBox: {
    backgroundColor: "#1a1a1a",
    borderWidth: 1,
    borderColor: "#333",
    borderRadius: 8,
    padding: 12,
    marginTop: 12,
  },
});
