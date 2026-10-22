"""Select bundled CJK faces at image build time, without runtime downloads."""
from pathlib import Path
from matplotlib import font_manager

config = Path('/opt/chatchat/matplotlib/matplotlibrc')
installed = {font.name for font in font_manager.fontManager.ttflist}
families = [name for name in ('Noto Sans CJK SC', 'Noto Sans CJK JP', 'DejaVu Sans') if name in installed]
if not any(name.startswith('Noto Sans CJK ') for name in families):
    raise RuntimeError('The Python runtime image must bundle Noto CJK fonts')
lines = config.read_text(encoding='utf-8').splitlines()
config.write_text('\n'.join(line.split(':', 1)[0] + ': ' + ', '.join(families)
                            if line.startswith(('font.family:', 'font.sans-serif:')) else line
                            for line in lines) + '\n', encoding='utf-8')
