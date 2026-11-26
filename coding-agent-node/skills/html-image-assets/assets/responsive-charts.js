(() => {
  const grids = document.querySelectorAll('[data-report-chart-grid]');
  if (!grids.length) return;

  let frame;
  function layout() {
    frame = undefined;
    const rem = Number.parseFloat(getComputedStyle(document.documentElement).fontSize);
    for (const grid of grids) {
      const figures = Array.from(grid.children).filter(figure => figure.hasAttribute('data-report-chart'));
      const style = getComputedStyle(grid);
      const gap = Number.parseFloat(style.columnGap) || 0;
      const width = grid.clientWidth - Number.parseFloat(style.paddingLeft) - Number.parseFloat(style.paddingRight);
      const hasPair = figures.filter(figure => !figure.hasAttribute('data-report-chart-wide')).length >= 2;
      grid.dataset.reportChartColumns = hasPair && width >= 44 * rem + gap ? '2' : '1';

      for (const figure of figures) {
        for (const image of figure.querySelectorAll('img')) {
          if (!image.naturalWidth || !image.naturalHeight) continue;
          // Limit tall charts by converting the height cap to a width at the intrinsic ratio.
          const maxWidth = Math.floor(30 * rem * image.naturalWidth / image.naturalHeight);
          image.style.setProperty('--report-chart-image-width', `min(100%, ${Math.max(1, maxWidth)}px)`);
        }
      }
    }
  }

  function queueLayout() {
    if (frame === undefined) frame = requestAnimationFrame(layout);
  }

  const widths = new WeakMap();
  if (typeof ResizeObserver === 'function') {
    const observer = new ResizeObserver(entries => {
      for (const entry of entries) {
        const width = entry.contentRect.width;
        if (width === widths.get(entry.target)) continue;
        widths.set(entry.target, width);
        queueLayout();
      }
    });
    for (const grid of grids) observer.observe(grid);
  }

  for (const grid of grids) grid.addEventListener('load', queueLayout, true);
  window.addEventListener('resize', queueLayout);
  queueLayout();
})();
