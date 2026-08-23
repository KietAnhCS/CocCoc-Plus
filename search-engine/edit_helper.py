"""Sửa tệp mà GIỮ NGUYÊN kiểu xuống dòng của nó.

Cây mã này có cả tệp CRLF (đã có sẵn, do .gitattributes trả về khi checkout
trên Windows) lẫn tệp LF (mới viết). Một script sửa hàng loạt mà ghi bừa một
kiểu sẽ làm prettier/git báo thay đổi ở hàng trăm dòng không liên quan — đúng
loại nhiễu che mất thay đổi thật trong bản diff.
"""
import io
import sys


def read(path):
    raw = io.open(path, encoding="utf-8", newline="").read()
    crlf = "\r\n" in raw
    return raw.replace("\r\n", "\n"), crlf


def write(path, text, crlf):
    io.open(path, "w", encoding="utf-8", newline="\r\n" if crlf else "\n").write(text)


def sub(path, pairs, required=True):
    """pairs: danh sách (cũ, mới). Ném nếu một mẫu không khớp và required=True."""
    text, crlf = read(path)
    for old, new in pairs:
        if old not in text:
            if required:
                raise SystemExit("KHONG KHOP trong %s:\n%s" % (path, old[:200]))
            continue
        text = text.replace(old, new)
    write(path, text, crlf)
    print("da sua", path)
