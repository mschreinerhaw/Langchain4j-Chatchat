from pathlib import Path
import re
# Browser-native assets must remain portable English text on Windows and Linux.
p = Path(__file__).resolve().parents[1] / 'frontend'
for name in ('index.html', 'app.js', 'product.js', 'style.css'):
    content = (p / name).read_text(encoding='utf-8')
    assert content.isascii(), f'{name}: unexpected non-ASCII UI text'
    assert not re.search(r'\?{3,}', content), f'{name}: corrupted question-mark text'
    assert 'https://cdn' not in content, f'{name}: external CDN dependency'
assert 'lang="en"' in (p / 'index.html').read_text(encoding='utf-8')
print('Frontend assets and English text integrity validated.')
