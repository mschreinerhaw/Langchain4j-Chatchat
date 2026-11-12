"""Independent pixel check for the explicit SYNTHETIC +2/-1/0 fixture only.

This is acceptance tooling, not a production business/chart validator. Inspect
the figures as well: arbitrary candlesticks do not obey this fixture geometry.
No image is modified. Requires Pillow in the verifier environment.
"""
import argparse
import hashlib
import json
from pathlib import Path
from PIL import Image

parser = argparse.ArgumentParser()
parser.add_argument("directory", type=Path)
parser.add_argument("--convention", choices=["cn", "international"], required=True)
args = parser.parse_args()
palette = {"red": "C62828", "green": "16845B", "flat": "64748B",
           "blue": "245A81", "teal": "168A91", "purple": "695AA6", "ochre": "9B6A27"}
rgb = {tuple(bytes.fromhex(value)): key for key, value in palette.items()}
files = []
directions = []
neutral_pixels = 0
for path in sorted(args.directory.glob("chart-*.png")):
    with Image.open(path) as image:
        image = image.convert("RGB")
        count = {key: 0 for key in palette}
        y_sum = {key: 0 for key in palette}
        pixels = image.get_flattened_data() if hasattr(image, "get_flattened_data") else image.getdata()
        for index, pixel in enumerate(pixels):
            role = rgb.get(pixel)
            if role:
                count[role] += 1
                y_sum[role] += index // image.width
        y = {role: y_sum[role] / count[role] if count[role] else None for role in palette}
        # Substantial regions, rather than a caption/legend alone, must encode changes.
        if count["red"] >= 1000 and count["green"] >= 1000:
            directions.append(y["red"] < y["green"] if args.convention == "cn"
                              else y["green"] < y["red"])
        neutral_pixels += sum(count[key] for key in ["blue", "teal", "purple", "ochre"])
        files.append({"file": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                      "size": list(image.size), "pixelCounts": count, "colorCentroidY": y})
checks = {"actualDirectionalPlotPresent": bool(directions),
          "allInspectedDirectionRegionsMatchFixture": bool(directions) and all(directions),
          "neutralComparisonSeriesPresent": neutral_pixels >= 1000}
result = {"passed": all(checks.values()), "scope": "SYNTHETIC_PLUS2_MINUS1_ZERO_FIXTURE_ONLY",
          "convention": args.convention, "checks": checks, "files": files}
(args.directory / "color-verification.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
print(json.dumps({"passed": result["passed"], "checks": checks}))
raise SystemExit(0 if result["passed"] else 1)
