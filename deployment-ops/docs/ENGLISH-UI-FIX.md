# English UI correction (2026-11-15)

The previous generated HTML/JavaScript contained literal question marks instead of Chinese text. Repeated replacements then treated different page names as the same string, duplicating menus and routing every page to the same handler.

The frontend now uses English ASCII text, ten unique machine-readable page IDs, a single navigation definition, and explicit page handlers. Static responses use Cache-Control: no-cache and versioned assets. Authentication controls remain hidden until login. The frontend validation script rejects non-ASCII text and runs of corrupted question marks.

Verified against the deployed server with installed Chrome and Playwright: login, ten unique menu entries, all ten pages, rendered text integrity, no page JavaScript errors, overview screenshot and sign out. Registry correctly reports unavailable because the optional service is not installed; the other nine pages loaded without notices. This check only reads management data; no application container operations were performed.

To repeat, use an existing local playwright-core installation (no runtime dependency is added):

```
PLAYWRIGHT_MODULE=/local/path/to/playwright-core CHROME_PATH=/local/path/to/chrome OPS_URL=http://server:19220 OPS_TEST_PASSWORD=... node tests/browser-smoke.cjs
```

The password comes from the environment. Screenshots and JSON results are written to test-artifacts/ (ignored by Git).
