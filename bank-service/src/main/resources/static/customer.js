// Online banking (#61): one customer's account. Everything goes through /api/customer/accounts/{account}/…; which
// account that is, the "acting as" picker decides, standing in for the login (#36).

import {
  $, $$, REJECTING_IBAN, TERMINAL, accountPostings, api, bankCodeOf, changeTracker, createDrawer, esc, formatIban, hue,
  label, money, nicknames, openDialog, parseCents, problem, setNickname, single, startPage, statusChip, toast, traced,
  transferDetails, transferRow, uuid,
} from './common.js';

const ACTING_AS_KEY = 'powerbank.actingAs';

const state = {
  accountId: null,
  accounts: [], // every customer account of the bank: only for the picker and the IBAN suggestions (lab)
  account: null,
  postings: [],
  transfers: [],
  filter: 'all',
  traceIds: new Map(), // transferId -> W3C trace ID, from the POST response (before the list knows it)
};
const changed = changeTracker();
const base = () => `/api/customer/accounts/${state.accountId}`;

// ---------- the customer's point of view ----------

const outgoing = (t) => t.debtorAccountId === state.accountId;

function title(t) {
  if (t.kind === 'DEPOSIT') return 'Cash deposit';
  if (t.kind === 'INCOMING') return 'From another bank';
  if (t.kind === 'EXTERNAL') return 'To another bank';
  return outgoing(t) ? `To ${label(t.creditorAccountId)}` : `From ${label(t.debtorAccountId)}`;
}

const signed = (t) => `${outgoing(t) ? '−' : '+'}${money(t.amountCents)}`;

// ---------- data ----------

function readActingAs() {
  const fromUrl = new URLSearchParams(location.search).get('account');
  if (fromUrl) return fromUrl;
  try { return localStorage.getItem(ACTING_AS_KEY); } catch { return null; }
}

function actAs(accountId) {
  state.accountId = accountId;
  state.account = null;
  state.transfers = [];
  state.postings = [];
  try { localStorage.setItem(ACTING_AS_KEY, accountId); } catch { /* not remembered */ }
  drawer.close();
  refresh();
}

const refresh = single(async () => {
  const quiet = { sampled: false };
  // The picker lists the bank's customer accounts: a lab shortcut instead of a login.
  const accounts = await api('GET', '/api/backoffice/accounts', undefined, quiet);
  if (accounts.ok) state.accounts = accounts.data;
  if (!state.accountId || (accounts.ok && !state.accounts.some((a) => a.id === state.accountId))) {
    state.accountId = state.accounts.find((a) => a.status !== 'CLOSED')?.id || null;
  }
  if (state.accountId) {
    const [account, transfers, postings] = await Promise.all([
      api('GET', base(), undefined, quiet),
      api('GET', `${base()}/transfers?limit=80`, undefined, quiet),
      api('GET', `${base()}/postings`, undefined, quiet),
    ]);
    if (account.ok) state.account = account.data;
    if (transfers.ok) state.transfers = transfers.data;
    if (postings.ok) state.postings = postings.data;
  }
  await drawer.refresh();
  render();
});

// ---------- rendering ----------

function render() {
  $('#no-account').hidden = !!state.accountId;
  $('#banking').hidden = !state.accountId;
  renderPicker();
  if (!state.accountId) return;
  renderAccount();
  renderTransfers();
}

function renderPicker() {
  const select = $('#acting-as');
  const html = state.accounts.map((a) => `<option value="${a.id}">${esc(label(a.id))} · ${esc(formatIban(a.iban))}</option>`).join('');
  if (select.dataset.html !== html) {
    select.innerHTML = html || '<option value="">No accounts</option>';
    select.dataset.html = html;
  }
  if (state.accountId) select.value = state.accountId;
  // IBAN suggestions: the other accounts of this bank (in real life, the customer's saved payees).
  $('#known-ibans').innerHTML = state.accounts.filter((a) => a.id !== state.accountId && a.status !== 'CLOSED')
    .map((a) => `<option value="${esc(formatIban(a.iban))}">${esc(label(a.id))}</option>`).join('');
}

function renderAccount() {
  const a = state.account;
  if (!a) { $('#account').innerHTML = '<p class="hint">Loading…</p>'; return; }
  const usedOverdraft = a.balanceCents < 0 && a.overdraftLimitCents > 0
    ? Math.min(100, Math.round((-a.balanceCents / a.overdraftLimitCents) * 100)) : 0;
  $('#account').innerHTML = `
    <div class="ac-top">
      <span class="avatar" style="--hue:${hue(a.id)}">${esc((nicknames()[a.id] || a.id).charAt(0).toUpperCase())}</span>
      <span class="ac-name"><strong>${esc(label(a.id))}</strong><span class="mono">${esc(formatIban(a.iban))}</span></span>
      ${statusChip(a.status)}
    </div>
    <div class="ac-balance big ${a.balanceCents < 0 ? 'negative' : ''}">${esc(money(a.balanceCents))}</div>
    <div class="ac-meta">Available ${esc(money(a.balanceCents + a.overdraftLimitCents))}${a.overdraftLimitCents ? ` · overdraft ${esc(money(a.overdraftLimitCents))}` : ''}</div>
    ${a.overdraftLimitCents ? `<div class="meter" title="Overdraft used"><span class="${usedOverdraft > 0 ? 'over' : ''}" style="width:${usedOverdraft}%"></span></div>` : ''}
    <div class="d-actions">
      <button class="btn ghost small" data-action="copy-iban">Copy IBAN</button>
      <button class="btn ghost small" data-action="rename">Name</button>
    </div>
    ${a.status === 'BLOCKED' ? '<p class="hint">This account is blocked: money can\'t leave it. Ask the bank (back office).</p>' : ''}`;
  $('#postings').innerHTML = accountPostings(state.postings);
}

const FILTERS = {
  all: () => true,
  flight: (t) => !TERMINAL.has(t.status),
  done: (t) => t.status === 'BOOKED' || t.status === 'COMPLETED',
  failed: (t) => t.status === 'REJECTED' || t.status === 'REFUNDED',
};

function renderTransfers() {
  for (const button of $$('#filters button')) {
    const count = state.transfers.filter(FILTERS[button.dataset.filter]).length;
    button.classList.toggle('active', button.dataset.filter === state.filter);
    button.innerHTML = `${esc(button.dataset.label ||= button.textContent)}<span class="count">${count}</span>`;
  }
  const flashing = changed(state.transfers);
  const visible = state.transfers.filter(FILTERS[state.filter]);
  $('#transfers').innerHTML = visible.length
    ? visible.map((t) => transferRow(t, { changed: flashing.has(t.transferId), title: title(t), amount: signed(t) })).join('')
    : '<div class="empty"><strong>No transfers here</strong><span>Send money above or try a scenario.</span></div>';
}

// ---------- drawer ----------

const drawer = createDrawer({
  async load(transferId) {
    const quiet = { sampled: false };
    const [live, history] = await Promise.all([
      api('GET', `${base()}/transfers/${transferId}`, undefined, quiet),
      api('GET', `${base()}/transfers/${transferId}/history`, undefined, quiet),
    ]);
    return { live: live.ok ? live.data : null, history: history.ok ? history.data : [] };
  },
  render(transferId, data) {
    const listed = state.transfers.find((t) => t.transferId === transferId) || { transferId };
    const t = { ...listed, ...(data?.live ? { status: data.live.status, reason: data.live.reason, amountCents: data.live.amountCents } : {}) };
    return transferDetails(t, {
      history: data?.history || [],
      title: listed.kind ? title(listed) : 'Transfer',
      traceId: listed.traceId || state.traceIds.get(transferId),
    });
  },
});

// ---------- sending ----------

async function send(body, span) {
  const result = await api('POST', `${base()}/transfers`, body, { span });
  if (result.ok && result.data.traceId) state.traceIds.set(result.data.transferId, result.data.traceId);
  return result;
}

function readForm() {
  const form = $('#send-form');
  const amountCents = parseCents(form.amount.value);
  if (!form.iban.value.trim()) return { error: 'Enter the IBAN to send to' };
  if (amountCents == null || Number.isNaN(amountCents) || amountCents <= 0) return { error: 'Enter a positive amount, e.g. 25 or 25,50' };
  return { body: { creditorIban: form.iban.value, amountCents, transferId: form.transferId.value.trim() || undefined } };
}

async function onSubmit(event) {
  event.preventDefault();
  const request = readForm();
  if (request.error) { toast('Check the form', esc(request.error), 'warn'); return; }
  const button = $('#send-form button[type="submit"]');
  button.disabled = true;
  const result = await traced('send transfer', {}, (span) => send(request.body, span));
  button.disabled = false;
  if (result.ok) {
    toast('Transfer accepted', `Workflow <code>transfer-${esc(result.data.transferId.slice(0, 8))}…</code> started`, 'ok');
    $('#send-form').transferId.value = '';
    drawer.open(result.data.transferId);
    refresh();
  } else {
    toast(result.status === 409 ? 'Rejected: duplicate' : `Error ${result.status || ''}`.trim(), esc(problem(result)), 'bad');
  }
}

// ---------- scenarios ----------

const SCENARIO_HINTS = {
  limit: 'Sends 15.000 € to an account of this bank, or 6.000 € to another bank. It waits for the back office to approve or deny it.',
  insufficient: 'Sends 1 € more than the account has available. The decider rejects it; nothing is booked.',
  rejected: 'Sends to a bank that is not a participant of the clearing house. It rejects the payment (RC01) and the reserved amount is refunded.',
  duplicate: 'Sends the same transfer ID twice. The second request gets 409: the workflow ID is the idempotency key.',
  burst: 'Fires 10 transfers at once, each a quarter of the available amount. Row locks make sure only some succeed and the account never goes below its limit.',
};

async function runScenario(name, button) {
  $('#scenario-hint').textContent = SCENARIO_HINTS[name] || '';
  const account = state.account;
  if (!account) return;
  const to = name === 'rejected' ? REJECTING_IBAN : $('#send-form').iban.value.trim();
  if (!to) { toast('Enter an IBAN first', 'The scenarios send from this account to the IBAN in the form.', 'warn'); return; }
  const available = account.balanceCents + account.overdraftLimitCents;
  const onUs = bankCodeOf(to) === bankCodeOf(account.iban);
  button.disabled = true;
  try {
    await traced(`scenario: ${name}`, { 'bank.scenario': name }, async (span) => {
      if (name === 'limit') {
        const r = await send({ creditorIban: to, amountCents: onUs ? 15_000_00 : 6_000_00 }, span);
        toast(r.ok ? 'Waiting for approval' : 'Error', r.ok ? 'The back office approves or denies it.' : esc(problem(r)), r.ok ? 'warn' : 'bad');
      } else if (name === 'insufficient') {
        const r = await send({ creditorIban: to, amountCents: Math.max(available, 0) + 100 }, span);
        if (!r.ok) toast('Error', esc(problem(r)), 'bad');
      } else if (name === 'rejected') {
        const r = await send({ creditorIban: to, amountCents: 25_00 }, span);
        toast(r.ok ? 'Sent to a bank outside the clearing house' : 'Error', r.ok ? 'Watch: reserve → pacs.008 → RJCT (RC01) → refund.' : esc(problem(r)), r.ok ? 'info' : 'bad');
      } else if (name === 'duplicate') {
        const body = { creditorIban: to, amountCents: 1_00, transferId: uuid() };
        const first = await send(body, span);
        const second = await send(body, span);
        toast('Duplicate submission',
          `First: <strong>${first.status}</strong> · second: <strong>${second.status}</strong> ${second.status === 409 ? '(rejected, as expected)' : ''}<br>${esc(second.ok ? '' : problem(second))}`,
          second.status === 409 ? 'ok' : 'bad');
      } else if (name === 'burst') {
        const amountCents = Math.max(100, Math.ceil(Math.max(available, 0) / 4));
        const results = await Promise.all(Array.from({ length: 10 }, () => send({ creditorIban: to, amountCents }, span)));
        toast(`Burst: ${results.filter((r) => r.ok).length} of 10 accepted`,
          `Each ${esc(money(amountCents))} (available ${esc(money(available))}). At most ${Math.floor(Math.max(available, 0) / amountCents)} can be booked; watch the rest get "Insufficient funds".`,
          'info');
      }
    });
  } finally {
    button.disabled = false;
  }
  refresh();
}

// ---------- events ----------

function bindEvents() {
  $('#acting-as').addEventListener('change', (event) => actAs(event.target.value));
  $('#send-form').addEventListener('submit', onSubmit);
  $('#new-id').addEventListener('click', () => { $('#send-form').transferId.value = uuid(); });
  for (const button of $$('#filters button')) {
    button.addEventListener('click', () => { state.filter = button.dataset.filter; renderTransfers(); });
  }
  for (const chip of $$('[data-scenario]')) {
    chip.addEventListener('click', () => runScenario(chip.dataset.scenario, chip));
    chip.addEventListener('mouseenter', () => { $('#scenario-hint').textContent = SCENARIO_HINTS[chip.dataset.scenario]; });
  }
  $('#rename-dialog').addEventListener('close', () => {
    const dialog = $('#rename-dialog');
    if (dialog.returnValue === 'save') { setNickname(state.accountId, $('form', dialog).nickname.value.trim()); render(); }
  });
  document.addEventListener('click', (event) => {
    const open = event.target.closest('[data-open-transfer]');
    if (open) { event.preventDefault(); drawer.open(open.dataset.openTransfer); return; }
    const element = event.target.closest('[data-action]');
    if (!element) return;
    if (element.dataset.action === 'rename') {
      $('#rename-dialog form').nickname.value = nicknames()[state.accountId] || '';
      openDialog('#rename-dialog');
    }
    if (element.dataset.action === 'copy-iban' && state.account) {
      navigator.clipboard?.writeText(formatIban(state.account.iban)).then(() => toast('IBAN copied', '', 'ok'));
    }
  });
}

state.accountId = readActingAs();
bindEvents();
startPage({ area: 'customer', refresh });
