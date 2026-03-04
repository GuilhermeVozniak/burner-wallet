#!/bin/bash
# Hook: PreToolUse for Edit|Write on signer/ Java files
# Checks for Java 5+ language features that will break CLDC 1.1 compilation
# Exit 0 = proceed, Exit 2 = block with feedback

INPUT=$(cat)
FILE_PATH=$(echo "$INPUT" | jq -r '.tool_input.file_path // .tool_input.file // empty' 2>/dev/null)

# Only check Java files under signer/src/
if [[ ! "$FILE_PATH" =~ signer/src/.*\.java$ ]]; then
    exit 0
fi

NEW_CONTENT=$(echo "$INPUT" | jq -r '.tool_input.new_string // .tool_input.content // empty' 2>/dev/null)

if [ -z "$NEW_CONTENT" ]; then
    exit 0
fi

ERRORS=""

# Check for generics (angle brackets with types, but not comparisons)
if echo "$NEW_CONTENT" | grep -Pq '(List|Map|Set|Vector|Hashtable|ArrayList|HashMap|Collection|Enumeration|Iterator)<\w'; then
    ERRORS="${ERRORS}\n- JAVA 5+ VIOLATION: Generics detected. Use raw types (Vector, Hashtable) without type parameters."
fi

# Check for enhanced for-loop
if echo "$NEW_CONTENT" | grep -Pq 'for\s*\(\s*\w+\s+\w+\s*:\s*'; then
    ERRORS="${ERRORS}\n- JAVA 5+ VIOLATION: Enhanced for-loop detected. Use indexed for-loop: for (int i = 0; i < v.size(); i++)"
fi

# Check for @Override annotation
if echo "$NEW_CONTENT" | grep -q '@Override'; then
    ERRORS="${ERRORS}\n- JAVA 5+ VIOLATION: @Override annotation detected. Remove it (not supported in Java 1.4)."
fi

# Check for StringBuilder (should use StringBuffer)
if echo "$NEW_CONTENT" | grep -q 'StringBuilder'; then
    ERRORS="${ERRORS}\n- JAVA 5+ VIOLATION: StringBuilder detected. Use StringBuffer instead."
fi

# Check for String.format
if echo "$NEW_CONTENT" | grep -q 'String\.format'; then
    ERRORS="${ERRORS}\n- JAVA 5+ VIOLATION: String.format() detected. Use string concatenation."
fi

# Check for String.isEmpty
if echo "$NEW_CONTENT" | grep -q '\.isEmpty()'; then
    ERRORS="${ERRORS}\n- JAVA 6+ VIOLATION: .isEmpty() detected. Use .length() == 0 instead."
fi

# Check for varargs
if echo "$NEW_CONTENT" | grep -Pq '\w+\s*\.\.\.\s*\w+'; then
    ERRORS="${ERRORS}\n- JAVA 5+ VIOLATION: Varargs detected. Use array parameters instead."
fi

# Check for enum keyword (as class declaration)
if echo "$NEW_CONTENT" | grep -Pq '^\s*(public\s+|private\s+|protected\s+)?(static\s+)?enum\s+'; then
    ERRORS="${ERRORS}\n- JAVA 5+ VIOLATION: enum detected. Use public static final int constants."
fi

# Check for autoboxing patterns
if echo "$NEW_CONTENT" | grep -Pq '(Integer|Long|Boolean|Byte|Short|Float|Double)\s+\w+\s*=\s*[0-9]'; then
    ERRORS="${ERRORS}\n- JAVA 5+ VIOLATION: Possible autoboxing. Use new Integer(val) explicitly."
fi

# Check for try-with-resources
if echo "$NEW_CONTENT" | grep -Pq 'try\s*\('; then
    ERRORS="${ERRORS}\n- JAVA 7+ VIOLATION: Try-with-resources detected. Use explicit try/finally blocks."
fi

# Check for diamond operator
if echo "$NEW_CONTENT" | grep -Pq 'new\s+\w+<>'; then
    ERRORS="${ERRORS}\n- JAVA 7+ VIOLATION: Diamond operator detected. Not applicable (no generics in Java 1.4)."
fi

# Check for lambda expressions
if echo "$NEW_CONTENT" | grep -Pq '\(\s*\w*\s*\)\s*->|\w+\s*->'; then
    ERRORS="${ERRORS}\n- JAVA 8+ VIOLATION: Lambda expression detected. Use anonymous inner classes."
fi

# Check for networking imports (air gap violation)
if echo "$NEW_CONTENT" | grep -q 'javax\.microedition\.io\.\|HttpConnection\|SocketConnection'; then
    ERRORS="${ERRORS}\n- AIR GAP VIOLATION: Networking import detected. The signer must NEVER access the network."
fi

if [ -n "$ERRORS" ]; then
    echo -e "Java ME Constraint Violations in $FILE_PATH:$ERRORS" >&2
    exit 2
fi

exit 0
