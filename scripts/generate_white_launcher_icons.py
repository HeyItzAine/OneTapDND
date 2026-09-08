"""Derive white launcher icons exactly from the canonical black artwork."""

from pathlib import Path

from PIL import Image, ImageOps


RESOURCE_ROOT = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res"


def invert_icon(source: Path, destination: Path) -> None:
    image = Image.open(source).convert("RGBA")
    red, green, blue, alpha = image.split()
    white_artwork = Image.merge(
        "RGBA",
        (ImageOps.invert(red), ImageOps.invert(green), ImageOps.invert(blue), alpha),
    )
    black_background = Image.new("RGBA", image.size, (0, 0, 0, 255))
    black_background.alpha_composite(white_artwork)
    black_background.save(destination, optimize=True)


for density in sorted(RESOURCE_ROOT.glob("mipmap-*")):
    invert_icon(density / "ic_launcher_black.png", density / "ic_launcher_white.png")
    invert_icon(
        density / "ic_launcher_black_round.png",
        density / "ic_launcher_white_round.png",
    )
