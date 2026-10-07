from pathlib import Path
import sys

from PIL import Image, ImageDraw, ImageFont, ImageOps

BRANDING = Path(__file__).resolve().parent
RESOURCES = BRANDING.parent / "src" / "main" / "resources" / "brand"
FONT = BRANDING.parent / "src" / "main" / "resources" / "fonts" / "Onest-Black.ttf"
ICON_SOURCE = BRANDING / "AWLauncher-mark.png"
WINDOW_ICON_SIZES = [16, 20, 24, 32, 40, 48, 64]
ICO_SIZES = WINDOW_ICON_SIZES + [256]
WORDMARK_SIZE = (624, 104)


def installer_assets() -> None:
    """WiX artwork at double resolution; interactive text stays native and accessible."""
    destination = BRANDING / "installer"
    destination.mkdir(parents=True, exist_ok=True)
    background = Image.new("RGB", (1120, 720), (247, 250, 248))
    with Image.open(RESOURCES / "aw-home-hero.png") as source:
        landscape = ImageOps.fit(source.convert("RGB"), (378, 720), centering=(0.48, 0.5))
    background.paste(landscape, (0, 0))
    shade = Image.new("RGBA", (378, 720))
    pixels = shade.load()
    for y in range(720):
        # Readable brand at the top, a visible landscape below, quiet footer.
        opacity = int(210 - 115 * min(y / 480, 1) + 80 * max((y - 500) / 220, 0))
        for x in range(378):
            pixels[x, y] = (12, 26, 21, opacity)
    sidebar = background.crop((0, 0, 378, 720)).convert("RGBA")
    sidebar.alpha_composite(shade)
    with Image.open(RESOURCES / "wordmark.png") as source:
        mark = source.convert("RGBA").resize((296, 49), Image.Resampling.LANCZOS)
    sidebar.alpha_composite(mark, (40, 46))
    draw = ImageDraw.Draw(sidebar)
    title = ImageFont.truetype(str(RESOURCES.parent / "fonts" / "Onest-SemiBold.ttf"), 36)
    small = ImageFont.truetype(str(RESOURCES.parent / "fonts" / "Onest-Medium.ttf"), 17)
    draw.multiline_text((40, 165), "Твой мир.\nТвои правила.", font=title, fill=(241, 248, 243), spacing=8)
    draw.text((40, 648), "ALPHEUSWORLD", font=small, fill=(181, 208, 193))
    background.paste(sidebar.convert("RGB"), (0, 0))
    draw = ImageDraw.Draw(background)
    draw.line((378, 0, 378, 720), fill=(215, 226, 220), width=1)
    draw.line((438, 616, 1064, 616), fill=(217, 228, 221), width=1)
    background.save(destination / "background.bmp")

    # Stock recovery/validation dialogs use the standard WiX dimensions.
    dialog = Image.new("RGB", (493, 312), (247, 250, 248))
    dialog.paste(sidebar.convert("RGB").resize((164, 312), Image.Resampling.LANCZOS), (0, 0))
    dialog.save(destination / "dialog.bmp")
    banner = Image.new("RGB", (493, 58), (247, 250, 248))
    icon = app_icon(42)
    banner.paste(icon, (438, 8), icon)
    banner.save(destination / "banner.bmp")
    print(f"Installer artwork written to {destination}")


def app_icon(size: int) -> Image.Image:
    with Image.open(ICON_SOURCE) as source:
        image = source.convert("RGBA")
    return image.resize((size, size), Image.Resampling.LANCZOS)


def wordmark(light: bool) -> Image.Image:
    width, height = WORDMARK_SIZE
    image = Image.new("RGBA", WORDMARK_SIZE, (0, 0, 0, 0))
    block = app_icon(92)
    image.alpha_composite(block, (0, 6))
    draw = ImageDraw.Draw(image)
    font = ImageFont.truetype(str(FONT), 70)
    aw_color = (21, 154, 91, 255) if light else (96, 222, 151, 255)
    launcher_color = (26, 43, 34, 255) if light else (245, 249, 246, 255)
    y = (height - 82) // 2
    draw.text((104, y), "AW", font=font, fill=aw_color, stroke_width=0)
    aw_width = draw.textbbox((104, y), "AW", font=font)[2] - 104
    draw.text((104 + aw_width + 4, y), "Launcher", font=font, fill=launcher_color, stroke_width=0)
    return image


def main() -> None:
    RESOURCES.mkdir(parents=True, exist_ok=True)
    wordmark(light=False).save(RESOURCES / "wordmark.png", optimize=True)
    wordmark(light=True).save(RESOURCES / "wordmark-light.png", optimize=True)

    icons = {size: app_icon(size) for size in ICO_SIZES}
    for size in WINDOW_ICON_SIZES:
        icons[size].save(RESOURCES / f"icon-{size}.png", optimize=True)
    icons[256].save(
        BRANDING / "AWLauncher.ico",
        sizes=[(size, size) for size in ICO_SIZES],
        append_images=[icons[size] for size in ICO_SIZES if size != 256],
    )
    print(f"AWLauncher wordmarks {WORDMARK_SIZE}; app icons {ICO_SIZES}")


if __name__ == "__main__":
    if "--installer" in sys.argv[1:]:
        installer_assets()
    else:
        main()
