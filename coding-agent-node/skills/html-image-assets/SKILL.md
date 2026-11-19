---
name: html-image-assets
description: Author HTML pages and reports that load image files by relative URL or render charts from data in JavaScript. Use when generating or repairing HTML with images, charts or screenshots, especially for offline delivery.
metadata:
  version: 1.0.0
---

# HTML image assets

- Keep images as separate files beside the page, for example as assets/images/holdings.png. Use an img src with a relative URL for a fixed image. For dynamic images, map known keys to relative URLs and assign img.src in JavaScript. Give each image meaningful alternative text and handle missing files with a visible fallback.
- Keep image bytes out of HTML, JavaScript, CSS, JSON and model input. Avoid data:image URLs, Base64, byte arrays and inline generated SVG. Do not open full-size images with Cline Read or attach them to model requests. Use local code to copy, resize or inspect assets. Return paths, dimensions, sizes and validation results as text. To repair existing embedded images, extract and validate the files locally and replace the references. Inspect bounded excerpts of the markup around the image instead of reading the encoded file into context.
- Render charts from authorized computed data with the available chart library or canvas. Generate a separate PNG or SVG file with code for static charts. Preserve original values, labels and units. When the data cannot support a chart, show the supported text or table and explain the gap.
- Package the HTML, scripts and images under the same authorized output root. Register required files through the task's existing delivery contract. Use task-authorized local paths and platform resources. The Frontend Resource Hub accepts HTML and catalog resources. Verify custom workspace images with a browser that can access the packaged files.
- Open the packaged page offline. Check that each required image loads (complete and naturalWidth > 0), charts render, and source files contain no embedded image payloads. Read browser results as text and report any preview or packaging limitation.

For example, use an image and a fallback message in the page:

~~~html
<img id="trend" alt="Monthly balance trend">
<p id="trend-unavailable" hidden>The chart image is unavailable.</p>
<script defer src="./assets/trend.js"></script>
~~~

In `assets/trend.js`, resolve URLs relative to the page:

~~~js
const chartImages = { monthly: './assets/images/monthly-balance.png' };
const trend = document.querySelector('#trend');
trend.addEventListener('error', () => {
  trend.hidden = true;
  document.querySelector('#trend-unavailable').hidden = false;
}, { once: true });
trend.src = chartImages.monthly;
~~~
