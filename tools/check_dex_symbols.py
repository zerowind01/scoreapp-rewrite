"""扫 dex 字符串池，确认关键符号真的打进了 APK。

用 Python 而不是 grep：dex 是二进制，字符串池里存的是 uleb128 前缀的 UTF-8，
直接 grep 二进制会漏（尤其符号被拆在相邻条目或带前缀长度字节时）。
这里老老实实按 dex 格式解析出 string_ids 表。
"""
import struct
import sys
import glob
import os


def read_uleb128(buf, pos):
    result = 0
    shift = 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            break
        shift += 7
    return result, pos


def dex_strings(path):
    with open(path, "rb") as f:
        buf = f.read()
    if buf[:4] not in (b"dex\n", b"cdex"):
        return []
    string_ids_size = struct.unpack_from("<I", buf, 0x38)[0]
    string_ids_off = struct.unpack_from("<I", buf, 0x3C)[0]
    out = []
    for i in range(string_ids_size):
        off = struct.unpack_from("<I", buf, string_ids_off + i * 4)[0]
        utf16_len, pos = read_uleb128(buf, off)
        # string_data_item 的 uleb128 是 **UTF-16 码元数**，不是字节数：
        # 中文这类多字节字符两者不等，按 n 取字节会把字符串截成乱码
        # （"编辑元数据" 会被读成 5 个字节），于是明明在包里却报 MISSING。
        # MUTF-8 以 \0 结尾，直接扫到终止符才是可靠的取法。
        end = buf.find(b"\x00", pos)
        if end < 0:
            end = min(pos + utf16_len, len(buf))
        raw = buf[pos:end]
        try:
            out.append(raw.decode("utf-8", errors="replace"))
        except Exception:
            pass
    return out


def main():
    dex_dir = sys.argv[1]
    symbols = sys.argv[2:]
    files = sorted(glob.glob(os.path.join(dex_dir, "classes*.dex")))
    all_strings = []
    for p in files:
        all_strings.extend(dex_strings(p))
    # 去重，减小比对量
    pool = set(all_strings)
    pool_joined = "\n".join(pool)

    print(f"扫描 {len(files)} 个 dex，字符串池共 {len(pool)} 条唯一字符串\n")
    print(f"{'符号':<26} {'字符串池':>8}  {'出现处'}")
    print("-" * 68)
    missing = []
    for sym in symbols:
        # 精确条目的数量 + 作为子串出现在别的字符串里的数量
        exact = sum(1 for s in pool if s == sym)
        # 类名形如 Lcom/example/scoreapp/util/PdfAssets;
        # 精确匹配会落空，所以也查「以该符号结尾的类描述符」
        as_class = sum(1 for s in pool if s.endswith(sym + ";") and s.startswith("L"))
        sub = pool_joined.count(sym)
        hits = max(exact, as_class)
        flag = "OK" if hits > 0 else "**MISSING**"
        if hits == 0:
            missing.append(sym)
        print(f"{sym:<26} {hits:>8}  {flag}  (含子串 {sub})")

    print()
    if missing:
        print("缺失符号：" + ", ".join(missing))
        return 1
    print("全部符号均在 APK 的 dex 中 ✅")
    return 0


if __name__ == "__main__":
    sys.exit(main())
