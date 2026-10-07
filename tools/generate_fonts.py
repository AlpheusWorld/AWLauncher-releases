"""Build offline UI font subsets from Google Fonts (SIL OFL).

Development requirements: requests, fonttools. Run after generate_translations.py.
Full source fonts and metadata are cached in build/font-sources.
"""
import concurrent.futures
import json
import re
import os
import subprocess
from pathlib import Path
from urllib.parse import quote
import requests
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools import subset
from fontTools.pens.recordingPen import DecomposingRecordingPen
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.pens.transformPen import TransformPen

ROOT = Path(__file__).resolve().parents[1]
FONTS = ROOT / "src/main/resources/fonts"
CACHE = ROOT / "build/font-sources"
BASE = "https://raw.githubusercontent.com/google/fonts/main/ofl/"
FAMILIES = {
    "NotoSans": ["el-GR", "vi-VN"],
    "NotoSansArabic": ["ar-SA", "fa-IR"], "NotoSansHebrew": ["he-IL"],
    "NotoSansDevanagari": ["hi-IN"], "NotoSansBengali": ["bn-BD"],
    "NotoSansThai": ["th-TH"], "NotoSansGeorgian": ["ka-GE"], "NotoSansArmenian": ["hy-AM"],
    "NotoSansSC": ["zh-CN"], "NotoSansTC": ["zh-TW"], "NotoSansJP": ["ja-JP"], "NotoSansKR": ["ko-KR"],
}

def locale_names():
    source = (ROOT / "src/main/kotlin/ru/aw/launcher/core/Settings.kt").read_text(encoding="utf-8")
    tags = re.findall(r'\w+\("[^"]+", "([^"]+)"\)', source)
    java = CACHE / "LocaleNames.java"
    java.parent.mkdir(parents=True, exist_ok=True)
    java.write_text('import java.util.Locale; class LocaleNames { public static void main(String[] args) {'
        + 'System.setOut(new java.io.PrintStream(System.out,true,java.nio.charset.StandardCharsets.UTF_8));'
        + 'String[] tags = ' + '{' + ','.join(json.dumps(tag) for tag in tags) + '};'
        + 'for(String target: tags) {for(String name: tags) System.out.println(Locale.forLanguageTag(name).getDisplayName(Locale.forLanguageTag(target)));}}}', encoding="utf-8")
    return subprocess.check_output([str(Path(os.environ["JAVA_HOME"]) / "bin/java.exe"), "-Dfile.encoding=UTF-8", str(java)], encoding="utf-8", timeout=120)

NAMES = ""

def add_fallback_glyphs(font, weight, characters):
    donor = TTFont(FONTS / ("Onest-" + ("Regular" if weight == 400 else "SemiBold") + ".ttf"))
    donor_map = donor.getBestCmap()
    glyphs = donor.getGlyphSet()
    scale = font["head"].unitsPerEm / donor["head"].unitsPerEm
    missing = characters - set(font.getBestCmap())
    order = list(font.getGlyphOrder())
    for code in sorted(missing & set(donor_map)):
        name = f"awFallback{code:04X}"
        recording = DecomposingRecordingPen(glyphs)
        glyphs[donor_map[code]].draw(recording)
        pen = TTGlyphPen(None)
        recording.replay(TransformPen(pen, (scale, 0, 0, scale, 0, 0)))
        font["glyf"][name] = pen.glyph()
        advance, bearing = donor["hmtx"][donor_map[code]]
        font["hmtx"][name] = (round(advance * scale), round(bearing * scale))
        if "vmtx" in font: font["vmtx"][name] = (font["head"].unitsPerEm, 0)
        order.append(name)
        for table in font["cmap"].tables:
            if table.isUnicode() and (code <= 0xffff or table.format == 12): table.cmap[code] = name
    font.setGlyphOrder(order)

def download(url, path):
    if path.exists(): return
    response = requests.get(url, timeout=(10, 120))
    response.raise_for_status()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(response.content)

def build(family, tags):
    folder = family.lower()
    metadata = CACHE / folder / "METADATA.pb"
    download(BASE + folder + "/METADATA.pb", metadata)
    filename = re.search(r'filename: "(.+?)"', metadata.read_text(encoding="utf-8"))[1]
    original = CACHE / folder / filename
    download(BASE + folder + "/" + quote(filename), original)
    download(BASE + folder + "/OFL.txt", FONTS / (family + "-OFL.txt"))
    text = NAMES + (ROOT / "src/main/kotlin/ru/aw/launcher/core/Settings.kt").read_text(encoding="utf-8")
    for tag in tags:
        text += "".join(json.loads((ROOT / "src/main/resources/i18n" / (tag + ".json")).read_text(encoding="utf-8")).values())
    codepoints = set(map(ord, text)) | set(range(32, 127))
    for weight, label in [(400, "Regular"), (600, "SemiBold")]:
        font = TTFont(original)
        axes = {axis.axisTag: (weight if axis.axisTag == "wght" else axis.defaultValue) for axis in font["fvar"].axes}
        # Subset before instantiation to avoid processing tens of thousands of CJK outlines.
        options = subset.Options()
        options.layout_features = ["*"]
        options.name_IDs = ["*"]
        options.name_legacy = True
        sub = subset.Subsetter(options=options)
        sub.populate(unicodes=codepoints)
        sub.subset(font)
        instantiateVariableFont(font, axes, inplace=True)
        add_fallback_glyphs(font, weight, codepoints)
        # These are modified subsets; use an AW-prefixed family name.
        for record in font["name"].names:
            if record.nameID in (1, 2, 3, 4, 6, 16, 17):
                value = {1: "AW " + family, 2: label, 3: "AW-" + family + "-" + label,
                    4: "AW " + family + " " + label, 6: "AW" + family + "-" + label,
                    16: "AW " + family, 17: label}[record.nameID]
                record.string = value.encode(record.getEncoding())
        output = FONTS / (family + "-" + label + ".ttf")
        font.save(output)
        print(f"Built {output.name}: {output.stat().st_size} bytes", flush=True)

if __name__ == "__main__":
    NAMES = locale_names()
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        list(pool.map(lambda pair: build(*pair), FAMILIES.items()))
