"""Run in the network-disabled, read-only Runtime image; fail on missing glyphs."""
import json
from pathlib import Path
import warnings

import matplotlib
matplotlib.use("Agg")
from matplotlib import font_manager, ft2font
import matplotlib.pyplot as plt

LABELS = ["九阳股份", "雪龙集团", "兴业股份"]
TITLE = "三只股票最新收盘价与涨跌幅对比"
AXIS = "收盘价（元）"


def main():
    output = Path("/workspace/output")
    output.mkdir(parents=True, exist_ok=True)
    font_path = font_manager.findfont(font_manager.FontProperties(family=["sans-serif"]), fallback_to_default=False)
    font = ft2font.FT2Font(font_path)
    required = set("".join(LABELS) + TITLE + AXIS + "−")
    missing = [char for char in required if not font.get_char_index(ord(char))]
    assert not missing, f"Missing Chinese chart glyphs: {missing}"
    with warnings.catch_warnings(record=True) as captured:
        warnings.simplefilter("always")
        fig, ax = plt.subplots(figsize=(10, 5), layout="constrained")
        # Synthetic regression values; no claim about current market prices.
        ax.bar(LABELS, [20, 14, 11], color=["#2f7cf6", "#f4a629", "#20b26b"])
        ax.set_title(TITLE)
        ax.set_ylabel(AXIS)
        for extension in ["png", "svg", "pdf"]:
            fig.savefig(output / f"chinese-font-regression.{extension}", dpi=150)
        plt.close(fig)
    glyph_warnings = [str(item.message) for item in captured if "Glyph" in str(item.message) or "missing from font" in str(item.message)]
    assert not glyph_warnings, glyph_warnings
    svg = (output / "chinese-font-regression.svg").read_text()
    assert "<text" not in svg, "SVG must use portable glyph outlines"
    result = {"status": "PASSED", "font": font.family_name, "glyphsChecked": len(required), "missingGlyphs": missing,
              "glyphWarnings": glyph_warnings, "formats": ["png", "svg", "pdf"], "syntheticRegressionData": True}
    (output / "font-acceptance.json").write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
