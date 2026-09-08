"""Generate centered inverse launcher icons for Android 7.x fallbacks."""

from pathlib import Path

from PIL import Image, ImageDraw


RESOURCE_ROOT = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res"
DENSITY_SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}


def draw_icon(size: int, background: str, foreground: str) -> Image.Image:
    scale = 4
    canvas_size = size * scale
    image = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    center = canvas_size / 2
    disk_radius = canvas_size * 0.46
    ring_radius = canvas_size * 0.25
    ring_width = max(scale, round(canvas_size * 0.035))

    draw.ellipse(
        (
            center - disk_radius,
            center - disk_radius,
            center + disk_radius,
            center + disk_radius,
        ),
        fill=background,
    )
    draw.ellipse(
        (
            center - ring_radius,
            center - ring_radius,
            center + ring_radius,
            center + ring_radius,
        ),
        outline=foreground,
        width=ring_width,
    )

    bar_width = canvas_size * 0.28
    bar_height = canvas_size * 0.055
    draw.rounded_rectangle(
        (
            center - bar_width / 2,
            center - bar_height / 2,
            center + bar_width / 2,
            center + bar_height / 2,
        ),
        radius=bar_height * 0.24,
        fill=foreground,
    )
    return image.resize((size, size), Image.Resampling.LANCZOS)


for density, size in DENSITY_SIZES.items():
    resource_dir = RESOURCE_ROOT / density
    black = draw_icon(size, "white", "black")
    white = draw_icon(size, "black", "white")
    for suffix in ("", "_round"):
        black.save(resource_dir / f"ic_launcher_black{suffix}.png", optimize=True)
        white.save(resource_dir / f"ic_launcher_white{suffix}.png", optimize=True)
