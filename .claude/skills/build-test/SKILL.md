---
name: build-test
description: Build and test the burner-wallet monorepo. Runs signer (Java ME / Ant) and companion (Rust / Cargo) test suites. Use after making code changes to verify correctness, or when the user asks to run tests, build, or check the project.
---

# Build & Test Runner

This monorepo has two independent build systems. Run the appropriate one(s) based on what changed.

## Quick Reference

| What Changed | Command | Expected |
|-------------|---------|----------|
| `signer/**` | `make test-signer` | 153 tests pass |
| `companion/core/**` | `make test-companion-core` | 32 tests pass |
| `companion/tui/**` | `make test-companion-tui` | All pass |
| Everything | `make test` | All suites pass |
| JAR size concern | `make size-check` | < 1,048,576 bytes |
| Rust style | `cd companion/core && cargo clippy -- -D warnings` | Zero warnings |
| Rust format | `cd companion/core && cargo fmt -- --check` | No changes needed |

## Signer Build Details

The signer requires **JDK 8** (Zulu or Temurin). The Makefile hardcodes:
```
SIGNER_JAVA = /Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home
```

If that path doesn't exist, check for alternatives:
```bash
# Find JDK 8 installations
ls /Library/Java/JavaVirtualMachines/ 2>/dev/null
/usr/libexec/java_home -V 2>&1 | grep 1.8
```

To run signer tests directly:
```bash
cd signer && JAVA_HOME=/path/to/jdk8 ant test
```

The Ant build (`signer/build.xml`) does:
1. Compile source with `-source 1.4 -target 1.4` against CLDC/MIDP stubs
2. Compile tests with `-source 1.8 -target 1.8` (desktop JDK)
3. Run JUnit tests on desktop JDK
4. ProGuard shrink + preverify for JAD/JAR output

## Companion Build Details

Standard Rust toolchain (stable, edition 2021):
```bash
cd companion/core && cargo test        # Unit + integration tests
cd companion/core && cargo clippy -- -D warnings  # Lint (CI-enforced)
cd companion/core && cargo fmt -- --check          # Format check
```

## Full Suite

```bash
make test   # Runs everything
```

## Troubleshooting

**Signer build fails with "javac not found":**
- Set `JAVA_HOME` to a JDK 8 installation
- On macOS: `export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)`
- On Linux CI: Temurin JDK 8 (`apt install temurin-8-jdk`)

**ProGuard errors:**
- Ensure `tools/proguard` symlink points to `tools/proguard-7.8.2/`
- Run `make setup-tools` if missing

**Cargo test fails with missing dependency:**
- Run `cargo update` in `companion/core/`
- Check `Cargo.lock` is committed

**Size check fails:**
- Current: ~286 KB. Budget: 1 MB.
- Review ProGuard config in `signer/build.xml` for tree-shaking optimization
- Consider removing unused Bouncy Castle classes

## What to Run When

After editing `signer/src/`:
```bash
cd signer && JAVA_HOME=$SIGNER_JAVA ant test && make size-check
```

After editing `companion/core/src/`:
```bash
cd companion/core && cargo test && cargo clippy -- -D warnings
```

After editing `protocol/vectors/`:
```bash
make test  # Both implementations must agree
```

Before opening a PR:
```bash
make test && make lint && make size-check
```
