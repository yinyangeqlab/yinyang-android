"""Build a bounded APK delta by reusing unchanged compressed ZIP entries."""
import gzip
import hashlib
import os
from pathlib import Path
import struct
import tempfile
import zipfile

LIMIT = 256 * 1024 * 1024


def payload_offset(stream, entry):
    stream.seek(entry.header_offset + 26)
    name, extra = struct.unpack("<HH", stream.read(4))
    return entry.header_offset + 30 + name + extra


def blocks(stream, length):
    while length:
        part = stream.read(min(length, 65536))
        if not part:
            raise ValueError("Incomplete APK")
        length -= len(part)
        yield part


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream,"sha256").hexdigest()


def build(base, target, folder):
    size = target.stat().st_size
    if size > LIMIT or base.stat().st_size > LIMIT:
        return None
    tmp = None
    try:
        with zipfile.ZipFile(base) as old, zipfile.ZipFile(target) as new, base.open("rb") as a, target.open("rb") as b:
            with tempfile.NamedTemporaryFile(dir=folder, delete=False) as stream:
                tmp = Path(stream.name)
                cursor = 0
                def literal(end, out):
                    if end > cursor:
                        out.write(struct.pack(">BI", 1, end-cursor))
                        b.seek(cursor)
                        for part in blocks(b, end-cursor): out.write(part)
                with gzip.GzipFile(filename="", fileobj=stream, mode="wb", mtime=0) as patch:
                    patch.write(b"YYD1")
                    entries=sorted(new.infolist(),key=lambda e:e.header_offset)
                    if len(entries)>30000: return None
                    for entry in entries:
                        try: previous = old.getinfo(entry.filename)
                        except KeyError: continue
                        n = entry.compress_size
                        if n < 128 or previous.compress_size != n or previous.CRC != entry.CRC: continue
                        src, dst = payload_offset(a,previous), payload_offset(b,entry)
                        if dst < cursor: return None
                        a.seek(src); b.seek(dst)
                        if any(part != b.read(len(part)) for part in blocks(a,n)): continue
                        literal(dst,patch)
                        patch.write(struct.pack(">BII",0,src,n))
                        cursor = dst+n
                    literal(size,patch)
                    patch.write(b"\x02")
        if tmp.stat().st_size >= size*0.9: return None
        sha = digest(tmp)
        result={"base":digest(base),"sha256":sha,"bytes":tmp.stat().st_size}
        os.replace(tmp,folder/(sha+".patch"))
        return result
    except (OSError, ValueError, zipfile.BadZipFile, struct.error):
        return None
    finally:
        if tmp: tmp.unlink(missing_ok=True)
