// PowerBank UI: plain ES module, no build step. Talks to the bank-service REST API on the same origin.

import { action as traced, initTelemetry, startRequest } from './telemetry.js';

// Overridden from /api/ui-config at startup (bank.ui.* properties), so nothing is tied to localhost.
let TEMPORAL_UI = 'http://localhost:8233';
let GRAFANA = 'http://localhost:3000';
let TEMPORAL_NAMESPACE = 'default';
const CASH = '00000000-0000-0000-0000-000000000001';
const CLEARING = '00000000-0000-0000-0000-000000000002';
const SETTLEMENT = '00000000-0000-0000-0000-000000000003';
const POLL_MS = 1500;
const TERMINAL = new Set(['BOOKED', 'COMPLETED', 'REFUNDED', 'REJECTED']);

const STATUS = {
  RECEIVED: { label: 'Received', tone: 'info', live: true,
    explain: 'Received in the ledger, and the workflow has started. The <strong>risk check activity</strong> decides whether a person needs to approve.' },
  AWAITING_APPROVAL: { label: 'Awaiting approval', tone: 'warn', live: true,
    explain: 'The workflow waits in <code>Workflow.await()</code> for up to 3 days. Nothing runs meanwhile: Temporal keeps the open workflow and a timer, and the <strong>ledger</strong> shows the transfer as awaiting approval. The <strong>approve update</strong> (checked by a validator) lets it continue or abort. Nothing has been booked yet.' },
  APPROVED: { label: 'Approved', tone: 'info', live: true,
    explain: 'The approval is recorded in the ledger. The workflow continues with the booking.' },
  RESERVED: { label: 'Reserved', tone: 'info', live: true,
    explain: 'SEPA Instant: the customer was debited and the amount is <strong>reserved</strong> on the clearing account. The workflow sends the <code>pacs.008</code> to the clearing house, retrying until the 10-second deadline.' },
  SENT_TO_EXTERNAL_BANK: { label: 'At clearing house', tone: 'info', live: true,
    explain: 'The clearing house has the payment and the creditor bank decides within seconds. The workflow waits for the <strong>confirm update</strong> (<code>pacs.002</code>); without it, it asks with a status inquiry (<code>pacs.028</code>).' },
  UNDER_INVESTIGATION: { label: 'Under investigation', tone: 'warn', live: true,
    explain: 'No final status from the clearing house: neither a confirmation nor a status inquiry gave a definitive answer. The amount <strong>stays reserved</strong>; nothing is guessed. The workflow keeps asking every 10 minutes and settles or refunds on the answer.' },
  BOOKED: { label: 'Booked', tone: 'ok',
    explain: 'Debit and credit were booked in <strong>one database transaction</strong>. No saga needed.' },
  COMPLETED: { label: 'Completed', tone: 'ok',
    explain: 'The clearing house confirmed the settlement (<code>ACSC</code>): the reserved amount went from the clearing account to the <strong>settlement account</strong>.' },
  REFUNDED: { label: 'Refunded', tone: 'warn',
    explain: 'Rejected with a reason code (<code>RJCT</code>), or the clearing house never received the payment. The reserved amount was booked back from the clearing account.' },
  REJECTED: { label: 'Rejected', tone: 'bad',
    explain: 'The transfer was rejected. <strong>Nothing was booked.</strong>' },
};

const KIND_ICON = {
  INTERNAL: '<svg viewBox="0 0 24 24"><path d="M7 7h12l-3-3M17 17H5l3 3"/></svg>',
  EXTERNAL: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c3 3.5 3 14.5 0 18M12 3c-3 3.5-3 14.5 0 18"/></svg>',
  DEPOSIT: '<svg viewBox="0 0 24 24"><path d="M12 4v11M7 10l5 5 5-5M5 20h14"/></svg>',
};
const CHECK = '<svg viewBox="0 0 24 24"><path d="m5 12 5 5 9-10"/></svg>';
const CROSS = '<svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6 6 18"/></svg>';

const state = {
  accounts: [],
  transfers: [],
  summary: null,
  filter: 'all',
  accountFilter: null,
  mode: 'internal',
  drawer: null, // { type: 'transfer' | 'account', id }
  drawerData: null,
  statuses: new Map(),
  transferCache: new Map(),
  pendingDeny: null,
  depositTarget: null,
  traceIds: new Map(), // transferId -> W3C trace ID, from the POST response (before the list knows it)
};

// ---------- helpers ----------

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
const esc = (value) => String(value ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const moneyFormat = new Intl.NumberFormat(undefined, { style: 'currency', currency: 'EUR' });
const money = (cents) => (cents == null ? '–' : moneyFormat.format(cents / 100));
const short = (id) => (id ? id.slice(0, 8) : '');
const relative = new Intl.RelativeTimeFormat('en', { numeric: 'auto' });

function ago(iso) {
  const seconds = Math.round((Date.parse(iso) - Date.now()) / 1000);
  if (Math.abs(seconds) < 60) return relative.format(seconds, 'second');
  const minutes = Math.round(seconds / 60);
  if (Math.abs(minutes) < 60) return relative.format(minutes, 'minute');
  const hours = Math.round(minutes / 60);
  if (Math.abs(hours) < 24) return relative.format(hours, 'hour');
  return relative.format(Math.round(hours / 24), 'day');
}

/** Parses "12", "12.5", "12,50" or "1.234,56" into cents without floating point surprises. */
function parseCents(raw) {
  let text = String(raw ?? '').trim().replace(/\s|€/g, '');
  if (!text) return null;
  if (/^\d{1,3}(\.\d{3})+(,\d{1,2})?$/.test(text)) text = text.replace(/\./g, '');
  text = text.replace(',', '.');
  if (!/^\d+(\.\d{1,2})?$/.test(text)) return NaN;
  const [euros, decimals = ''] = text.split('.');
  return Number(euros) * 100 + Number(decimals.padEnd(2, '0'));
}

const uuid = () => (crypto.randomUUID ? crypto.randomUUID()
  : '10000000-1000-4000-8000-100000000000'.replace(/[018]/g, (c) => (c ^ (crypto.getRandomValues(new Uint8Array(1))[0] & (15 >> (c / 4)))).toString(16)));

function hue(id) {
  let h = 0;
  for (const c of id) h = (h * 31 + c.charCodeAt(0)) % 360;
  return h;
}

/** Builds an IBAN with valid check digits (ISO 13616, mod 97). */
function iban(country, bban) {
  const digits = (bban + country + '00').replace(/[A-Z]/g, (c) => String(c.charCodeAt(0) - 55));
  const check = 98n - (BigInt(digits) % 97n);
  return country + String(check).padStart(2, '0') + bban;
}
const formatIban = (value) => value.replace(/(.{4})/g, '$1 ').trim();
function randomIban() {
  let account = '';
  while (account.length < 10) account += Math.floor(Math.random() * 10);
  if (account.endsWith('000000')) account = account.slice(0, 9) + '1';
  return formatIban(iban('DE', '37040044' + account));
}
const REJECTING_IBAN = formatIban(iban('DE', '370400440532013000')); // a real bank code, not a participant: RC01

// ---------- nicknames (browser only: the bank never sees names) ----------

const NICK_KEY = 'powerbank.nicknames';
function nicknames() {
  try { return JSON.parse(localStorage.getItem(NICK_KEY)) || {}; } catch { return {}; }
}
function setNickname(id, name) {
  try {
    const all = nicknames();
    if (name) all[id] = name; else delete all[id];
    localStorage.setItem(NICK_KEY, JSON.stringify(all));
  } catch { /* storage unavailable: names just aren't remembered */ }
}
function label(id) {
  if (!id) return 'External bank';
  if (id === CASH) return 'Cash desk';
  if (id === CLEARING) return 'External clearing';
  if (id === SETTLEMENT) return 'Settlement (central bank)';
  return nicknames()[id] || `Account ${short(id).slice(0, 4).toUpperCase()}`;
}

// ---------- W3C trace context ----------
// Every user action is the root span of its own trace (telemetry.js); its API calls send a W3C `traceparent`
// that the backend and Temporal continue. Polling sends an unsampled traceparent to keep Tempo free of noise.

function grafanaTraceUrl(traceId, fromIso, toIso) {
  const from = fromIso ? Date.parse(fromIso) - 5 * 60_000 : 'now-24h';
  const to = toIso ? Date.parse(toIso) + 10 * 60_000 : 'now';
  const panes = {
    a: {
      datasource: 'tempo',
      queries: [{ refId: 'A', datasource: { type: 'tempo', uid: 'tempo' }, queryType: 'traceql', query: traceId }],
      range: { from: String(from), to: String(to) },
    },
  };
  return `${GRAFANA}/explore?schemaVersion=1&orgId=1&panes=${encodeURIComponent(JSON.stringify(panes))}`;
}

/** Grafana Explore with a TraceQL search, e.g. all traces of one account over the last day. */
function grafanaSearchUrl(traceql) {
  const panes = {
    a: {
      datasource: 'tempo',
      queries: [{ refId: 'A', datasource: { type: 'tempo', uid: 'tempo' }, queryType: 'traceql', query: traceql, limit: 50, tableType: 'traces' }],
      range: { from: 'now-24h', to: 'now' },
    },
  };
  return `${GRAFANA}/explore?schemaVersion=1&orgId=1&panes=${encodeURIComponent(JSON.stringify(panes))}`;
}

const accountTraceQl = (id) =>
  `{ span.bank.account.id = "${id}" || span.bank.debtor.account.id = "${id}" || span.bank.creditor.account.id = "${id}" }`;

// ---------- API ----------

/** `span`: the user action this call belongs to (its client span becomes a child); `sampled: false` for polling. */
async function api(method, path, body, { sampled = true, span = null } = {}) {
  const request = startRequest(span, method, path, { sampled });
  try {
    const headers = { ...request.headers };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const response = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    request.end(response.status);
    const text = await response.text();
    let data = null;
    if (text) { try { data = JSON.parse(text); } catch { data = text; } }
    return { ok: response.ok, status: response.status, data };
  } catch (error) {
    request.end(0);
    return { ok: false, status: 0, data: { detail: 'Backend not reachable. Is bank-service running?' } };
  }
}
const problem = (r) => r.data?.detail || r.data?.title || `HTTP ${r.status}`;

/** Escape keeps the previous returnValue of a <dialog>, so reset it before every opening. */
function openDialog(selector) {
  const dialog = $(selector);
  dialog.returnValue = '';
  dialog.showModal();
  return dialog;
}

// ---------- toasts ----------

function toast(title, detail = '', tone = 'info') {
  const element = document.createElement('div');
  element.className = `toast ${tone}`;
  element.innerHTML = `<i class="t-icon"></i><div><strong>${esc(title)}</strong>${detail ? `<span>${detail}</span>` : ''}</div>`;
  $('#toasts').append(element);
  setTimeout(() => {
    element.classList.add('leaving');
    element.addEventListener('animationend', () => element.remove(), { once: true });
  }, tone === 'bad' ? 7000 : 4500);
}

// ---------- data refresh ----------

let refreshing = false;
async function refresh() {
  if (refreshing) return;
  refreshing = true;
  try {
    const query = state.accountFilter ? `?accountId=${state.accountFilter}&limit=80` : '?limit=80';
    const quiet = { sampled: false };
    const [accounts, transfers, summary] = await Promise.all([
      api('GET', '/api/accounts', undefined, quiet),
      api('GET', `/api/transfers${query}`, undefined, quiet),
      api('GET', '/api/ledger/summary', undefined, quiet),
    ]);
    if (accounts.ok) state.accounts = accounts.data;
    if (transfers.ok) {
      state.transfers = transfers.data;
      for (const t of transfers.data) state.transferCache.set(t.transferId, t);
    }
    state.summary = summary.ok ? summary.data : null;
    state.offline = !accounts.ok && accounts.status === 0;
    await refreshDrawer();
    render();
  } finally {
    refreshing = false;
  }
}

async function refreshDrawer() {
  const drawer = state.drawer;
  if (!drawer) return;
  if (drawer.type === 'transfer') {
    const quiet = { sampled: false };
    const [live, postings, history] = await Promise.all([
      api('GET', `/api/transfers/${drawer.id}`, undefined, quiet),
      api('GET', `/api/transfers/${drawer.id}/postings`, undefined, quiet),
      api('GET', `/api/transfers/${drawer.id}/history`, undefined, quiet),
    ]);
    state.drawerData = {
      live: live.ok ? live.data : null,
      postings: postings.ok ? postings.data : [],
      history: history.ok ? history.data : [],
    };
  } else {
    const postings = await api('GET', `/api/accounts/${drawer.id}/postings`, undefined, { sampled: false });
    state.drawerData = { postings: postings.ok ? postings.data : [] };
  }
}

// ---------- rendering ----------

function render() {
  renderHealth();
  renderApprovals();
  renderAccounts();
  renderTransfers();
  syncSelects();
  renderDrawer();
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
  element.innerHTML = `<span class="dot"></span>
    <span>Σ ${esc(money(s.sumOfAllPostingsCents))}<span class="health-label"> · ${balanced ? 'balanced' : 'UNBALANCED'}</span></span>
    <span class="sep extra">|</span>
    <span class="extra muted">${s.postingCount} postings · ${s.transferCount} transfers</span>`;
}

function renderApprovals() {
  const pending = state.transfers.filter((t) => t.status === 'AWAITING_APPROVAL');
  const element = $('#approvals');
  element.hidden = pending.length === 0;
  if (!pending.length) return;
  element.innerHTML = `
    <div class="approvals-head">
      <svg viewBox="0 0 24 24"><path d="M12 3 2 20h20L12 3zM12 10v4M12 17h0"/></svg>
      ${pending.length} transfer${pending.length > 1 ? 's' : ''} awaiting approval
    </div>
    ${pending.map((t) => `
      <div class="approval-row">
        <div class="tx-main">
          <span class="tx-title" data-open-transfer="${t.transferId}">${esc(route(t))}</span>
          <span class="tx-sub">${esc(t.reason || 'Manual review required')} · ${esc(ago(t.createdAt))}</span>
        </div>
        <span class="tx-amount">${esc(money(t.amountCents))}</span>
        <div class="approval-actions">
          <button class="btn ok small" data-action="approve" data-id="${t.transferId}">Approve</button>
          <button class="btn ghost small" data-action="deny" data-id="${t.transferId}">Deny</button>
        </div>
      </div>`).join('')}`;
}

function renderAccounts() {
  const customers = state.accounts.filter((a) => a.kind === 'CUSTOMER')
    .sort((a, b) => label(a.id).localeCompare(label(b.id)));
  const system = state.accounts.filter((a) => a.kind === 'SYSTEM');
  const list = $('#accounts');

  if (!customers.length) {
    list.innerHTML = `
      <div class="empty">
        <strong>No accounts yet</strong>
        <span>Start with two funded accounts and play through every scenario.</span>
        <button class="btn primary" data-action="quick-start">Quick start: Alice &amp; Bob</button>
      </div>`;
  } else {
    list.innerHTML = customers.map((a) => {
      const name = label(a.id);
      const usedOverdraft = a.balanceCents < 0 && a.overdraftLimitCents > 0
        ? Math.min(100, Math.round((-a.balanceCents / a.overdraftLimitCents) * 100)) : 0;
      return `
        <button class="account-card ${state.accountFilter === a.id ? 'selected' : ''}" data-open-account="${a.id}">
          <div class="ac-top">
            <span class="avatar" style="--hue:${hue(a.id)}">${esc((nicknames()[a.id] || a.id).charAt(0).toUpperCase())}</span>
            <span class="ac-name"><strong>${esc(name)}</strong><span class="mono">${a.iban ? esc(formatIban(a.iban)) : short(a.id)}</span></span>
            ${statusChip(a.status)}
          </div>
          <div class="ac-balance ${a.balanceCents < 0 ? 'negative' : ''}">${esc(money(a.balanceCents))}</div>
          <div class="ac-meta">Available ${esc(money(a.balanceCents + a.overdraftLimitCents))}${a.overdraftLimitCents ? ` · overdraft ${esc(money(a.overdraftLimitCents))}` : ''}</div>
          ${a.overdraftLimitCents ? `<div class="meter" title="Overdraft used"><span class="${usedOverdraft > 0 ? 'over' : ''}" style="width:${usedOverdraft}%"></span></div>` : ''}
        </button>`;
    }).join('');
  }

  $('#system-accounts').innerHTML = system.map((a) => `
    <button class="system-row" data-open-account="${a.id}">
      <span>${esc(label(a.id))}</span>
      <strong class="${a.balanceCents < 0 ? 'neg' : ''}">${esc(money(a.balanceCents))}</strong>
    </button>`).join('');
}

function statusChip(status) {
  const tone = { OPEN: 'ok', BLOCKED: 'warn', CLOSED: 'bad' }[status] || '';
  return `<span class="chip ${tone}">${esc(status.toLowerCase())}</span>`;
}

function transferChip(status) {
  const info = STATUS[status] || { label: status, tone: '' };
  return `<span class="chip ${info.tone}">${info.live ? '<i class="spinner"></i>' : ''}${esc(info.label)}</span>`;
}

function route(t) {
  if (t.kind === 'DEPOSIT') return `Cash deposit → ${label(t.creditorAccountId)}`;
  if (t.kind === 'EXTERNAL') return `${label(t.debtorAccountId)} → External bank`;
  if (!t.kind) return `Transfer ${short(t.transferId)}`;
  return `${label(t.debtorAccountId)} → ${label(t.creditorAccountId)}`;
}

const FILTERS = {
  all: () => true,
  flight: (t) => !TERMINAL.has(t.status),
  approval: (t) => t.status === 'AWAITING_APPROVAL' || t.status === 'APPROVED',
  done: (t) => t.status === 'BOOKED' || t.status === 'COMPLETED',
  failed: (t) => t.status === 'REJECTED' || t.status === 'REFUNDED',
};

function renderTransfers() {
  for (const button of $$('#filters button')) {
    const count = state.transfers.filter(FILTERS[button.dataset.filter]).length;
    button.classList.toggle('active', button.dataset.filter === state.filter);
    button.innerHTML = `${esc(button.dataset.label ||= button.textContent)}<span class="count">${count}</span>`;
  }

  const filterBar = $('#account-filter');
  filterBar.hidden = !state.accountFilter;
  if (state.accountFilter) {
    filterBar.innerHTML = `<span>Showing transfers of <strong>${esc(label(state.accountFilter))}</strong></span>
      <button class="btn ghost small" data-action="clear-account-filter">Show all</button>`;
  }

  const visible = state.transfers.filter(FILTERS[state.filter]);
  const feed = $('#transfers');
  if (!visible.length) {
    feed.innerHTML = `<div class="empty"><strong>No transfers here</strong><span>Send one above or try a scenario.</span></div>`;
    return;
  }

  const changed = new Set();
  for (const t of state.transfers) {
    const previous = state.statuses.get(t.transferId);
    if (previous && previous !== t.status) changed.add(t.transferId);
    state.statuses.set(t.transferId, t.status);
  }

  feed.innerHTML = visible.map((t) => {
    const kind = t.kind || 'INTERNAL';
    return `
      <button class="tx-row ${changed.has(t.transferId) ? 'flash' : ''}" data-open-transfer="${t.transferId}">
        <span class="tx-icon ${kind.toLowerCase()}">${KIND_ICON[kind]}</span>
        <span class="tx-main">
          <span class="tx-title">${esc(route(t))}</span>
          <span class="tx-sub">${esc(kind.charAt(0) + kind.slice(1).toLowerCase())} · ${esc(ago(t.createdAt))} · <span class="mono">${short(t.transferId)}</span></span>
          ${t.reason ? `<span class="tx-reason">${esc(t.reason)}</span>` : ''}
        </span>
        <span class="tx-right">
          <span class="tx-amount">${esc(money(t.amountCents))}</span>
          ${transferChip(t.status)}
        </span>
      </button>`;
  }).join('');
}

/** Keeps the account selects in sync without losing the user's selection. */
function syncSelects() {
  const customers = state.accounts.filter((a) => a.kind === 'CUSTOMER' && a.status !== 'CLOSED')
    .sort((a, b) => label(a.id).localeCompare(label(b.id)));
  const options = customers.map((a) => [a.id, `${label(a.id)} · ${money(a.balanceCents)}`]);
  const form = $('#transfer-form');
  const fill = (select, fallbackIndex) => {
    const current = select.value;
    const html = options.map(([id, text]) => `<option value="${id}">${esc(text)}</option>`).join('');
    if (select.dataset.html !== html) {
      select.innerHTML = html || '<option value="">No accounts yet</option>';
      select.dataset.html = html;
    }
    if (current && options.some(([id]) => id === current)) select.value = current;
    else if (options.length) select.value = options[Math.min(fallbackIndex, options.length - 1)][0];
  };
  fill(form.debtor, 0);
  fill(form.creditor, 1);
  fill(form.depositTarget, 0);
  // Accounts can appear one by one (e.g. quick start). Unless the user picked "To" deliberately,
  // never leave it on the same account as "From".
  if (!form.creditor.dataset.touched && form.creditor.value === form.debtor.value) {
    const other = options.find(([id]) => id !== form.debtor.value);
    if (other) form.creditor.value = other[0];
  }
}

// ---------- drawer ----------

function openDrawer(type, id) {
  state.drawer = { type, id };
  state.drawerData = null;
  $('#drawer').classList.add('open');
  $('#drawer').setAttribute('aria-hidden', 'false');
  $('#scrim').hidden = false;
  renderDrawer();
  refreshDrawer().then(renderDrawer);
}

function closeDrawer() {
  state.drawer = null;
  $('#drawer').classList.remove('open');
  $('#drawer').setAttribute('aria-hidden', 'true');
  $('#scrim').hidden = true;
}

function renderDrawer() {
  const drawer = state.drawer;
  if (!drawer) return;
  const content = $('#drawer-content');
  content.innerHTML = drawer.type === 'transfer' ? transferDetails(drawer.id) : accountDetails(drawer.id);
}

function transferDetails(id) {
  const cached = state.transferCache.get(id) || { transferId: id };
  const live = state.drawerData?.live;
  const t = { ...cached, ...(live ? { status: live.status, reason: live.reason } : {}) };
  if (!t.amountCents && live?.amountCents) t.amountCents = live.amountCents;
  if (!t.kind && live) t.kind = live.external ? 'EXTERNAL' : 'INTERNAL';
  const info = STATUS[t.status] || { label: t.status || 'Loading…', explain: '' };
  const postings = state.drawerData?.postings || [];
  const temporalLink = `${TEMPORAL_UI}/namespaces/${encodeURIComponent(TEMPORAL_NAMESPACE)}/workflows?query=${encodeURIComponent(`WorkflowId="transfer-${id}"`)}`;
  const traceId = t.traceId || state.traceIds.get(id);

  return `
    <div class="d-eyebrow">${esc((t.kind || 'transfer').toLowerCase())} transfer</div>
    <div class="d-title">${esc(money(t.amountCents))}</div>
    <div class="d-route"><strong>${esc(route(t))}</strong></div>
    ${t.status ? transferChip(t.status) : ''}
    ${t.status ? stepper(t, state.drawerData?.history || []) : ''}
    ${info.explain ? `<div class="explain">${info.explain}${t.reason ? `<br><br>Reason: <strong>${esc(t.reason)}</strong>` : ''}</div>` : ''}
    ${t.status === 'AWAITING_APPROVAL' ? `
      <div class="d-actions">
        <button class="btn ok" data-action="approve" data-id="${id}">Approve</button>
        <button class="btn ghost" data-action="deny" data-id="${id}">Deny</button>
      </div>` : ''}
    <div class="d-section">
      <h3>History in the ledger</h3>
      ${historyTable(state.drawerData?.history || [])}
    </div>
    <div class="d-section">
      <h3>Postings in the ledger</h3>
      ${postingsTable(postings, true)}
    </div>
    <div class="d-section">
      <h3>Details</h3>
      <dl class="d-grid">
        <dt>Transfer ID</dt><dd class="mono">${esc(id)}</dd>
        <dt>Workflow ID</dt><dd class="mono">transfer-${esc(id)}</dd>
        <dt>Trace ID</dt><dd class="mono">${traceId ? esc(traceId) : '<span class="muted">not recorded</span>'}</dd>
        ${t.createdAt ? `<dt>Created</dt><dd>${esc(new Date(t.createdAt).toLocaleString())}</dd>` : ''}
      </dl>
      <div class="d-actions">
        <a class="btn ghost" href="${temporalLink}" target="_blank" rel="noopener">Open in Temporal UI
          <svg viewBox="0 0 24 24"><path d="M7 17 17 7M9 7h8v8"/></svg></a>
        ${traceId ? `<a class="btn ghost" href="${grafanaTraceUrl(traceId, t.createdAt, t.updatedAt)}" target="_blank" rel="noopener">Open trace in Grafana
          <svg viewBox="0 0 24 24"><path d="M7 17 17 7M9 7h8v8"/></svg></a>` : ''}
        <button class="btn ghost" data-action="copy" data-value="${id}">Copy ID</button>
      </div>
    </div>`;
}

function stepper(t, history) {
  const external = t.kind === 'EXTERNAL';
  const withApproval = t.status === 'AWAITING_APPROVAL' || history.some((h) => h.status === 'AWAITING_APPROVAL');
  const steps = [{ key: 'received', label: 'Received' }];
  if (withApproval) steps.push({ key: 'approval', label: 'Approval' });
  if (external) steps.push({ key: 'bank', label: 'Clearing house' });
  steps.push({ key: 'final', label: external ? 'Completed' : 'Booked' });
  steps.forEach((s) => { s.state = 'todo'; });

  const set = (key, value, text) => {
    const step = steps.find((s) => s.key === key);
    if (step) { step.state = value; if (text) step.label = text; }
  };
  const doneBefore = (key) => {
    for (const s of steps) { if (s.key === key) break; s.state = 'done'; }
  };

  switch (t.status) {
    case 'RECEIVED': set('received', 'current'); break;
    case 'AWAITING_APPROVAL': doneBefore('approval'); set('approval', 'current'); break;
    case 'APPROVED': doneBefore(external ? 'bank' : 'final'); set(external ? 'bank' : 'final', 'current'); break;
    case 'RESERVED': case 'SENT_TO_EXTERNAL_BANK': doneBefore('bank'); set('bank', 'current'); break;
    case 'UNDER_INVESTIGATION': doneBefore('bank'); set('bank', 'current', 'Under investigation'); break;
    case 'BOOKED': case 'COMPLETED': steps.forEach((s) => { s.state = 'done'; }); break;
    case 'REFUNDED': doneBefore('final'); set('final', 'fail', 'Refunded'); break;
    case 'REJECTED':
      if (withApproval && !history.some((h) => h.status === 'APPROVED')) { doneBefore('approval'); set('approval', 'fail', 'Not approved'); }
      else { const at = external ? 'bank' : 'final'; doneBefore(at); set(at, 'fail', 'Rejected'); }
      break;
    default: break;
  }

  return `<div class="stepper">${steps.map((s, i) => `
    <div class="step ${s.state}">
      <span class="bullet">${s.state === 'done' ? CHECK : s.state === 'fail' ? CROSS : i + 1}</span>
      <span>${esc(s.label)}</span>
    </div>`).join('')}</div>`;
}

/** The transfer's business history from the ledger: every status it went through, with time and reason. */
function historyTable(history) {
  if (!history.length) return '<p class="hint">Loading…</p>';
  return `
    <table class="ledger-table">
      <thead><tr><th>Time</th><th>Status</th><th>Reason or comment</th></tr></thead>
      <tbody>${history.map((h) => `
        <tr>
          <td>${esc(new Date(h.at).toLocaleTimeString())}</td>
          <td>${esc(STATUS[h.status]?.label || h.status)}</td>
          <td>${h.reason ? esc(h.reason) : '<span class="muted">–</span>'}</td>
        </tr>`).join('')}</tbody>
    </table>`;
}

function postingsTable(postings, withSum) {
  if (!postings.length) {
    return '<p class="hint">No postings. Either nothing has been booked yet, or the transfer was rejected before booking.</p>';
  }
  const sum = postings.reduce((total, p) => total + p.amountCents, 0);
  return `
    <table class="ledger-table">
      <thead><tr><th>Account</th><th>Leg</th><th class="amount">Amount</th></tr></thead>
      <tbody>${postings.map((p) => `
        <tr>
          <td>${esc(label(p.accountId))}</td>
          <td class="leg">${esc(p.leg)}</td>
          <td class="amount ${p.amountCents < 0 ? 'neg' : 'pos'}">${esc(money(p.amountCents))}</td>
        </tr>`).join('')}</tbody>
      ${withSum ? `<tfoot><tr><td colspan="2">Σ (double-entry)</td><td class="amount ${sum === 0 ? 'pos' : 'neg'}">${esc(money(sum))} ${sum === 0 ? '✓' : '✗'}</td></tr></tfoot>` : ''}
    </table>`;
}

function accountDetails(id) {
  const account = state.accounts.find((a) => a.id === id);
  if (!account) return '<p class="hint">Loading…</p>';
  const system = account.kind === 'SYSTEM';
  const postings = state.drawerData?.postings || [];
  let running = 0;
  const rows = postings.map((p) => { running += p.amountCents; return { ...p, running }; }).reverse().slice(0, 100);

  return `
    <div class="d-eyebrow">${system ? 'System account' : 'Customer account'}</div>
    <div class="d-title ${account.balanceCents < 0 ? 'negative' : ''}">${esc(money(account.balanceCents))}</div>
    <div class="d-route"><strong>${esc(label(id))}</strong> ${statusChip(account.status)}</div>
    <dl class="d-grid">
      <dt>Account ID</dt><dd class="mono">${esc(id)}</dd>
      ${account.iban ? `<dt>IBAN</dt><dd class="mono">${esc(formatIban(account.iban))}</dd>` : ''}
      ${system ? '' : `<dt>Overdraft</dt><dd>${esc(money(account.overdraftLimitCents))}</dd>
      <dt>Available</dt><dd>${esc(money(account.balanceCents + account.overdraftLimitCents))}</dd>`}
    </dl>
    <div class="d-actions">
      ${system ? '' : `
        <button class="btn primary" data-action="deposit" data-id="${id}">Deposit</button>
        <button class="btn ghost" data-action="send-from" data-id="${id}">Send from here</button>
        ${account.status === 'BLOCKED'
          ? `<button class="btn ghost" data-action="unblock" data-id="${id}">Unblock</button>`
          : account.status === 'OPEN' ? `<button class="btn ghost" data-action="block" data-id="${id}">Block</button>` : ''}
        <button class="btn ghost" data-action="rename" data-id="${id}">Rename</button>`}
      <button class="btn ghost" data-action="filter-account" data-id="${id}">Show transfers</button>
      <a class="btn ghost" href="${grafanaSearchUrl(accountTraceQl(id))}" target="_blank" rel="noopener">Traces in Grafana
        <svg viewBox="0 0 24 24"><path d="M7 17 17 7M9 7h8v8"/></svg></a>
    </div>
    ${system ? '' : `<p class="hint">Blocked accounts can still receive money, but every debit is rejected by the decider.</p>`}
    <div class="d-section">
      <h3>Postings (newest first)</h3>
      ${rows.length ? `
        <table class="ledger-table">
          <thead><tr><th>Transfer</th><th>Leg</th><th class="amount">Amount</th><th class="amount">Balance</th></tr></thead>
          <tbody>${rows.map((p) => `
            <tr>
              <td><a href="#" class="mono" data-open-transfer="${p.transferId}">${short(p.transferId)}</a></td>
              <td class="leg">${esc(p.leg)}</td>
              <td class="amount ${p.amountCents < 0 ? 'neg' : 'pos'}">${esc(money(p.amountCents))}</td>
              <td class="amount">${esc(money(p.running))}</td>
            </tr>`).join('')}</tbody>
        </table>` : '<p class="hint">No postings yet. The balance is always the sum of the postings.</p>'}
    </div>`;
}

// ---------- transfer form ----------

function setMode(mode) {
  state.mode = mode;
  for (const button of $$('.segmented button')) button.classList.toggle('active', button.dataset.mode === mode);
  for (const field of $$('#transfer-form [data-show]')) {
    field.dataset.hidden = String(!field.dataset.show.split(' ').includes(mode));
  }
  const form = $('#transfer-form');
  if (mode === 'external' && !form.iban.value) form.iban.value = randomIban();
}

function readForm() {
  const form = $('#transfer-form');
  const amountCents = parseCents(form.amount.value);
  if (amountCents == null || Number.isNaN(amountCents) || amountCents <= 0) {
    return { error: 'Enter a positive amount, e.g. 25 or 25,50' };
  }
  const transferId = form.transferId.value.trim() || undefined;
  if (state.mode === 'deposit') {
    if (!form.depositTarget.value) return { error: 'Create an account first' };
    return { path: `/api/accounts/${form.depositTarget.value}/deposits`, body: { amountCents, transferId } };
  }
  if (!form.debtor.value) return { error: 'Create an account first' };
  const body = { transferId, debtorAccountId: form.debtor.value, amountCents };
  if (state.mode === 'internal') body.creditorAccountId = form.creditor.value;
  else body.creditorIban = form.iban.value;
  return { path: '/api/transfers', body };
}

async function submitTransfer(path, body, { open = true, span = null } = {}) {
  const result = await api('POST', path, body, { span });
  if (result.ok) {
    if (result.data.traceId) state.traceIds.set(result.data.transferId, result.data.traceId);
    if (open) openDrawer('transfer', result.data.transferId);
    refresh();
  }
  return result;
}

async function onSubmit(event) {
  event.preventDefault();
  const request = readForm();
  if (request.error) { toast('Check the form', esc(request.error), 'warn'); return; }
  const button = $('#transfer-form button[type="submit"]');
  button.disabled = true;
  const name = state.mode === 'deposit' ? 'deposit' : 'send transfer';
  const result = await traced(name, { 'bank.transfer.kind': state.mode.toUpperCase() },
    (span) => submitTransfer(request.path, request.body, { span }));
  button.disabled = false;
  if (result.ok) {
    toast('Transfer accepted', `Workflow <code>transfer-${short(result.data.transferId)}…</code> started`, 'ok');
    $('#transfer-form').transferId.value = '';
  } else {
    toast(result.status === 409 ? 'Rejected: duplicate' : `Error ${result.status || ''}`.trim(), esc(problem(result)), 'bad');
  }
}

// ---------- scenarios ----------

const SCENARIO_HINTS = {
  limit: 'Sends 15.000 € (internal) or 6.000 € (external). The workflow pauses until you approve or deny it.',
  insufficient: 'Sends 1 € more than the debtor has available. The decider rejects it; nothing is booked.',
  rejected: 'Sends to a bank that is not a participant of the clearing house. It rejects the payment (RC01) and the reserved amount is refunded.',
  duplicate: 'Sends the same transfer ID twice. The second request gets 409: the workflow ID is the idempotency key.',
  burst: 'Fires 10 transfers at once, each a quarter of the available amount. Row locks make sure only some succeed and the account never goes below its limit.',
};

function selectedAccounts() {
  const form = $('#transfer-form');
  const debtor = state.accounts.find((a) => a.id === form.debtor.value);
  const creditor = state.accounts.find((a) => a.id === form.creditor.value);
  return { debtor, creditor };
}

async function runScenario(name, button) {
  $('#scenario-hint').textContent = SCENARIO_HINTS[name] || '';
  const { debtor, creditor } = selectedAccounts();
  const external = state.mode === 'external';
  if (!debtor || (!external && (!creditor || creditor.id === debtor.id) && name !== 'rejected')) {
    toast('Pick two different accounts', 'Use "Quick start" or create a second account first.', 'warn');
    return;
  }
  const available = debtor.balanceCents + debtor.overdraftLimitCents;
  const target = (extra) => (external || name === 'rejected'
    ? { creditorIban: name === 'rejected' ? REJECTING_IBAN : ($('#transfer-form').iban.value || randomIban()), ...extra }
    : { creditorAccountId: creditor.id, ...extra });

  button.disabled = true;
  try {
    await traced(`scenario: ${name}`, { 'bank.scenario': name }, (span) => runScenarioSteps(name, { debtor, external, available, target, span }));
  } finally {
    button.disabled = false;
  }
}

async function runScenarioSteps(name, { debtor, external, available, target, span }) {
  if (name === 'limit') {
    const amountCents = external ? 6_000_00 : 15_000_00;
    const r = await submitTransfer('/api/transfers', { debtorAccountId: debtor.id, amountCents, ...target() }, { span });
    toast(r.ok ? 'Waiting for approval' : 'Error', r.ok ? 'Approve or deny it in the banner or the drawer.' : esc(problem(r)), r.ok ? 'warn' : 'bad');
  } else if (name === 'insufficient') {
    const amountCents = Math.max(available, 0) + 100;
    const r = await submitTransfer('/api/transfers', { debtorAccountId: debtor.id, amountCents, ...target() }, { span });
    if (!r.ok) toast('Error', esc(problem(r)), 'bad');
  } else if (name === 'rejected') {
    const r = await submitTransfer('/api/transfers', { debtorAccountId: debtor.id, amountCents: 25_00, ...target() }, { span });
    toast(r.ok ? 'Sent to a bank outside the clearing house' : 'Error', r.ok ? 'Watch: reserve → pacs.008 → RJCT (RC01) → refund.' : esc(problem(r)), r.ok ? 'info' : 'bad');
  } else if (name === 'duplicate') {
    const body = { transferId: uuid(), debtorAccountId: debtor.id, amountCents: 1_00, ...target() };
    const first = await submitTransfer('/api/transfers', body, { span });
    const second = await api('POST', '/api/transfers', body, { span });
    toast('Duplicate submission',
      `First: <strong>${first.status}</strong> · second: <strong>${second.status}</strong> ${second.status === 409 ? '(rejected, as expected)' : ''}<br>${esc(second.ok ? '' : problem(second))}`,
      second.status === 409 ? 'ok' : 'bad');
  } else if (name === 'burst') {
    const amountCents = Math.max(100, Math.ceil(Math.max(available, 0) / 4));
    const results = await Promise.all(Array.from({ length: 10 }, () =>
      api('POST', '/api/transfers', { debtorAccountId: debtor.id, amountCents, ...target() }, { span })));
    const accepted = results.filter((r) => r.ok).length;
    toast(`Burst: ${accepted} of 10 accepted`,
      `Each ${esc(money(amountCents))} from ${esc(label(debtor.id))} (available ${esc(money(available))}). At most ${Math.floor(Math.max(available, 0) / amountCents)} can be booked; watch the rest get "Insufficient funds".`,
      'info');
    state.filter = 'all';
    refresh();
  }
}

async function quickStart(button) {
  button.disabled = true;
  const people = [
    { name: 'Alice', overdraft: 200_00, deposit: 1_000_00 },
    { name: 'Bob', overdraft: 0, deposit: 250_00 },
  ];
  await traced('quick start', {}, async (span) => {
    for (const person of people) {
      await createAccount(person.name, person.overdraft, person.deposit, false, span);
    }
  });
  toast('Alice and Bob are ready', 'Deposits run through the TransferWorkflow, balances appear in a moment.', 'ok');
  refresh();
}

async function createAccount(nickname, overdraftCents, depositCents, notify = true, span = null) {
  const created = await api('POST', '/api/accounts', { overdraftLimitCents: overdraftCents || 0 }, { span });
  if (!created.ok) { toast('Could not create account', esc(problem(created)), 'bad'); return; }
  if (nickname) setNickname(created.data.id, nickname);
  if (depositCents > 0) {
    const deposit = await api('POST', `/api/accounts/${created.data.id}/deposits`, { amountCents: depositCents }, { span });
    if (!deposit.ok) toast('Deposit failed', esc(problem(deposit)), 'bad');
  }
  if (notify) toast('Account opened', esc(label(created.data.id)), 'ok');
  refresh();
}

// ---------- approvals ----------

async function decide(id, approved, comment) {
  const result = await traced(approved ? 'approve transfer' : 'deny transfer', { 'bank.transfer.id': id },
    (span) => api('POST', `/api/transfers/${id}/approval`, { approved, comment: comment || undefined }, { span }));
  if (result.ok) {
    toast(approved ? 'Approved' : 'Denied', `Update accepted, workflow is now <strong>${esc(STATUS[result.data.status]?.label || result.data.status)}</strong>`, approved ? 'ok' : 'warn');
  } else {
    toast('Update rejected', esc(problem(result)), 'bad');
  }
  refresh();
}

// ---------- events ----------

function bindEvents() {
  $('#transfer-form').addEventListener('submit', onSubmit);
  for (const button of $$('.segmented button')) button.addEventListener('click', () => setMode(button.dataset.mode));
  for (const button of $$('#filters button')) {
    button.addEventListener('click', () => { state.filter = button.dataset.filter; renderTransfers(); });
  }
  for (const chip of $$('[data-scenario]')) {
    chip.addEventListener('click', () => runScenario(chip.dataset.scenario, chip));
    chip.addEventListener('mouseenter', () => { $('#scenario-hint').textContent = SCENARIO_HINTS[chip.dataset.scenario]; });
  }
  $('#transfer-form').creditor.addEventListener('change', (event) => { event.target.dataset.touched = 'true'; });
  $('#transfer-form').debtor.addEventListener('change', () => syncSelects());
  $('#random-iban').addEventListener('click', () => { $('#transfer-form').iban.value = randomIban(); });
  $('#new-id').addEventListener('click', () => { $('#transfer-form').transferId.value = uuid(); });
  $('#new-account').addEventListener('click', () => {
    const form = $('#account-dialog form');
    form.reset();
    openDialog('#account-dialog');
  });
  $('#scrim').addEventListener('click', closeDrawer);
  document.addEventListener('keydown', (event) => { if (event.key === 'Escape' && state.drawer && !$('dialog[open]')) closeDrawer(); });

  // dialogs
  $('#account-dialog').addEventListener('close', () => {
    const dialog = $('#account-dialog');
    if (dialog.returnValue !== 'create') return;
    const form = $('form', dialog);
    const overdraft = parseCents(form.overdraft.value) || 0;
    const deposit = parseCents(form.deposit.value) || 0;
    if (Number.isNaN(overdraft) || Number.isNaN(deposit)) { toast('Invalid amount', '', 'warn'); return; }
    traced('open account', {}, (span) => createAccount(form.nickname.value.trim(), overdraft, deposit, true, span));
  });
  $('#deposit-dialog').addEventListener('close', async () => {
    const dialog = $('#deposit-dialog');
    if (dialog.returnValue !== 'deposit' || !state.depositTarget) return;
    const amountCents = parseCents($('form', dialog).amount.value);
    if (!amountCents || Number.isNaN(amountCents)) { toast('Invalid amount', '', 'warn'); return; }
    const target = state.depositTarget;
    const result = await traced('deposit', { 'bank.account.id': target },
      (span) => submitTransfer(`/api/accounts/${target}/deposits`, { amountCents }, { open: false, span }));
    toast(result.ok ? 'Deposit started' : 'Deposit failed', result.ok ? esc(money(amountCents)) : esc(problem(result)), result.ok ? 'ok' : 'bad');
  });
  $('#rename-dialog').addEventListener('close', () => {
    const dialog = $('#rename-dialog');
    if (dialog.returnValue === 'save') { setNickname(dialog.dataset.id, $('form', dialog).nickname.value.trim()); render(); }
  });
  $('#deny-dialog').addEventListener('close', () => {
    const dialog = $('#deny-dialog');
    if (dialog.returnValue === 'deny' && state.pendingDeny) decide(state.pendingDeny, false, $('form', dialog).comment.value.trim());
    state.pendingDeny = null;
  });

  // delegated clicks (re-rendered content)
  document.addEventListener('click', async (event) => {
    const openTransfer = event.target.closest('[data-open-transfer]');
    if (openTransfer) { event.preventDefault(); openDrawer('transfer', openTransfer.dataset.openTransfer); return; }
    const openAccount = event.target.closest('[data-open-account]');
    if (openAccount) { openDrawer('account', openAccount.dataset.openAccount); return; }

    const actionElement = event.target.closest('[data-action]');
    if (!actionElement) return;
    const { action, id } = actionElement.dataset;
    switch (action) {
      case 'close-drawer': closeDrawer(); break;
      case 'quick-start': quickStart(actionElement); break;
      case 'approve': actionElement.disabled = true; decide(id, true); break;
      case 'deny': {
        state.pendingDeny = id;
        $('#deny-dialog form').reset();
        openDialog('#deny-dialog');
        break;
      }
      case 'deposit': {
        state.depositTarget = id;
        $('#deposit-dialog form').reset();
        openDialog('#deposit-dialog');
        break;
      }
      case 'rename': {
        const dialog = $('#rename-dialog');
        dialog.dataset.id = id;
        $('form', dialog).nickname.value = nicknames()[id] || '';
        dialog.returnValue = '';
        dialog.showModal();
        break;
      }
      case 'block':
      case 'unblock': {
        const result = await traced(`${action} account`, { 'bank.account.id': id },
          (span) => api('POST', `/api/accounts/${id}/${action}`, undefined, { span }));
        toast(result.ok ? (action === 'block' ? 'Account blocked' : 'Account unblocked') : 'Not possible', result.ok ? '' : esc(problem(result)), result.ok ? 'ok' : 'bad');
        refresh();
        break;
      }
      case 'send-from': {
        setMode('internal');
        $('#transfer-form').debtor.value = id;
        closeDrawer();
        $('#transfer-form').amount.focus();
        break;
      }
      case 'filter-account': state.accountFilter = id; closeDrawer(); refresh(); break;
      case 'clear-account-filter': state.accountFilter = null; refresh(); break;
      case 'copy': navigator.clipboard?.writeText(actionElement.dataset.value).then(() => toast('Copied', '', 'ok')); break;
      default: break;
    }
  });
}

/** Links and telemetry settings from the backend (bank.ui.* properties). */
async function loadConfig() {
  const result = await api('GET', '/api/ui-config', undefined, { sampled: false });
  if (!result.ok) return { browserTelemetry: false };
  GRAFANA = result.data.grafanaUrl;
  TEMPORAL_UI = result.data.temporalUiUrl;
  TEMPORAL_NAMESPACE = result.data.temporalNamespace || 'default';
  // Bank A and bank B run side by side: the header says which one this is.
  $('#bank-name').textContent = result.data.bankName;
  $('#bank-bic').textContent = `BIC ${result.data.bic} · Temporal banking lab`;
  document.title = result.data.bankName;
  $('#temporal-link').href = TEMPORAL_UI;
  $('#grafana-link').href = GRAFANA;
  return result.data;
}

async function start() {
  bindEvents();
  setMode('internal');
  const config = await loadConfig();
  await initTelemetry({ enabled: config.browserTelemetry });
  refresh();
  setInterval(refresh, POLL_MS);
}

start();
