// The back office (#61): what bank employees work with, through /api/backoffice/…: the work queues (approvals,
// payments under investigation), customer accounts (open, cash deposits, block) and every transfer.

import {
  $, $$, STATUS, TERMINAL, accountPostings, accountTraceQl, ago, api, changeTracker, createDrawer, esc, externalLink,
  formatIban, grafanaSearchUrl, hue, label, money, nicknames, openDialog, parseCents, problem, route, setNickname,
  single, startPage, statusChip, toast, traced, transferDetails, transferRow,
} from './common.js';

const state = {
  accounts: [],
  transfers: [],
  approvals: [],
  investigations: [],
  filter: 'all',
  accountFilter: null,
  pendingDeny: null,
  depositTarget: null,
};
const changed = changeTracker();

const refresh = single(async () => {
  const quiet = { sampled: false };
  const query = state.accountFilter ? `?accountId=${state.accountFilter}&limit=80` : '?limit=80';
  const [accounts, transfers, approvals, investigations] = await Promise.all([
    api('GET', '/api/backoffice/accounts', undefined, quiet),
    api('GET', `/api/backoffice/transfers${query}`, undefined, quiet),
    api('GET', '/api/backoffice/transfers?status=AWAITING_APPROVAL', undefined, quiet),
    api('GET', '/api/backoffice/transfers?status=UNDER_INVESTIGATION', undefined, quiet),
  ]);
  if (accounts.ok) state.accounts = accounts.data;
  if (transfers.ok) state.transfers = transfers.data;
  if (approvals.ok) state.approvals = approvals.data;
  if (investigations.ok) state.investigations = investigations.data;
  await drawer.refresh();
  render();
});

// ---------- rendering ----------

function render() {
  renderApprovals();
  renderInvestigations();
  renderAccounts();
  renderTransfers();
}

/** The approval queue: suspicious transfers wait (up to 3 days) for a person to decide. */
function renderApprovals() {
  const pending = state.approvals;
  const element = $('#approvals');
  element.hidden = pending.length === 0;
  if (!pending.length) return;
  element.innerHTML = `
    <div class="queue-head">
      <svg viewBox="0 0 24 24"><path d="M12 3 2 20h20L12 3zM12 10v4M12 17h0"/></svg>
      ${pending.length} transfer${pending.length > 1 ? 's' : ''} awaiting approval
    </div>
    ${pending.map((t) => `
      <div class="queue-row">
        <div class="tx-main">
          <span class="tx-title" data-open-transfer="${t.transferId}">${esc(route(t))}</span>
          <span class="tx-sub">${esc(t.reason || 'Manual review required')} · ${esc(ago(t.createdAt))}</span>
        </div>
        <span class="tx-amount">${esc(money(t.amountCents))}</span>
        <div class="queue-actions">
          <button class="btn ok small" data-action="approve" data-id="${t.transferId}">Approve</button>
          <button class="btn ghost small" data-action="deny" data-id="${t.transferId}">Deny</button>
        </div>
      </div>`).join('')}`;
}

/**
 * Payments without a final status from the clearing house (#59, #60): nothing is guessed. The workflow keeps asking;
 * here they are visible until the answer comes. Returns of money that can't be credited come with the recall (#63).
 */
function renderInvestigations() {
  const unclear = state.investigations;
  const element = $('#investigations');
  element.hidden = unclear.length === 0;
  if (!unclear.length) return;
  element.innerHTML = `
    <div class="queue-head">
      <svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/></svg>
      ${unclear.length} payment${unclear.length > 1 ? 's' : ''} under investigation
      <span class="hint">The workflow keeps asking the clearing house every 10 minutes; the amount stays reserved.</span>
    </div>
    ${unclear.map((t) => `
      <div class="queue-row">
        <div class="tx-main">
          <span class="tx-title" data-open-transfer="${t.transferId}">${esc(route(t))}</span>
          <span class="tx-sub">${esc(t.reason || 'No final status')} · since ${esc(ago(t.updatedAt))}</span>
        </div>
        <span class="tx-amount">${esc(money(t.amountCents))}</span>
        <div class="queue-actions"><button class="btn ghost small" data-open-transfer="${t.transferId}">Details</button></div>
      </div>`).join('')}`;
}

function renderAccounts() {
  const customers = [...state.accounts].sort((a, b) => label(a.id).localeCompare(label(b.id)));
  const list = $('#accounts');
  if (!customers.length) {
    list.innerHTML = `
      <div class="empty">
        <strong>No accounts yet</strong>
        <span>Start with two funded accounts and play through every scenario in online banking.</span>
        <button class="btn primary" data-action="quick-start">Quick start: Alice &amp; Bob</button>
      </div>`;
    return;
  }
  list.innerHTML = customers.map((a) => `
    <button class="account-card ${state.accountFilter === a.id ? 'selected' : ''}" data-open-account="${a.id}">
      <div class="ac-top">
        <span class="avatar" style="--hue:${hue(a.id)}">${esc((nicknames()[a.id] || a.id).charAt(0).toUpperCase())}</span>
        <span class="ac-name"><strong>${esc(label(a.id))}</strong><span class="mono">${esc(formatIban(a.iban))}</span></span>
        ${statusChip(a.status)}
      </div>
      <div class="ac-balance">${esc(money(a.balanceCents))}</div>
      <div class="ac-meta">Available ${esc(money(a.balanceCents + a.overdraftLimitCents))}${a.overdraftLimitCents ? ` · overdraft ${esc(money(a.overdraftLimitCents))}` : ''}</div>
    </button>`).join('');
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
  const flashing = changed(state.transfers);
  const visible = state.transfers.filter(FILTERS[state.filter]);
  $('#transfers').innerHTML = visible.length
    ? visible.map((t) => transferRow(t, { changed: flashing.has(t.transferId) })).join('')
    : '<div class="empty"><strong>No transfers here</strong><span>Customers send them in online banking.</span></div>';
}

// ---------- drawer ----------

const drawer = createDrawer({
  async load({ type, id }) {
    const quiet = { sampled: false };
    if (type === 'account') {
      const postings = await api('GET', `/api/backoffice/accounts/${id}/postings`, undefined, quiet);
      return { postings: postings.ok ? postings.data : [] };
    }
    const [live, postings, history] = await Promise.all([
      api('GET', `/api/backoffice/transfers/${id}`, undefined, quiet),
      api('GET', `/api/backoffice/transfers/${id}/postings`, undefined, quiet),
      api('GET', `/api/backoffice/transfers/${id}/history`, undefined, quiet),
    ]);
    return { live: live.ok ? live.data : null, postings: postings.ok ? postings.data : [], history: history.ok ? history.data : [] };
  },
  render({ type, id }, data) {
    return type === 'account' ? accountDetails(id, data) : transferDrawer(id, data);
  },
});

function transferDrawer(id, data) {
  const listed = [...state.transfers, ...state.approvals, ...state.investigations].find((t) => t.transferId === id) || { transferId: id };
  const t = { ...listed, ...(data?.live ? { status: data.live.status, reason: data.live.reason, amountCents: data.live.amountCents } : {}) };
  if (!t.kind && data?.live) t.kind = data.live.external ? 'EXTERNAL' : 'INTERNAL';
  const actions = t.status === 'AWAITING_APPROVAL' ? `
    <button class="btn ok" data-action="approve" data-id="${id}">Approve</button>
    <button class="btn ghost" data-action="deny" data-id="${id}">Deny</button>` : '';
  return transferDetails(t, { history: data?.history || [], postings: data?.postings || [], actions });
}

function accountDetails(id, data) {
  const account = state.accounts.find((a) => a.id === id);
  if (!account) return '<p class="hint">Loading…</p>';
  return `
    <div class="d-eyebrow">Customer account</div>
    <div class="d-title ${account.balanceCents < 0 ? 'negative' : ''}">${esc(money(account.balanceCents))}</div>
    <div class="d-route"><strong>${esc(label(id))}</strong> ${statusChip(account.status)}</div>
    <dl class="d-grid">
      <dt>Account ID</dt><dd class="mono">${esc(id)}</dd>
      <dt>IBAN</dt><dd class="mono">${esc(formatIban(account.iban))}</dd>
      <dt>Overdraft</dt><dd>${esc(money(account.overdraftLimitCents))}</dd>
      <dt>Available</dt><dd>${esc(money(account.balanceCents + account.overdraftLimitCents))}</dd>
    </dl>
    <div class="d-actions">
      <button class="btn primary" data-action="deposit" data-id="${id}">Cash deposit</button>
      ${account.status === 'BLOCKED'
    ? `<button class="btn ghost" data-action="unblock" data-id="${id}">Unblock</button>`
    : account.status === 'OPEN' ? `<button class="btn ghost" data-action="block" data-id="${id}">Block</button>` : ''}
      <button class="btn ghost" data-action="rename" data-id="${id}">Name</button>
      <button class="btn ghost" data-action="filter-account" data-id="${id}">Show transfers</button>
      <a class="btn ghost" href="customer.html?account=${id}">Online banking as this customer</a>
      ${externalLink(grafanaSearchUrl(accountTraceQl(id)), 'Traces in Grafana')}
    </div>
    <p class="hint">A blocked account still receives internal transfers, but every debit is rejected, and payments from other banks are refused (AC06).</p>
    <div class="d-section">
      <h3>Postings (newest first)</h3>
      ${data ? accountPostings(data.postings) : '<p class="hint">Loading…</p>'}
    </div>`;
}

// ---------- actions ----------

async function decide(id, approved, comment) {
  const result = await traced(approved ? 'approve transfer' : 'deny transfer', { 'bank.transfer.id': id },
    (span) => api('POST', `/api/backoffice/transfers/${id}/approval`, { approved, comment: comment || undefined }, { span }));
  if (result.ok) {
    toast(approved ? 'Approved' : 'Denied', `Update accepted, the transfer is now <strong>${esc(STATUS[result.data.status]?.label || result.data.status)}</strong>`, approved ? 'ok' : 'warn');
  } else {
    toast('Update rejected', esc(problem(result)), 'bad');
  }
  refresh();
}

async function openAccount(nickname, overdraftCents, depositCents, span, notify = true) {
  const created = await api('POST', '/api/backoffice/accounts', { overdraftLimitCents: overdraftCents || 0 }, { span });
  if (!created.ok) { toast('Could not open the account', esc(problem(created)), 'bad'); return; }
  if (nickname) setNickname(created.data.id, nickname);
  if (depositCents > 0) {
    const deposit = await api('POST', `/api/backoffice/accounts/${created.data.id}/deposits`, { amountCents: depositCents }, { span });
    if (!deposit.ok) toast('Deposit failed', esc(problem(deposit)), 'bad');
  }
  if (notify) toast('Account opened', esc(`${label(created.data.id)} · ${formatIban(created.data.iban)}`), 'ok');
}

async function quickStart(button) {
  button.disabled = true;
  await traced('quick start', {}, async (span) => {
    await openAccount('Alice', 200_00, 1_000_00, span, false);
    await openAccount('Bob', 0, 250_00, span, false);
  });
  toast('Alice and Bob are ready', 'Deposits run through the TransferWorkflow, balances appear in a moment. Act as one of them in online banking.', 'ok');
  refresh();
}

// ---------- events ----------

function bindEvents() {
  for (const button of $$('#filters button')) {
    button.addEventListener('click', () => { state.filter = button.dataset.filter; renderTransfers(); });
  }
  $('#new-account').addEventListener('click', () => { $('#account-dialog form').reset(); openDialog('#account-dialog'); });

  $('#account-dialog').addEventListener('close', () => {
    const dialog = $('#account-dialog');
    if (dialog.returnValue !== 'create') return;
    const form = $('form', dialog);
    const overdraft = parseCents(form.overdraft.value) || 0;
    const deposit = parseCents(form.deposit.value) || 0;
    if (Number.isNaN(overdraft) || Number.isNaN(deposit)) { toast('Invalid amount', '', 'warn'); return; }
    traced('open account', {}, (span) => openAccount(form.nickname.value.trim(), overdraft, deposit, span)).then(refresh);
  });
  $('#deposit-dialog').addEventListener('close', async () => {
    const dialog = $('#deposit-dialog');
    if (dialog.returnValue !== 'deposit' || !state.depositTarget) return;
    const amountCents = parseCents($('form', dialog).amount.value);
    if (!amountCents || Number.isNaN(amountCents)) { toast('Invalid amount', '', 'warn'); return; }
    const target = state.depositTarget;
    const result = await traced('deposit', { 'bank.account.id': target },
      (span) => api('POST', `/api/backoffice/accounts/${target}/deposits`, { amountCents }, { span }));
    toast(result.ok ? 'Deposit started' : 'Deposit failed', result.ok ? esc(money(amountCents)) : esc(problem(result)), result.ok ? 'ok' : 'bad');
    refresh();
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

  document.addEventListener('click', async (event) => {
    const openTransfer = event.target.closest('[data-open-transfer]');
    if (openTransfer) { event.preventDefault(); drawer.open({ type: 'transfer', id: openTransfer.dataset.openTransfer }); return; }
    const openAccountCard = event.target.closest('[data-open-account]');
    if (openAccountCard) { drawer.open({ type: 'account', id: openAccountCard.dataset.openAccount }); return; }

    const element = event.target.closest('[data-action]');
    if (!element) return;
    const { action, id } = element.dataset;
    switch (action) {
      case 'quick-start': quickStart(element); break;
      case 'approve': element.disabled = true; decide(id, true); break;
      case 'deny':
        state.pendingDeny = id;
        $('#deny-dialog form').reset();
        openDialog('#deny-dialog');
        break;
      case 'deposit':
        state.depositTarget = id;
        $('#deposit-dialog form').reset();
        openDialog('#deposit-dialog');
        break;
      case 'rename': {
        const dialog = $('#rename-dialog');
        dialog.dataset.id = id;
        $('form', dialog).nickname.value = nicknames()[id] || '';
        openDialog('#rename-dialog');
        break;
      }
      case 'block':
      case 'unblock': {
        const result = await traced(`${action} account`, { 'bank.account.id': id },
          (span) => api('POST', `/api/backoffice/accounts/${id}/${action}`, undefined, { span }));
        toast(result.ok ? (action === 'block' ? 'Account blocked' : 'Account unblocked') : 'Not possible', result.ok ? '' : esc(problem(result)), result.ok ? 'ok' : 'bad');
        refresh();
        break;
      }
      case 'filter-account': state.accountFilter = id; drawer.close(); refresh(); break;
      case 'clear-account-filter': state.accountFilter = null; refresh(); break;
      default: break;
    }
  });
}

bindEvents();
startPage({ area: 'backoffice', refresh });
