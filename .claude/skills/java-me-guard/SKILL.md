---
name: java-me-guard
description: Enforces Java ME constraints when writing code in signer/. Prevents Java 5+ language features, validates CLDC 1.1 and MIDP 2.0 API usage, and monitors JAR size. Use whenever editing or creating Java files under signer/src/ or signer/test/.
---

# Java ME Constraint Enforcer

You are working in an **extremely constrained** Java ME environment targeting the Nokia C1-01 feature phone. Every line of code must be compatible with CLDC 1.1 / MIDP 2.0 and compile with `javac -source 1.4 -target 1.4`.

## Hard Constraints — Never Violate These

### Language Level: Java 1.4 Only

**FORBIDDEN** Java 5+ features (will cause compilation failure):

| Feature | Java Version | What to Write Instead |
|---------|-------------|----------------------|
| Generics `List<String>` | 5 | `Vector` / raw types |
| Enhanced for-loop `for (X x : list)` | 5 | `for (int i = 0; i < v.size(); i++)` |
| Autoboxing `Integer i = 5` | 5 | `Integer i = new Integer(5)` |
| Varargs `foo(String... args)` | 5 | `foo(String[] args)` |
| Enums `enum` | 5 | `public static final int` constants |
| Annotations `@Override` | 5 | Remove annotation |
| Static imports | 5 | Use fully qualified names |
| `StringBuilder` | 5 | `StringBuffer` |
| `String.format()` | 5 | Manual concatenation |
| `Arrays.copyOf()` | 6 | `System.arraycopy()` |
| Try-with-resources | 7 | Explicit `finally` blocks |
| Diamond operator `<>` | 7 | N/A (no generics) |
| Lambda expressions | 8 | Anonymous inner classes |
| `Optional` | 8 | Null checks |

### Collections Available

Only these are available under CLDC 1.1:
- `java.util.Vector` (not ArrayList)
- `java.util.Hashtable` (not HashMap)
- `java.util.Stack`
- `java.util.Enumeration` (not Iterator)

### String Operations

- No `String.isEmpty()` — use `s.length() == 0`
- No `String.contains()` — use `s.indexOf(sub) >= 0`
- No `String.split()` — write manual tokenizer
- No `StringBuilder` — use `StringBuffer`
- No `String.format()` — use concatenation

### I/O and Streams

- No `java.io.Reader/Writer` (limited subset)
- No `java.nio.*` (does not exist)
- No `BufferedReader` for text — use `InputStream` with byte arrays
- Storage: `javax.microedition.rms.RecordStore` only

### Numeric Types

- `BigInteger` is available (we include `rt.jar` for this)
- No `BigDecimal`
- Be careful with `long` — some CLDC implementations have quirks

### Crypto

- Bouncy Castle `bcprov-jdk14` only (injected via ProGuard, not as library jar)
- Use `org.bouncycastle.crypto.*` API, not JCE providers
- No `javax.crypto.*` (not in CLDC)

## JAR Size Budget

- **Hard limit:** 1,048,576 bytes (1 MB)
- **Current size:** ~286 KB
- **Budget remaining:** ~738 KB
- Every class you add costs bytes. Prefer extending existing classes over creating new ones.
- Run `make size-check` after any structural changes.

## Test Code Exceptions

Files under `signer/test/` compile with `-source 1.8` and run on desktop JDK 8. Tests MAY use:
- `@Override` annotation
- Enhanced for-loops
- Generics
- `String.format()`
- `assertEquals`, `assertTrue`, etc. (JUnit 4)

Tests MUST NOT use:
- Lambdas (keep tests readable for Java ME developers)
- Streams API
- Try-with-resources (keep test patterns close to production code)

## Build Verification

After writing signer code, always verify:

```bash
# Compile + run tests (153 tests must pass)
cd signer && JAVA_HOME=$SIGNER_JAVA ant test

# Check JAR size
make size-check
```

## Common Mistakes to Avoid

1. **Forgetting `finally` blocks** — CLDC has no try-with-resources
2. **Using `System.out.println`** — Not available on device. Use custom logging or remove
3. **Assuming UTF-8** — Use explicit encoding: `new String(bytes, "UTF-8")`
4. **Large string literals** — The 2048-word BIP39 wordlist is already in Bip39Wordlist.java; don't duplicate
5. **Thread safety** — MIDP is single-threaded for UI; background tasks need `Thread` + callbacks
