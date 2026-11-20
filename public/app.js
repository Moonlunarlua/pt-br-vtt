// Estremere VTT — cliente. JS puro, sem etapa de build.
import { createBoard } from './board.js';
import { renderCombat, setupCombat } from './combat.js';

const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];

export const S = {
  token: localStorage.getItem('estremere.token') || '',
  me: null,
  board: { scene: {}, tokens: {}, combat: { list: [], round: 0 } },
  chat: [],
  online: [],
  known: [],
  condicoes: [],
  lan: [],
  selected: null,
  ws: null,
};

// ---------- Utilidades ----------
export function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
let toastTimer;
export function toast(text, ok = false) {
  const t = $('#toast');
  t.textContent = text;
  t.classList.toggle('ok', ok);
  t.classList.remove('hidden');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.add('hidden'), 3500);
}
export function send(msg) {
  if (S.ws && S.ws.readyState === 1) S.ws.send(JSON.stringify(msg));
  else toast('Sem conexão com a mesa. Tentando reconectar…');
}
export const isGM = () => S.me && S.me.role === 'gm';
export const sameName = (a, b) => !!a && !!b && a.toLowerCase() === b.toLowerCase();
export const canEditToken = (t) => isGM() || sameName(t.owner, S.me.name);

export async function uploadImage(file) {
  const res = await fetch('/api/upload', { method: 'POST', headers: { 'content-type': file.type, 'x-token': S.token }, body: file });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.erro || 'Falha ao enviar a imagem.');
  return data.url;
}

// ---------- Login ----------
function showLogin(err = '') {
  $('#app').classList.add('hidden');
  $('#login').classList.remove('hidden');
  $('#login-error').textContent = err;
  $('#login-name').value = localStorage.getItem('estremere.name') || '';
  ($('#login-name').value ? $('#login-pass') : $('#login-name')).focus();
}

$('#login-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  $('#login-error').textContent = '';
  const name = $('#login-name').value.trim();
  const password = $('#login-pass').value;
  try {
    const res = await fetch('/api/login', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ name, password }) });
    const data = await res.json();
    if (!res.ok) throw new Error(data.erro || 'Não deu para entrar.');
    S.token = data.token;
    localStorage.setItem('estremere.token', data.token);
    localStorage.setItem('estremere.name', data.name);
    $('#login-pass').value = '';
    connect();
  } catch (err) {
    $('#login-error').textContent = err.message === 'Failed to fetch' ? 'O servidor não respondeu. Ele está ligado?' : err.message;
  }
});

$('#btn-logout').addEventListener('click', () => {
  localStorage.removeItem('estremere.token');
  S.token = '';
  if (S.ws) { S.ws.onclose = null; S.ws.close(); }
  showLogin();
});

// ---------- Conexão ----------
let retry = 0;
function connect() {
  if (!S.token) return showLogin();
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const ws = new WebSocket(`${proto}://${location.host}/ws`);
  S.ws = ws;
  ws.onopen = () => { retry = 0; ws.send(JSON.stringify({ t: 'hello', token: S.token })); };
  ws.onmessage = (ev) => handle(JSON.parse(ev.data));
  ws.onclose = () => {
    if (S.ws !== ws) return;
    if (!S.me) return setTimeout(connect, 1500);
    $('#conn').classList.remove('hidden');
    setTimeout(connect, Math.min(5000, 500 * 2 ** retry++));
  };
}

export let board;
function handle(m) {
  switch (m.t) {
    case 'auth':
      localStorage.removeItem('estremere.token');
      S.token = '';
      S.ws.onclose = null;
      S.ws.close();
      showLogin('Sua sessão expirou. Entre de novo.');
      break;
    case 'welcome':
      S.me = m.me;
      S.board = m.board;
      S.chat = m.chat;
      S.condicoes = m.condicoes || [];
      S.lan = m.lan || [];
      document.body.classList.toggle('is-gm', m.me.role === 'gm');
      $('#login').classList.add('hidden');
      $('#app').classList.remove('hidden');
      $('#conn').classList.add('hidden');
      if (!board) board = createBoard($('#board'), { onSelect: selectToken });
      board.setBoard(S.board);
      renderChat();
      renderBoardSide();
      break;
    case 'board':
      S.board = m.board;
      board.setBoard(S.board);
      renderBoardSide();
      break;
    case 'tok':
      S.board.tokens[m.token.id] = m.token;
      board.updateToken(m.token);
      if (S.selected === m.token.id) fillEditor();
      break;
    case 'users':
      S.online = m.online;
      S.known = m.known;
      renderOnline();
      break;
    case 'chat':
      S.chat.push(m.msg);
      if (S.chat.length > 300) S.chat.shift();
      appendMsg(m.msg, true);
      break;
    case 'chatReset':
      S.chat = m.chat;
      renderChat();
      break;
    case 'ping':
      board.ping(m.x, m.y, m.color, m.from);
      break;
    case 'error':
      toast(m.text);
      break;
  }
}

function renderBoardSide() {
  $('#scene-name').textContent = S.board.scene.name ? `· ${S.board.scene.name}` : '';
  if (S.selected && !S.board.tokens[S.selected]) selectToken(null);
  else if (S.selected) fillEditor();
  renderCombat();
}

function renderOnline() {
  $('#online').innerHTML = S.online.map((u) =>
    `<span class="who ${u.role === 'gm' ? 'gm' : ''}"><span class="dot" style="background:${esc(u.color)}"></span>${esc(u.name)}${u.role === 'gm' ? ' (mestre)' : ''}</span>`).join('');
  const sel = $('#tok-editor select[name=owner]');
  const cur = sel.value;
  sel.innerHTML = '<option value="">— Ninguém (NPC) —</option>' +
    S.known.filter((u) => u.role === 'player').map((u) => `<option value="${esc(u.name)}">${esc(u.name)}</option>`).join('');
  sel.value = cur;
}

// ---------- Chat ----------
const fmtTime = (ts) => new Date(ts).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' });

function rollHtml(msg) {
  const r = msg.roll;
  let dice = '';
  r.parts.forEach((p, i) => {
    if (i > 0 || p.sign < 0) dice += `<span class="op">${p.sign < 0 ? '−' : '+'}</span>`;
    if (p.kind === 'num') { dice += `<span class="op">${p.value}</span>`; return; }
    for (const d of p.rolls) {
      let cls = 'die';
      if (d.dropped) cls += ' dropped';
      else if (p.sides === 20 && d.v === 20) cls += ' crit';
      else if (p.sides === 20 && d.v === 1) cls += ' fail';
      else if (d.v === p.sides) cls += ' max';
      dice += `<span class="${cls}" title="d${p.sides}">${d.v}<small>d${p.sides}</small></span>`;
    }
  });
  const flags = [];
  if (r.nat20) flags.push('<span class="flag crit">20 natural!</span>');
  else if (r.nat1) flags.push('<span class="flag fail">1 natural</span>');
  if (msg.damage && r.maxDamage) flags.push('<span class="flag stun">Dano máximo! O alvo fica atordoado (stun)</span>');
  return `<div class="roll">
    ${msg.label ? `<div class="label">${esc(msg.label)}</div>` : ''}
    <div class="expr">${esc(r.expr)}${msg.damage ? ' · dano' : ''}</div>
    <div class="dice">${dice}</div>
    <div class="total"><span>${flags.join(' ')}</span><b>${r.total}</b></div>
  </div>`;
}

function msgHtml(m) {
  const c = `style="--c:${esc(m.color || '')}"`;
  const meta = (extra = '') => `<div class="meta"><b>${esc(m.from)}</b>${extra}<time>${fmtTime(m.ts)}</time></div>`;
  switch (m.type) {
    case 'system':
      return `<div class="msg system ${m.turn ? 'turn' : ''}">${esc(m.text)}</div>`;
    case 'me':
      return `<div class="msg me" ${c}>${esc(m.from)} ${esc(m.text)}</div>`;
    case 'whisper': {
      const to = sameName(m.from, S.me.name) ? `para ${m.to.map(esc).join(', ')}` : 'sussurrou para você';
      return `<div class="msg whisper" ${c}>${meta(` <span class="tag">${to}</span>`)}${esc(m.text)}</div>`;
    }
    case 'roll':
      return `<div class="msg ${m.gmOnly ? 'secret' : ''}" ${c}>${meta(m.gmOnly ? ' <span class="tag">secreta (mestre)</span>' : '')}${rollHtml(m)}</div>`;
    default:
      return `<div class="msg" ${c}>${meta()}${esc(m.text)}</div>`;
  }
}

function renderChat() {
  const log = $('#chat-log');
  log.innerHTML = S.chat.map(msgHtml).join('') || '<p class="empty">Nada no chat ainda. Role um dado!</p>';
  log.scrollTop = log.scrollHeight;
}
function appendMsg(m, notify) {
  const log = $('#chat-log');
  const atBottom = log.scrollHeight - log.scrollTop - log.clientHeight < 80;
  const empty = $('.empty', log);
  if (empty) empty.remove();
  log.insertAdjacentHTML('beforeend', msgHtml(m));
  if (atBottom || sameName(m.from, S.me.name)) log.scrollTop = log.scrollHeight;
  if (notify && m.type === 'roll' && !$('#tab-chat').classList.contains('hidden')) return;
}

const HELP = [
  'Comandos:',
  '/r 1d20+2 # Teste de Força — rola e mostra o comentário',
  '/dano 2d4 — rolagem de dano (avisa quando dá dano máximo = stun)',
  '/gr 1d20 — rolagem secreta (só você e o mestre veem)',
  '/r 1d20 vant ou desv — vantagem/desvantagem; também 2d20kh1, 4d6kh3, 2d20kl1',
  '/w nome mensagem — sussurro (/w mestre … fala só com o mestre)',
  '/me pula a mesa — ação do personagem',
  'Dados aceitos: qualquer dN, inclusive d2, d3 e d5.',
];

const history = [];
let histPos = 0;
$('#chat-form').addEventListener('submit', (e) => {
  e.preventDefault();
  const input = $('#chat-input');
  const text = input.value.trim();
  if (!text) return;
  history.push(text);
  histPos = history.length;
  input.value = '';
  if (/^\/(ajuda|help|\?)$/i.test(text)) {
    appendMsg({ type: 'system', text: HELP.join('\n'), ts: Date.now() });
    $('#chat-log').lastElementChild.style.whiteSpace = 'pre-line';
    return;
  }
  send({ t: 'chat', text });
});
$('#chat-input').addEventListener('keydown', (e) => {
  if (e.key === 'ArrowUp' && histPos > 0) { e.preventDefault(); e.target.value = history[--histPos]; }
  if (e.key === 'ArrowDown' && histPos < history.length) { e.preventDefault(); e.target.value = history[++histPos] || ''; }
});
$$('.dice-bar button').forEach((b) => b.addEventListener('click', (e) => {
  const sides = b.dataset.die;
  const input = $('#chat-input');
  // Se tem um modificador digitado (ex.: "+2"), usa junto.
  const mod = /^[+-]\s*\d+$/.test(input.value.trim()) ? input.value.replace(/\s/g, '') : '';
  const cmd = e.shiftKey ? '/gr' : (sides !== '20' && e.altKey ? '/dano' : '/r');
  send({ t: 'chat', text: `${cmd} 1d${sides}${mod}` });
  if (mod) input.value = '';
}));

// ---------- Abas ----------
$$('.tab').forEach((t) => t.addEventListener('click', () => {
  $$('.tab').forEach((x) => x.classList.toggle('active', x === t));
  $$('.tab-body').forEach((b) => b.classList.toggle('hidden', b.id !== `tab-${t.dataset.tab}`));
  if (t.dataset.tab === 'chat') $('#chat-log').scrollTop = 1e9;
}));

// ---------- Editor de token ----------
const ed = $('#tok-editor');
export function selectToken(id) {
  S.selected = id;
  board && board.setSelected(id);
  if (!id) { ed.classList.add('hidden'); return; }
  fillEditor();
  ed.classList.remove('hidden');
}
function fillEditor() {
  const t = S.board.tokens[S.selected];
  if (!t) return selectToken(null);
  const editable = canEditToken(t);
  ed.classList.toggle('readonly', !editable);
  $('#te-readonly').classList.toggle('hidden', editable);
  $('#te-title').textContent = t.name || 'Token';
  for (const el of ed.elements) {
    if (!el.name || document.activeElement === el) continue;
    if (el.type === 'checkbox') el.checked = !!t[el.name];
    else if (el.name === 'owner') {
      if (t.owner && ![...el.options].some((o) => o.value === t.owner)) el.insertAdjacentHTML('beforeend', `<option>${esc(t.owner)}</option>`);
      el.value = t.owner || '';
    } else el.value = t[el.name] ?? '';
  }
  const hpVisible = 'hp' in t;
  ed.elements.hp.closest('label').classList.toggle('hidden', !hpVisible);
  ed.elements.hpMax.closest('label').classList.toggle('hidden', !hpVisible);
  $$('.row', ed).forEach((r) => r.classList.toggle('hidden', !editable));
  $('#te-combat').classList.toggle('hidden', !editable || S.board.combat.list.some((c) => c.tokenId === t.id));
}
ed.addEventListener('change', (e) => {
  const el = e.target;
  if (!el.name || !S.selected) return;
  const v = el.type === 'checkbox' ? el.checked : el.value;
  send({ t: 'token.update', id: S.selected, patch: { [el.name]: v } });
});
ed.addEventListener('submit', (e) => e.preventDefault());
$('#te-close').addEventListener('click', () => selectToken(null));
$('#te-delete').addEventListener('click', () => {
  if (S.selected) send({ t: 'token.delete', id: S.selected });
  selectToken(null);
});
$('#te-combat').addEventListener('click', () => S.selected && send({ t: 'combat.add', tokenId: S.selected }));
$('#te-img').addEventListener('change', async (e) => {
  const f = e.target.files[0];
  e.target.value = '';
  if (!f || !S.selected) return;
  try {
    const url = await uploadImage(f);
    send({ t: 'token.update', id: S.selected, patch: { img: url } });
  } catch (err) { toast(err.message); }
});
$('#te-img-clear').addEventListener('click', () => S.selected && send({ t: 'token.update', id: S.selected, patch: { img: '' } }));

// ---------- Ferramentas do mapa ----------
$$('.tool[data-tool]').forEach((b) => b.addEventListener('click', () => setTool(b.dataset.tool)));
function setTool(tool) {
  $$('.tool[data-tool]').forEach((x) => x.classList.toggle('active', x.dataset.tool === tool));
  board.setTool(tool);
}
$('#btn-add-token').addEventListener('click', () => {
  const c = board.centerCell();
  send({ t: 'token.add', token: { name: isGM() ? 'Inimigo' : S.me.name, x: c.x, y: c.y } });
});
$('#btn-center').addEventListener('click', () => board.fit());
document.addEventListener('keydown', (e) => {
  if (e.target.closest('input, textarea, select')) return;
  if (e.key === 'r' || e.key === 'R') setTool('ruler');
  if (e.key === 'v' || e.key === 'V') setTool('move');
  if (e.key === 'Escape') selectToken(null);
  if ((e.key === 'Delete' || e.key === 'Backspace') && S.selected) {
    const t = S.board.tokens[S.selected];
    if (t && canEditToken(t)) { send({ t: 'token.delete', id: S.selected }); selectToken(null); }
  }
});

// ---------- Cena e link (mestre) ----------
const sceneForm = $('#scene-form');
$('#btn-scene').addEventListener('click', () => {
  const s = S.board.scene;
  for (const el of sceneForm.elements) if (el.name) el.value = s[el.name] ?? '';
  $('#scene-dialog').showModal();
});
sceneForm.addEventListener('change', (e) => {
  if (!e.target.name) return;
  send({ t: 'scene.update', patch: { [e.target.name]: e.target.value } });
});
$('#scene-bg').addEventListener('change', async (e) => {
  const f = e.target.files[0];
  e.target.value = '';
  if (!f) return;
  try {
    const url = await uploadImage(f);
    // Sugere largura/altura pela proporção da imagem, mantendo a largura atual.
    const img = new Image();
    img.onload = () => {
      const cols = S.board.scene.cols || 30;
      const rows = Math.max(1, Math.round(cols * img.naturalHeight / img.naturalWidth));
      send({ t: 'scene.update', patch: { bg: url, rows } });
      sceneForm.elements.rows.value = rows;
      board.fit();
    };
    img.src = url;
  } catch (err) { toast(err.message); }
});
$('#scene-bg-clear').addEventListener('click', () => send({ t: 'scene.update', patch: { bg: '' } }));

$('#btn-lan').addEventListener('click', () => {
  const list = S.lan.length ? S.lan : [location.origin];
  $('#lan-list').innerHTML = list.map((u) => `<li><code>${esc(u)}</code><button type="button" class="btn small" data-copy="${esc(u)}">Copiar</button></li>`).join('');
  $('#lan-dialog').showModal();
});
$('#lan-list').addEventListener('click', async (e) => {
  const b = e.target.closest('[data-copy]');
  if (!b) return;
  try { await navigator.clipboard.writeText(b.dataset.copy); toast('Link copiado!', true); }
  catch { toast('Não deu para copiar. Selecione o texto e copie na mão.'); }
});

setupCombat();
connect();
