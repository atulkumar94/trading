// Tests for the portal's browser-side range arithmetic (portal-metrics.js).
// Run: node --test src/test/js/   (from rotation-engine/; Node >= 18, no dependencies)
// Tests marked [generated] read output/rotation/ and are skipped if the engine has not run.
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const M = require('../../main/resources/portal/portal-metrics.js');

const OUT = path.join(__dirname, '..', '..', '..', 'output', 'rotation');
const PORTAL = path.join(OUT, 'rotation_portal.html');
const close = (a, b, tol, msg) => assert.ok(Math.abs(a - b) <= tol, `${msg}: ${a} vs ${b}`);

/** n weekday sessions, each +1% time-weighted, optional contribution at index ci. */
function synthetic(from, n, ci, contribution) {
  const sessions = [];
  const p = { d: [], eq: [], con: [], twr: [], ret: [] };
  let eq = 1000;
  let twr = 1;
  for (let d = from; sessions.length < n; d = M.addDays(d, 1)) {
    const dow = new Date(d + 'T00:00:00Z').getUTCDay();
    if (dow === 0 || dow === 6) continue;
    const c = sessions.length === ci ? contribution : 0;
    eq = (eq + c) * 1.01;
    twr *= 1.01;
    p.d.push(sessions.length);
    sessions.push(d);
    p.eq.push(eq);
    p.con.push(c);
    p.twr.push(twr);
    p.ret.push(1);
  }
  return M.createSeries(p, sessions, 1000);
}

test('30 trading sessions compound exactly 30 daily returns', () => {
  const s = synthetic('2024-01-01', 60);
  const m = M.preset(s, '30S', s.dates[59]);
  assert.equal(m.sessions, 30);
  assert.equal(m.baseDate, s.dates[29]);
  close(m.returnPct, (Math.pow(1.01, 30) - 1) * 100, 1e-9, 'return');
  assert.equal(m.truncated, false);
});

test('30 calendar days resolve a weekend as-of to Friday (known example)', () => {
  const s = synthetic('2024-01-01', 60);
  const m = M.preset(s, '30D', '2024-03-10'); // Sunday
  assert.equal(m.asOf, '2024-03-08');
  assert.equal(m.firstSession, '2024-02-08');
  assert.equal(m.baseDate, '2024-02-07');
  assert.equal(m.sessions, 22);
  close(m.returnPct, (Math.pow(1.01, 22) - 1) * 100, 1e-9, 'return');
});

test('custom range excludes contributions from P&L and return', () => {
  const s = synthetic('2024-01-01', 20, 10, 500);
  const m = M.range(s, 'custom', s.dates[5], s.dates[15]);
  assert.equal(m.sessions, 11);
  close(m.contributions, 500, 1e-9, 'contributions');
  close(m.returnPct, (Math.pow(1.01, 11) - 1) * 100, 1e-9, 'return');
  close(m.pnl, s.p.eq[15] - s.p.eq[4] - 500, 1e-9, 'pnl');
});

test('windows longer than the history are flagged; invalid ranges return null', () => {
  const s = synthetic('2024-01-01', 10);
  assert.equal(M.preset(s, '30S', s.dates[9]).truncated, true);
  assert.equal(M.preset(s, '30D', s.dates[9]).truncated, true);
  assert.equal(M.preset(s, 'ALL', s.dates[9]).truncated, false);
  assert.equal(M.range(s, 'x', s.dates[5], s.dates[2]), null, 'from after as-of');
  assert.equal(M.preset(s, 'ALL', '2023-12-31'), null, 'as-of before data');
});

function loadPortalData() {
  const html = fs.readFileSync(PORTAL, 'utf8');
  const m = html.match(/<script id="portal-data" type="application\/json">([\s\S]*?)<\/script>/);
  assert.ok(m, 'embedded data block present');
  return { html, data: JSON.parse(m[1]) };
}

const generated = fs.existsSync(PORTAL) ? test : test.skip;

generated('[generated] portal is self-contained: no external scripts, styles or fetches', () => {
  const { html } = loadPortalData();
  assert.doesNotMatch(html, /<script[^>]+src=/i);
  assert.doesNotMatch(html, /<link[^>]+href=/i);
  assert.doesNotMatch(html, /\bfetch\(|XMLHttpRequest/);
  assert.match(html, /window\.LightweightCharts=/, 'chart library inlined');
  assert.doesNotMatch(html, /__PORTAL_|\/\*__/, 'all template placeholders replaced');
});

generated('[generated] browser presets match the Java reference metrics', () => {
  const { data } = loadPortalData();
  const s = M.createSeries(data.portfolio, data.sessions, data.manifest.strategy.initial_capital);
  const ref = data.manifest.reference_metrics;
  const js = M.presets(s, ref.as_of);
  assert.equal(js.length, ref.presets.length);
  for (const r of ref.presets) {
    const b = js.find((x) => x.label === r.label);
    assert.ok(b, r.label);
    assert.equal(b.sessions, r.sessions, r.label + ' sessions');
    assert.equal(b.baseDate, r.base_date, r.label + ' base');
    assert.equal(b.firstSession, r.first_session, r.label + ' first');
    assert.equal(b.truncated, r.truncated, r.label + ' truncated');
    close(b.returnPct, r.return_pct, 1e-5, r.label + ' return');
    close(b.pnl, r.pnl, 0.011, r.label + ' pnl'); // embedded equity is rounded to 0.01
    close(b.maxDrawdownPct, r.max_drawdown_pct, 1e-5, r.label + ' max dd');
  }
});

generated('[generated] ranges match an independent recomputation from the daily CSV', () => {
  const { data } = loadPortalData();
  const s = M.createSeries(data.portfolio, data.sessions, data.manifest.strategy.initial_capital);
  const lines = fs.readFileSync(path.join(OUT, 'rotation_daily_portfolio.csv'), 'utf8').trim().split('\n');
  const head = lines[0].split(',');
  const col = (n) => head.indexOf(n);
  const rows = lines.slice(1).map((l) => l.split(','));
  const dates = rows.map((r) => r[col('date')]);
  const eq = rows.map((r) => +r[col('account_equity')]);
  const ret = rows.map((r) => +r[col('daily_return_pct')]);
  const con = rows.map((r) => +r[col('contribution')]);
  assert.deepEqual(dates, s.dates, 'portal and CSV cover the same sessions');
  // Independent: compound the CSV daily returns over sessions in (base, asOf].
  for (const asOfIdx of [40, 900, 2000, 3333, dates.length - 1]) {
    for (const len of [30, 250]) {
      const startIdx = Math.max(0, asOfIdx - len + 1);
      let growth = 1;
      let contributions = 0;
      for (let i = startIdx; i <= asOfIdx; i++) {
        growth *= 1 + ret[i] / 100;
        contributions += con[i];
      }
      const startEq = startIdx === 0 ? data.manifest.strategy.initial_capital : eq[startIdx - 1];
      const m = M.range(s, 'x', dates[startIdx], dates[asOfIdx]);
      close(m.returnPct, (growth - 1) * 100, 1e-3, `return ${dates[startIdx]}..${dates[asOfIdx]}`);
      close(m.pnl, eq[asOfIdx] - startEq - contributions, 0.01, `pnl ${dates[startIdx]}..${dates[asOfIdx]}`);
    }
  }
  // Non-trading as-of dates resolve to the previous session.
  const sat = dates.find((d) => new Date(d + 'T00:00:00Z').getUTCDay() === 5);
  assert.equal(s.dates[M.resolveAsOf(s, M.addDays(sat, 1))], sat);
});

generated('[generated] full-year TWR equals the engine yearly report; partial years are flagged', () => {
  const { data } = loadPortalData();
  const s = M.createSeries(data.portfolio, data.sessions, data.manifest.strategy.initial_capital);
  const firstInvested = data.sessions[data.rebalances[0].d];
  const years = M.periodReturns(s, 'year', s.dates.length - 1, firstInvested);
  const contributed = data.portfolio.con.some((c) => c !== 0);
  for (const y of years) {
    const engine = data.yearly.find((e) => String(e.year) === y.key);
    assert.ok(engine, 'engine row for ' + y.key);
    close(y.endEquity, engine.end_equity, 0.006, y.key + ' year-end equity');
    if (!contributed) close(y.returnPct, engine.yearly_return_pct, 0.006, y.key + ' return');
  }
  assert.equal(years[0].partial, true, 'first year starts before the first investment');
  assert.equal(years[years.length - 1].partial, true, 'data ends mid-year');
  assert.ok(years.slice(1, -1).every((y) => !y.partial), 'middle years are complete');
});

generated('[generated] decoded prices match the daily market snapshot', () => {
  const { data } = loadPortalData();
  const want = new Set(['TITAN', 'BAJFINANCE', data.symbols[0]]);
  const found = {};
  const text = fs.readFileSync(path.join(OUT, 'rotation_daily_market_snapshot.csv'), 'utf8');
  for (const line of text.split('\n')) {
    const p = line.split(',');
    if (want.has(p[1])) (found[p[1]] = found[p[1]] || new Map()).set(p[0], p.slice(3, 7).map(Number));
  }
  for (const sym of want) {
    if (!data.prices[sym]) continue;
    const bars = M.decodePrices(data.prices[sym], data.sessions);
    const scale = Math.pow(10, -data.prices[sym].k);
    let checked = 0;
    for (const b of bars) {
      if (b.missing) continue;
      const [o, hi, lo, c] = found[sym].get(b.time);
      close(b.open, o, scale, sym + ' open ' + b.time);
      close(b.high, hi, scale, sym + ' high ' + b.time);
      close(b.low, lo, scale, sym + ' low ' + b.time);
      close(b.close, c, scale / 2 + 1e-9, sym + ' close ' + b.time);
      checked++;
    }
    assert.equal(checked, found[sym].size, sym + ' bar count');
  }
});

generated('[generated] holdings on every session reconcile to the daily portfolio', () => {
  const { data } = loadPortalData();
  const P = data.positions;
  const bySession = new Map();
  const keys = new Set();
  for (let i = 0; i < P.d.length; i++) {
    const k = P.d[i] + ':' + P.s[i];
    assert.ok(!keys.has(k), 'unique date/symbol ' + k);
    keys.add(k);
    const t = bySession.get(P.d[i]) || { mv: 0, n: 0 };
    t.mv += P.mv[i];
    t.n++;
    bySession.set(P.d[i], t);
  }
  const pf = data.portfolio;
  for (let i = 0; i < pf.d.length; i++) {
    const t = bySession.get(pf.d[i]) || { mv: 0, n: 0 };
    assert.equal(t.n, pf.pos[i], 'position count on ' + data.sessions[pf.d[i]]);
    close(t.mv, pf.inv[i], 0.01 * Math.max(1, t.n), 'invested on ' + data.sessions[pf.d[i]]);
    close(pf.eq[i], pf.cash[i] + pf.inv[i], 0.011, 'equity = cash + invested on ' + data.sessions[pf.d[i]]);
  }
});
