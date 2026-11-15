// Shared by the three areas of the PowerBank UI (#61): online banking, back office, operations. Plain ES modules,
// no build step; every page imports what it needs from here and talks to its own part of the API.

import { action, initTelemetry, startRequest } from './telemetry.js';

// Overridden from /api/ui-config at startup (bank.ui.* properties), so nothing is tied to localhost.
export const config = { grafana: 'http://localhost:3000', temporalUi: 'http://localhost:8233', temporalNamespace: 'default' };

export const CASH = '00000000-0000-0000-0000-000000000001';
export const CLEARING = '00000000-0000-0000-0000-000000000002';
export const SETTLEMENT = '00000000-0000-0000-0000-000000000003';
export const POLL_MS = 1500;
export const TERMINAL = new Set(['BOOKED', 'COMPLETED', 'REFUNDED', 'REJECTED']);

export const STATUS = {
  RECEIVED: { label: 'Received', tone: 'info', live: true,
    explain: 'Received in the ledger, and the workflow has started. The <strong>risk check activity</strong> decides whether a person needs to approve.' },
  AWAITING_APPROVAL: { label: 'Awaiting approval', tone: 'warn', live: true,
    explain: 'The workflow waits in <code>Workflow.await()</code> for up to 3 days. Nothing runs meanwhile: Temporal keeps the open workflow and a timer, and the <strong>ledger</strong> shows the transfer as awaiting approval. The back office\'s <strong>approve update</strong> (checked by a validator) lets it continue or abort. Nothing has been booked yet.' },
  APPROVED: { label: 'Approved', tone: 'info', live: true,
    explain: 'The approval is recorded in the ledger. The workflow continues with the booking.' },
  ACCEPTED: { label: 'Accepted', tone: 'info', live: true,
    explain: 'Incoming SEPA Instant payment: the account can receive it, so the bank answered the clearing house with <code>ACCP</code>. The workflow waits for the settlement (<code>ACSC</code>) before it credits the customer.' },
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

// What a status means for a payment from another bank, where it differs from an outgoing transfer.
const INCOMING_EXPLAIN = {
  RECEIVED: 'The clearing house forwarded a payment from another bank (<code>pacs.008</code>). The ledger checks whether the account can receive it; the answer goes back within the 10-second deadline.',
  BOOKED: 'The clearing house confirmed the settlement (<code>ACSC</code>): the amount was credited from the <strong>settlement account</strong>.',
  REJECTED: 'The bank answered no with a reason code, or the clearing house didn\'t settle the payment. <strong>Nothing was booked.</strong>',
  UNDER_INVESTIGATION: 'No final status from the clearing house, or the account could no longer take the money. <strong>Nothing is credited</strong> until it is clear.',
};

export const KIND_ICON = {
  INTERNAL: '<svg viewBox="0 0 24 24"><path d="M7 7h12l-3-3M17 17H5l3 3"/></svg>',
  EXTERNAL: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c3 3.5 3 14.5 0 18M12 3c-3 3.5-3 14.5 0 18"/></svg>',
  DEPOSIT: '<svg viewBox="0 0 24 24"><path d="M12 4v11M7 10l5 5 5-5M5 20h14"/></svg>',
  INCOMING: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M12 7v9M8 12l4 4 4-4"/></svg>',
};
const CHECK = '<svg viewBox="0 0 24 24"><path d="m5 12 5 5 9-10"/></svg>';
const CROSS = '<svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6 6 18"/></svg>';
const EXTERNAL_LINK = '<svg viewBox="0 0 24 24"><path d="M7 17 17 7M9 7h8v8"/></svg>';

// ---------- helpers ----------

export const $ = (selector, root = document) => root.querySelector(selector);
export const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
export const esc = (value) => String(value ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const moneyFormat = new Intl.NumberFormat(undefined, { style: 'currency', currency: 'EUR' });
export const money = (cents) => (cents == null ? '–' : moneyFormat.format(cents / 100));
export const short = (id) => (id ? id.slice(0, 8) : '');
const relative = new Intl.RelativeTimeFormat('en', { numeric: 'auto' });

export function ago(iso) {
  const seconds = Math.round((Date.parse(iso) - Date.now()) / 1000);
  if (Math.abs(seconds) < 60) return relative.format(seconds, 'second');
  const minutes = Math.round(seconds / 60);
  if (Math.abs(minutes) < 60) return relative.format(minutes, 'minute');
  const hours = Math.round(minutes / 60);
  if (Math.abs(hours) < 24) return relative.format(hours, 'hour');
  return relative.format(Math.round(hours / 24), 'day');
}

/** Parses "12", "12.5", "12,50" or "1.234,56" into cents without floating point surprises. */
export function parseCents(raw) {
  let text = String(raw ?? '').trim().replace(/\s|€/g, '');
  if (!text) return null;
  if (/^\d{1,3}(\.\d{3})+(,\d{1,2})?$/.test(text)) text = text.replace(/\./g, '');
  text = text.replace(',', '.');
  if (!/^\d+(\.\d{1,2})?$/.test(text)) return NaN;
  const [euros, decimals = ''] = text.split('.');
  return Number(euros) * 100 + Number(decimals.padEnd(2, '0'));
}

export const uuid = () => (crypto.randomUUID ? crypto.randomUUID()
  : '10000000-1000-4000-8000-100000000000'.replace(/[018]/g, (c) => (c ^ (crypto.getRandomValues(new Uint8Array(1))[0] & (15 >> (c / 4)))).toString(16)));

export function hue(id) {
  let h = 0;
  for (const c of id) h = (h * 31 + c.charCodeAt(0)) % 360;
  return h;
}

/** Builds an IBAN with valid check digits (ISO 13616, mod 97). */
export function iban(country, bban) {
  const digits = (bban + country + '00').replace(/[A-Z]/g, (c) => String(c.charCodeAt(0) - 55));
  const check = 98n - (BigInt(digits) % 97n);
  return country + String(check).padStart(2, '0') + bban;
}
export const formatIban = (value) => value.replace(/\s/g, '').replace(/(.{4})/g, '$1 ').trim();
/** The bank code in a German IBAN: which bank it belongs to. */
export const bankCodeOf = (value) => value.replace(/\s/g, '').slice(4, 12);
/** An IBAN at a real bank that isn't a participant of the clearing house: it rejects payments to it (RC01). */
export const REJECTING_IBAN = formatIban(iban('DE', '370400440532013000'));

// ---------- nicknames (browser only: the bank never sees names) ----------

const NICK_KEY = 'powerbank.nicknames';
export function nicknames() {
  try { return JSON.parse(localStorage.getItem(NICK_KEY)) || {}; } catch { return {}; }
}
export function setNickname(id, name) {
  try {
    const all = nicknames();
    if (name) all[id] = name; else delete all[id];
    localStorage.setItem(NICK_KEY, JSON.stringify(all));
  } catch { /* storage unavailable: names just aren't remembered */ }
}
export function label(id) {
  if (!id) return 'External bank';
  if (id === CASH) return 'Cash desk';
  if (id === CLEARING) return 'External clearing';
  if (id === SETTLEMENT) return 'Settlement (central bank)';
  return nicknames()[id] || `Account ${short(id).slice(0, 4).toUpperCase()}`;
}

// ---------- Grafana and Temporal ----------
// Every user action is the root span of its own trace (telemetry.js); its API calls send a W3C `traceparent`
// that the backend and Temporal continue. Polling sends an unsampled traceparent to keep Tempo free of noise.

export function grafanaTraceUrl(traceId, fromIso, toIso) {
  const from = fromIso ? Date.parse(fromIso) - 5 * 60_000 : 'now-24h';
  const to = toIso ? Date.parse(toIso) + 10 * 60_000 : 'now';
  const panes = {
    a: {
      datasource: 'tempo',
      queries: [{ refId: 'A', datasource: { type: 'tempo', uid: 'tempo' }, queryType: 'traceql', query: traceId }],
      range: { from: String(from), to: String(to) },
    },
  };
  return `${config.grafana}/explore?schemaVersion=1&orgId=1&panes=${encodeURIComponent(JSON.stringify(panes))}`;
}

/** Grafana Explore with a TraceQL search, e.g. all traces of one account over the last day. */
export function grafanaSearchUrl(traceql) {
  const panes = {
    a: {
      datasource: 'tempo',
      queries: [{ refId: 'A', datasource: { type: 'tempo', uid: 'tempo' }, queryType: 'traceql', query: traceql, limit: 50, tableType: 'traces' }],
      range: { from: 'now-24h', to: 'now' },
    },
  };
  return `${config.grafana}/explore?schemaVersion=1&orgId=1&panes=${encodeURIComponent(JSON.stringify(panes))}`;
}

export const accountTraceQl = (id) =>
  `{ span.bank.account.id = "${id}" || span.bank.debtor.account.id = "${id}" || span.bank.creditor.account.id = "${id}" }`;

export const workflowIdOf = (t) => (t.kind === 'INCOMING' ? `incoming-payment-${t.transferId}` : `transfer-${t.transferId}`);

export const temporalUrl = (workflowId) =>
  `${config.temporalUi}/namespaces/${encodeURIComponent(config.temporalNamespace)}/workflows?query=${encodeURIComponent(`WorkflowId="${workflowId}"`)}`;

export const externalLink = (href, text) =>
  `<a class="btn ghost" href="${esc(href)}" target="_blank" rel="noopener">${esc(text)} ${EXTERNAL_LINK}</a>`;

// ---------- API ----------

let area = 'unknown';

/** `span`: the user action this call belongs to (its client span becomes a child); `sampled: false` for polling. */
export async function api(method, path, body, { sampled = true, span = null } = {}) {
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
export const problem = (r) => r.data?.detail || r.data?.title || `HTTP ${r.status}`;

/** Runs `fn` at most once at a time: a poll that comes while the last one still runs is skipped. */
export function single(fn) {
  let running = false;
  return async () => {
    if (running) return;
    running = true;
    try { await fn(); } finally { running = false; }
  };
}

/** A user action as the root span of its own trace, tagged with the area it happened in. */
export const traced = (name, attributes, fn) => action(name, { ...attributes, 'bank.ui.area': area }, fn);

/** Escape keeps the previous returnValue of a <dialog>, so reset it before every opening. */
export function openDialog(selector) {
  const dialog = $(selector);
  dialog.returnValue = '';
  dialog.showModal();
  return dialog;
}

export function toast(title, detail = '', tone = 'info') {
  const element = document.createElement('div');
  element.className = `toast ${tone}`;
  element.innerHTML = `<i class="t-icon"></i><div><strong>${esc(title)}</strong>${detail ? `<span>${detail}</span>` : ''}</div>`;
  $('#toasts').append(element);
  setTimeout(() => {
    element.classList.add('leaving');
    element.addEventListener('animationend', () => element.remove(), { once: true });
  }, tone === 'bad' ? 7000 : 4500);
}

// ---------- transfers ----------

export function statusChip(status) {
  const tone = { OPEN: 'ok', BLOCKED: 'warn', CLOSED: 'bad' }[status] || '';
  return `<span class="chip ${tone}">${esc(status.toLowerCase())}</span>`;
}

export function transferChip(status) {
  const info = STATUS[status] || { label: status, tone: '' };
  return `<span class="chip ${info.tone}">${info.live ? '<i class="spinner"></i>' : ''}${esc(info.label)}</span>`;
}

/** From where to where, as the bank sees it. */
export function route(t) {
  if (t.kind === 'DEPOSIT') return `Cash deposit → ${label(t.creditorAccountId)}`;
  if (t.kind === 'EXTERNAL') return `${label(t.debtorAccountId)} → External bank`;
  if (t.kind === 'INCOMING') return `External bank → ${t.creditorAccountId ? label(t.creditorAccountId) : 'unknown account'}`;
  if (!t.kind) return `Transfer ${short(t.transferId)}`;
  return `${label(t.debtorAccountId)} → ${label(t.creditorAccountId)}`;
}

/** One row of a transfer list. `title` and `amount` let an area show it from its own point of view. */
export function transferRow(t, { changed = false, title = route(t), amount = money(t.amountCents) } = {}) {
  const kind = t.kind || 'INTERNAL';
  return `
    <button class="tx-row ${changed ? 'flash' : ''}" data-open-transfer="${t.transferId}">
      <span class="tx-icon ${kind.toLowerCase()}">${KIND_ICON[kind]}</span>
      <span class="tx-main">
        <span class="tx-title">${esc(title)}</span>
        <span class="tx-sub">${esc(kind.charAt(0) + kind.slice(1).toLowerCase())} · ${esc(ago(t.createdAt))} · <span class="mono">${short(t.transferId)}</span></span>
        ${t.reason ? `<span class="tx-reason">${esc(t.reason)}</span>` : ''}
      </span>
      <span class="tx-right">
        <span class="tx-amount">${esc(amount)}</span>
        ${transferChip(t.status)}
      </span>
    </button>`;
}

/** Remembers each transfer's status, so a row that just changed flashes once. */
export function changeTracker() {
  const statuses = new Map();
  return (transfers) => {
    const changed = new Set();
    for (const t of transfers) {
      const previous = statuses.get(t.transferId);
      if (previous && previous !== t.status) changed.add(t.transferId);
      statuses.set(t.transferId, t.status);
    }
    return changed;
  };
}

function stepper(t, history) {
  if (t.kind === 'INCOMING') return incomingStepper(t, history);
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
  return stepsHtml(steps);
}

/** A payment from another bank: received → answered → credited once the clearing house settles it. */
function incomingStepper(t, history) {
  const accepted = t.status === 'ACCEPTED' || history.some((h) => h.status === 'ACCEPTED');
  const steps = [{ label: 'Received' }, { label: 'Answer' }, { label: 'Credited' }];
  const states = {
    RECEIVED: ['current', 'todo', 'todo'],
    ACCEPTED: ['done', 'done', 'current'],
    UNDER_INVESTIGATION: ['done', 'done', 'current'],
    BOOKED: ['done', 'done', 'done'],
    REJECTED: accepted ? ['done', 'done', 'fail'] : ['done', 'fail', 'todo'],
  }[t.status] || ['todo', 'todo', 'todo'];
  steps.forEach((s, i) => { s.state = states[i]; });
  if (accepted) steps[1].label = 'Accepted';
  if (t.status === 'REJECTED') (accepted ? steps[2] : steps[1]).label = 'Rejected';
  if (t.status === 'UNDER_INVESTIGATION') steps[2].label = 'Under investigation';
  return stepsHtml(steps);
}

const stepsHtml = (steps) => `<div class="stepper">${steps.map((s, i) => `
    <div class="step ${s.state}">
      <span class="bullet">${s.state === 'done' ? CHECK : s.state === 'fail' ? CROSS : i + 1}</span>
      <span>${esc(s.label)}</span>
    </div>`).join('')}</div>`;

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

export function postingsTable(postings, withSum) {
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

/** An account's postings with the running balance, newest first; `transferLinks` makes the transfer IDs clickable. */
export function accountPostings(postings, { transferLinks = true } = {}) {
  let running = 0;
  const rows = postings.map((p) => { running += p.amountCents; return { ...p, running }; }).reverse().slice(0, 100);
  if (!rows.length) return '<p class="hint">No postings yet. The balance is always the sum of the postings.</p>';
  return `
    <table class="ledger-table">
      <thead><tr><th>Transfer</th><th>Leg</th><th class="amount">Amount</th><th class="amount">Balance</th></tr></thead>
      <tbody>${rows.map((p) => `
        <tr>
          <td>${transferLinks ? `<a href="#" class="mono" data-open-transfer="${p.transferId}">${short(p.transferId)}</a>` : `<span class="mono">${short(p.transferId)}</span>`}</td>
          <td class="leg">${esc(p.leg)}</td>
          <td class="amount ${p.amountCents < 0 ? 'neg' : 'pos'}">${esc(money(p.amountCents))}</td>
          <td class="amount">${esc(money(p.running))}</td>
        </tr>`).join('')}</tbody>
    </table>`;
}

/**
 * A transfer in the drawer: status, steps, explanation and history. `postings` (null: not shown, the customer doesn't
 * see the bank's bookings), `actions` (HTML for the area's buttons) and `title` are up to the area.
 */
export function transferDetails(t, { history = [], postings = null, actions = '', title = route(t), traceId = t.traceId } = {}) {
  const status = STATUS[t.status] || { label: t.status || 'Loading…', explain: '' };
  const explain = (t.kind === 'INCOMING' && INCOMING_EXPLAIN[t.status]) || status.explain;
  const kind = (t.kind || 'transfer').toLowerCase();
  return `
    <div class="d-eyebrow">${esc(kind)} transfer</div>
    <div class="d-title">${esc(money(t.amountCents))}</div>
    <div class="d-route"><strong>${esc(title)}</strong></div>
    ${t.status ? transferChip(t.status) : ''}
    ${t.status ? stepper(t, history) : ''}
    ${explain ? `<div class="explain">${explain}${t.reason ? `<br><br>Reason: <strong>${esc(t.reason)}</strong>` : ''}</div>` : ''}
    ${actions ? `<div class="d-actions">${actions}</div>` : ''}
    <div class="d-section"><h3>History in the ledger</h3>${historyTable(history)}</div>
    ${postings ? `<div class="d-section"><h3>Postings in the ledger</h3>${postingsTable(postings, true)}</div>` : ''}
    <div class="d-section">
      <h3>Details</h3>
      <dl class="d-grid">
        <dt>Transfer ID</dt><dd class="mono">${esc(t.transferId)}</dd>
        <dt>Workflow ID</dt><dd class="mono">${esc(workflowIdOf(t))}</dd>
        ${traceId ? `<dt>Trace ID</dt><dd class="mono">${esc(traceId)}</dd>` : ''}
        ${t.createdAt ? `<dt>Created</dt><dd>${esc(new Date(t.createdAt).toLocaleString())}</dd>` : ''}
      </dl>
      <div class="d-actions">
        ${externalLink(temporalUrl(workflowIdOf(t)), 'Open in Temporal UI')}
        ${traceId ? externalLink(grafanaTraceUrl(traceId, t.createdAt, t.updatedAt), 'Open trace in Grafana') : ''}
        <button class="btn ghost" data-action="copy" data-value="${esc(t.transferId)}">Copy ID</button>
      </div>
    </div>`;
}

// ---------- drawer ----------

/**
 * The details drawer of a page. `render(subject, data)` returns its HTML, `load(subject)` its data; the page calls
 * `refresh()` on every poll, so an open drawer stays live.
 */
export function createDrawer({ render, load }) {
  let subject = null;
  let data = null;
  const draw = () => {
    if (subject) $('#drawer-content').innerHTML = render(subject, data);
  };
  const drawer = {
    get subject() { return subject; },
    async open(next) {
      subject = next;
      data = null;
      $('#drawer').classList.add('open');
      $('#drawer').setAttribute('aria-hidden', 'false');
      $('#scrim').hidden = false;
      draw();
      await drawer.refresh();
    },
    close() {
      subject = null;
      $('#drawer').classList.remove('open');
      $('#drawer').setAttribute('aria-hidden', 'true');
      $('#scrim').hidden = true;
    },
    async refresh() {
      if (!subject) return;
      const current = subject;
      const loaded = await load(current);
      if (subject !== current) return;
      data = loaded;
      draw();
    },
  };
  $('#scrim').addEventListener('click', () => drawer.close());
  document.addEventListener('keydown', (event) => { if (event.key === 'Escape' && subject && !$('dialog[open]')) drawer.close(); });
  document.addEventListener('click', (event) => {
    const element = event.target.closest('[data-action]');
    if (!element) return;
    if (element.dataset.action === 'close-drawer') drawer.close();
    if (element.dataset.action === 'copy') navigator.clipboard?.writeText(element.dataset.value).then(() => toast('Copied', '', 'ok'));
  });
  return drawer;
}

// ---------- page ----------

/**
 * Starts a page of one area: loads the UI config (bank name, links), marks the area in the navigation, starts browser
 * telemetry with the area as an attribute, then polls `refresh`.
 */
export async function startPage({ area: pageArea, refresh }) {
  area = pageArea;
  const result = await api('GET', '/api/ui-config', undefined, { sampled: false });
  const ui = result.ok ? result.data : { browserTelemetry: false };
  if (result.ok) {
    config.grafana = ui.grafanaUrl;
    config.temporalUi = ui.temporalUiUrl;
    config.temporalNamespace = ui.temporalNamespace || 'default';
    // Bank A and bank B run side by side: the header says which one this is.
    $('#bank-name').textContent = ui.bankName;
    $('#bank-bic').textContent = `BIC ${ui.bic} · Temporal banking lab`;
    document.title = `${ui.bankName} · ${document.title.split('·').pop().trim()}`;
  }
  for (const link of $$('.area-nav a')) link.classList.toggle('active', link.dataset.area === area);
  await initTelemetry({ enabled: ui.browserTelemetry, area });
  if (refresh) {
    await refresh();
    setInterval(refresh, POLL_MS);
  }
  return ui;
}
