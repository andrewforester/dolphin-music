#!/usr/bin/env python3
import glob
import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw

CELL_WIDTH = 360
LABEL_HEIGHT = 28
COLUMNS = 4
BACKGROUND = (245, 245, 245)


def label(path: Path) -> str:
    parts = path.stem.split(".")
    return ".".join(parts[-2:])


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit("usage: contact_sheet.py <out.png> [images...]")
    out = Path(sys.argv[1])
    paths = [Path(p) for p in (sys.argv[2:] or sorted(glob.glob("androidApp/src/test/screenshots/*.png")))]
    if not paths:
        sys.exit("no images")
    images = []
    for path in paths:
        image = Image.open(path).convert("RGB")
        scale = CELL_WIDTH / image.width
        images.append((path, image.resize((CELL_WIDTH, round(image.height * scale)))))
    cell_height = max(image.height for _, image in images) + LABEL_HEIGHT
    columns = min(COLUMNS, len(images))
    rows = math.ceil(len(images) / columns)
    sheet = Image.new("RGB", (columns * CELL_WIDTH, rows * cell_height), BACKGROUND)
    draw = ImageDraw.Draw(sheet)
    for index, (path, image) in enumerate(images):
        x = (index % columns) * CELL_WIDTH
        y = (index // columns) * cell_height
        sheet.paste(image, (x, y + LABEL_HEIGHT))
        draw.text((x + 6, y + 8), label(path), fill=(20, 20, 20))
    sheet.save(out)
    print(f"{out}: {len(images)} images")


if __name__ == "__main__":
    main()
