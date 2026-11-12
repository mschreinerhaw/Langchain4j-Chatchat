"""Stateless presentation helpers. Call only with actual change data, not balances."""
from decimal import Decimal, InvalidOperation

RED = "#C62828"
GREEN = "#16845B"
FLAT = "#64748B"
SERIES = ("#245A81", "#168A91", "#695AA6", "#9B6A27")
TEXT = "#243746"
GRID = "#E2E8F0"
BACKGROUND = "#FFFFFF"


def direction_palette(profile="financial-auto", market=None):
    """Unresolved/mixed markets have no directional palette; caller must disclose."""
    if profile == "financial-auto":
        observed = str(market or "").strip().upper()
        if observed in {"SSE", "SZSE", "BSE", "CN", "CN-SSE", "CN-SZSE", "CN-BSE"}:
            profile = "financial-cn"
        elif observed in {"HKEX", "HK", "NYSE", "NASDAQ", "US"}:
            profile = "financial-international"
        else:
            return None
    if profile == "financial-cn":
        return {"up": RED, "down": GREEN, "flat": FLAT}
    if profile == "financial-international":
        return {"up": GREEN, "down": RED, "flat": FLAT}
    return None


def change_color(value, palette):
    """Missing/nonfinite changes stay missing. Unknown convention stays neutral."""
    try:
        change = Decimal(str(value))
    except (InvalidOperation, ValueError):
        return None
    if not change.is_finite():
        return None
    if palette is None:
        return FLAT
    return palette["up" if change > 0 else "down" if change < 0 else "flat"]
