#!/usr/bin/env python3
"""APK/AAB ichidagi .so fayllar 16 KB sahifa hajmiga mosligini tekshiradi (Play talabi).

Ishlatish:  python3 tools/check_16kb.py app/build/outputs/apk/debug/app-debug.apk
Chiqish kodi: 0 = hammasi mos, 1 = mos kelmaydigan kutubxona bor.
Faqat Python standart kutubxonasi kerak (Termux'da ham ishlaydi).
"""
import struct
import sys
import zipfile

PT_LOAD = 1
NEED = 16384


def check_elf(data):
    if data[:4] != b"\x7fELF" or data[4] != 2:       # faqat 64-bit
        return None
    endian = "<" if data[5] == 1 else ">"
    e_phoff = struct.unpack_from(endian + "Q", data, 0x20)[0]
    e_phentsize, e_phnum = struct.unpack_from(endian + "HH", data, 0x36)
    worst = None
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type = struct.unpack_from(endian + "I", data, off)[0]
        if p_type != PT_LOAD:
            continue
        p_align = struct.unpack_from(endian + "Q", data, off + 0x30)[0]
        worst = p_align if worst is None else min(worst, p_align)
    return worst


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    bad = 0
    total = 0
    with zipfile.ZipFile(sys.argv[1]) as z:
        for name in sorted(z.namelist()):
            if not name.endswith(".so") or "arm64" not in name and "x86_64" not in name:
                continue
            total += 1
            align = check_elf(z.read(name))
            ok = align is not None and align >= NEED
            if not ok:
                bad += 1
            print(("OK      " if ok else "MOS EMAS"), name, "align=%s" % align)
    print("-" * 50)
    print("Jami 64-bit .so: %d, mos kelmaydigan: %d" % (total, bad))
    if bad:
        print("DIQQAT: Play 16 KB ni talab qiladi. Bu kutubxonalarni yangilang.")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
