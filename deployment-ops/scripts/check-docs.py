"""Check delivery documentation without external dependencies."""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
documents = [root / 'README.md', *sorted((root / 'docs').glob('*.md'))]
for document in documents:
    content = document.read_bytes().decode('utf-8')
    assert content.isascii(), f'{document.name}: expected portable English text'
    assert not re.search(r'\?{3,}', content), f'{document.name}: corrupted question-mark text'
    assert not any(ord(c) < 32 and c not in '\n\r\t' for c in content), f'{document.name}: control characters'
    for link in re.findall(r'\]\(([^)]+)\)', content):
        if '://' in link or link.startswith('#'):
            continue
        target = link.split('#', 1)[0]
        assert (document.parent / target).is_file(), f'{document.name}: broken link {link}'
print(f'Validated {len(documents)} English documents: encoding, text integrity and local links.')
