/*
 * Rotation portal - range and period arithmetic on engine-generated daily data.
 *
 * Nothing here re-runs the strategy. Every figure is derived from the engine's
 * daily portfolio (account equity, contributions and the chained time-weighted
 * index) by the same rules as com.rotation.engine.PerformanceCalculator:
 *   - an as-of date resolves to the last session on/before it;
 *   - a range [from, asOf] holds the sessions dated in it and is measured from
 *     the close of the session before `from`, or from initial capital at inception;
 *   - a range is truncated when it asks for history the backtest does not have.
 * Loaded as a plain script in the portal and as a CommonJS module in tests.
 */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) {
    module.exports = api;
  } else {
    root.PortalMetrics = api;
  }
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  const DAY_MS = 86400000;

  function toUtc(iso) {
    return Date.UTC(+iso.slice(0, 4), +iso.slice(5, 7) - 1, +iso.slice(8, 10));
  }

  function fromUtc(ms) {
    return new Date(ms).toISOString().slice(0, 10);
  }

  function addDays(iso, days) {
    return fromUtc(toUtc(iso) + days * DAY_MS);
  }

  function daysBetween(a, b) {
    return Math.round((toUtc(b) - toUtc(a)) / DAY_MS);
  }

  function isIsoDate(value) {
    if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
    return fromUtc(toUtc(value)) === value;
  }

  /** Index of the last element <= value in an ascending array (-1 when none). */
  function lastAtOrBefore(sorted, value) {
    let lo = 0;
    let hi = sorted.length - 1;
    let found = -1;
    while (lo <= hi) {
      const mid = (lo + hi) >> 1;
      if (sorted[mid] <= value) {
        found = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    return found;
  }

  /** Index of the first element >= value in an ascending array (length when none). */
  function firstAtOrAfter(sorted, value) {
    let lo = 0;
    let hi = sorted.length;
    while (lo < hi) {
      const mid = (lo + hi) >> 1;
      if (sorted[mid] < value) lo = mid + 1;
      else hi = mid;
    }
    return lo;
  }

  /**
   * Wraps the columnar daily portfolio. `sessions` is the full data calendar
   * (incl. warm-up before the trade start); `portfolio.d` indexes into it.
   */
  function createSeries(portfolio, sessions, initialCapital) {
    const dates = portfolio.d.map((i) => sessions[i]);
    return { p: portfolio, dates, sessions, initialCapital };
  }

  function resolveAsOf(series, iso) {
    return lastAtOrBefore(series.dates, iso);
  }

  function missingHistory(series, from) {
    const first = series.dates[0];
    if (from >= first) return false;
    const cal = series.sessions;
    if (!cal.length || from < cal[0]) return true;
    const i = firstAtOrAfter(cal, from);
    return i < cal.length && cal[i] < first;
  }

  function compute(series, label, requestedFrom, start, end, truncated) {
    const p = series.p;
    const fromInception = start === 0;
    const baseIndex = fromInception ? 1 : p.twr[start - 1];
    const startEquity = fromInception ? series.initialCapital : p.eq[start - 1];
    let contributions = 0;
    let peak = baseIndex;
    let maxDd = 0;
    let best = null;
    let worst = null;
    let sum = 0;
    let sumSq = 0;
    let n = 0;
    for (let i = start; i <= end; i++) {
      contributions += p.con[i];
      peak = Math.max(peak, p.twr[i]);
      maxDd = Math.min(maxDd, p.twr[i] / peak - 1);
      const r = p.ret[i];
      if (r !== null && r !== undefined) {
        if (best === null || r > best.value) best = { value: r, date: series.dates[i] };
        if (worst === null || r < worst.value) worst = { value: r, date: series.dates[i] };
        sum += r;
        sumSq += r * r;
        n++;
      }
    }
    const growth = p.twr[end] / baseIndex;
    const measuredFrom = fromInception ? series.dates[0] : series.dates[start - 1];
    const days = daysBetween(measuredFrom, series.dates[end]);
    const annualized = days >= 365 && growth > 0 ? (Math.pow(growth, 365.25 / days) - 1) * 100 : null;
    // Sample std-dev of daily returns, annualized with sqrt(252); needs >= 20 sessions.
    const volatility = n >= 20
      ? Math.sqrt(Math.max(0, (sumSq - (sum * sum) / n) / (n - 1))) * Math.sqrt(252)
      : null;
    return {
      label,
      requestedFrom,
      asOf: series.dates[end],
      firstSession: series.dates[start],
      baseDate: fromInception ? null : series.dates[start - 1],
      startRow: start,
      endRow: end,
      sessions: end - start + 1,
      startEquity,
      endEquity: p.eq[end],
      contributions,
      pnl: p.eq[end] - startEquity - contributions,
      returnPct: (growth - 1) * 100,
      maxDrawdownPct: maxDd * 100,
      annualizedReturnPct: annualized,
      volatilityPct: volatility,
      bestDay: best,
      worstDay: worst,
      truncated,
    };
  }

  /** Custom range; null when no session exists on/before asOf or the range is empty. */
  function range(series, label, from, asOf) {
    const end = resolveAsOf(series, asOf);
    if (end < 0 || from > series.dates[end]) return null;
    const start = Math.min(firstAtOrAfter(series.dates, from), end);
    return compute(series, label, from, start, end, missingHistory(series, from));
  }

  const PRESETS = ['30D', '30S', 'MTD', 'YTD', 'ALL'];
  const PRESET_LABELS = {
    '30D': 'Last 30 calendar days',
    '30S': 'Last 30 trading sessions',
    MTD: 'Month to date',
    YTD: 'Year to date',
    ALL: 'All history',
  };

  /** The `from` date a preset implies for an as-of row (mirrors the Java presets). */
  function presetFrom(series, preset, end) {
    const asOf = series.dates[end];
    switch (preset) {
      case '30D':
        return addDays(asOf, -29);
      case '30S':
        return series.dates[Math.max(0, end - 29)];
      case 'MTD':
        return asOf.slice(0, 8) + '01';
      case 'YTD':
        return asOf.slice(0, 5) + '01-01';
      case 'ALL':
        return series.dates[0];
      default:
        throw new Error('Unknown preset ' + preset);
    }
  }

  function preset(series, name, asOf) {
    const end = resolveAsOf(series, asOf);
    if (end < 0) return null;
    if (name === '30S') {
      const start = end - 29;
      return start >= 0
        ? compute(series, name, series.dates[start], start, end, false)
        : compute(series, name, series.dates[0], 0, end, true);
    }
    return range(series, name, presetFrom(series, name, end), series.dates[end]);
  }

  function presets(series, asOf) {
    return PRESETS.map((name) => preset(series, name, asOf)).filter(Boolean);
  }

  /**
   * Calendar-month or calendar-year returns up to the as-of row. A period is
   * partial at the start when the account was not invested from the period's first
   * session (`firstInvested`), and partial at the end when the as-of date stops
   * before the period's last session.
   */
  function periodReturns(series, kind, asOfRow, firstInvested) {
    const keyLen = kind === 'year' ? 4 : 7;
    const out = [];
    if (asOfRow < 0) return out;
    const cal = series.sessions;
    const lastCal = cal[cal.length - 1];
    let start = 0;
    while (start <= asOfRow) {
      const key = series.dates[start].slice(0, keyLen);
      let end = start;
      while (end + 1 <= asOfRow && series.dates[end + 1].slice(0, keyLen) === key) end++;
      const m = compute(series, key, series.dates[start], start, end, false);
      // First calendar session of the period in the data (warm-up included).
      const periodFirst = cal[firstAtOrAfter(cal, key.length === 4 ? key + '-01-01' : key + '-01')];
      const investedFrom = firstInvested && firstInvested > periodFirst ? firstInvested : periodFirst;
      const startsLate = series.dates[start] > periodFirst || investedFrom > periodFirst;
      const nextIdx = firstAtOrAfter(cal, addDays(series.dates[end], 1));
      const periodContinues = nextIdx < cal.length
        ? cal[nextIdx].slice(0, keyLen) === key
        : periodEndAfter(key, lastCal);
      out.push({
        key,
        firstSession: series.dates[start],
        lastSession: series.dates[end],
        returnPct: m.returnPct,
        pnl: m.pnl,
        contributions: m.contributions,
        startEquity: m.startEquity,
        endEquity: m.endEquity,
        maxDrawdownPct: m.maxDrawdownPct,
        sessions: m.sessions,
        partial: startsLate || periodContinues,
        partialReason: startsLate
          ? 'invested from ' + (investedFrom > series.dates[start] ? investedFrom : series.dates[start])
          : periodContinues ? 'to ' + series.dates[end] : null,
      });
      start = end + 1;
    }
    return out;
  }

  /** True when a period has calendar weekdays after `lastSession` (data ends mid-period). */
  function periodEndAfter(key, lastSession) {
    let periodEnd;
    if (key.length === 4) {
      periodEnd = key + '-12-31';
    } else {
      const y = +key.slice(0, 4);
      const m = +key.slice(5, 7);
      periodEnd = fromUtc(Date.UTC(y, m, 0));
    }
    for (let d = addDays(lastSession, 1); d <= periodEnd; d = addDays(d, 1)) {
      const dow = new Date(toUtc(d)).getUTCDay();
      if (dow !== 0 && dow !== 6) return true;
    }
    return false;
  }

  /** Drawdown episodes on the TWR index up to the as-of row, deepest first. */
  function drawdownEpisodes(series, asOfRow, limit) {
    const p = series.p;
    const episodes = [];
    let peakIdx = -1; // -1 = inception (index 1.0)
    let peakVal = 1;
    let current = null;
    for (let i = 0; i <= asOfRow; i++) {
      const v = p.twr[i];
      if (v >= peakVal) {
        if (current) {
          current.recovery = series.dates[i];
          current.recoverySessions = i - current.peakRow;
          episodes.push(current);
          current = null;
        }
        peakVal = v;
        peakIdx = i;
      } else {
        const depth = (v / peakVal - 1) * 100;
        if (!current) {
          current = {
            peak: peakIdx < 0 ? null : series.dates[peakIdx],
            peakRow: peakIdx,
            trough: series.dates[i],
            depthPct: depth,
            recovery: null,
            recoverySessions: null,
          };
        } else if (depth < current.depthPct) {
          current.trough = series.dates[i];
          current.depthPct = depth;
        }
      }
    }
    if (current) episodes.push(current);
    episodes.sort((a, b) => a.depthPct - b.depthPct);
    return episodes.slice(0, limit || 10);
  }

  /** Decode one symbol's embedded prices (see PortalExporter) into per-session arrays. */
  function decodePrices(entry, sessions) {
    const scale = Math.pow(10, entry.k);
    const out = [];
    let close = 0;
    for (let i = 0; i < entry.c.length; i++) {
      if (entry.c[i] === null) {
        out.push({ time: sessions[entry.s + i], missing: true });
        continue;
      }
      close += entry.c[i];
      out.push({
        time: sessions[entry.s + i],
        open: (close + entry.o[i]) / scale,
        high: (close + entry.h[i]) / scale,
        low: (close + entry.l[i]) / scale,
        close: close / scale,
      });
    }
    return out;
  }

  return {
    PRESETS,
    PRESET_LABELS,
    addDays,
    daysBetween,
    isIsoDate,
    lastAtOrBefore,
    firstAtOrAfter,
    createSeries,
    resolveAsOf,
    range,
    preset,
    presets,
    presetFrom,
    periodReturns,
    drawdownEpisodes,
    decodePrices,
  };
});
