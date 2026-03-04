---
name: size-monitor
description: Monitor and optimize the signer JAR size to stay under the 1 MB budget. Use when adding new classes or dependencies to signer/, or when the user is concerned about JAR size.
---

# Signer JAR Size Monitor

The signer MIDlet must fit in a 1 MB JAR (1,048,576 bytes) to run on the Nokia C1-01. This includes all application classes AND the tree-shaken Bouncy Castle crypto library.

## Current Budget

| Component | Size | Notes |
|-----------|------|-------|
| Application classes | ~50 KB | 27 Java source files |
| Bouncy Castle (pruned) | ~236 KB | After ProGuard tree-shaking |
| **Total JAR** | **~286 KB** | Well under budget |
| **Budget remaining** | **~738 KB** | Available for M1c-M1e features |

## Check Current Size

```bash
make size-check
# Or manually:
ls -la signer/dist/BurnerWallet.jar 2>/dev/null
```

## Size Optimization Strategies

### ProGuard Configuration

The build uses ProGuard 7.8.2 with `-microedition`. Key settings in `signer/build.xml`:

- Bouncy Castle is injected as `-injars` (not `-libraryjars`) for aggressive tree-shaking
- `-keep class org.burnerwallet.**` preserves all app classes
- `-keep class * extends javax.microedition.midlet.MIDlet` preserves the entry point
- Unused BC classes are stripped automatically

### If Size Grows Too Large

1. **Check ProGuard output** for which BC classes survived:
   ```bash
   jar tf signer/dist/BurnerWallet.jar | grep bouncycastle | wc -l
   ```

2. **Review unused imports** in signer source that pull in BC classes:
   ```bash
   grep -r "import org.bouncycastle" signer/src/ | sort -u
   ```

3. **Consider** splitting features across MIDlet suites (last resort)

4. **Avoid** adding new third-party libraries — every dependency goes through ProGuard

### Size Impact Estimates for M1c-M1e

| Feature | Estimated Size Impact |
|---------|----------------------|
| PSBT parser | +15-25 KB |
| BIP143 sighash | +5-10 KB |
| QR code generator | +30-50 KB (depends on library) |
| QR code reader (camera) | +20-40 KB |
| Transaction serializer | +5-10 KB |
| **Total M1c-M1e estimate** | **+75-135 KB** |
| **Projected total** | **~360-420 KB** |

Even with all planned features, we should stay well under 1 MB.

## When to Run Size Check

- After adding any new Java source file to `signer/src/`
- After adding new Bouncy Castle imports
- After modifying ProGuard configuration
- Before every PR that touches `signer/`
- As part of CI (automated in `make test`)
