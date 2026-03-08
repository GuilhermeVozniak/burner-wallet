import { StyleSheet, Text, View } from "react-native";

export default function App() {
  return (
    <View style={styles.container}>
      <Text style={styles.title}>Burner Wallet Companion</Text>
      <Text style={styles.text}>Network: testnet (default)</Text>
      <Text style={styles.muted}>
        Rust integration via JSI bridge planned for a future milestone.
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: "#111",
    alignItems: "center",
    justifyContent: "center",
    padding: 20,
  },
  title: {
    color: "#0ff",
    fontSize: 24,
    fontWeight: "bold",
    marginBottom: 16,
  },
  text: {
    color: "#eee",
    fontSize: 16,
    marginBottom: 8,
  },
  muted: {
    color: "#888",
    fontSize: 14,
    textAlign: "center",
  },
});
