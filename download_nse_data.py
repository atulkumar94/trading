"""
Bulk-download daily OHLC data for NSE stocks using yfinance.

SETUP (run once):
    pip install yfinance pandas

INPUT:
    A CSV file with a header row and one column of NSE stock codes, e.g.:

        Symbol
        RELIANCE
        TCS
        INFY
        HDFCBANK

    (".NS" is added automatically if not already present — you can also
    list "RELIANCE.NS" directly, both work.)

USAGE:
    python download_nse_data.py symbols.csv
    python download_nse_data.py symbols.csv --start 2023-01-01 --end 2026-09-20
    python download_nse_data.py symbols.csv --outdir data

OUTPUT:
    One CSV per symbol, saved as <outdir>/<SYMBOL>.csv, containing
    Date, Open, High, Low, Close, Volume.
    A summary file <outdir>/_download_log.csv lists success/failure per symbol.
"""

import argparse
import hashlib
import sys
import time
from pathlib import Path
from typing import Optional

import pandas as pd

try:
    import yfinance as yf
except ImportError:
    sys.exit(
        "ERROR: yfinance is not installed.\n"
        "Run:  pip install yfinance pandas\n"
        "then try again."
    )


def load_symbols(csv_path: str) -> list[str]:
    """Read the first column of the CSV (header row skipped) as symbols."""
    df = pd.read_csv(csv_path)
    if df.empty:
        sys.exit(f"ERROR: '{csv_path}' has no data rows below the header.")
    col = df.columns[0]
    raw = df[col].astype(str).str.strip()
    raw = raw[raw != ""]
    symbols = []
    for s in raw:
        s = s.upper()
        if not s.endswith(".NS"):
            s = s + ".NS"
        symbols.append(s)
    # de-duplicate while preserving order
    seen = set()
    unique_symbols = []
    for s in symbols:
        if s not in seen:
            seen.add(s)
            unique_symbols.append(s)
    return unique_symbols


def download_symbol(symbol: str, start: str, end: Optional[str], outdir: Path) -> dict:
    clean_name = symbol.replace(".NS", "")

    try:
        df = yf.download(
            symbol,
            start=start,
            end=end,
            progress=False,
            auto_adjust=False,
        )
        if df is None or df.empty:
            return {
                "symbol": symbol,
                "status": "no_data",
                "rows": 0,
                "message": "no data returned.",
            }

        if isinstance(df.columns, pd.MultiIndex):
            df.columns = df.columns.get_level_values(0)

        out_path = outdir / f"{clean_name}.csv"
        df.to_csv(out_path)
        return {
            "symbol": symbol,
            "status": "ok",
            "rows": len(df),
            "message": f"saved {len(df)} rows -> {out_path}",
        }
    except Exception as exc:
        return {
            "symbol": symbol,
            "status": f"error: {exc}",
            "rows": 0,
            "message": f"FAILED ({exc})",
        }


def main():
    parser = argparse.ArgumentParser(description="Bulk-download NSE daily OHLC data via yfinance.")
    parser.add_argument("csv_path", help="Path to CSV file with a header row and NSE stock codes.")
    parser.add_argument("--start", default="2015-01-01", help="Start date, YYYY-MM-DD (default: 2020-01-01)")
    parser.add_argument("--end", default=None, help="End date, YYYY-MM-DD (default: today)")
    parser.add_argument(
        "--outdir",
        default="/Users/kumaratl/Downloads/chDownloads/stocksData/stocks/daily",
        help="Output folder for CSVs (default: /Users/kumaratl/Downloads/chDownloads/stocksData/stocks/daily)",
    )
    parser.add_argument("--sleep", type=float, default=0.4, help="Seconds to wait between downloads (default: 0.4)")
    parser.add_argument(
        "--workers",
        type=int,
        default=1,
        help="Deprecated. Downloads always run sequentially to avoid cross-contamination; values >1 are ignored.",
    )
    args = parser.parse_args()

    if args.sleep < 0:
        sys.exit("ERROR: --sleep cannot be negative.")
    if args.workers != 1:
        print(
            "WARNING: concurrent downloads corrupt data (yfinance shares session/cache state, "
            "which cross-assigns one ticker's response to another symbol's file). "
            "Forcing sequential download (workers=1)."
        )

    symbols = load_symbols(args.csv_path)
    print(f"Loaded {len(symbols)} symbols from {args.csv_path}")

    outdir = Path(args.outdir)
    outdir.mkdir(parents=True, exist_ok=True)

    print(f"Downloading sequentially with {args.sleep:.2f}s pause between symbols")

    log_rows = []
    for index, symbol in enumerate(symbols):
        result = download_symbol(symbol, args.start, args.end, outdir)
        print(f"[{index + 1}/{len(symbols)}] {symbol} ... {result['message']}")
        log_rows.append(
            {
                "symbol": result["symbol"],
                "status": result["status"],
                "rows": result["rows"],
            }
        )
        if args.sleep and index < len(symbols) - 1:
            time.sleep(args.sleep)

    log_df = pd.DataFrame(log_rows)
    log_path = outdir / "_download_log.csv"
    log_df.to_csv(log_path, index=False)
    print(f"\nDone. Summary saved to {log_path}")
    ok_count = (log_df["status"] == "ok").sum()
    print(f"{ok_count}/{len(symbols)} symbols downloaded successfully.")

    verify_no_duplicates(outdir)


def verify_no_duplicates(outdir: Path) -> None:
    """Fail-loud integrity guard: flag any CSVs that share identical data rows."""
    hashes: dict[str, list[str]] = {}
    for csv_file in sorted(outdir.glob("*.csv")):
        if csv_file.name == "_download_log.csv":
            continue
        with open(csv_file, "rb") as fh:
            next(fh, None)  # skip header line
            digest = hashlib.md5(fh.read()).hexdigest()
        hashes.setdefault(digest, []).append(csv_file.name)

    dups = [names for names in hashes.values() if len(names) > 1]
    if dups:
        print("\nWARNING: identical data detected in these file groups (possible bad download):")
        for names in dups:
            print("  " + ", ".join(names))
    else:
        print("Integrity check passed: no duplicate data files.")


if __name__ == "__main__":
    main()
