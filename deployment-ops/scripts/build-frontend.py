from pathlib import Path
# Browser-native ES JavaScript: no npm, CDN or generated remote dependencies.
p=Path(__file__).resolve().parents[1]/'frontend'
for name in ('index.html','app.js','style.css'):
 assert (p/name).is_file(),name
 assert 'https://cdn' not in (p/name).read_text(encoding='utf-8')
print('Frontend assets validated; Docker COPY packages the standalone frontend.')
