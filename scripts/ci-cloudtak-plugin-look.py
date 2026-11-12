#!/usr/bin/env python3
"""ci-cloudtak-plugin-look: open CloudTAK in a headless browser, signed in, and look for a plugin's menu entry. (ops #692)

  ci-cloudtak-plugin-look.py --map-url https://map.example.org --token-file FILE --label 'Search Containment' --screenshot out.png

The token is a CloudTAK API token (ci-cloudtak-plugin-check.py signs one inside the api container). It goes into
localStorage the way CloudTAK's own login does (CapacitorStorage.token). The page loads, the menu opens, the plugin's
row must be there by its text. Exit 0 and a PNG when it is; 1 when it is not (a PNG of what was seen is still saved).
Needs playwright (a venv, not the system Python) and Google Chrome. CloudTAK never goes network-idle: wait for load.
Traps (lessons ops #461, #468): the menu rows have no title attribute; click [title=Open Menu], then the row by text.
"""
import argparse, sys
from playwright.sync_api import sync_playwright


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--map-url", required=True); ap.add_argument("--token-file", required=True)
    ap.add_argument("--label", required=True); ap.add_argument("--screenshot", required=True)
    a = ap.parse_args()
    token = open(a.token_file).read().strip()
    with sync_playwright() as p:
        b = p.chromium.launch(channel="chrome", headless=True)
        ctx = b.new_context(ignore_https_errors=True, viewport={"width": 1400, "height": 900})
        page = ctx.new_page()
        page.goto(a.map_url, wait_until="load", timeout=90000)
        page.evaluate("t => localStorage.setItem('CapacitorStorage.token', t)", token)
        page.goto(a.map_url, wait_until="load", timeout=90000)
        page.wait_for_timeout(8000)
        try:
            page.click("[title='Open Menu']", timeout=30000)
        except Exception as e:
            page.screenshot(path=a.screenshot)
            print("no menu button (signed in? map loaded?): %s" % str(e)[:160], file=sys.stderr); b.close(); return 1
        page.wait_for_timeout(1500)
        found = page.get_by_text(a.label, exact=False).count() > 0
        page.screenshot(path=a.screenshot)
        b.close()
    if not found:
        print("menu is open but has no row with text %r" % a.label, file=sys.stderr)
        return 1
    print("menu row %r found" % a.label)
    return 0


if __name__ == "__main__":
    sys.exit(main())
