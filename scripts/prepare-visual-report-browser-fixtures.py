"""Create synthetic Python artifacts for the local browser test, not a Cline end-to-end test.

Run in the existing approved Python image with an output directory mounted writable:
  python prepare-visual-report-browser-fixtures.py /output
Then copy the files to target/assets-local/visual-report-fixtures or set VISUAL_REPORT_FIXTURES.
Requires the already available Matplotlib and Plotly packages; installs nothing.
"""
import json
import sys
from decimal import Decimal
from pathlib import Path

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import plotly.graph_objects as go

root = Path(sys.argv[1])
root.mkdir(parents=True, exist_ok=True)
labels = ['A', 'B', 'C']
values = [Decimal('1.25'), Decimal('2.50'), Decimal('3.75')]
figure, axis = plt.subplots()
axis.bar(labels, list(map(float, values)))
axis.set_title('Synthetic Python sample')
for extension in ('png', 'svg'):
    figure.savefig(root / ('sample.' + extension))
plt.close(figure)
interactive = go.Figure(go.Bar(x=labels, y=list(map(float, values))))
interactive.update_layout(template=None)
interactive.write_json(root / 'sample.plotly.json')
(root / 'facts.json').write_text(json.dumps({
    'labels': labels, 'values': list(map(str, values)), 'total': str(sum(values))
}), encoding='utf-8')
