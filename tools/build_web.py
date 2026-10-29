#!/usr/bin/env python3
"""Prepara la app web para el APK.

Toma web/index.html (la misma página que el artifact de Claude) y genera
app/src/main/assets/www/index.html para que funcione sin internet:
- agrega la cabecera HTML completa (el artifact la recibe del visor),
- cambia las librerías de cdnjs por copias locales en lib/,
- cambia Google Fonts por las fuentes incluidas en lib/fonts.
"""
import pathlib
import re
import shutil
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "web" / "index.html"
LIB = ROOT / "web" / "lib"
OUT = ROOT / "app" / "src" / "main" / "assets" / "www"

SCRIPTS = {
    "https://cdnjs.cloudflare.com/ajax/libs/Chart.js/4.4.1/chart.umd.min.js": "lib/chart.umd.js",
    "https://cdnjs.cloudflare.com/ajax/libs/jspdf/2.5.1/jspdf.umd.min.js": "lib/jspdf.umd.min.js",
    "https://cdnjs.cloudflare.com/ajax/libs/jspdf-autotable/3.8.2/jspdf.plugin.autotable.min.js": "lib/jspdf.plugin.autotable.min.js",
}

FONTS = [
    ("Barlow", 400, "normal", "Barlow-Regular.ttf"),
    ("Barlow", 500, "normal", "Barlow-Medium.ttf"),
    ("Barlow", 600, "normal", "Barlow-SemiBold.ttf"),
    ("Barlow Condensed", 500, "normal", "BarlowCondensed-Medium.ttf"),
    ("Barlow Condensed", 600, "normal", "BarlowCondensed-SemiBold.ttf"),
    ("Barlow Condensed", 700, "normal", "BarlowCondensed-Bold.ttf"),
    ("Barlow Condensed", 700, "italic", "BarlowCondensed-BoldItalic.ttf"),
    ("Barlow Condensed", 800, "italic", "BarlowCondensed-ExtraBoldItalic.ttf"),
    ("IBM Plex Mono", 400, "normal", "IBMPlexMono-Regular.ttf"),
    ("IBM Plex Mono", 500, "normal", "IBMPlexMono-Medium.ttf"),
]

HEAD = (
    '<!doctype html><html lang="es"><head><meta charset="utf-8">'
    '<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">'
    "<style>:root{color-scheme:light}body{margin:0;font:14px system-ui,sans-serif}"
    "img{max-width:100%}[hidden]{display:none!important}</style>"
    '<link rel="stylesheet" href="lib/fonts.css"></head><body>'
)


def main() -> int:
    html = SRC.read_text(encoding="utf-8")
    for url, local in SCRIPTS.items():
        if url not in html:
            print(f"Aviso: no se encontró {url} en web/index.html", file=sys.stderr)
        html = html.replace(url, local)
    html = re.sub(r'<link rel="preconnect" href="https://fonts\.googleapis\.com">\s*', "", html)
    html = re.sub(r'<link rel="stylesheet" href="https://fonts\.googleapis\.com/[^"]*">\s*', "", html)
    left = re.findall(r'https://(?:cdnjs|fonts)\.[^"\']+', html)
    if left:
        print("Error: quedan recursos externos: " + ", ".join(sorted(set(left))), file=sys.stderr)
        return 1

    if OUT.exists():
        shutil.rmtree(OUT)
    (OUT / "lib").mkdir(parents=True)
    shutil.copytree(LIB, OUT / "lib", dirs_exist_ok=True)
    css = "\n".join(
        f"@font-face{{font-family:'{fam}';font-weight:{w};font-style:{st};font-display:swap;src:url('fonts/{f}') format('truetype')}}"
        for fam, w, st, f in FONTS
    )
    (OUT / "lib" / "fonts.css").write_text(css + "\n", encoding="utf-8")
    (OUT / "index.html").write_text(HEAD + html + "\n</body></html>\n", encoding="utf-8")
    print(f"OK: {OUT / 'index.html'} ({len(html) // 1024} KB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
