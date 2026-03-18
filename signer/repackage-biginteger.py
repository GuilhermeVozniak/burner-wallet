#!/usr/bin/env python3
"""
Repackage java.math.BigInteger to cldc.math.BigInteger in a JAR.

Nokia S40 / CLDC 1.1 KVM rejects classes in java.* packages loaded
from MIDlet JARs ("Cannot create class in system package"). This
script renames java/math → cldc/math in all class file bytecode
and file paths. Both strings are 9 bytes, so the replacement is
size-preserving and safe for constant pool entries.
"""
import sys
import zipfile

OLD = b'java/math'
NEW = b'cldc/math'


def process_jar(input_jar, output_jar):
    patched_classes = 0
    renamed_files = 0

    with zipfile.ZipFile(input_jar, 'r') as zin:
        with zipfile.ZipFile(output_jar, 'w',
                             compression=zipfile.ZIP_DEFLATED) as zout:
            for info in zin.infolist():
                raw = zin.read(info.filename)
                name = info.filename

                # Rename java/math/ paths to cldc/math/
                if name.startswith('java/math/'):
                    name = name.replace('java/math/', 'cldc/math/', 1)
                    renamed_files += 1

                # Patch class files: replace java/math → cldc/math
                if name.endswith('.class') and OLD in raw:
                    raw = raw.replace(OLD, NEW)
                    patched_classes += 1

                info2 = zipfile.ZipInfo(name)
                info2.compress_type = zipfile.ZIP_DEFLATED
                zout.writestr(info2, raw)

    print("Renamed %d files, patched %d classes" %
          (renamed_files, patched_classes))


if __name__ == '__main__':
    if len(sys.argv) != 3:
        print("Usage: %s <input.jar> <output.jar>" % sys.argv[0])
        sys.exit(1)
    process_jar(sys.argv[1], sys.argv[2])
