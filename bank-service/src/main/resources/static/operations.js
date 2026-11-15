// Operations (#61): the bank's own books and the systems around it, through /api/operations/…: the ledger check, the
// system accounts, the clearing house's chaos settings and settlement accounts, links to Grafana and Temporal.

import {
  $, $$, accountPostings, api, config, createDrawer, esc, externalLink, label, money, problem, single, startPage, toast,
  traced,
} from './common.js';

const state = { summary: null, offline: false, systemAccounts: [], clearing: null, chaos: null };

const NORMAL = { forwardDelayMs: 0, dropForwardRate: 0, outage: false, unreachableBanks: [] };
const PRESETS = {
  normal: NORMAL,
  slow: { ...NORMAL, forwardDelayMs: 12_000 },
  lossy: { ...NORMAL, dropForwardRate: 0.5 },
  outage: { ...NORMAL, outage: true },
};

/** The participants, by bank code, from the clearing house's settlement accounts ("bank:<code>"). */
const participants = () => (Array.isArray(state.clearing) ? state.clearing : [])
  .map((a) => a.account).filter((a) => a.startsWith('bank:')).map((a) => a.slice(5));

const refresh = single(async () => {
  const quiet = { sampled: false };
  const [summary, accounts, clearing] = await Promise.all([
    api('GET', '/api/operations/ledger/summary', undefined, quiet),
    api('GET', '/api/operations/system-accounts', undefined, quiet),
    api('GET', '/api/operations/clearing-house/settlement-accounts', undefined, quiet),
  ]);
  state.offline = summary.status === 0;
  state.summary = summary.ok ? summary.data : null;
  if (accounts.ok) state.systemAccounts = accounts.data;
  const known = participants().join();
  state.clearing = clearing.ok ? clearing.data : { error: problem(clearing) };
  await drawer.refresh();
  render();
  if (participants().join() !== known) renderUnreachable();
});

async function loadChaos() {
  const result = await api('GET', '/api/operations/clearing-house/chaos', undefined, { sampled: false });
  if (result.ok) fillChaos(result.data);
}

function fillChaos(settings) {
  const form = $('#chaos-form');
  form.forwardDelayMs.value = settings.forwardDelayMs;
  form.dropForwardRate.value = settings.dropForwardRate;
  form.outage.checked = Boolean(settings.outage);
  state.chaos = settings;
  renderUnreachable();
}

function readChaos(form) {
  return {
    forwardDelayMs: Number(form.forwardDelayMs.value),
    dropForwardRate: Number(form.dropForwardRate.value),
    outage: form.outage.checked,
    unreachableBanks: $$('#unreachable-banks input:checked').map((input) => input.value),
  };
}

function describeChaos(s) {
  const parts = [];
  if (s.outage) parts.push('down');
  if (s.forwardDelayMs) parts.push(`${s.forwardDelayMs} ms delay`);
  if (s.dropForwardRate) parts.push(`${Math.round(s.dropForwardRate * 100)} % of the forwards lost`);
  if (s.unreachableBanks.length) parts.push(`can't reach ${s.unreachableBanks.join(', ')}`);
  return parts.length ? parts.join(', ') : 'normal';
}

// ---------- rendering ----------

function render() {
  renderHealth();
  renderLedger();
  renderSystemAccounts();
  renderClearing();
}

function renderHealth() {
  const element = $('#ledger-health');
  if (state.offline) {
    element.className = 'health bad';
    element.innerHTML = '<span class="dot"></span>Backend offline';
    return;
  }
  const s = state.summary;
  if (!s) return;
  const balanced = s.sumOfAllPostingsCents === 0;
  element.className = `health ${balanced ? 'ok' : 'bad'}`;
  element.innerHTML = `<span class="dot"></span><span>Σ ${esc(money(s.sumOfAllPostingsCents))}<span class="health-label"> · ${balanced ? 'balanced' : 'UNBALANCED'}</span></span>`;
}

function renderLedger() {
  const s = state.summary;
  if (!s) { $('#ledger').innerHTML = '<p class="hint">Loading…</p>'; return; }
  const stat = (value, text, tone = '') => `<div class="stat ${tone}"><strong>${esc(value)}</strong><span>${esc(text)}</span></div>`;
  $('#ledger').innerHTML = [
    stat(money(s.sumOfAllPostingsCents), 'sum of all postings', s.sumOfAllPostingsCents === 0 ? 'ok' : 'bad'),
    stat(s.postingCount, 'postings'),
    stat(s.transferCount, 'transfers'),
    stat(s.customerAccountCount, 'customer accounts'),
  ].join('');
}

function renderSystemAccounts() {
  $('#system-accounts').innerHTML = state.systemAccounts.map((a) => `
    <button class="system-row" data-open-account="${a.id}">
      <span>${esc(label(a.id))}</span>
      <strong class="${a.balanceCents < 0 ? 'neg' : ''}">${esc(money(a.balanceCents))}</strong>
    </button>`).join('');
}

/** One checkbox per participant; keeps what is ticked in the form until the settings are applied or reloaded. */
function renderUnreachable() {
  const element = $('#unreachable-banks');
  const codes = participants();
  if (!codes.length) { element.innerHTML = '<span class="hint">No participants known</span>'; return; }
  const ticked = new Set(state.chaos?.unreachableBanks ?? []);
  element.innerHTML = codes.map((code) => `
    <label class="check"><input type="checkbox" value="${esc(code)}" ${ticked.has(code) ? 'checked' : ''}><span>Bank ${esc(code)}</span></label>`).join('');
}

function renderClearing() {
  const element = $('#clearing-balances');
  if (!state.clearing) { element.innerHTML = '<p class="hint">Loading…</p>'; return; }
  if (state.clearing.error) {
    element.innerHTML = `<p class="hint">Not reachable: ${esc(state.clearing.error)}. Is the clearing house running?</p>`;
    return;
  }
  element.innerHTML = `
    <table class="ledger-table">
      <thead><tr><th>Account</th><th class="amount">Balance</th></tr></thead>
      <tbody>${state.clearing.map((a) => `
        <tr><td class="mono">${esc(a.account)}</td><td class="amount">${esc(money(a.balanceCents))}</td></tr>`).join('')}</tbody>
    </table>`;
}

function renderLinks() {
  const temporal = `${config.temporalUi}/namespaces/${encodeURIComponent(config.temporalNamespace)}/workflows`;
  $('#links').innerHTML = [
    externalLink(`${config.grafana}/d/powerbank-overview`, 'Dashboard: PowerBank overview'),
    externalLink(`${config.grafana}/d/powerbank-clearing`, 'Dashboard: clearing house'),
    externalLink(`${config.grafana}/d/powerbank-infrastructure`, 'Dashboard: infrastructure'),
    externalLink(`${config.grafana}/alerting/list`, 'Alert rules'),
    externalLink(temporal, `Temporal: namespace ${config.temporalNamespace}`),
  ].join('');
}

// ---------- drawer: a system account's postings ----------

const drawer = createDrawer({
  async load(id) {
    const postings = await api('GET', `/api/operations/system-accounts/${id}/postings`, undefined, { sampled: false });
    return { postings: postings.ok ? postings.data : [] };
  },
  render(id, data) {
    const account = state.systemAccounts.find((a) => a.id === id);
    if (!account) return '<p class="hint">Loading…</p>';
    return `
      <div class="d-eyebrow">System account</div>
      <div class="d-title ${account.balanceCents < 0 ? 'negative' : ''}">${esc(money(account.balanceCents))}</div>
      <div class="d-route"><strong>${esc(label(id))}</strong></div>
      <dl class="d-grid"><dt>Account ID</dt><dd class="mono">${esc(id)}</dd></dl>
      <div class="d-section">
        <h3>Postings (newest first)</h3>
        ${data ? accountPostings(data.postings, { transferLinks: false }) : '<p class="hint">Loading…</p>'}
      </div>`;
  },
});

// ---------- events ----------

function bindEvents() {
  for (const preset of $$('[data-preset]')) preset.addEventListener('click', () => fillChaos(PRESETS[preset.dataset.preset]));
  $('#unreachable-banks').addEventListener('change', () => {
    state.chaos = { ...(state.chaos ?? NORMAL), unreachableBanks: readChaos($('#chaos-form')).unreachableBanks };
  });
  $('#chaos-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const settings = readChaos(event.target);
    const result = await traced('update clearing house chaos', {},
      (span) => api('PUT', '/api/operations/clearing-house/chaos', settings, { span }));
    if (result.ok) {
      fillChaos(result.data);
      toast('Clearing house updated', esc(describeChaos(settings)), 'ok');
    } else {
      toast('Not applied', esc(problem(result)), 'bad');
    }
  });
  document.addEventListener('click', (event) => {
    const open = event.target.closest('[data-open-account]');
    if (open) drawer.open(open.dataset.openAccount);
  });
}

bindEvents();
startPage({ area: 'operations', refresh }).then(() => { renderLinks(); loadChaos(); });
