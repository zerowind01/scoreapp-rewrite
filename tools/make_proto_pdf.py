"""
生成原型内置 PDF 的字节码（base64），用于 scoreapp-prototype.html。

为什么要有这个脚本：
原型的 BUNDLED_PDF 原本只有一个 size 占位，FILE_STORE 里登记了路径却没有真实字节，
于是「封面渲染文件首页」这条规格在 HTML 侧无法真跑——只能声称。
这里造一份**结构完整、可被解析器解析**的最小 PDF，第 0 页画的是乐谱纸面
（五线谱 + 符头符干 + 小节线 + 抬头），使「读首页 → 出位图 → Fit」这条链路在浏览器里是实的。

输出：一段 base64 字符串，粘进 HTML 的 BUNDLED_PDF.data。
"""

import base64
import zlib

PAGE_W, PAGE_H = 595.0, 842.0


def esc(s):
    return s.replace("\\", r"\\").replace("(", r"\(").replace(")", r"\)")


def content_stream():
    """构造第 0 页的内容流：一张乐谱纸面。"""
    out = []
    paper = "0.984 0.976 0.957"
    ink = "0.094 0.086 0.078"
    staff = "0.72 0.70 0.68"

    # 纸面底色
    out.append(f"{paper} rg")
    out.append(f"0 0 {PAGE_W} {PAGE_H} re f")

    # 抬头横线
    out.append(f"{ink} rg")
    out.append(f"0.30 w 178 792 m 417 792 l S")

    rows = 5
    top = 700.0
    sys_gap = 120.0
    staff_h = 42.0
    line_gap = staff_h / 4.0
    pad = 46.0
    inner_w = PAGE_W - pad * 2

    for row in range(rows):
        sys_top = top - row * sys_gap
        # 谱线是描边，必须用 RG（描边色）而不是 rg（填充色）
        out.append(f"{staff} RG")
        for i in range(5):
            y = sys_top - i * line_gap
            out.append(f"0.6 w {pad} {y:.2f} m {PAGE_W - pad} {y:.2f} l S")

        # 音符：确定性伪随机（用行列索引算，保证每次生成同样的字节）
        note_count = 7 + ((row * 5 + 3) % 5)
        cell = inner_w / note_count
        for n in range(note_count):
            if (row * 31 + n * 17) % 11 == 0:
                continue
            cx = pad + cell * (n + 0.5)
            step = (row * 7 + n * 3) % 5
            cy = sys_top - step * line_gap
            rw, rh = cell * 0.15, cell * 0.10
            # 符头（用两条贝塞尔近似椭圆）—— 填充用 rg
            x0, y0 = cx - rw, cy
            out.append(f"{ink} rg")
            out.append(
                f"{x0:.2f} {y0:.2f} m "
                f"{x0 + rw * 0.2:.2f} {y0 + rh:.2f} {x0 + rw * 1.8:.2f} {y0 + rh:.2f} {x0 + rw * 2:.2f} {y0:.2f} c "
                f"{x0 + rw * 1.8:.2f} {y0 - rh:.2f} {x0 + rw * 0.2:.2f} {y0 - rh:.2f} {x0:.2f} {y0:.2f} c f"
            )
            # 符干：描边（RG）
            out.append(f"{ink} RG")
            if (row + n) % 2 == 0:
                out.append(f"0.9 w {cx + rw * 0.85:.2f} {cy:.2f} m {cx + rw * 0.85:.2f} {cy + staff_h * 0.9:.2f} l S")
            else:
                out.append(f"0.9 w {cx - rw * 0.85:.2f} {cy:.2f} m {cx - rw * 0.85:.2f} {cy - staff_h * 0.9:.2f} l S")
            # 小节线：描边（RG）
            if (n + 1) % 3 == 0 and n < note_count - 1:
                bx = pad + cell * (n + 1)
                out.append(
                    f"0.66 0.64 0.62 RG 0.8 w {bx:.2f} {sys_top + line_gap * 0.5:.2f} m "
                    f"{bx:.2f} {sys_top - staff_h - line_gap * 0.5:.2f} l S"
                )

    # 纸张内描边
    out.append("0.93 0.93 0.93 RG 1 w")
    out.append(f"0.5 0.5 {PAGE_W - 1} {PAGE_H - 1} re S")

    return "\n".join(out).encode("latin-1")


def build():
    cs = content_stream()
    cs_z = zlib.compress(cs)

    objects = []

    def add(body):
        objects.append(body)
        return len(objects)

    # 1 Catalog / 2 Pages / 3 Font / 4 Content / 5 Page
    objs = {}

    objs[1] = b"<< /Type /Catalog /Pages 2 0 R >>"
    objs[2] = (
        b"<< /Type /Pages /Kids [5 0 R] /Count 1 "
        b"/MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R >> >> >>"
    )
    objs[3] = b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
    objs[4] = (
        b"<< /Length " + str(len(cs_z)).encode() + b" /Filter /FlateDecode >>\nstream\n"
        + cs_z + b"\nendstream"
    )
    objs[5] = b"<< /Type /Page /Parent 2 0 R /Contents 4 0 R >>"

    out = bytearray(b"%PDF-1.7\n%\xe2\xe3\xcf\xd3\n")
    offsets = {}
    for i in range(1, 6):
        offsets[i] = len(out)
        out += str(i).encode() + b" 0 obj\n" + objs[i] + b"\nendobj\n"

    xref_pos = len(out)
    out += b"xref\n0 6\n"
    out += b"0000000000 65535 f \n"
    for i in range(1, 6):
        out += ("%010d 00000 n \n" % offsets[i]).encode()
    out += b"trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n"
    out += str(xref_pos).encode() + b"\n%%EOF\n"
    return bytes(out)


if __name__ == "__main__":
    pdf = build()
    assert pdf.startswith(b"%PDF-1.7"), "头部不对"
    assert b"startxref" in pdf and pdf.rstrip().endswith(b"%%EOF"), "尾部不对"
    b64 = base64.b64encode(pdf).decode("ascii")
    print(f"bytes={len(pdf)} b64len={len(b64)}")
    with open("tools/proto_pdf_b64.txt", "w", encoding="ascii") as f:
        f.write(b64)
    print("written tools/proto_pdf_b64.txt")
