// =============================================================================
// Burner Wallet Companion - Renderer UI Logic
// All crypto operations are delegated to window.burnerAPI (preload bridge).
// =============================================================================

(function () {
  "use strict";

  // -------------------------------------------------------------------------
  // State
  // -------------------------------------------------------------------------
  var currentNetwork = "testnet";
  var walletMnemonic = null;
  var walletSeed = null;
  var walletAddress = null;

  // -------------------------------------------------------------------------
  // DOM helpers
  // -------------------------------------------------------------------------
  function $(id) { return document.getElementById(id); }
  function show(el) { el.classList.remove("hidden"); }
  function hide(el) { el.classList.add("hidden"); }

  function showToast(msg) {
    var toast = $("copy-toast");
    toast.textContent = msg;
    toast.classList.add("show");
    setTimeout(function () { toast.classList.remove("show"); }, 1500);
  }

  function setStatus(id, msg, type) {
    var el = $(id);
    if (!el) return;
    el.className = "status status-" + type;
    el.textContent = msg;
  }

  // -------------------------------------------------------------------------
  // Wallet operations (delegate crypto to preload bridge)
  // -------------------------------------------------------------------------
  async function loadWallet(mnemonic) {
    walletMnemonic = mnemonic;
    walletSeed = await window.burnerAPI.mnemonicToSeed(mnemonic, "");
    walletAddress = window.burnerAPI.deriveAddress(walletSeed, currentNetwork, 0, 0);

    $("dash-network").textContent = currentNetwork;
    $("dash-mnemonic").textContent = mnemonic;
    $("dash-mnemonic").classList.add("mnemonic-hidden");
    $("dash-address").textContent = walletAddress;
    $("dash-balance").textContent = "-- sats";
    $("dash-balance-detail").textContent = "";
    setStatus("sync-status", "", "info");

    hide($("setup-section"));
    show($("dashboard-section"));
  }

  async function syncBalance() {
    if (!walletAddress) return;
    setStatus("sync-status", "Syncing...", "info");
    $("btn-sync").disabled = true;
    try {
      var data = await window.burnerAPI.fetchBalance(walletAddress, currentNetwork);
      var total = data.confirmed + data.unconfirmed;
      $("dash-balance").textContent = total.toLocaleString() + " sats";
      var parts = ["Confirmed: " + data.confirmed.toLocaleString() + " sats"];
      if (data.unconfirmed !== 0) {
        parts.push("Unconfirmed: " + data.unconfirmed.toLocaleString() + " sats");
      }
      $("dash-balance-detail").textContent = parts.join(" | ");
      setStatus("sync-status", "Synced at " + new Date().toLocaleTimeString(), "ok");
    } catch (e) {
      setStatus("sync-status", "Sync failed: " + e.message, "err");
    } finally {
      $("btn-sync").disabled = false;
    }
  }

  function clearWallet() {
    if (!confirm("Clear wallet from memory? Make sure you have your mnemonic backed up.")) return;
    walletMnemonic = null;
    walletSeed = null;
    walletAddress = null;
    hide($("dashboard-section"));
    show($("setup-section"));
    $("gen-mnemonic-text").textContent = "";
    hide($("generated-mnemonic"));
    $("import-mnemonic").value = "";
    setStatus("import-status", "", "info");
  }

  // -------------------------------------------------------------------------
  // Event handlers
  // -------------------------------------------------------------------------
  function initUI() {
    // Network selector
    document.querySelectorAll(".net-btn").forEach(function (btn) {
      btn.addEventListener("click", async function () {
        document.querySelectorAll(".net-btn").forEach(function (b) {
          b.classList.remove("active");
        });
        this.classList.add("active");
        currentNetwork = this.dataset.net;
        if (walletMnemonic) {
          walletAddress = window.burnerAPI.deriveAddress(walletSeed, currentNetwork, 0, 0);
          $("dash-network").textContent = currentNetwork;
          $("dash-address").textContent = walletAddress;
          $("dash-balance").textContent = "-- sats";
          $("dash-balance-detail").textContent = "";
          setStatus("sync-status", "Network changed. Sync to update balance.", "info");
        }
      });
    });

    // Tab switching
    document.querySelectorAll(".tab-btn").forEach(function (btn) {
      btn.addEventListener("click", function () {
        document.querySelectorAll(".tab-btn").forEach(function (b) {
          b.classList.remove("active");
        });
        this.classList.add("active");
        var tab = this.dataset.tab;
        if (tab === "create") { show($("tab-create")); hide($("tab-import")); }
        else { hide($("tab-create")); show($("tab-import")); }
      });
    });

    // Generate mnemonic
    $("btn-generate").addEventListener("click", async function () {
      this.disabled = true;
      this.textContent = "Generating...";
      try {
        var wordCount = parseInt($("word-count").value);
        var mnemonic = window.burnerAPI.generateMnemonic(wordCount);
        $("gen-mnemonic-text").textContent = mnemonic;
        show($("generated-mnemonic"));
      } catch (e) {
        alert("Error: " + e.message);
      } finally {
        this.disabled = false;
        this.textContent = "Generate Mnemonic";
      }
    });

    // Use generated mnemonic
    $("btn-use-generated").addEventListener("click", async function () {
      var mnemonic = $("gen-mnemonic-text").textContent;
      if (!mnemonic) return;
      this.disabled = true;
      this.textContent = "Loading...";
      try {
        await loadWallet(mnemonic);
      } catch (e) {
        alert("Error: " + e.message);
      } finally {
        this.disabled = false;
        this.textContent = "Use This Mnemonic";
      }
    });

    // Import mnemonic
    $("btn-import").addEventListener("click", async function () {
      var phrase = $("import-mnemonic").value.trim().toLowerCase();
      if (!phrase) {
        setStatus("import-status", "Please enter a mnemonic phrase.", "err");
        return;
      }
      this.disabled = true;
      this.textContent = "Validating...";
      try {
        var valid = window.burnerAPI.validateMnemonic(phrase);
        if (!valid) {
          setStatus("import-status", "Invalid mnemonic. Check spelling and word count.", "err");
          return;
        }
        setStatus("import-status", "Valid mnemonic. Loading wallet...", "ok");
        await loadWallet(phrase);
      } catch (e) {
        setStatus("import-status", "Error: " + e.message, "err");
      } finally {
        this.disabled = false;
        this.textContent = "Import Wallet";
      }
    });

    // Mnemonic reveal/hide toggle
    $("dash-mnemonic").addEventListener("click", function () {
      this.classList.toggle("mnemonic-hidden");
    });

    // Copy address
    $("dash-address").addEventListener("click", function () {
      if (walletAddress) {
        navigator.clipboard.writeText(walletAddress);
        showToast("Address copied!");
      }
    });
    $("btn-copy-addr").addEventListener("click", function () {
      if (walletAddress) {
        navigator.clipboard.writeText(walletAddress);
        showToast("Address copied!");
      }
    });

    // Copy generated mnemonic
    $("gen-mnemonic-text").addEventListener("click", function () {
      if (this.textContent) {
        navigator.clipboard.writeText(this.textContent);
        showToast("Mnemonic copied!");
      }
    });

    // Sync balance
    $("btn-sync").addEventListener("click", syncBalance);

    // Clear wallet
    $("btn-clear-wallet").addEventListener("click", clearWallet);
  }

  // Start
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", initUI);
  } else {
    initUI();
  }
})();
