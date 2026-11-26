import sys, os, zipfile, time, struct
root, stub, out, version = sys.argv[1:5]
zpath = out + '.zip'
now = time.localtime()[:6]
with zipfile.ZipFile(zpath, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for d, _, files in os.walk(root):
        for f in files:
            full = os.path.join(d, f)
            rel = os.path.relpath(full, root).replace(os.sep, '/')
            info = zipfile.ZipInfo(rel, date_time=now)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            with open(full, 'rb') as fh: z.writestr(info, fh.read(), compresslevel=9)
s = open(stub, 'rb').read(); p = open(zpath, 'rb').read()
v = version.encode()[:32].ljust(32, b'\0')
open(out, 'wb').write(s + p + v + struct.pack('<Q', len(s)) + b'WBPPAYL1')
os.remove(zpath)
print(out, os.path.getsize(out))
