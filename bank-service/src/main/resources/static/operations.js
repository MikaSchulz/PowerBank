// Operations (#61): the bank's own books and the systems around it, through /api/operations/…: the ledger check, the
// system accounts, the clearing house's chaos settings and settlement accounts, links to Grafana and Temporal.

import {
  $, $$, accountPostings, api, config, createDrawer, esc, externalLink, label, money, problem, single, startPage, toast,
  traced,
} from './common.js';

const state = { summary: null, offline: false, systemAccounts: [], clearing: null, chaos: null };

const PRESETS = {
  normal: { forwardDelayMs: 0, dropForwardRate: 0 },
  slow: { forwardDelayMs: 12_000, dropForwardRate: 0 },
  lossy: { forwardDelayMs: 0, dropForwardRate: 0.5 },
};

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
  state.clearing = clearing.ok ? clearing.data : { error: problem(clearing) };
  await drawer.refresh();
  render();
});

async function loadChaos() {
  const result = await api('GET', '/api/operations/clearing-house/chaos', undefined, { sampled: false });
  if (result.ok) fillChaos(result.data);
}

function fillChaos(settings) {
  const form = $('#chaos-form');
  form.forwardDelayMs.value = settings.forwardDelayMs;
  form.dropForwardRate.value = settings.dropForwardRate;
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
  $('#chaos-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const form = event.target;
    const settings = { forwardDelayMs: Number(form.forwardDelayMs.value), dropForwardRate: Number(form.dropForwardRate.value) };
    const result = await traced('update clearing house chaos', {},
      (span) => api('PUT', '/api/operations/clearing-house/chaos', settings, { span }));
    if (result.ok) {
      fillChaos(result.data);
      toast('Clearing house updated', `${settings.forwardDelayMs} ms delay, ${Math.round(settings.dropForwardRate * 100)} % of the forwards lost`, 'ok');
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
