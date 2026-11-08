#!/usr/bin/env python3
"""Build the yt-dlp zipapp that ships inside the APK.

It follows yt-dlp's own `make yt-dlp-extra` recipe (the official "yt-dlp" release file):
every package folder's .py files, the bundled .js files, yt-dlp-ejs, and __main__.py at
the root, behind a "#!/usr/bin/env python3" line. The app later replaces it with the
official nightly file from GitHub, which has exactly the same layout.

usage: make_ytdlp_zip.py <yt_dlp package dir> <yt_dlp_ejs wheel> <output file>
"""
import hashlib
import os
import re
import sys
import zipfile

EJS_SHA256 = "79300e5fca7f937a1eeede11f0456862c1b41107ce1d726871e0207424f4bdb4"
STAMP = (2000, 1, 1, 1, 1, 0)


def add(z, arcname, data):
    info = zipfile.ZipInfo(arcname, STAMP)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    z.writestr(info, data)


def main():
    pkg, wheel, out = sys.argv[1:4]
    pkg = os.path.abspath(pkg)
    root = os.path.dirname(pkg)
    if os.path.basename(pkg) != "yt_dlp" or not os.path.isfile(os.path.join(pkg, "version.py")):
        sys.exit("not a yt_dlp package folder: " + pkg)
    if not os.path.isfile(os.path.join(pkg, "extractor", "lazy_extractors.py")):
        sys.exit("lazy_extractors.py is missing: startup on phones would be very slow")
    with open(wheel, "rb") as f:
        if hashlib.sha256(f.read()).hexdigest() != EJS_SHA256:
            sys.exit("unexpected yt-dlp-ejs wheel")

    files = []  # (arcname, data)
    for d, dirs, names in os.walk(pkg):
        dirs[:] = sorted(x for x in dirs if not x.startswith("__"))
        rel = os.path.relpath(d, root).replace(os.sep, "/")
        is_pkg = "__init__.py" in names
        for n in sorted(names):
            if (n.endswith(".py") and is_pkg) or n.endswith(".js"):
                if rel == "yt_dlp" and n == "__main__.py":
                    continue
                with open(os.path.join(d, n), "rb") as f:
                    files.append((rel + "/" + n, f.read()))

    # Same as the official zipapp: no "installed with pip" hint, updates come from GitHub.
    names = dict(files)
    ver = names["yt_dlp/version.py"].decode()
    ver = re.sub(r"(?m)^VARIANT = .*$", "VARIANT = None", ver)
    ver = re.sub(r"(?m)^UPDATE_HINT = .*$", "UPDATE_HINT = None", ver)
    names["yt_dlp/version.py"] = ver.encode()
    version = re.search(r"__version__ = '([^']+)'", ver).group(1)

    with zipfile.ZipFile(wheel) as w:
        ejs = sorted(n for n in w.namelist() if n.startswith("yt_dlp_ejs/") and n.endswith((".py", ".js")))
        if "yt_dlp_ejs/yt/solver/lib.min.js" not in ejs:
            sys.exit("wheel has no solver scripts")
        ejs_files = [(n, w.read(n)) for n in ejs]

    with open(os.path.join(pkg, "__main__.py"), "rb") as f:
        main_py = f.read()

    tmp = out + ".tmp"
    with open(tmp, "wb") as raw:
        raw.write(b"#!/usr/bin/env python3\n")
        with zipfile.ZipFile(raw, "w") as z:
            for n, _ in files:
                add(z, n, names[n])
            for n, data in ejs_files:
                add(z, n, data)
            add(z, "__main__.py", main_py)
    os.replace(tmp, out)
    print("yt-dlp", version, "->", out, os.path.getsize(out), "bytes,", len(files) + len(ejs_files) + 1, "files")


if __name__ == "__main__":
    main()
