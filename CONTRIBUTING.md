# Contributing to Burner Wallet

Thank you for your interest in contributing. This document explains the process and expectations.

## Developer Certificate of Origin (DCO)

This project uses the [Developer Certificate of Origin](https://developercertificate.org/) (DCO). All commits must include a `Signed-off-by` trailer certifying that you have the right to submit the contribution under the project's MIT license.

```bash
git commit -s -m "feat(signer): implement BIP39 mnemonic generation"
```

This adds a line like:

```
Signed-off-by: Your Name <your@email.com>
```

If you forget, you can amend your last commit:

```bash
git commit --amend -s
```

CI will reject commits without a valid sign-off.

## Getting Started

1. Fork the repository
2. Clone your fork: `git clone https://github.com/<your-user>/burner-wallet.git`
3. Create a feature branch: `git checkout -b feat/my-feature`
4. Set up your development environment (see [README](README.md#prerequisites))

## Branching Strategy

We use **GitHub Flow**:

- `main` is always releasable and protected
- All changes go through pull requests
- Squash merge is the default merge strategy

### Branch Naming

| Prefix | Purpose |
|--------|---------|
| `feat/` | New features |
| `fix/` | Bug fixes |
| `docs/` | Documentation changes |
| `chore/` | Tooling, CI, dependencies |
| `security/` | Security-related changes |
| `test/` | Test additions or fixes |
| `refactor/` | Code restructuring |

## Commit Conventions

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <description>

[optional body]

[optional footer(s)]
Signed-off-by: Your Name <your@email.com>
```

### Types

`feat`, `fix`, `docs`, `test`, `chore`, `security`, `refactor`, `perf`, `ci`

### Scopes

`signer`, `companion-core`, `companion-tui`, `companion-web`, `companion-desktop`, `companion-mobile`, `companion-extension`, `protocol`, `crypto`, `storage`, `transport`, `ci`, `docs`, `spec`

### Examples

```
feat(signer): implement BIP39 mnemonic generation
fix(crypto): correct secp256k1 point multiplication edge case
docs(spec): add PSBT transport encoding detail
test(vectors): add BIP32 derivation test suite
chore(ci): configure Java ME SDK in build container
security(storage): harden key derivation parameters
```

## Pull Request Process

1. Ensure your branch is up to date with `main`
2. Run all relevant checks locally:
   ```bash
   make lint
   make test
   ```
3. Open a PR against `main` with a clear title and description
4. Fill in the PR template completely
5. Wait for CI to pass and a maintainer to review
6. Address review feedback
7. Once approved, a maintainer will squash merge

### PR Requirements

- [ ] All commits are signed off (DCO)
- [ ] CI passes
- [ ] Tests added or updated for the change
- [ ] Documentation updated if behavior changes
- [ ] No unrelated changes included

## Code Style

### Java ME (Signer)

- Standard Java conventions adapted for CLDC 1.1 / MIDP 2.0
- **No Java 5+ features**: no generics, no autoboxing, no enhanced for-loop, no varargs
- All classes must compile against the CLDC 1.1 class library
- Run `ant check` in `signer/` to validate

### Rust (Companion Core + TUI)

- Format with `rustfmt`
- Lint with `clippy` (warnings are errors in CI)
- Follow the [Rust API Guidelines](https://rust-lang.github.io/api-guidelines/)

### TypeScript (Web, Desktop, Extension, Mobile)

- ESLint + Prettier (configs at companion workspace root)
- Strict TypeScript (`strict: true`)

## Security-Sensitive Changes

If your change touches cryptography, key storage, signing logic, or transport encoding:

1. Add a `security` label to your PR
2. Include a brief security analysis in the PR description
3. Ensure deterministic test vectors cover the change
4. Expect a more thorough review process

## Reporting Bugs

Use the [bug report template](https://github.com/GuilhermeVozniak/burner-wallet/issues/new?template=bug_report.yml) on GitHub Issues.

## Requesting Features

Use the [feature request template](https://github.com/GuilhermeVozniak/burner-wallet/issues/new?template=feature_request.yml) on GitHub Issues.

## Reporting Security Vulnerabilities

**Do not open public issues for security vulnerabilities.** Use [GitHub Private Vulnerability Reporting](https://github.com/GuilhermeVozniak/burner-wallet/security/advisories/new). See [SECURITY.md](SECURITY.md) for details.

## Questions?

Open a [discussion](https://github.com/GuilhermeVozniak/burner-wallet/discussions) or reach out in an existing issue thread.
