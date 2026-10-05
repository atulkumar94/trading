function getStepLogger(manager) {
    try {
        if (manager && typeof manager.getLogger === "function") {
            return manager.getLogger();
        }
    }
    catch (_error) {
        // Ignore and fall back to console logger.
    }
    return {
        info: function (message) { if (typeof console !== "undefined" && console.log) { console.log(message); } },
        warn: function (message) { if (typeof console !== "undefined" && console.warn) { console.warn(message); } },
        error: function (message) { if (typeof console !== "undefined" && console.error) { console.error(message); } },
        severe: function (message) { if (typeof console !== "undefined" && console.error) { console.error(message); } }
    };
}

/**
 * STEP Business Rule
 * Trademark: AI Labz Ltd (TM)
 * Purpose: Describe the rule intent and expected side effects.
 * Notes: Keep transactions short and avoid repeated writes in loops.
 */

/*
 * Rotation backtest portal (read-only). Displays, filters and charts the data the
 * engine embedded in #portal-data. Financial figures come from the engine; the
 * only arithmetic here is range/period compounding in portal-metrics.js.
 */
(function () {
  'use strict';

  const M = window.PortalMetrics;
  const LWC = window.LightweightCharts;
  const VIEWS = ['overview', 'tradebook', 'holdings', 'stocks', 'performance', 'rankings', 'run'];
  const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

  let D = null;            // embedded data
  let X = null;            // derived indexes
  const S = {              // UI state (mirrored in the URL hash)
    view: 'overview',
    asOf: null,
    preset: '30D',
    from: null,
    symbol: '',
    stock: null,
    cmp: [],
    reb: null,
    tbAction: 'TRADES',
    reweights: true,
  };
  const charts = [];

  // ------------------------------------------------------------------ boot

  /**
 * STEP business rule function: boot.
 * TODO: Describe purpose, side effects, and expected node/context state.
 */
  function boot() {
    try {
      if (!M || !LWC) throw new Error('Embedded scripts did not load.');
      D = JSON.parse(document.getElementById('portal-data').textContent);
      X = index(D);
    } catch (err) {
      showFatal('Could not read the embedded backtest data.', err);
      return;
    }
    try {
      initTheme();
      readHash();
      wireToolbar();
      render();
      document.getElementById('loading').hidden = true;
    } catch (err) {
      showFatal('The portal failed to render.', err);
    }
  }

  /**
 * STEP business rule function: showFatal.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} message
 * @param {*} err
 */
  function showFatal(message, err) {
    const box = document.querySelector('#loading .state-box');
    replaceKids(box, h('p', { class: 'loss', text: message }),
      h('p', { class: 'muted', text: String(err && err.message || err) }),
      h('p', { class: 'muted', text: 'Regenerate the portal with the rotation engine (see README).' }));
    document.getElementById('loading').hidden = false;
    console.error(err);
  }

  /**
 * STEP business rule function: index.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} data
 */
  function index(data) {
    const x = {};
    x.sessions = data.sessions;
    x.symbols = data.symbols;
    x.symIdx = new Map(data.symbols.map((s, i) => [s, i]));
    x.manifest = data.manifest;
    x.capital = data.manifest.strategy.initial_capital;
    x.topN = data.manifest.strategy.top_n;
    x.series = M.createSeries(data.portfolio, data.sessions, x.capital);
    x.rowOfDate = new Map(x.series.dates.map((d, i) => [d, i]));
    if (!x.series.dates.length) throw new Error('The daily portfolio is empty.');

    const pos = data.positions;
    x.posBySession = new Map();
    for (let i = 0; i < pos.d.length; i++) {
      const key = pos.d[i];
      const range = x.posBySession.get(key);
      if (range) range[1] = i + 1;
      else x.posBySession.set(key, [i, i + 1]);
    }

    const L = data.ledger;
    x.ledger = L.d.map((d, i) => ({
      date: x.sessions[d],
      signal: x.sessions[L.sd[i]],
      reb: L.reb[i],
      action: data.actions[L.a[i]],
      symbol: x.symbols[L.s[i]],
      qty: L.q[i],
      price: L.p[i],
      value: L.v[i],
      entryDate: x.sessions[L.ed[i]],
      entryPrice: L.ep[i],
      avgBefore: L.acb[i],
      avgAfter: L.aca[i],
      posAfter: L.pa[i],
      realized: L.rp[i],
      vsEntry: L.pe[i],
      cash: L.cash[i],
      reason: L.r[i],
    }));

    x.rebalances = data.rebalances.map((r) => ({
      n: r.n, date: x.sessions[r.d], signal: x.sessions[r.sd], lookbackStart: r.lb === null ? null : x.sessions[r.lb],
      before: r.before, after: r.after, equity: r.eq, pnl: r.pnl, ret: r.ret, contribution: r.con, count: r.cnt,
      entered: splitList(r.entered), exited: splitList(r.exited), held: splitList(r.held),
    }));
    const R = data.rankings;
    x.rankRange = new Map();
    for (let i = 0; i < R.n.length; i++) {
      const rr = x.rankRange.get(R.n[i]);
      if (rr) rr[1] = i + 1;
      else x.rankRange.set(R.n[i], [i, i + 1]);
    }
    const LB = data.lookback;
    x.lookbackSignals = [];
    x.lookbackRange = new Map();
    for (let i = 0; i < LB.sd.length; i++) {
      const sig = x.sessions[LB.sd[i]];
      const rr = x.lookbackRange.get(sig);
      if (rr) rr[1] = i + 1;
      else {
        x.lookbackRange.set(sig, [i, i + 1]);
        x.lookbackSignals.push(sig);
      }
    }
    x.firstInvested = x.rebalances.length ? x.rebalances[0].date : null;
    x.priceCache = new Map();
    return x;
  }

  /**
 * STEP business rule function: splitList.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} s
 */
  function splitList(s) {
    return s ? s.split(',').filter(Boolean) : [];
  }

  // ------------------------------------------------------------------ state & context

  /**
 * STEP business rule function: readHash.
 * TODO: Describe purpose, side effects, and expected node/context state.
 */
  function readHash() {
    const params = new URLSearchParams(location.hash.slice(1));
    const v = params.get('v');
    if (VIEWS.includes(v)) S.view = v;
    const asOf = params.get('asof');
    S.asOf = M.isIsoDate(asOf) ? asOf : lastDate();
    const p = params.get('p');
    if (M.PRESETS.includes(p) || p === 'CUSTOM') S.preset = p;
    const from = params.get('from');
    if (S.preset === 'CUSTOM') S.from = M.isIsoDate(from) ? from : X.series.dates[0];
    S.symbol = params.get('sym') || '';
    const stock = params.get('stock');
    if (stock && X.symIdx.has(stock)) S.stock = stock;
    S.cmp = (params.get('cmp') || '').split(',').filter((c) => c === 'PORTFOLIO' || X.symIdx.has(c)).slice(0, 2);
    const reb = +params.get('reb');
    if (reb > 0) S.reb = reb;
  }

  /**
 * STEP business rule function: writeHash.
 * TODO: Describe purpose, side effects, and expected node/context state.
 */
  function writeHash() {
    const params = new URLSearchParams();
    params.set('v', S.view);
    params.set('asof', S.asOf);
    params.set('p', S.preset);
    if (S.preset === 'CUSTOM') params.set('from', S.from);
    if (S.symbol) params.set('sym', S.symbol);
    if (S.stock) params.set('stock', S.stock);
    if (S.cmp.length) params.set('cmp', S.cmp.join(','));
    if (S.reb) params.set('reb', String(S.reb));
    history.replaceState(null, '', '#' + params.toString());
  }

  /**
 * STEP business rule function: lastDate.
 * TODO: Describe purpose, side effects, and expected node/context state.
 */
  function lastDate() {
    return X.series.dates[X.series.dates.length - 1];
  }

  /** Everything derived from the toolbar: the effective as-of row and the range. */
  function context() {
    const c = { requestedAsOf: S.asOf };
    c.row = M.resolveAsOf(X.series, S.asOf);
    if (c.row < 0) {
      c.noData = true;
      return c;
    }
    c.asOf = X.series.dates[c.row];
    c.session = D.portfolio.d[c.row];
    c.shifted = c.asOf !== S.asOf;
    c.future = S.asOf > lastDate();
    if (S.preset === 'CUSTOM') {
      c.from = S.from;
      c.invalid = !M.isIsoDate(S.from) || S.from > c.asOf;
      c.range = c.invalid ? null : M.range(X.series, 'Custom range', S.from, c.asOf);
      if (!c.range) c.invalid = true;
    } else {
      c.range = M.preset(X.series, S.preset, c.asOf);
      c.from = M.presetFrom(X.series, S.preset, c.row);
    }
    return c;
  }

  /**
 * STEP business rule function: setState.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} patch
 */
  function setState(patch) {
    Object.assign(S, patch);
    render();
  }

  // ------------------------------------------------------------------ toolbar

  /**
 * STEP business rule function: wireToolbar.
 * TODO: Describe purpose, side effects, and expected node/context state.
 */
  function wireToolbar() {
    const asOf = document.getElementById('as-of');
    const from = document.getElementById('range-from');
    const sym = document.getElementById('symbol-filter');
    asOf.min = X.series.dates[0];
    asOf.max = lastDate();
    from.min = X.sessions[0];
    from.max = lastDate();
    asOf.addEventListener('change', () => {
      if (M.isIsoDate(asOf.value)) setState({ asOf: asOf.value });
    });
    from.addEventListener('change', () => {
      if (M.isIsoDate(from.value)) setState({ preset: 'CUSTOM', from: from.value });
    });
    document.querySelectorAll('#presets button').forEach((b) => {
      b.addEventListener('click', () => setState({ preset: b.dataset.preset, from: null }));
    });
    const list = document.getElementById('symbol-list');
    X.symbols.forEach((s) => list.append(h('option', { value: s })));
    let timer = null;
    sym.addEventListener('input', () => {
      clearTimeout(timer);
      timer = setTimeout(() => {
        const value = sym.value.trim().toUpperCase();
        const patch = { symbol: value };
        if (X.symIdx.has(value)) patch.stock = value;
        setState(patch);
      }, 200);
    });
    document.querySelectorAll('.tabs button').forEach((b) => {
      b.addEventListener('click', () => setState({ view: b.dataset.view }));
      b.addEventListener('keydown', (e) => {
        if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return;
        const i = VIEWS.indexOf(S.view) + (e.key === 'ArrowRight' ? 1 : -1);
        const next = VIEWS[(i + VIEWS.length) % VIEWS.length];
        setState({ view: next });
        document.querySelector('.tabs button[data-view="' + next + '"]').focus();
      });
    });
    document.getElementById('theme-toggle').addEventListener('click', () => {
      const dark = getComputedStyle(document.documentElement).colorScheme.includes('dark');
      const theme = dark ? 'light' : 'dark';
      document.documentElement.dataset.theme = theme;
      try { localStorage.setItem('rotation-portal-theme', theme); } catch (e) { /* file:// may block storage */ }
      render();
    });
    const m = X.manifest;
    document.getElementById('run-label').textContent =
      'top ' + m.strategy.top_n + ' · ' + m.strategy.lookback_days + 'd lookback · ' + m.strategy.rebalance_mode
      + ' · generated ' + m.generated_at.replace('T', ' ').slice(0, 16);
  }

  /**
 * STEP business rule function: initTheme.
 * TODO: Describe purpose, side effects, and expected node/context state.
 */
  function initTheme() {
    try {
      const t = localStorage.getItem('rotation-portal-theme');
      if (t === 'dark' || t === 'light') document.documentElement.dataset.theme = t;
    } catch (e) { /* ignore */ }
  }

  /**
 * STEP business rule function: renderToolbar.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} c
 */
  function renderToolbar(c) {
    document.getElementById('as-of').value = S.asOf;
    const from = document.getElementById('range-from');
    from.value = c.from || '';
    from.setAttribute('aria-invalid', c.invalid ? 'true' : 'false');
    document.querySelectorAll('#presets button').forEach((b) => {
      b.setAttribute('aria-pressed', String(b.dataset.preset === S.preset));
    });
    const sym = document.getElementById('symbol-filter');
    if (document.activeElement !== sym) sym.value = S.symbol;
    document.querySelectorAll('.tabs button').forEach((b) => {
      b.setAttribute('aria-selected', String(b.dataset.view === S.view));
      b.tabIndex = b.dataset.view === S.view ? 0 : -1;
    });

    const line = document.getElementById('scope-line');
    const parts = [];
    if (c.noData) {
      parts.push(h('span', { class: 'bad', text: 'No backtest session on or before ' + S.asOf
        + '. The backtest covers ' + X.series.dates[0] + ' → ' + lastDate() + '.' }));
    } else {
      parts.push(h('span', {}, 'As of ', h('strong', { text: dateLabel(c.asOf) }), ' (end of day, after that session\'s executions)'));
      if (c.shifted) {
        parts.push(h('span', { class: 'warn', text: c.future
          ? S.asOf + ' is after the last available session; showing ' + c.asOf
          : S.asOf + ' is not a trading session; showing the previous session ' + c.asOf }));
      }
      if (c.invalid) {
        parts.push(h('span', { class: 'bad', text: 'Invalid range: From ' + (S.from || '?') + ' is after the as-of date ' + c.asOf + '.' }));
      } else if (c.range) {
        const r = c.range;
        parts.push(h('span', {}, 'Range ', h('strong', { text: (S.preset === 'CUSTOM' ? 'Custom' : M.PRESET_LABELS[S.preset]) }),
          ': ' + r.firstSession + ' → ' + r.asOf + ' · ' + r.sessions + ' session' + (r.sessions === 1 ? '' : 's')
          + ' · measured from ' + (r.baseDate ? r.baseDate + ' close' : 'initial capital')));
        if (r.truncated) {
          parts.push(h('span', { class: 'warn', text: 'Insufficient history: the backtest starts ' + X.series.dates[0]
            + ', so this range covers only ' + r.sessions + ' session' + (r.sessions === 1 ? '' : 's') }));
        }
      }
      if (S.symbol) parts.push(h('span', {}, 'Symbol filter ', h('strong', { text: S.symbol })));
    }
    replaceKids(line, ...parts);
  }

  // ------------------------------------------------------------------ render

  /**
 * STEP business rule function: render.
 * TODO: Describe purpose, side effects, and expected node/context state.
 */
  function render() {
    const c = context();
    renderToolbar(c);
    writeHash();
    while (charts.length) charts.pop().remove();
    VIEWS.forEach((v) => { document.getElementById('view-' + v).hidden = v !== S.view; });
    const root = document.getElementById('view-' + S.view);
    replaceKids(root);
    if (c.noData && S.view !== 'run') {
      root.append(panel('No data', null, h('div', { class: 'notice bad', text: 'There is no backtest data on or before '
        + S.asOf + '. Choose an as-of date between ' + X.series.dates[0] + ' and ' + lastDate() + '.' })));
      return;
    }
    ({
      overview: renderOverview,
      tradebook: renderTradebook,
      holdings: renderHoldings,
      stocks: renderStocks,
      performance: renderPerformance,
      rankings: renderRankings,
      run: renderRun,
    })[S.view](root, c);
  }

  // ------------------------------------------------------------------ overview

  /**
 * STEP business rule function: renderOverview.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} root
 * @param {*} c
 */
  function renderOverview(root, c) {
    const p = D.portfolio;
    const i = c.row;
    const peakRow = findPeak(i);
    const missing = p.miss[i];
    root.append(panel('Account', 'asof', h('div', { class: 'kpis' },
      kpi('Account equity', money0(p.eq[i]), 'net capital ' + money0(p.net[i])),
      kpi('Cash', money0(p.cash[i]), pct(p.eq[i] ? p.cash[i] / p.eq[i] * 100 : 0, 1, false) + ' of equity'),
      kpi('Invested value', money0(p.inv[i]), p.pos[i] + ' position' + (p.pos[i] === 1 ? '' : 's')
        + (missing ? ' · ' + missing + ' missing price' : '')),
      kpi('Total P&L', signedMoney(p.tpnl[i]), h('span', {}, 'realized ', signedMoney(p.real[i]), ' · unrealized ', signedMoney(p.unr[i]))),
      kpi('Drawdown from peak', pct(p.dd[i], 2, true), peakRow === null ? 'at peak' : 'peak ' + X.series.dates[peakRow]),
      kpi('Contributions to date', money0(p.ccon[i]), 'excluded from P&L and returns'),
    ), missing ? h('div', { class: 'notice warn', text: missing + ' held position(s) have no close on ' + c.asOf
      + ' and are valued at zero, as the engine does. See Holdings.' }) : null));

    if (c.invalid || !c.range) {
      root.append(invalidRange(c));
      return;
    }
    const r = c.range;
    root.append(panel('Selected range', 'range', h('div', { class: 'kpis' },
      kpi('Range return (TWR)', pct(r.returnPct, 2, true), 'from ' + (r.baseDate ? r.baseDate + ' close' : 'inception')),
      kpi('Range P&L', signedMoney(r.pnl), 'excludes contributions'),
      kpi('Contributions in range', money0(r.contributions), r.sessions + ' sessions'),
      kpi('Max drawdown in range', pct(r.maxDrawdownPct, 2, true), 'peak reset at range start'),
      kpi('Best / worst day', r.bestDay ? h('span', {}, pct(r.bestDay.value, 2, true), ' / ', pct(r.worstDay.value, 2, true)) : '—',
        r.bestDay ? r.bestDay.date + ' / ' + r.worstDay.date : ''),
      kpi('Annualized return', r.annualizedReturnPct === null ? 'n/a' : pct(r.annualizedReturnPct, 2, true),
        r.annualizedReturnPct === null ? 'range under 1 year' : 'time-weighted'),
      kpi('Annualized volatility', r.volatilityPct === null ? 'n/a' : pct(r.volatilityPct, 2, false),
        r.volatilityPct === null ? 'needs ≥ 20 sessions' : 'daily, √252'),
    )));

    const start = Math.max(0, r.startRow - 1);
    const rows = [];
    for (let k = start; k <= r.endRow; k++) rows.push(k);
    const eqLegend = h('div', { class: 'legend' });
    const eqBox = h('div', { class: 'chart' });
    const ddLegend = h('div', { class: 'legend' });
    const ddBox = h('div', { class: 'chart small' });
    const retLegend = h('div', { class: 'legend' });
    const retBox = h('div', { class: 'chart small' });
    root.append(panel('Equity curve', 'range', eqLegend, eqBox,
      h('p', { class: 'muted', text: 'Account equity at each session close. The grey line is net capital (initial capital + contributions); the axis scales to equity, so it is off-chart when far below.' })));
    root.append(h('div', { class: 'grid-2' },
      panel('Drawdown (TWR, from running peak since inception)', 'range', ddLegend, ddBox),
      panel('Daily returns (contribution-adjusted)', 'range', retLegend, retBox)));

    const css = cssVars();
    const eqChart = makeChart(eqBox);
    const eqSeries = eqChart.addSeries(LWC.LineSeries, { color: css.series1, lineWidth: 2, priceLineVisible: false, title: '' });
    // The net-capital reference must not drive the scale (it would flatten the equity curve).
    const netSeries = eqChart.addSeries(LWC.LineSeries, { color: css.reference, lineWidth: 1, priceLineVisible: false, lastValueVisible: false,
      crosshairMarkerVisible: false, autoscaleInfoProvider: () => null });
    eqSeries.setData(rows.map((k) => ({ time: X.series.dates[k], value: p.eq[k] })));
    netSeries.setData(rows.map((k) => ({ time: X.series.dates[k], value: p.net[k] })));
    const ddChart = makeChart(ddBox, { priceFormat: 'pct' });
    const ddSeries = ddChart.addSeries(LWC.AreaSeries, {
      lineColor: css.loss, topColor: withAlpha(css.loss, 0.05), bottomColor: withAlpha(css.loss, 0.3), lineWidth: 2,
      priceLineVisible: false, priceFormat: { type: 'custom', formatter: (v) => v.toFixed(1) + '%' },
    });
    ddSeries.setData(rows.map((k) => ({ time: X.series.dates[k], value: p.dd[k] })));
    const retChart = makeChart(retBox);
    const retSeries = retChart.addSeries(LWC.HistogramSeries, {
      priceLineVisible: false, priceFormat: { type: 'custom', formatter: (v) => v.toFixed(2) + '%' },
    });
    retSeries.setData(rows.map((k) => {
      const v = p.ret[k] === null ? 0 : p.ret[k];
      return { time: X.series.dates[k], value: v, color: v >= 0 ? css.gainMark : css.lossMark };
    }));
    [eqChart, ddChart, retChart].forEach((ch) => ch.timeScale().fitContent());
    syncCharts([eqChart, ddChart, retChart]);

    /**
 * STEP business rule function: legendAt.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} k
 */
    const legendAt = (k) => {
      replaceKids(eqLegend, legendItem(css.series1, 'Equity', money0(p.eq[k])),
        legendItem(css.reference, 'Net capital', money0(p.net[k])), h('span', { class: 'muted', text: dateLabel(X.series.dates[k]) }));
      replaceKids(ddLegend, legendItem(css.loss, 'Drawdown', pct(p.dd[k], 2, true)), h('span', { class: 'muted', text: X.series.dates[k] }));
      replaceKids(retLegend, legendItem(p.ret[k] >= 0 ? css.gainMark : css.lossMark, 'Return', p.ret[k] === null ? 'n/a' : pct(p.ret[k], 2, true), true),
        legendItem(null, 'P&L', signedMoney(p.dpnl[k])), h('span', { class: 'muted', text: X.series.dates[k] }));
    };
    legendAt(r.endRow);
    [eqChart, ddChart, retChart].forEach((ch) => ch.subscribeCrosshairMove((param) => {
      const k = param && param.time ? X.rowOfDate.get(timeKey(param.time)) : undefined;
      legendAt(k === undefined ? r.endRow : k);
    }));
  }

  /**
 * STEP business rule function: findPeak.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} row
 */
  function findPeak(row) {
    const twr = D.portfolio.twr;
    let peakRow = null;
    let peak = 1;
    for (let k = 0; k <= row; k++) {
      if (twr[k] >= peak) { peak = twr[k]; peakRow = k; }
    }
    return twr[row] >= peak ? null : peakRow;
  }

  // ------------------------------------------------------------------ tradebook

  /**
 * STEP business rule function: renderTradebook.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} root
 * @param {*} c
 */
  function renderTradebook(root, c) {
    if (c.invalid || !c.range) {
      root.append(invalidRange(c));
      return;
    }
    const from = c.range.firstSession;
    const filters = {
      TRADES: (r) => r.action === 'ENTRY' || r.action === 'EXIT' || r.action === 'STOP' || r.action === 'DROP',
      ALL: () => true,
    };
    const actionFilter = filters[S.tbAction] || ((r) => r.action === S.tbAction);
    const rows = X.ledger.filter((r) => r.date >= from && r.date <= c.asOf && actionFilter(r) && symbolMatch(r.symbol));
    const counts = {};
    let realized = 0;
    let vsEntry = 0;
    rows.forEach((r) => {
      counts[r.action] = (counts[r.action] || 0) + 1;
      if (r.realized !== null) realized += r.realized;
      if (r.vsEntry !== null) vsEntry += r.vsEntry;
    });
    const select = h('select', { 'aria-label': 'Action filter', onchange: (e) => setState({ tbAction: e.target.value }) },
      ...[['TRADES', 'Entries, exits & stops'], ['ALL', 'All fills incl. re-weights'], ['ENTRY', 'ENTRY'], ['EXIT', 'EXIT'],
        ['STOP', 'STOP'], ['ADD', 'ADD (re-weight)'], ['TRIM', 'TRIM (re-weight)'], ['DROP', 'DROP (write-off)']]
        .map(([v, l]) => h('option', { value: v, selected: v === S.tbAction, text: l })));
    const summary = h('div', { class: 'inline-controls secondary' },
      h('span', {}, Object.keys(counts).length ? Object.entries(counts).map(([a, n]) => a + ' ' + n).join(' · ') : 'No fills'),
      h('span', {}, 'Realized P&L (avg cost): ', signedMoney(realized)),
      h('span', {}, 'Exit P&L vs entry price: ', signedMoney(vsEntry)));
    const table = dataTable({
      columns: ledgerColumns(),
      rows,
      sort: { key: 'date', dir: 'desc' },
      exportName: 'tradebook_' + from + '_' + c.asOf,
      empty: 'No ' + (S.tbAction === 'TRADES' ? 'trades' : S.tbAction + ' fills') + ' between ' + from + ' and ' + c.asOf
        + (S.symbol ? ' for ' + S.symbol : '') + '.',
      onRowClick: (r) => openStock(r.symbol),
      tools: [select],
    });
    root.append(panel('Tradebook · fills executed ' + from + ' → ' + c.asOf, 'range', summary, table,
      h('p', { class: 'muted', text: 'Trade date = execution at that session\'s open; signal date = the close the decision used. '
        + 'Realized P&L uses average cost incl. re-weights (ADD/TRIM rows); "vs entry" is the engine tradebook\'s (exit − first entry price) × quantity. '
        + 'Click a row to open its stock chart.' })));
  }

  /**
 * STEP business rule function: ledgerColumns.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} opts
 */
  function ledgerColumns(opts) {
    const cols = [
      { key: 'date', label: 'Trade date', type: 'date', get: (r) => r.date },
      { key: 'signal', label: 'Signal date', type: 'date', get: (r) => r.signal },
      { key: 'reb', label: 'Rebal #', type: 'int', get: (r) => r.reb },
      { key: 'action', label: 'Action', type: 'text', get: (r) => r.action, fmt: (r) => h('span', { class: 'act ' + actionClass(r.action), text: r.action }) },
      { key: 'symbol', label: 'Symbol', type: 'text', get: (r) => r.symbol },
      { key: 'qty', label: 'Qty', type: 'num', get: (r) => r.qty, fmt: (r) => qty(r.qty) },
      { key: 'price', label: 'Price', type: 'num', get: (r) => r.price, fmt: (r) => price(r.price) },
      { key: 'value', label: 'Trade value', type: 'num', get: (r) => r.value, fmt: (r) => money(r.value) },
      { key: 'entryDate', label: 'Entry date', type: 'date', get: (r) => r.entryDate },
      { key: 'avg', label: 'Avg cost', type: 'num', get: (r) => (isSell(r.action) ? r.avgBefore : r.avgAfter), fmt: (r) => price(isSell(r.action) ? r.avgBefore : r.avgAfter) },
      { key: 'realized', label: 'Realized P&L', type: 'num', get: (r) => r.realized, fmt: (r) => (r.realized === null ? '' : signedMoney(r.realized)) },
      { key: 'vsEntry', label: 'P&L vs entry', type: 'num', get: (r) => r.vsEntry, fmt: (r) => (r.vsEntry === null ? '' : signedMoney(r.vsEntry)) },
      { key: 'posAfter', label: 'Position after', type: 'num', get: (r) => r.posAfter, fmt: (r) => qty(r.posAfter) },
      { key: 'cash', label: 'Cash after', type: 'num', get: (r) => r.cash, fmt: (r) => money(r.cash) },
      { key: 'reason', label: 'Reason', type: 'text', get: (r) => r.reason, wrap: true },
    ];
    return opts && opts.withoutSymbol ? cols.filter((col) => col.key !== 'symbol') : cols;
  }

  /**
 * STEP business rule function: isSell.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} action
 */
  function isSell(action) {
    return action === 'EXIT' || action === 'STOP' || action === 'TRIM' || action === 'DROP';
  }

  /**
 * STEP business rule function: actionClass.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} a
 */
  function actionClass(a) {
    return a === 'ENTRY' || a === 'ADD' ? 'gain' : a === 'EXIT' || a === 'STOP' || a === 'DROP' ? 'loss' : 'secondary';
  }

  // ------------------------------------------------------------------ holdings

  /**
 * STEP business rule function: positionsAt.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} session
 */
  function positionsAt(session) {
    const range = X.posBySession.get(session);
    if (!range) return [];
    const P = D.positions;
    const out = [];
    for (let i = range[0]; i < range[1]; i++) {
      out.push({
        symbol: X.symbols[P.s[i]], sector: D.sectors[X.symbols[P.s[i]]] || '', entryDate: X.sessions[P.ed[i]],
        reb: P.reb[i], qty: P.q[i], entryPrice: P.ep[i], avg: P.ac[i], cost: P.cb[i], close: P.c[i],
        mv: P.mv[i], upnl: P.upnl[i], upct: P.upct[i], weight: P.w[i], status: D.priceStatuses[P.st[i]],
      });
    }
    return out;
  }

  /**
 * STEP business rule function: renderHoldings.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} root
 * @param {*} c
 */
  function renderHoldings(root, c) {
    const p = D.portfolio;
    const i = c.row;
    const all = positionsAt(c.session);
    const rows = all.filter((r) => symbolMatch(r.symbol));
    const book = X.rebalances.find((r) => r.n === p.reb[i]);
    const pending = X.rebalances.find((r) => r.signal === c.asOf && r.date > c.asOf);
    const facts = h('dl', { class: 'facts' },
      h('dt', { text: 'Valuation' }), h('dd', { text: dateLabel(c.asOf) + ' close (adjusted)' }),
      h('dt', { text: 'Book from' }), h('dd', { text: book ? 'Rebalance #' + book.n + ', executed ' + book.date + ' open (signal ' + book.signal + ' close)' : 'No rebalance yet — all cash' }),
      h('dt', { text: 'Equity' }), h('dd', { text: money(p.eq[i]) }),
      h('dt', { text: 'Cash' }), h('dd', { text: money(p.cash[i]) }),
      h('dt', { text: 'Invested' }), h('dd', { text: money(p.inv[i]) }),
      h('dt', { text: 'Unrealized P&L' }), h('dd', {}, signedMoney(p.unr[i])),
      h('dt', { text: 'Realized P&L to date' }), h('dd', {}, signedMoney(p.real[i])));
    const notices = [];
    if (pending) {
      notices.push(h('div', { class: 'notice', text: 'Rebalance #' + pending.n + ' was signalled on the ' + c.asOf
        + ' close and executes at the ' + pending.date + ' open; it is not reflected in these holdings.' }));
    }
    const flagged = all.filter((r) => r.status !== 'OK');
    flagged.forEach((r) => notices.push(h('div', { class: 'notice warn', text: r.symbol + ': '
      + (r.status === 'MISSING' ? 'no close on ' + c.asOf + ' — valued at zero (engine convention).'
        : 'bar identical to the prior session — price may be stale (forward-filled).') })));

    const totals = rows.reduce((t, r) => ({ cost: t.cost + r.cost, mv: t.mv + r.mv, upnl: t.upnl + r.upnl, w: t.w + r.weight }), { cost: 0, mv: 0, upnl: 0, w: 0 });
    const table = dataTable({
      columns: [
        { key: 'symbol', label: 'Symbol', type: 'text', get: (r) => r.symbol },
        { key: 'sector', label: 'Sector', type: 'text', get: (r) => r.sector },
        { key: 'entryDate', label: 'Entry date', type: 'date', get: (r) => r.entryDate },
        { key: 'days', label: 'Days held', type: 'int', get: (r) => M.daysBetween(r.entryDate, c.asOf) },
        { key: 'qty', label: 'Qty', type: 'num', get: (r) => r.qty, fmt: (r) => qty(r.qty) },
        { key: 'entryPrice', label: 'Entry price', type: 'num', get: (r) => r.entryPrice, fmt: (r) => price(r.entryPrice) },
        { key: 'avg', label: 'Avg cost', type: 'num', get: (r) => r.avg, fmt: (r) => price(r.avg) },
        { key: 'cost', label: 'Cost basis', type: 'num', get: (r) => r.cost, fmt: (r) => money(r.cost) },
        { key: 'close', label: 'Close', type: 'num', get: (r) => r.close, fmt: (r) => (r.close === null ? h('span', { class: 'badge bad', text: 'missing' }) : price(r.close)) },
        { key: 'mv', label: 'Market value', type: 'num', get: (r) => r.mv, fmt: (r) => money(r.mv) },
        { key: 'upnl', label: 'Unrealized P&L', type: 'num', get: (r) => r.upnl, fmt: (r) => signedMoney(r.upnl) },
        { key: 'upct', label: 'Unrealized %', type: 'num', get: (r) => r.upct, fmt: (r) => pct(r.upct, 2, true) },
        { key: 'weight', label: 'Weight %', type: 'num', get: (r) => r.weight, fmt: (r) => pct(r.weight, 2, false) },
        { key: 'status', label: 'Price', type: 'text', get: (r) => r.status, fmt: (r) => h('span', { class: 'badge ' + (r.status === 'OK' ? 'ok' : r.status === 'MISSING' ? 'bad' : 'warn'), text: r.status }) },
      ],
      rows,
      sort: { key: 'mv', dir: 'desc' },
      exportName: 'holdings_' + c.asOf,
      empty: all.length ? 'No holdings match the symbol filter.' : 'No positions held at the ' + c.asOf + ' close (all cash).',
      onRowClick: (r) => openStock(r.symbol),
      footer: rows.length ? ['Total' + (S.symbol ? ' (filtered)' : ''), '', '', '', '', '', '', money(totals.cost), '', money(totals.mv), signedMoney(totals.upnl), '', pct(totals.w, 2, false), ''] : null,
    });

    const sectors = new Map();
    all.forEach((r) => {
      const key = r.sector || 'Unmapped';
      const s = sectors.get(key) || { sector: key, count: 0, mv: 0 };
      s.count++;
      s.mv += r.mv;
      sectors.set(key, s);
    });
    const sectorRows = [...sectors.values()].map((s) => Object.assign(s, { weight: p.eq[i] ? s.mv / p.eq[i] * 100 : 0 }));
    sectorRows.push({ sector: 'Cash', count: 0, mv: p.cash[i], weight: p.eq[i] ? p.cash[i] / p.eq[i] * 100 : 0 });
    const sectorTable = dataTable({
      columns: [
        { key: 'sector', label: 'Sector', type: 'text', get: (r) => r.sector },
        { key: 'count', label: 'Positions', type: 'int', get: (r) => r.count },
        { key: 'mv', label: 'Value', type: 'num', get: (r) => r.mv, fmt: (r) => money(r.mv) },
        { key: 'weight', label: 'Weight %', type: 'num', get: (r) => r.weight, fmt: (r) => pct(r.weight, 2, false) },
      ],
      rows: sectorRows, sort: { key: 'weight', dir: 'desc' }, search: false, exportName: 'sector_weights_' + c.asOf,
    });

    root.append(h('div', { class: 'grid-2' },
      panel('Portfolio on ' + c.asOf, 'asof', facts, ...notices),
      panel('Allocation by sector', 'asof', sectorTable)));
    root.append(panel('Holdings at the ' + c.asOf + ' close', 'asof', table,
      h('p', { class: 'muted', text: 'Average cost includes rebalance re-weights; entry price is the first entry of the continuous holding. Click a row to open its chart.' })));
  }

  // ------------------------------------------------------------------ stocks

  /**
 * STEP business rule function: prices.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} symbol
 */
  function prices(symbol) {
    if (!X.priceCache.has(symbol)) {
      const entry = D.prices[symbol];
      X.priceCache.set(symbol, entry ? M.decodePrices(entry, X.sessions) : []);
    }
    return X.priceCache.get(symbol);
  }

  /**
 * STEP business rule function: defaultStock.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} c
 */
  function defaultStock(c) {
    if (S.stock) return S.stock;
    const held = positionsAt(c.session);
    if (held.length) return held.sort((a, b) => b.mv - a.mv)[0].symbol;
    const lastTrade = X.ledger.filter((r) => r.date <= c.asOf).pop();
    return lastTrade ? lastTrade.symbol : X.symbols[0];
  }

  /**
 * STEP business rule function: renderStocks.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} root
 * @param {*} c
 */
  function renderStocks(root, c) {
    const symbol = defaultStock(c);
    const css = cssVars();
    const bars = prices(symbol).filter((b) => b.time <= c.asOf);
    const held = positionsAt(c.session).find((r) => r.symbol === symbol);
    const trades = X.ledger.filter((r) => r.symbol === symbol && r.date <= c.asOf);
    const lastRank = latestRank(symbol, c.asOf);

    const pick = h('select', { 'aria-label': 'Stock', onchange: (e) => setState({ stock: e.target.value }) },
      ...X.symbols.map((s) => h('option', { value: s, selected: s === symbol, text: s })));
    const cmpOptions = (current, slot) => h('select', {
      'aria-label': 'Comparison ' + (slot + 1),
      onchange: (e) => {
        const next = S.cmp.slice();
        next[slot] = e.target.value;
        setState({ cmp: next.filter(Boolean).slice(0, 2) });
      },
    }, h('option', { value: '', text: 'None' }), h('option', { value: 'PORTFOLIO', selected: current === 'PORTFOLIO', text: 'Portfolio (TWR)' }),
    ...X.symbols.filter((s) => s !== symbol).map((s) => h('option', { value: s, selected: s === current, text: s })));
    const reweights = h('label', {}, h('input', { type: 'checkbox', checked: S.reweights, onchange: (e) => setState({ reweights: e.target.checked }) }), 'Show re-weight markers (ADD/TRIM)');
    const controls = h('div', { class: 'inline-controls' }, h('label', {}, 'Stock ', pick), reweights);

    const facts = h('dl', { class: 'facts' },
      h('dt', { text: 'Sector' }), h('dd', { text: D.sectors[symbol] || 'Unmapped' }),
      h('dt', { text: 'Prices shown' }), h('dd', { text: bars.length ? bars[0].time + ' → ' + bars[bars.length - 1].time + ' (adjusted, nothing after the as-of date)' : 'none' }),
      h('dt', { text: 'On ' + c.asOf }), h('dd', {}, held
        ? h('span', {}, qty(held.qty) + ' sh @ avg ' + price(held.avg) + ' · value ' + money(held.mv) + ' · ', signedMoney(held.upnl), ' (' , pct(held.upct, 2, true), ')')
        : 'Not held'),
      h('dt', { text: 'Latest ranking' }), h('dd', { text: lastRank ? 'Rank ' + lastRank.rank + ' of ' + lastRank.of + ' at rebalance #' + lastRank.n + ' (signal ' + lastRank.signal + ')' + (lastRank.selected ? ' · selected' : '') : 'Not ranked at any rebalance up to ' + c.asOf }),
      h('dt', { text: 'Fills to date' }), h('dd', { text: String(trades.length) }));

    const legend = h('div', { class: 'legend' });
    const box = h('div', { class: 'chart tall' });
    root.append(panel(symbol + ' · adjusted daily candles', 'asof', controls, h('div', { class: 'grid-2' }, facts, h('div', {},
      h('p', { class: 'muted', text: 'Markers: ▲ ENTRY · ▼ EXIT · ■ STOP (filled at the next open) · ● ADD/TRIM · ✕ DROP. Volume is not shown: the engine\'s daily snapshot has no volume.' }),
      h('p', { class: 'muted', text: 'Initial view = selected range; pan left for earlier history. The dashed line is the average cost while held.' }))),
    legend, box));

    if (!bars.length) {
      box.append(h('div', { class: 'chart-empty', text: 'No adjusted price data for ' + symbol + ' on or before ' + c.asOf + '.' }));
    } else {
      const chart = makeChart(box);
      const candles = chart.addSeries(LWC.CandlestickSeries, {
        upColor: css.gainMark, downColor: css.lossMark, borderVisible: false,
        wickUpColor: css.gainMark, wickDownColor: css.lossMark, priceLineVisible: false,
      });
      candles.setData(bars.map((b) => (b.missing ? { time: b.time } : b)));
      const markers = [];
      trades.forEach((t) => {
        if ((t.action === 'ADD' || t.action === 'TRIM') && !S.reweights) return;
        const m = {
          ENTRY: { position: 'belowBar', shape: 'arrowUp', color: css.gainMark, text: 'E' },
          ADD: { position: 'belowBar', shape: 'circle', color: css.series1, text: '' },
          TRIM: { position: 'aboveBar', shape: 'circle', color: css.series1, text: '' },
          EXIT: { position: 'aboveBar', shape: 'arrowDown', color: css.lossMark, text: 'X' },
          STOP: { position: 'aboveBar', shape: 'square', color: css.series2, text: 'S' },
          DROP: { position: 'aboveBar', shape: 'square', color: css.lossMark, text: 'D' },
        }[t.action];
        markers.push(Object.assign({ time: t.date, size: 1 }, m));
      });
      markers.sort((a, b) => (a.time < b.time ? -1 : a.time > b.time ? 1 : 0));
      LWC.createSeriesMarkers(candles, markers);
      if (held) {
        candles.createPriceLine({ price: held.avg, color: css.reference, lineWidth: 1, lineStyle: LWC.LineStyle.Dashed, axisLabelVisible: true, title: 'avg cost' });
      }
      const byTime = new Map(bars.map((b) => [b.time, b]));
      const tradesByTime = new Map();
      trades.forEach((t) => tradesByTime.set(t.date, (tradesByTime.get(t.date) || []).concat(t)));
      /**
 * STEP business rule function: showBar.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} time
 */
      const showBar = (time) => {
        const b = byTime.get(time);
        if (!b || b.missing) {
          replaceKids(legend, h('span', { class: 'muted', text: (time || '') + ' no bar' }));
          return;
        }
        const prev = bars[bars.indexOf(b) - 1];
        const chg = prev && !prev.missing ? (b.close / prev.close - 1) * 100 : null;
        const fills = (tradesByTime.get(time) || []).map((t) => t.action + ' ' + qty(t.qty) + ' @ ' + price(t.price));
        replaceKids(legend, h('span', { class: 'muted', text: dateLabel(time) }),
          legendItem(null, 'O', price(b.open)), legendItem(null, 'H', price(b.high)), legendItem(null, 'L', price(b.low)),
          legendItem(null, 'C', price(b.close)), chg === null ? null : legendItem(null, 'Chg', pct(chg, 2, true)),
          fills.length ? h('span', { text: fills.join(' · ') }) : null);
      };
      showBar(bars[bars.length - 1].time);
      chart.subscribeCrosshairMove((param) => showBar(param && param.time ? timeKey(param.time) : bars[bars.length - 1].time));
      if (c.range) {
        const fromTime = c.range.baseDate || c.range.firstSession;
        const visible = bars.filter((b) => b.time >= fromTime);
        if (visible.length >= 2) chart.timeScale().setVisibleRange({ from: visible[0].time, to: visible[visible.length - 1].time });
        else chart.timeScale().fitContent();
      } else {
        chart.timeScale().fitContent();
      }
    }

    // Relative performance over the selected range: one axis, everything rebased to 100.
    const relLegend = h('div', { class: 'legend' });
    const relBox = h('div', { class: 'chart' });
    const cmpControls = h('div', { class: 'inline-controls' }, h('label', {}, 'Compare with ', cmpOptions(S.cmp[0], 0)), h('label', {}, 'and ', cmpOptions(S.cmp[1], 1)));
    root.append(panel('Relative performance · rebased to 100 at range start', 'range', cmpControls, relLegend, relBox));
    if (c.invalid || !c.range) {
      relBox.append(h('div', { class: 'chart-empty', text: 'Choose a valid range to compare performance.' }));
    } else {
      renderRelative(relBox, relLegend, symbol, c, css);
    }

    root.append(panel(symbol + ' · fills up to ' + c.asOf, 'asof', dataTable({
      columns: ledgerColumns({ withoutSymbol: true }), rows: trades, sort: { key: 'date', dir: 'desc' },
      exportName: symbol + '_fills_' + c.asOf, empty: symbol + ' was not traded on or before ' + c.asOf + '.',
    })));
    root.append(panel(symbol + ' · ranking at each rebalance up to ' + c.asOf, 'asof', dataTable({
      columns: [
        { key: 'n', label: 'Rebal #', type: 'int', get: (r) => r.n },
        { key: 'signal', label: 'Signal date', type: 'date', get: (r) => r.signal },
        { key: 'date', label: 'Execution', type: 'date', get: (r) => r.date },
        { key: 'rank', label: 'Rank', type: 'int', get: (r) => r.rank },
        { key: 'of', label: 'Of', type: 'int', get: (r) => r.of },
        { key: 'ret', label: 'Lookback return %', type: 'num', get: (r) => r.ret, fmt: (r) => pct(r.ret, 2, true) },
        { key: 'sel', label: 'Selected', type: 'text', get: (r) => (r.selected ? 'yes' : 'no') },
      ],
      rows: rankHistory(symbol, c.asOf), sort: { key: 'n', dir: 'desc' }, exportName: symbol + '_rank_history_' + c.asOf,
      empty: symbol + ' was not eligible at any rebalance signalled on or before ' + c.asOf + '.',
    })));
  }

  /**
 * STEP business rule function: renderRelative.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} box
 * @param {*} legend
 * @param {*} symbol
 * @param {*} c
 * @param {*} css
 */
  function renderRelative(box, legend, symbol, c, css) {
    const base = c.range.baseDate || c.range.firstSession;
    const lines = [];
    const stockLine = rebased(prices(symbol), base, c.asOf, (b) => b.close);
    lines.push({ name: symbol, color: css.series1, data: stockLine });
    const colors = [css.series2, css.series3];
    S.cmp.filter((x) => x !== symbol).forEach((cmp, k) => {
      if (cmp === 'PORTFOLIO') {
        // Same rebasing point as the price lines: the base close, else the first session.
        const first = c.range.baseDate ? c.range.startRow - 1 : c.range.startRow;
        const pts = [];
        for (let i = first; i <= c.range.endRow; i++) {
          pts.push({ time: X.series.dates[i], value: 100 * D.portfolio.twr[i] / D.portfolio.twr[first] });
        }
        lines.push({ name: 'Portfolio (TWR)', color: colors[k], data: pts });
      } else {
        lines.push({ name: cmp, color: colors[k], data: rebased(prices(cmp), base, c.asOf, (b) => b.close) });
      }
    });
    if (!stockLine.length && lines.every((l) => !l.data.length)) {
      box.append(h('div', { class: 'chart-empty', text: 'No price data in the selected range.' }));
      return;
    }
    const chart = makeChart(box);
    const lookups = lines.map((l) => {
      const s = chart.addSeries(LWC.LineSeries, { color: l.color, lineWidth: 2, priceLineVisible: false, lastValueVisible: true, title: '' });
      s.setData(l.data);
      return new Map(l.data.map((pt) => [pt.time, pt.value]));
    });
    chart.timeScale().fitContent();
    /**
 * STEP business rule function: show.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} time
 */
    const show = (time) => {
      replaceKids(legend, ...lines.map((l, k) => {
        const v = time ? lookups[k].get(time) : l.data.length ? l.data[l.data.length - 1].value : undefined;
        return legendItem(l.color, l.name, v === undefined ? (l.data.length ? '—' : 'no data in range') : v.toFixed(2));
      }), h('span', { class: 'muted', text: time || ('rebased at ' + base) }));
    };
    show(null);
    chart.subscribeCrosshairMove((param) => show(param && param.time ? timeKey(param.time) : null));
  }

  /**
 * STEP business rule function: rebased.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} bars
 * @param {*} base
 * @param {*} asOf
 * @param {*} get
 */
  function rebased(bars, base, asOf, get) {
    const inRange = bars.filter((b) => !b.missing && b.time >= base && b.time <= asOf);
    if (!inRange.length) return [];
    const b0 = get(inRange[0]);
    return inRange.map((b) => ({ time: b.time, value: 100 * get(b) / b0 }));
  }

  /**
 * STEP business rule function: rankHistory.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} symbol
 * @param {*} asOf
 */
  function rankHistory(symbol, asOf) {
    const R = D.rankings;
    const sIdx = X.symIdx.get(symbol);
    const out = [];
    X.rebalances.forEach((rb) => {
      if (rb.signal > asOf) return;
      const range = X.rankRange.get(rb.n);
      if (!range) return;
      for (let i = range[0]; i < range[1]; i++) {
        if (R.s[i] === sIdx) {
          out.push({ n: rb.n, signal: rb.signal, date: rb.date, rank: R.r[i], of: range[1] - range[0], ret: R.ret[i], selected: R.sel[i] === 1 });
          break;
        }
      }
    });
    return out;
  }

  /**
 * STEP business rule function: latestRank.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} symbol
 * @param {*} asOf
 */
  function latestRank(symbol, asOf) {
    const hist = rankHistory(symbol, asOf);
    return hist.length ? hist[hist.length - 1] : null;
  }

  /**
 * STEP business rule function: openStock.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} symbol
 */
  function openStock(symbol) {
    setState({ view: 'stocks', stock: symbol });
    window.scrollTo(0, 0);
  }

  // ------------------------------------------------------------------ performance

  /**
 * STEP business rule function: renderPerformance.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} root
 * @param {*} c
 */
  function renderPerformance(root, c) {
    const rows = M.presets(X.series, c.asOf).map((m) => Object.assign({ name: M.PRESET_LABELS[m.label] }, m));
    if (S.preset === 'CUSTOM' && c.range) rows.push(Object.assign({ name: 'Selected custom range' }, c.range));
    const selectedLabel = S.preset === 'CUSTOM' ? 'Custom range' : S.preset;
    root.append(panel('Range performance as of ' + c.asOf, 'range', dataTable({
      columns: [
        { key: 'name', label: 'Range', type: 'text', get: (r) => r.name, fmt: (r) => h('span', {}, r.name, r.label === selectedLabel ? h('span', { class: 'badge', text: 'selected' }) : null) },
        { key: 'firstSession', label: 'First session', type: 'date', get: (r) => r.firstSession },
        { key: 'baseDate', label: 'Measured from', type: 'text', get: (r) => r.baseDate || 'inception' },
        { key: 'sessions', label: 'Sessions', type: 'int', get: (r) => r.sessions },
        { key: 'startEquity', label: 'Start equity', type: 'num', get: (r) => r.startEquity, fmt: (r) => money(r.startEquity) },
        { key: 'endEquity', label: 'End equity', type: 'num', get: (r) => r.endEquity, fmt: (r) => money(r.endEquity) },
        { key: 'contributions', label: 'Contributions', type: 'num', get: (r) => r.contributions, fmt: (r) => money(r.contributions) },
        { key: 'pnl', label: 'P&L', type: 'num', get: (r) => r.pnl, fmt: (r) => signedMoney(r.pnl) },
        { key: 'returnPct', label: 'Return (TWR)', type: 'num', get: (r) => r.returnPct, fmt: (r) => pct(r.returnPct, 2, true) },
        { key: 'ann', label: 'Annualized', type: 'num', get: (r) => r.annualizedReturnPct, fmt: (r) => (r.annualizedReturnPct === null ? h('span', { class: 'muted', text: 'n/a (<1y)' }) : pct(r.annualizedReturnPct, 2, true)) },
        { key: 'dd', label: 'Max drawdown', type: 'num', get: (r) => r.maxDrawdownPct, fmt: (r) => pct(r.maxDrawdownPct, 2, true) },
        { key: 'note', label: 'Note', type: 'text', get: (r) => (r.truncated ? 'Insufficient history: covers ' + r.sessions + ' session(s) from backtest start' : ''), fmt: (r) => (r.truncated ? h('span', { class: 'badge warn', text: 'insufficient history' }) : '') },
      ],
      rows, search: false, sortable: false, exportName: 'range_performance_' + c.asOf,
    }), h('p', { class: 'muted', text: 'Returns chain the engine\'s contribution-adjusted daily returns; P&L = end equity − start equity − contributions.' })));

    const months = M.periodReturns(X.series, 'month', c.row, X.firstInvested);
    const years = M.periodReturns(X.series, 'year', c.row, X.firstInvested);
    root.append(panel('Monthly returns (TWR) up to ' + c.asOf, 'asof', monthlyGrid(months, years),
      h('p', { class: 'muted', text: '† partial period: the account was not invested for the whole period, or the as-of date ends before the period does. Hover a cell for details.' })));

    const yearlyByYear = new Map(D.yearly.map((y) => [String(y.year), y]));
    const yearRows = years.map((y) => {
      const engine = yearlyByYear.get(y.key);
      const usable = engine && engine.end_date <= c.asOf;
      return {
        year: y.key, start: usable ? engine.start_date : (y.firstSession), end: y.lastSession,
        startEq: usable ? engine.start_equity : y.startEquity, endEq: usable ? engine.end_equity : y.endEquity,
        contributions: usable ? engine.contributions : y.contributions, pnl: usable ? engine.period_pnl : y.pnl,
        engineRet: usable ? engine.yearly_return_pct : null, twr: y.returnPct, cagr: usable ? engine.cagr_pct : null,
        rebalances: usable ? engine.rebalance_count : X.rebalances.filter((r) => r.date.slice(0, 4) === y.key && r.date <= c.asOf).length,
        source: usable ? 'engine yearly report' : 'daily data to ' + y.lastSession,
        partial: y.partial, partialReason: y.partialReason,
      };
    }).filter((y) => y.end >= (X.firstInvested || ''));
    root.append(panel('Yearly results', 'asof', dataTable({
      columns: [
        { key: 'year', label: 'Year', type: 'text', get: (r) => r.year },
        { key: 'start', label: 'Start', type: 'date', get: (r) => r.start },
        { key: 'end', label: 'End', type: 'date', get: (r) => r.end },
        { key: 'startEq', label: 'Start equity', type: 'num', get: (r) => r.startEq, fmt: (r) => money(r.startEq) },
        { key: 'endEq', label: 'End equity', type: 'num', get: (r) => r.endEq, fmt: (r) => money(r.endEq) },
        { key: 'contributions', label: 'Contributions', type: 'num', get: (r) => r.contributions, fmt: (r) => money(r.contributions) },
        { key: 'pnl', label: 'P&L', type: 'num', get: (r) => r.pnl, fmt: (r) => signedMoney(r.pnl) },
        { key: 'engineRet', label: 'Return (engine)', type: 'num', get: (r) => r.engineRet, fmt: (r) => (r.engineRet === null ? h('span', { class: 'muted', text: '—' }) : pct(r.engineRet, 2, true)) },
        { key: 'twr', label: 'Return (TWR)', type: 'num', get: (r) => r.twr, fmt: (r) => pct(r.twr, 2, true) },
        { key: 'cagr', label: 'CAGR since start (XIRR)', type: 'num', get: (r) => r.cagr, fmt: (r) => (r.cagr === null ? h('span', { class: 'muted', text: '—' }) : pct(r.cagr, 2, true)) },
        { key: 'rebalances', label: 'Rebalances', type: 'int', get: (r) => r.rebalances },
        { key: 'status', label: 'Status', type: 'text', get: (r) => (r.partial ? 'partial (' + r.partialReason + ')' : 'full year'), fmt: (r) => (r.partial ? h('span', { class: 'badge warn', title: r.partialReason, text: 'partial · ' + r.partialReason }) : h('span', { class: 'badge', text: 'full year' })) },
        { key: 'source', label: 'Source', type: 'text', get: (r) => r.source },
      ],
      rows: yearRows, sort: { key: 'year', dir: 'desc' }, search: false, exportName: 'yearly_' + c.asOf,
    }), h('p', { class: 'muted', text: 'Engine rows are the engine\'s yearly report (contributions treated at year end; CAGR is money-weighted from the first rebalance). '
      + 'Years not yet complete as of the selected date are computed from daily data so nothing after the as-of date is shown.' })));

    root.append(panel('Largest drawdowns up to ' + c.asOf, 'asof', dataTable({
      columns: [
        { key: 'peak', label: 'Peak', type: 'text', get: (r) => r.peak || 'inception' },
        { key: 'trough', label: 'Trough', type: 'date', get: (r) => r.trough },
        { key: 'depthPct', label: 'Depth', type: 'num', get: (r) => r.depthPct, fmt: (r) => pct(r.depthPct, 2, true) },
        { key: 'recovery', label: 'Recovered', type: 'text', get: (r) => r.recovery || 'not recovered as of ' + c.asOf },
        { key: 'recoverySessions', label: 'Sessions peak → recovery', type: 'int', get: (r) => r.recoverySessions },
      ],
      rows: M.drawdownEpisodes(X.series, c.row, 10), sort: { key: 'depthPct', dir: 'asc' }, search: false,
      exportName: 'drawdowns_' + c.asOf, empty: 'No drawdowns up to ' + c.asOf + '.',
    })));
  }

  /**
 * STEP business rule function: monthlyGrid.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} months
 * @param {*} years
 */
  function monthlyGrid(months, years) {
    const byYear = new Map();
    months.forEach((m) => {
      const y = m.key.slice(0, 4);
      if (!byYear.has(y)) byYear.set(y, {});
      byYear.get(y)[+m.key.slice(5, 7) - 1] = m;
    });
    const yearMap = new Map(years.map((y) => [y.key, y]));
    /**
 * STEP business rule function: cell.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} m
 */
    const cell = (m) => {
      if (!m) return h('td', { class: 'num muted', text: '' });
      const td = h('td', { class: 'num ' + (m.returnPct >= 0 ? 'gain' : 'loss'), title: m.key + ': ' + fmtPct(m.returnPct, 2, true)
        + ' · P&L ' + fmtMoney(m.pnl, 0) + (m.partial ? ' · partial: ' + m.partialReason : '') + ' · ' + m.sessions + ' sessions' },
      fmtPct(m.returnPct, 1, true) + (m.partial ? '†' : ''));
      return td;
    };
    const head = h('tr', {}, h('th', { scope: 'col', text: 'Year' }), ...MONTHS.map((m) => h('th', { scope: 'col', class: 'num', text: m })), h('th', { scope: 'col', class: 'num', text: 'Year' }));
    const body = [...byYear.keys()].sort().reverse().map((y) => h('tr', {}, h('th', { scope: 'row', text: y }),
      ...MONTHS.map((_, k) => cell(byYear.get(y)[k])), cell(yearMap.get(y))));
    const exportRows = months.map((m) => [m.key, m.firstSession, m.lastSession, m.returnPct, m.pnl, m.contributions, m.partial ? 'partial: ' + m.partialReason : '']);
    const exportBtn = h('button', { type: 'button', class: 'btn', onclick: () => downloadCsv('monthly_returns.csv',
      ['month', 'first_session', 'last_session', 'return_pct', 'pnl', 'contributions', 'note'], exportRows), text: 'Export CSV' });
    return h('div', {}, h('div', { class: 'table-tools' }, h('span', { class: 'count', text: months.length + ' months' }), exportBtn),
      h('div', { class: 'table-wrap' }, h('table', {}, h('thead', {}, head), h('tbody', {}, ...body))));
  }

  // ------------------------------------------------------------------ rankings

  /**
 * STEP business rule function: renderRankings.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} root
 * @param {*} c
 */
  function renderRankings(root, c) {
    const available = X.rebalances.filter((r) => r.signal <= c.asOf);
    if (!available.length) {
      root.append(panel('Rebalance rankings', 'asof', h('div', { class: 'notice', text: 'No rebalance was signalled on or before ' + c.asOf
        + '. The first signal is ' + (X.rebalances[0] ? X.rebalances[0].signal : 'n/a') + '.' })));
      renderLookback(root, c);
      return;
    }
    const executed = available.filter((r) => r.date <= c.asOf);
    let rb = available.find((r) => r.n === S.reb);
    if (!rb) rb = executed.length ? executed[executed.length - 1] : available[available.length - 1];
    const pending = rb.date > c.asOf;
    const select = h('select', { 'aria-label': 'Rebalance', onchange: (e) => setState({ reb: +e.target.value }) },
      ...available.slice().reverse().map((r) => h('option', { value: r.n, selected: r.n === rb.n,
        text: '#' + r.n + ' · signal ' + r.signal + ' → exec ' + r.date + (r.date > c.asOf ? ' (executes next session)' : '') })));
    const R = D.rankings;
    const range = X.rankRange.get(rb.n) || [0, 0];
    const entered = new Set(rb.entered);
    const exited = new Set(rb.exited);
    const heldSet = new Set(rb.held);
    const rows = [];
    for (let i = range[0]; i < range[1]; i++) {
      const sym = X.symbols[R.s[i]];
      if (!symbolMatch(sym)) continue;
      rows.push({ rank: R.r[i], symbol: sym, sector: D.sectors[sym] || '', lp: R.lp[i], cp: R.cp[i], ret: R.ret[i], hist: R.h[i], selected: R.sel[i] === 1,
        decision: entered.has(sym) ? 'Entered' : heldSet.has(sym) ? 'Held (retained)' : exited.has(sym) ? 'Exited' : '' });
    }
    const ranked = new Set(rows.map((r) => r.symbol));
    const unranked = rb.exited.filter((s) => !ranked.has(s) && symbolMatch(s));
    const facts = h('dl', { class: 'facts' },
      h('dt', { text: 'Signal' }), h('dd', { text: dateLabel(rb.signal) + ' close (ranking date)' }),
      h('dt', { text: 'Execution' }), h('dd', { text: dateLabel(rb.date) + ' open' + (pending ? ' — after the as-of date; not yet executed' : '') }),
      h('dt', { text: 'Lookback window' }), h('dd', { text: (rb.lookbackStart || '?') + ' → ' + rb.signal + ' (' + X.manifest.strategy.lookback_days + ' sessions)' }),
      h('dt', { text: 'Ranked' }), h('dd', { text: (range[1] - range[0]) + ' eligible symbols (top.n ' + X.topN + ', exit rank ' + X.manifest.strategy.effective_exit_n + ')' }),
      h('dt', { text: 'Entered' }), h('dd', { text: rb.entered.join(', ') || '—' }),
      h('dt', { text: 'Held' }), h('dd', { text: rb.held.join(', ') || '—' }),
      h('dt', { text: 'Exited' }), h('dd', { text: rb.exited.join(', ') || '—' }),
      h('dt', { text: 'Period result' }), h('dd', {}, pending ? h('span', { class: 'muted', text: 'hidden: valued at the ' + rb.date + ' open, after the as-of date' })
        : h('span', {}, signedMoney(rb.pnl), ' (', pct(rb.ret, 2, true), ') at the ' + rb.date + ' open; equity ' + money(rb.equity))));
    const table = dataTable({
      columns: [
        { key: 'rank', label: 'Rank', type: 'int', get: (r) => r.rank },
        { key: 'symbol', label: 'Symbol', type: 'text', get: (r) => r.symbol },
        { key: 'sector', label: 'Sector', type: 'text', get: (r) => r.sector },
        { key: 'lp', label: 'Lookback price', type: 'num', get: (r) => r.lp, fmt: (r) => price(r.lp) },
        { key: 'cp', label: 'Signal close', type: 'num', get: (r) => r.cp, fmt: (r) => price(r.cp) },
        { key: 'ret', label: 'Lookback return %', type: 'num', get: (r) => r.ret, fmt: (r) => pct(r.ret, 2, true) },
        { key: 'hist', label: 'History days', type: 'int', get: (r) => r.hist },
        { key: 'selected', label: 'Selected', type: 'text', get: (r) => (r.selected ? 'yes' : ''), fmt: (r) => (r.selected ? h('span', { class: 'badge ok', text: 'selected' }) : '') },
        { key: 'decision', label: 'Decision', type: 'text', get: (r) => r.decision, fmt: (r) => (r.decision ? h('span', { class: 'act ' + (r.decision === 'Entered' ? 'gain' : r.decision === 'Exited' ? 'loss' : 'secondary'), text: r.decision }) : '') },
      ],
      rows, sort: { key: 'rank', dir: 'asc' }, exportName: 'rankings_rebalance_' + rb.n,
      empty: S.symbol ? 'No ranked symbols match the filter.' : 'No eligible symbols at this rebalance.',
      onRowClick: (r) => openStock(r.symbol),
      rowClass: (r) => (r.selected ? 'selected' : ''),
    });
    root.append(panel('Rebalance rankings', 'asof', h('div', { class: 'inline-controls' }, h('label', {}, 'Rebalance ', select)), facts,
      unranked.length ? h('div', { class: 'notice warn', text: 'Exited without a ranking (ineligible at the signal): ' + unranked.join(', ') }) : null,
      table));
    renderLookback(root, c);
  }

  /**
 * STEP business rule function: renderLookback.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} root
 * @param {*} c
 */
  function renderLookback(root, c) {
    const signals = X.lookbackSignals.filter((s) => s <= c.asOf);
    const span = X.lookbackSignals.length ? X.lookbackSignals[0] + ' → ' + X.lookbackSignals[X.lookbackSignals.length - 1] : 'n/a';
    if (!signals.length) {
      root.append(panel('Daily lookback ranking', 'asof', h('div', { class: 'notice', text: 'The engine exports the daily lookback ranking only for the last 30 sessions ('
        + span + '). Choose an as-of date in that window to see it.' })));
      return;
    }
    const sig = signals[signals.length - 1];
    const LB = D.lookback;
    const range = X.lookbackRange.get(sig);
    const rows = [];
    for (let i = range[0]; i < range[1]; i++) {
      const sym = X.symbols[LB.s[i]];
      if (!symbolMatch(sym)) continue;
      rows.push({ rank: LB.r[i], symbol: sym, sector: D.sectors[sym] || '', ret: LB.ret[i], lp: LB.lp[i], cp: LB.cp[i], hist: LB.h[i], top: LB.sel[i] === 1 });
    }
    root.append(panel('Daily lookback ranking on ' + sig + ' close', 'asof',
      h('div', { class: 'notice', text: 'Indicative ranking with the current parameters (plain top-' + X.topN + ', no sector cap). '
        + 'Only scheduled rebalances trade. Available for ' + span + '.' }),
      dataTable({
        columns: [
          { key: 'rank', label: 'Rank', type: 'int', get: (r) => r.rank },
          { key: 'symbol', label: 'Symbol', type: 'text', get: (r) => r.symbol },
          { key: 'sector', label: 'Sector', type: 'text', get: (r) => r.sector },
          { key: 'lp', label: 'Lookback price', type: 'num', get: (r) => r.lp, fmt: (r) => price(r.lp) },
          { key: 'cp', label: 'Close', type: 'num', get: (r) => r.cp, fmt: (r) => price(r.cp) },
          { key: 'ret', label: 'Lookback return %', type: 'num', get: (r) => r.ret, fmt: (r) => pct(r.ret, 2, true) },
          { key: 'hist', label: 'History days', type: 'int', get: (r) => r.hist },
          { key: 'top', label: 'In top N', type: 'text', get: (r) => (r.top ? 'yes' : '') },
        ],
        rows, sort: { key: 'rank', dir: 'asc' }, exportName: 'lookback_' + sig, onRowClick: (r) => openStock(r.symbol),
        empty: 'No symbols match the filter.',
      })));
  }

  // ------------------------------------------------------------------ run details

  /**
 * STEP business rule function: renderRun.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} root
 * @param {*} c
 */
  function renderRun(root, c) {
    const m = X.manifest;
    const st = m.strategy;
    const cov = m.coverage;
    const kv = (obj, labels) => h('dl', { class: 'facts' }, ...Object.keys(labels).flatMap((k) => [
      h('dt', { text: labels[k] }), h('dd', { text: obj[k] === null || obj[k] === undefined || obj[k] === '' ? '—' : String(obj[k]) })]));
    root.append(h('div', { class: 'grid-2' },
      panel('Strategy configuration', null, kv(st, {
        lookback_days: 'Lookback (sessions)', top_n: 'top.n', exit_n: 'exit.n (0 = off)', effective_exit_n: 'Effective exit rank',
        rebalance_mode: 'Rebalance mode', rebalance_interval_sessions: 'Sessions between rebalances', allocation_mode: 'Allocation',
        capital_per_stock: 'Capital per stock', initial_capital: 'Initial capital', monthly_contribution: 'Monthly contribution',
        stop_loss_pct: 'Stop loss %', trailing_stop_pct: 'Trailing stop %', min_history_days: 'Min history (effective)',
        max_per_sector: 'Max per sector', sector_file: 'Sector file',
      })),
      panel('Common pipeline configuration', null, kv(m.common_config || {}, {
        market_sector: 'Market sector', symbols_file: 'Symbols file', start_date: 'start.date',
        end_date: 'end.date', data_path: 'Data path',
      })),
      panel('Run & coverage', null, kv(Object.assign({ generated_at: m.generated_at, config_file: m.config_file, output_dir: m.output_dir }, cov), {
        generated_at: 'Generated', config_file: 'Config file', output_dir: 'Output directory',
        data_first_session: 'Data from', data_last_session: 'Data to', data_sessions: 'Data sessions', symbols: 'Symbols',
        backtest_first_session: 'Backtest from', backtest_last_session: 'Backtest to', backtest_sessions: 'Backtest sessions',
        first_signal: 'First signal', first_rebalance: 'First rebalance', last_rebalance: 'Last rebalance', rebalances: 'Rebalances',
      }), h('p', { class: 'muted', text: 'Fills: ' + Object.entries(cov.fills_by_action).map(([a, n]) => a + ' ' + n).join(' · ')
        + '. Volume: ' + (cov.volume_available ? 'available' : 'not available in the daily snapshot') + '.' }))));

    const warnings = m.warnings.map((w) => h('li', { text: w }));
    root.append(panel('Data quality', null, h('ul', { class: 'plain' }, ...warnings)));
    root.append(panel('Reconciliation checks (run by the engine)', null, dataTable({
      columns: [
        { key: 'passed', label: 'Result', type: 'text', get: (r) => (r.passed ? 'pass' : 'FAIL'), fmt: (r) => h('span', { class: 'badge ' + (r.passed ? 'ok' : 'bad'), text: r.passed ? '✓ pass' : '✕ fail' }) },
        { key: 'name', label: 'Check', type: 'text', get: (r) => r.name },
        { key: 'detail', label: 'Detail', type: 'text', get: (r) => r.detail, wrap: true },
      ],
      rows: m.reconciliation.checks, search: false, sortable: false, exportName: 'reconciliation_checks',
    })));

    const browser = M.presets(X.series, m.reference_metrics.as_of);
    const cross = m.reference_metrics.presets.map((ref) => {
      const js = browser.find((b) => b.label === ref.label);
      const ok = js && Math.abs(js.returnPct - ref.return_pct) < 1e-4 && Math.abs(js.pnl - ref.pnl) <= 0.011 && js.sessions === ref.sessions
        && js.truncated === ref.truncated && Math.abs(js.maxDrawdownPct - ref.max_drawdown_pct) < 1e-4;
      return { label: ref.label, sessions: ref.sessions, engine: ref.return_pct, browser: js ? js.returnPct : null, enginePnl: ref.pnl, browserPnl: js ? js.pnl : null, ok };
    });
    root.append(panel('Range metrics cross-check (engine vs. browser) as of ' + m.reference_metrics.as_of, null, dataTable({
      columns: [
        { key: 'label', label: 'Preset', type: 'text', get: (r) => r.label },
        { key: 'sessions', label: 'Sessions', type: 'int', get: (r) => r.sessions },
        { key: 'engine', label: 'Return (Java)', type: 'num', get: (r) => r.engine, fmt: (r) => fmtPct(r.engine, 4, true) },
        { key: 'browser', label: 'Return (browser)', type: 'num', get: (r) => r.browser, fmt: (r) => fmtPct(r.browser, 4, true) },
        { key: 'enginePnl', label: 'P&L (Java)', type: 'num', get: (r) => r.enginePnl, fmt: (r) => money(r.enginePnl) },
        { key: 'browserPnl', label: 'P&L (browser)', type: 'num', get: (r) => r.browserPnl, fmt: (r) => money(r.browserPnl) },
        { key: 'ok', label: 'Match', type: 'text', get: (r) => (r.ok ? 'yes' : 'NO'), fmt: (r) => h('span', { class: 'badge ' + (r.ok ? 'ok' : 'bad'), text: r.ok ? '✓ match' : '✕ mismatch' }) },
      ],
      rows: cross, search: false, sortable: false, exportName: 'metrics_cross_check',
    })));

    root.append(panel('Valuation conventions', null, h('ul', { class: 'plain' }, ...m.conventions.map((t) => h('li', { text: t })),
      h('li', { text: 'Views marked AS OF use the end-of-day state on the as-of date; views marked RANGE use the selected range, which always ends on the as-of date. No view shows trades, prices or rankings after the as-of date.' }))));
    root.append(panel('Output files', null, dataTable({
      columns: [
        { key: 'file', label: 'File (in ' + m.output_dir + ')', type: 'text', get: (r) => r.file },
        { key: 'rows', label: 'Rows', type: 'int', get: (r) => r.rows },
        { key: 'expected_rows', label: 'Expected', type: 'int', get: (r) => (r.expected_rows === undefined ? null : r.expected_rows) },
        { key: 'ok', label: 'Status', type: 'text', get: (r) => (r.expected_rows === undefined ? 'not derivable' : r.rows === r.expected_rows ? 'ok' : 'MISMATCH'),
          fmt: (r) => (r.expected_rows === undefined ? h('span', { class: 'muted', text: 'not derivable' }) : h('span', { class: 'badge ' + (r.rows === r.expected_rows ? 'ok' : 'bad'), text: r.rows === r.expected_rows ? '✓ ok' : '✕ mismatch' })) },
      ],
      rows: m.outputs, search: false, sortable: false, exportName: 'output_files',
    })));
  }

  // ------------------------------------------------------------------ components

  /**
 * STEP business rule function: panel.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} title
 * @param {*} scope
 * @param {*} children
 */
  function panel(title, scope, ...children) {
    const head = h('div', { class: 'panel-head' }, h('h2', { text: title }), h('span', { class: 'spacer' }),
      scope === 'asof' ? h('span', { class: 'scope asof', title: 'Uses the end-of-day state on the as-of date', text: 'As of' })
        : scope === 'range' ? h('span', { class: 'scope range', title: 'Uses the selected date range (ending on the as-of date)', text: 'Range' }) : null);
    return h('section', { class: 'panel' }, head, ...children);
  }

  /**
 * STEP business rule function: invalidRange.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} c
 */
  function invalidRange(c) {
    return panel('Selected range', 'range', h('div', { class: 'notice bad', text: 'Invalid range: the From date (' + (S.from || '?')
      + ') is after the as-of date (' + c.asOf + '). Pick an earlier From date or a preset.' }));
  }

  /**
 * STEP business rule function: kpi.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} label
 * @param {*} value
 * @param {*} sub
 */
  function kpi(label, value, sub) {
    return h('div', { class: 'kpi' }, h('div', { class: 'label', text: label }), h('div', { class: 'value' }, value), sub ? h('div', { class: 'sub' }, sub) : null);
  }

  /**
 * STEP business rule function: legendItem.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} color
 * @param {*} name
 * @param {*} value
 * @param {*} bar
 */
  function legendItem(color, name, value, bar) {
    return h('span', {}, color ? h('span', { class: 'key' + (bar ? ' bar' : ''), style: 'background:' + color }) : null,
      h('strong', {}, value), ' ', h('span', { text: name }));
  }

  /**
   * Sortable, searchable, paginated table with CSV export of the filtered rows.
   * opts: columns[{key,label,type,get,fmt,wrap}], rows, sort{key,dir}, search, sortable,
   *       exportName, empty, onRowClick, rowClass, footer[], tools[]
   */
  function dataTable(opts) {
    const state = { sort: opts.sort || null, query: '', page: 0 };
    const pageSize = opts.pageSize || 100;
    const wrap = h('div', { class: 'table-wrap' });
    const count = h('span', { class: 'count' });
    const pager = h('div', { class: 'pager' });
    const search = opts.search === false ? null : h('input', { type: 'search', placeholder: 'Filter rows…', 'aria-label': 'Filter rows',
      oninput: (e) => { state.query = e.target.value.trim().toLowerCase(); state.page = 0; draw(); } });
    const exportBtn = h('button', { type: 'button', class: 'btn', text: 'Export CSV', onclick: () => {
      const rows = filtered();
      downloadCsv((opts.exportName || 'table') + '.csv', opts.columns.map((col) => col.label),
        rows.map((r) => opts.columns.map((col) => col.get(r))));
    } });
    const tools = h('div', { class: 'table-tools' }, ...(opts.tools || []), search, count, h('span', { class: 'spacer', style: 'flex:1' }), exportBtn);

    /**
 * STEP business rule function: filtered.
 * TODO: Describe purpose, side effects, and expected node/context state.
 */
    function filtered() {
      let rows = opts.rows;
      if (state.query) {
        rows = rows.filter((r) => opts.columns.some((col) => {
          const v = col.get(r);
          return v !== null && v !== undefined && String(v).toLowerCase().includes(state.query);
        }));
      }
      if (state.sort) {
        const col = opts.columns.find((x) => x.key === state.sort.key);
        if (col) {
          const dir = state.sort.dir === 'asc' ? 1 : -1;
          rows = rows.slice().sort((a, b) => {
            const va = col.get(a);
            const vb = col.get(b);
            if (va === null || va === undefined) return vb === null || vb === undefined ? 0 : 1;
            if (vb === null || vb === undefined) return -1;
            return (va < vb ? -1 : va > vb ? 1 : 0) * dir;
          });
        }
      }
      return rows;
    }

    /**
 * STEP business rule function: draw.
 * TODO: Describe purpose, side effects, and expected node/context state.
 */
    function draw() {
      const rows = filtered();
      const pages = Math.max(1, Math.ceil(rows.length / pageSize));
      state.page = Math.min(state.page, pages - 1);
      const slice = rows.slice(state.page * pageSize, (state.page + 1) * pageSize);
      const head = h('tr', {}, ...opts.columns.map((col) => {
        const numeric = col.type === 'num' || col.type === 'int';
        const sorted = state.sort && state.sort.key === col.key;
        const th = h('th', { scope: 'col', class: (numeric ? 'num ' : '') + (opts.sortable === false ? '' : 'sortable'),
          'aria-sort': sorted ? (state.sort.dir === 'asc' ? 'ascending' : 'descending') : null, tabindex: opts.sortable === false ? null : '0', text: col.label });
        if (opts.sortable !== false) {
          /**
 * STEP business rule function: toggle.
 * TODO: Describe purpose, side effects, and expected node/context state.
 */
          const toggle = () => {
            state.sort = { key: col.key, dir: sorted && state.sort.dir === 'desc' ? 'asc' : sorted ? 'desc' : numeric || col.type === 'date' ? 'desc' : 'asc' };
            draw();
          };
          th.addEventListener('click', toggle);
          th.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(); } });
        }
        return th;
      }));
      const body = slice.length ? slice.map((r) => {
        const tr = h('tr', { class: [opts.onRowClick ? 'clickable' : '', opts.rowClass ? opts.rowClass(r) : ''].join(' ').trim() || null,
          tabindex: opts.onRowClick ? '0' : null },
        ...opts.columns.map((col) => {
          const numeric = col.type === 'num' || col.type === 'int';
          const v = col.fmt ? col.fmt(r) : col.get(r);
          const text = v === null || v === undefined ? '' : v instanceof Node ? v : col.type === 'int' ? fmtInt(v) : String(v);
          return h('td', { class: (numeric ? 'num' : '') + (col.wrap ? ' wrap' : '') }, text);
        }));
        if (opts.onRowClick) {
          tr.addEventListener('click', () => opts.onRowClick(r));
          tr.addEventListener('keydown', (e) => { if (e.key === 'Enter') opts.onRowClick(r); });
        }
        return tr;
      }) : [h('tr', { class: 'empty-row' }, h('td', { colspan: String(opts.columns.length), text: opts.empty || 'No rows.' }))];
      const foot = opts.footer ? h('tfoot', {}, h('tr', {}, ...opts.footer.map((f, k) => {
        const col = opts.columns[k];
        return h('td', { class: col && (col.type === 'num' || col.type === 'int') ? 'num' : '' }, f);
      }))) : null;
      replaceKids(wrap, h('table', {}, h('thead', {}, head), h('tbody', {}, ...body), foot));
      count.textContent = rows.length === opts.rows.length ? rows.length + ' row' + (rows.length === 1 ? '' : 's')
        : rows.length + ' of ' + opts.rows.length + ' rows';
      replaceKids(pager);
      if (pages > 1) {
        pager.append(h('button', { type: 'button', class: 'btn', disabled: state.page === 0, onclick: () => { state.page--; draw(); }, text: '‹ Prev' }),
          h('span', { text: 'Page ' + (state.page + 1) + ' of ' + pages }),
          h('button', { type: 'button', class: 'btn', disabled: state.page >= pages - 1, onclick: () => { state.page++; draw(); }, text: 'Next ›' }));
      }
    }
    draw();
    return h('div', {}, tools, wrap, pager);
  }

  /**
 * STEP business rule function: downloadCsv.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} name
 * @param {*} header
 * @param {*} rows
 */
  function downloadCsv(name, header, rows) {
    /**
 * STEP business rule function: esc.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} v
 */
    const esc = (v) => {
      if (v === null || v === undefined) return '';
      const s = typeof v === 'number' ? String(v) : String(v);
      return /[",\n\r]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
    };
    const text = [header.map(esc).join(',')].concat(rows.map((r) => r.map(esc).join(','))).join('\n') + '\n';
    const url = URL.createObjectURL(new Blob([text], { type: 'text/csv;charset=utf-8' }));
    const a = h('a', { href: url, download: name.replace(/[^\w.-]+/g, '_') });
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  // ------------------------------------------------------------------ charts

  /**
 * STEP business rule function: cssVars.
 * TODO: Describe purpose, side effects, and expected node/context state.
 */
  function cssVars() {
    const cs = getComputedStyle(document.documentElement);
    const v = (n) => cs.getPropertyValue(n).trim();
    return {
      surface: v('--surface-1'), text: v('--text-secondary'), grid: v('--grid'), border: v('--border'),
      series1: v('--series-1'), series2: v('--series-2'), series3: v('--series-3'), reference: v('--reference'),
      gainMark: v('--gain-mark'), lossMark: v('--loss-mark'), loss: v('--loss-mark'),
    };
  }

  /**
 * STEP business rule function: makeChart.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} el
 */
  function makeChart(el) {
    const css = cssVars();
    const chart = LWC.createChart(el, {
      autoSize: true,
      layout: { background: { type: 'solid', color: css.surface }, textColor: css.text, fontSize: 11, attributionLogo: false,
        fontFamily: getComputedStyle(document.body).fontFamily },
      grid: { vertLines: { color: css.grid }, horzLines: { color: css.grid } },
      rightPriceScale: { borderColor: css.border },
      timeScale: { borderColor: css.border, rightOffset: 3 },
      crosshair: { mode: LWC.CrosshairMode.Normal },
      localization: { priceFormatter: (v) => fmtNumber(v, Math.abs(v) >= 1000 ? 0 : 2) },
    });
    charts.push(chart);
    return chart;
  }

  /**
 * STEP business rule function: syncCharts.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} list
 */
  function syncCharts(list) {
    let busy = false;
    list.forEach((src) => src.timeScale().subscribeVisibleLogicalRangeChange((range) => {
      if (busy || !range) return;
      busy = true;
      list.forEach((dst) => { if (dst !== src) dst.timeScale().setVisibleLogicalRange(range); });
      busy = false;
    }));
  }

  /**
 * STEP business rule function: timeKey.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} t
 */
  function timeKey(t) {
    if (typeof t === 'string') return t;
    if (typeof t === 'object' && t.year) return t.year + '-' + String(t.month).padStart(2, '0') + '-' + String(t.day).padStart(2, '0');
    return new Date(t * 1000).toISOString().slice(0, 10);
  }

  /**
 * STEP business rule function: withAlpha.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} hex
 * @param {*} a
 */
  function withAlpha(hex, a) {
    const m = /^#([0-9a-f]{6})$/i.exec(hex);
    if (!m) return hex;
    const n = parseInt(m[1], 16);
    return 'rgba(' + (n >> 16) + ',' + ((n >> 8) & 255) + ',' + (n & 255) + ',' + a + ')';
  }

  // ------------------------------------------------------------------ formatting

  const numberFormats = new Map();
  /**
 * STEP business rule function: fmtNumber.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} v
 * @param {*} dp
 */
  function fmtNumber(v, dp) {
    if (!numberFormats.has(dp)) numberFormats.set(dp, new Intl.NumberFormat('en-IN', { minimumFractionDigits: dp, maximumFractionDigits: dp }));
    return numberFormats.get(dp).format(v);
  }
  /**
 * STEP business rule function: fmtInt.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} v
 */
  function fmtInt(v) { return v === null || v === undefined ? '' : fmtNumber(v, 0); }
  /**
 * STEP business rule function: fmtMoney.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} v
 * @param {*} dp
 */
  function fmtMoney(v, dp) { return v === null || v === undefined ? '' : '₹' + fmtNumber(v, dp === undefined ? 2 : dp); }
  /**
 * STEP business rule function: fmtPct.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} v
 * @param {*} dp
 * @param {*} signed
 */
  function fmtPct(v, dp, signed) {
    if (v === null || v === undefined || Number.isNaN(v)) return '';
    const s = fmtNumber(v, dp) + '%';
    return signed && v > 0 ? '+' + s : s.replace('-', '−');
  }
  /**
 * STEP business rule function: money.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} v
 */
  function money(v) { return v === null || v === undefined ? '' : fmtNumber(v, 2); }
  /**
 * STEP business rule function: money0.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} v
 */
  function money0(v) { return fmtMoney(v, 0); }
  /**
 * STEP business rule function: qty.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} v
 */
  function qty(v) { return v === null || v === undefined ? '' : fmtNumber(v, Number.isInteger(v) ? 0 : 2); }
  /**
 * STEP business rule function: price.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} v
 */
  function price(v) { return v === null || v === undefined ? '' : fmtNumber(v, Math.abs(v) < 10 ? 4 : 2); }
  /**
 * STEP business rule function: signedMoney.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} v
 */
  function signedMoney(v) {
    if (v === null || v === undefined) return '';
    const r = Math.round(v * 100) / 100;
    const text = (r > 0 ? '+' : r < 0 ? '−' : '') + fmtNumber(Math.abs(r), 2);
    return h('span', { class: r > 0 ? 'gain' : r < 0 ? 'loss' : '', text });
  }
  /**
 * STEP business rule function: pct.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} v
 * @param {*} dp
 * @param {*} signed
 */
  function pct(v, dp, signed) {
    const text = fmtPct(v, dp, signed);
    if (!signed) return text;
    return h('span', { class: v > 0 ? 'gain' : v < 0 ? 'loss' : '', text });
  }
  /**
 * STEP business rule function: dateLabel.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} iso
 */
  function dateLabel(iso) {
    if (!iso) return '';
    return WEEKDAYS[new Date(iso + 'T00:00:00Z').getUTCDay()] + ' ' + iso;
  }

  /**
 * STEP business rule function: symbolMatch.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} sym
 */
  function symbolMatch(sym) {
    if (!S.symbol) return true;
    if (X.symIdx.has(S.symbol)) return sym === S.symbol;
    return sym.includes(S.symbol);
  }

  /** DOM builder; strings become text nodes (never parsed as HTML). */
  function h(tag, props) {
    const e = document.createElement(tag);
    if (props) {
      for (const k of Object.keys(props)) {
        const v = props[k];
        if (v === null || v === undefined || v === false) continue;
        if (k === 'class') e.className = v;
        else if (k === 'text') e.textContent = v;
        else if (k === 'style') e.style.cssText = v;
        else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
        else if (k === 'checked' || k === 'selected' || k === 'disabled') e[k] = !!v;
        else e.setAttribute(k, v === true ? '' : v);
      }
    }
    for (let i = 2; i < arguments.length; i++) append(e, arguments[i]);
    return e;
  }
  /**
 * STEP business rule function: replaceKids.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} el
 */
  function replaceKids(el) {
    el.replaceChildren();
    for (let i = 1; i < arguments.length; i++) append(el, arguments[i]);
  }
  /**
 * STEP business rule function: append.
 * TODO: Describe purpose, side effects, and expected node/context state.
 * @param {*} parent
 * @param {*} kid
 */
  function append(parent, kid) {
    if (kid === null || kid === undefined || kid === false) return;
    if (Array.isArray(kid)) { kid.forEach((k) => append(parent, k)); return; }
    parent.append(kid instanceof Node ? kid : document.createTextNode(String(kid)));
  }

  window.addEventListener('hashchange', () => {
    const before = location.hash;
    readHash();
    if (location.hash === before) render();
  });
  requestAnimationFrame(() => setTimeout(boot, 0));
})();
