import re
from pathlib import Path

import numpy as np
import matplotlib.pyplot as plt
from PIL import Image

LOG_PATH   = Path("logcat.txt")
MAP_PATH   = Path("wgb_floor0.jpg")
OUTPUT_PDF = Path("wgb_overlay.pdf")

# Log.d(TAG, "User pixel location updated to: ($pixelX, $pixelY)")
RE_PIXEL = re.compile(
    r"User pixel location updated to:\s*\(([-\d.eE]+),\s*([-\d.eE]+)\)"
)

def parse_log(path: Path):
    pixels  = []

    for line in path.read_text(encoding="utf-16").splitlines():
        m = RE_PIXEL.search(line)
        if m is not None:
            pixels.append((float(m.group(1)), float(m.group(2))))

    return np.array(pixels)

def main():
    pixels_array = parse_log(LOG_PATH)

    img = np.array(Image.open(MAP_PATH))
    imgHeight, imgWidth = img.shape[:2]
    print(f"Map image: {imgWidth} x {imgHeight} pixels")

    # ---------- Plot ----------
    fig, ax = plt.subplots(figsize=(10, 10))
    ax.imshow(img)

    # Pixel positions
    ax.plot(pixels_array[:, 0], pixels_array[:, 1],
            '-', color='blue', linewidth=1.2, alpha=0.75,
            label="App path")
    ax.scatter(pixels_array[:, 0], pixels_array[:, 1],
               s=12, color='blue', zorder=3)

    # Start / end markers
    ax.scatter(*pixels_array[0],  s=140, marker='s', color='black',
               edgecolor='white', linewidth=1.5, zorder=4, label='Start')
    ax.scatter(*pixels_array[-1], s=140, marker='X', color='red',
               edgecolor='white', linewidth=1.5, zorder=4, label='End')

    # Ground truth rectangle
    gt_corners = np.array([
        [696.0, 2240.0],
        [4500.0, 2240.0],
        [4500.0, 1728.0],
        [696.0, 1728.0],
        [696.0, 2240.0],   # close the loop
    ])
    ax.plot(gt_corners[:, 0], gt_corners[:, 1],
            '-', color='green', linewidth=2.0, alpha=0.9,
            label="Ground truth path")

    ax.set_xlim(0, imgWidth)
    ax.set_ylim(imgHeight, 0)
    ax.set_aspect('equal')
    ax.set_title(f"WGB overlay -- {len(pixels_array)} position updates")
    ax.legend(loc='lower right', framealpha=0.9)
    ax.set_xticks([])
    ax.set_yticks([])

    plt.tight_layout()
    plt.savefig(OUTPUT_PDF, dpi=200, bbox_inches='tight')
    print(f"Wrote {OUTPUT_PDF}")


if __name__ == "__main__":
    main()