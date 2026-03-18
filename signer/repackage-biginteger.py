#!/usr/bin/env python3
"""
Post-process a ProGuard-output JAR for CLDC 1.1 / Nokia S40 compatibility.

Fixes applied to every class file:
  1. Rename java/math -> cldc/math (CLDC KVM rejects java.* in MIDlet JARs)
  2. Replace java.lang.Number superclass with java.lang.Object (Number
     does not exist in CLDC 1.1)
  3. Redirect Integer.valueOf(int) -> Integers.valueOf(int) (Integer.valueOf
     was added in Java 5, not available in CLDC 1.1; BC's Integers.valueOf
     uses the CLDC-safe `new Integer(int)` constructor)
  4. Downgrade class file version from 52 (Java 8) to 48 (Java 1.4)
"""
import struct
import sys
import zipfile

# --- Constants ---

OLD_PKG = b'java/math'
NEW_PKG = b'cldc/math'

# Exact UTF8 CP entry for Number (tag + length + value) to avoid
# corrupting java/lang/NumberFormatException
OLD_SUPER_ENTRY = b'\x01\x00\x10java/lang/Number'
NEW_SUPER_ENTRY = b'\x01\x00\x10java/lang/Object'

TARGET_VERSION = 48  # Java 1.4

# CP tag constants
TAG_UTF8 = 1
TAG_CLASS = 7
TAG_METHODREF = 10
TAG_NAME_AND_TYPE = 12


def parse_cp(data):
    """Parse the constant pool. Returns (entries, cp_end_offset).

    Each entry is a tuple:
      ('Utf8', offset, value_str)
      ('Class', offset, name_index)
      ('Methodref', offset, class_index, nat_index)
      ('NameAndType', offset, name_index, desc_index)
      ('Other', offset)
    Index 0 is unused (None).
    """
    cp_count = struct.unpack_from('>H', data, 8)[0]
    offset = 10
    entries = [None]
    i = 1
    while i < cp_count:
        tag = data[offset]
        if tag == TAG_UTF8:
            length = struct.unpack_from('>H', data, offset + 1)[0]
            val = data[offset + 3:offset + 3 + length].decode(
                'utf-8', errors='replace')
            entries.append(('Utf8', offset, val))
            offset += 3 + length
        elif tag == TAG_CLASS:
            ni = struct.unpack_from('>H', data, offset + 1)[0]
            entries.append(('Class', offset, ni))
            offset += 3
        elif tag == TAG_METHODREF:
            ci = struct.unpack_from('>H', data, offset + 1)[0]
            ni = struct.unpack_from('>H', data, offset + 3)[0]
            entries.append(('Methodref', offset, ci, ni))
            offset += 5
        elif tag == TAG_NAME_AND_TYPE:
            ni = struct.unpack_from('>H', data, offset + 1)[0]
            di = struct.unpack_from('>H', data, offset + 3)[0]
            entries.append(('NameAndType', offset, ni, di))
            offset += 5
        elif tag in (3, 4):  # Integer, Float
            entries.append(('Other', offset))
            offset += 5
        elif tag in (5, 6):  # Long, Double
            entries.append(('Other', offset))
            entries.append(None)
            i += 1
            offset += 9
        elif tag == 8:  # String
            entries.append(('Other', offset))
            offset += 3
        elif tag in (9, 11):  # Fieldref, InterfaceMethodref
            entries.append(('Other', offset))
            offset += 5
        elif tag == 15:  # MethodHandle
            entries.append(('Other', offset))
            offset += 4
        elif tag == 16:  # MethodType
            entries.append(('Other', offset))
            offset += 3
        elif tag == 18:  # InvokeDynamic
            entries.append(('Other', offset))
            offset += 5
        else:
            # Unknown tag; stop parsing
            break
        i += 1
    return entries, cp_count, offset


def get_utf8(entries, idx):
    """Get the string value of a Utf8 constant pool entry."""
    e = entries[idx] if idx < len(entries) else None
    if e and e[0] == 'Utf8':
        return e[2]
    return None


def fix_integer_valueof(data, entries, cp_count, cp_end):
    """Redirect Integer.valueOf(I) -> Integers.valueOf(I).

    Finds the Methodref for java/lang/Integer.valueOf:(I)Ljava/lang/Integer;
    and changes its class_index to point to org/bouncycastle/util/Integers.

    If the Integers class isn't in the constant pool, adds new entries.
    Returns the (possibly modified) data.
    """
    # Find all Methodref entries for Integer.valueOf
    valueof_mrefs = []
    for i, e in enumerate(entries):
        if e and e[0] == 'Methodref':
            class_e = entries[e[2]] if e[2] < len(entries) else None
            if class_e and class_e[0] == 'Class':
                cname = get_utf8(entries, class_e[2])
                if cname == 'java/lang/Integer':
                    nat_e = entries[e[3]] if e[3] < len(entries) else None
                    if nat_e and nat_e[0] == 'NameAndType':
                        mname = get_utf8(entries, nat_e[2])
                        mdesc = get_utf8(entries, nat_e[3])
                        if (mname == 'valueOf' and
                                mdesc == '(I)Ljava/lang/Integer;'):
                            valueof_mrefs.append(i)

    if not valueof_mrefs:
        return data

    data = bytearray(data)

    # Find existing Class_info for Integers
    integers_class_idx = None
    for i, e in enumerate(entries):
        if e and e[0] == 'Class':
            cname = get_utf8(entries, e[2])
            if cname == 'org/bouncycastle/util/Integers':
                integers_class_idx = i
                break

    if integers_class_idx is None:
        # Add new UTF8 + Class entries at end of constant pool
        utf8_val = b'org/bouncycastle/util/Integers'
        new_utf8_entry = struct.pack('>BH', TAG_UTF8,
                                     len(utf8_val)) + utf8_val
        new_class_entry = struct.pack('>BH', TAG_CLASS, cp_count)
        new_cp_count = cp_count + 2
        integers_class_idx = cp_count + 1

        # Insert before cp_end
        data = (data[:cp_end] + bytearray(new_utf8_entry) +
                bytearray(new_class_entry) + data[cp_end:])
        # Update CP count
        struct.pack_into('>H', data, 8, new_cp_count)

        # Re-parse to get updated entries and offsets
        entries, cp_count, cp_end = parse_cp(data)

    # Patch each valueOf Methodref to point to Integers
    for mref_idx in valueof_mrefs:
        e = entries[mref_idx]
        struct.pack_into('>H', data, e[1] + 1, integers_class_idx)

    return bytes(data)


def downgrade_version(data):
    """Downgrade class file major version to TARGET_VERSION if higher."""
    major = struct.unpack_from('>H', data, 6)[0]
    if major > TARGET_VERSION:
        data = bytearray(data)
        struct.pack_into('>H', data, 6, TARGET_VERSION)
        return bytes(data), True
    return data, False


def patch_class(raw):
    """Apply all CLDC patches to a single class file."""
    changed = False

    # 1. Repackage java/math -> cldc/math
    if OLD_PKG in raw:
        raw = raw.replace(OLD_PKG, NEW_PKG)
        changed = True

    # 2. Replace Number superclass with Object
    if OLD_SUPER_ENTRY in raw:
        raw = raw.replace(OLD_SUPER_ENTRY, NEW_SUPER_ENTRY)
        changed = True

    # 3. Redirect Integer.valueOf -> Integers.valueOf
    entries, cp_count, cp_end = parse_cp(raw)
    raw2 = fix_integer_valueof(raw, entries, cp_count, cp_end)
    if raw2 is not raw:
        raw = raw2
        changed = True

    # 4. Downgrade class version
    raw, did_downgrade = downgrade_version(raw)
    if did_downgrade:
        changed = True

    return raw, changed


def process_jar(input_jar, output_jar):
    patched = 0
    renamed = 0

    with zipfile.ZipFile(input_jar, 'r') as zin:
        with zipfile.ZipFile(output_jar, 'w',
                             compression=zipfile.ZIP_DEFLATED) as zout:
            for info in zin.infolist():
                raw = zin.read(info.filename)
                name = info.filename

                # Rename java/math/ paths to cldc/math/
                if name.startswith('java/math/'):
                    name = name.replace('java/math/', 'cldc/math/', 1)
                    renamed += 1

                if name.endswith('.class'):
                    raw, changed = patch_class(raw)
                    if changed:
                        patched += 1

                info2 = zipfile.ZipInfo(name)
                info2.compress_type = zipfile.ZIP_DEFLATED
                zout.writestr(info2, raw)

    print("Renamed %d files, patched %d classes" % (renamed, patched))


if __name__ == '__main__':
    if len(sys.argv) != 3:
        print("Usage: %s <input.jar> <output.jar>" % sys.argv[0])
        sys.exit(1)
    process_jar(sys.argv[1], sys.argv[2])
