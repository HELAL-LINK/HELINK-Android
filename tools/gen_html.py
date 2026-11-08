#!/usr/bin/env python3
"""Builds the app's page (fonts and the About photo embedded, works offline) and, for
screenshots, a preview page with a fake phone side.

usage: gen_html.py <ui dir> <fonts dir> <out index.html> [<preview.html> <preview-data.json>]
"""
import base64
import os
import sys

RANGE_AR = "U+0600-06FF,U+0750-077F,U+0870-088E,U+0890-0891,U+0897-08E1,U+08E3-08FF,U+200C-200E,U+2010-2011,U+204F,U+2E41,U+FB50-FDFF,U+FE70-FE74,U+FE76-FEFC"
RANGE_LATIN = "U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+0304,U+0308,U+0329,U+2000-206F,U+20AC,U+2122,U+2190-2193,U+2212,U+2215,U+FEFF,U+FFFD"


def face(path, rng):
    with open(path, 'rb') as f:
        b64 = base64.b64encode(f.read()).decode()
    return ('@font-face{font-family:"Alexandria";font-style:normal;font-display:block;font-weight:100 900;'
            'src:url(data:font/woff2;base64,' + b64 + ') format("woff2");unicode-range:' + rng + ';}')


def main():
    ui, fonts, out = sys.argv[1:4]
    with open(os.path.join(ui, 'index.html'), encoding='utf-8') as f:
        html = f.read()
    css = face(os.path.join(fonts, 'alexandria-arabic-wght-normal.woff2'), RANGE_AR) + '\n' + \
        face(os.path.join(fonts, 'alexandria-latin-wght-normal.woff2'), RANGE_LATIN)
    assert '/*@FONTS@*/' in html
    html = html.replace('/*@FONTS@*/', css, 1)
    # The owner's photo on the About screen, inside the page like the fonts.
    with open(os.path.join(ui, 'owner.jpg'), 'rb') as f:
        photo = 'data:image/jpeg;base64,' + base64.b64encode(f.read()).decode()
    assert '@OWNER_PHOTO@' in html
    html = html.replace('@OWNER_PHOTO@', photo, 1)
    with open(out, 'w', encoding='utf-8') as f:
        f.write(html)
    print('page ->', out, len(html), 'bytes')
    if len(sys.argv) > 5:
        preview, data = sys.argv[4:6]
        with open(os.path.join(ui, 'mock.js'), encoding='utf-8') as f:
            mock = f.read()
        with open(data, encoding='utf-8') as f:
            d = f.read()
        marker = "<script>\n(() => {\n'use strict';"
        assert marker in html
        inject = '<script>window.__nzPreviewData=' + d + ';</script>\n<script>' + mock + '</script>\n' + marker
        with open(preview, 'w', encoding='utf-8') as f:
            f.write(html.replace(marker, inject, 1))
        print('preview ->', preview)


if __name__ == '__main__':
    main()
