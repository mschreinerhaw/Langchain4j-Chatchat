"""Real glyph coverage and PNG/SVG rendering; works in a read-only sandbox."""
import io
import json
import warnings
from pathlib import Path

import matplotlib
from matplotlib import font_manager
from matplotlib.ft2font import FT2Font
from matplotlib import pyplot as plt


def verify(output_directory=None):
    text = '\u4e2d\u6587\u4ea4\u6613\u8d44\u4ea7\u76c8\u4e8f\u5206\u6790\u5ba2\u6237\u91d1\u989d ABC 123 -1'
    families = matplotlib.rcParams['font.family']
    fonts = []
    for family in families:
        try:
            path = font_manager.findfont(font_manager.FontProperties(family=[family]), fallback_to_default=False)
            fonts.append(FT2Font(path))
        except ValueError:
            # Debian's TTC exposes JP; environments may additionally bundle SC.
            continue
    assert all(any(font.get_char_index(ord(char)) for font in fonts) for char in text if not char.isspace()), 'Missing required chart glyphs'
    # Reproduce the historical generated code that forced Latin sans-serif.
    with matplotlib.rc_context({'font.sans-serif': ['DejaVu Sans']}), warnings.catch_warnings(record=True) as captured:
        warnings.simplefilter('always')
        fig, ax = plt.subplots()
        ax.bar([text, 'ABC'], [-1, 2])
        ax.set_title(text)
        ax.set_ylabel(text)
        sizes = {}
        if output_directory:
            Path(output_directory).mkdir(parents=True, exist_ok=True)
        for kind in ('png', 'svg'):
            output = io.BytesIO()
            fig.savefig(output, format=kind)
            sizes[kind] = len(output.getvalue())
            if output_directory:
                Path(output_directory, 'font-verification.' + kind).write_bytes(output.getvalue())
            assert sizes[kind] > 1000
            if kind == 'svg':
                assert b'<text' not in output.getvalue(), 'SVG must embed glyph paths'
        plt.close(fig)
        missing = [str(item.message) for item in captured if 'Glyph' in str(item.message) and 'missing' in str(item.message)]
        assert not missing, 'Missing glyph warnings: ' + '; '.join(missing)
    result = {'status': 'PASSED', 'matplotlib': matplotlib.__version__, 'families': families,
              'glyphCoverage': True, 'legacySansOverride': True, 'renderedBytes': sizes}
    if output_directory:
        Path(output_directory, 'rows.jsonl').write_text(json.dumps(result) + '\n', encoding='utf-8')
    print(json.dumps(result))


if __name__ == '__main__':
    verify()
