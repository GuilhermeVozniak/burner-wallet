# Security Policy

## Project Status

**This software is pre-alpha and must not be used with real Bitcoin.** Use testnet only until the project explicitly reaches mainnet-ready status.

## Supported Versions

During pre-alpha, only the latest version on `main` is supported with security updates.

| Version | Supported |
|---------|-----------|
| `main` (HEAD) | Yes |
| All others | No |

## Reporting a Vulnerability

**Do not open public issues for security vulnerabilities.**

### How to Report

Use [GitHub Private Vulnerability Reporting](https://github.com/GuilhermeVozniak/burner-wallet/security/advisories/new):

1. Go to the **Security** tab of this repository
2. Click **Report a vulnerability**
3. Fill in the advisory form with:
   - Description of the vulnerability
   - Steps to reproduce
   - Affected component(s): signer, companion, protocol, or build/release
   - Potential impact assessment
   - Suggested fix (if any)

### Response Timeline

| Action | Target |
|--------|--------|
| Acknowledgment of report | Within 48 hours |
| Initial assessment | Within 7 days |
| Fix or mitigation plan | Within 30 days |
| Public disclosure | After fix is released, coordinated with reporter |

### Scope

The following are considered security vulnerabilities:

- Private key leakage or exposure
- Seed/mnemonic leakage
- Bypass of PIN or passphrase protection
- Weaknesses in key derivation or entropy generation
- PSBT signing producing incorrect or manipulable transactions
- Transport protocol vulnerabilities that could leak signing data
- Build/release pipeline compromise vectors
- Dependency vulnerabilities with exploitable impact

The following are **not** in scope:

- Social engineering or phishing attacks on end users
- Physical attacks requiring disassembly of the Nokia device
- Denial of service on the companion app
- Bugs that only affect testnet operation with no mainnet implications

## Security Design Principles

- The signer device never connects to any IP network
- The signer never trusts the companion beyond PSBT structure
- All signing keys remain exclusively on the signer device
- Cryptographic operations use audited libraries (Bouncy Castle Lightweight API)
- Deterministic signing (RFC 6979) prevents nonce reuse
- Seeds are encrypted at rest with PIN-derived keys
- Reproducible builds enable independent verification of released artifacts
