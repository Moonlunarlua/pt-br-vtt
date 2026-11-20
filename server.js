'use strict';
// Estremere VTT: servidor da mesa. Rode com "node server.js" (ou iniciar.bat)
// e os jogadores entram pelo navegador usando o IP da sua rede local.

const fs = require('fs');
const path = require('path');
const os = require('os');
const http = require('http');
const crypto = require('crypto');
const express = require('express');
const { WebSocketServer } = require('ws');

const dice = require('./lib/dice');
const F = require('./lib/filter');
const { Store } = require('./lib/store');

const ROOT = __dirname;
const DATA = process.env.ESTREMERE_DATA || path.join(ROOT, 'data');
const UPLOADS = path.join(DATA, 'uploads');
fs.mkdirSync(UPLOADS, { recursive: true });

// ---------- Configuração (senhas) ----------
const CONFIG_FILE = path.join(DATA, 'config.json');
function randomPass(n) {
  const abc = 'abcdefghjkmnpqrstuvwxyz23456789';
  let s = '';
  for (let i = 0; i < n; i++) s += abc[crypto.randomInt(abc.length)];
  return s;
}
let config;
if (fs.existsSync(CONFIG_FILE)) {
  config = JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf8'));
} else {
  config = {
    porta: 3000,
    senhaMestre: randomPass(8),
    senhaJogadores: randomPass(5),
    segredo: crypto.randomBytes(24).toString('hex'),
  };
  fs.writeFileSync(CONFIG_FILE, JSON.stringify(config, null, 2));
}
if (!config.segredo) {
  config.segredo = crypto.randomBytes(24).toString('hex');
  fs.writeFileSync(CONFIG_FILE, JSON.stringify(config, null, 2));
}
const PORT = Number(process.env.PORT) || config.porta || 3000;

// Sessão sem banco: token = nome|papel|assinatura. Trocar a senha no
// config.json não derruba sessões; trocar o "segredo" derruba todas.
function sign(name, role) {
  const payload = Buffer.from(JSON.stringify({ name, role })).toString('base64url');
  const mac = crypto.createHmac('sha256', config.segredo).update(payload).digest('base64url');
  return `${payload}.${mac}`;
}
function verify(token) {
  if (typeof token !== 'string' || !token.includes('.')) return null;
  const [payload, mac] = token.split('.');
  const good = crypto.createHmac('sha256', config.segredo).update(payload).digest('base64url');
  if (mac.length !== good.length || !crypto.timingSafeEqual(Buffer.from(mac), Buffer.from(good))) return null;
  try {
    const u = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8'));
    if (!u.name || (u.role !== 'gm' && u.role !== 'player')) return null;
    return u;
  } catch { return null; }
}

// ---------- Estado ----------
const store = new Store(path.join(DATA, 'state.json'));
const state = store.state;

const COLORS = ['#c0392b', '#2e86c1', '#27ae60', '#8e44ad', '#d68910', '#16a085', '#cb4335', '#5d6d7e', '#a04000', '#1abc9c'];
function colorFor(name) {
  let h = 0;
  for (const ch of name.toLowerCase()) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  return COLORS[h % COLORS.length];
}

const id = () => crypto.randomBytes(6).toString('hex');
const str = (v, max = 80) => String(v == null ? '' : v).slice(0, max).trim();
const num = (v, def = 0, min = -1e6, max = 1e6) => {
  const n = Number(v);
  return Number.isFinite(n) ? Math.min(max, Math.max(min, n)) : def;
};

// ---------- HTTP ----------
const app = express();
app.disable('x-powered-by');
app.use(express.json({ limit: '100kb' }));
app.use('/uploads', express.static(UPLOADS, { maxAge: '7d' }));
app.use(express.static(path.join(ROOT, 'public')));

app.post('/api/login', (req, res) => {
  const name = str(req.body && req.body.name, 24).replace(/\s+/g, ' ');
  const pass = String((req.body && req.body.password) || '');
  if (!name) return res.status(400).json({ erro: 'Digite seu nome.' });
  if (/^(mestre|gm|todos)$/i.test(name)) return res.status(400).json({ erro: 'Esse nome é reservado. Escolha outro.' });
  let role = null;
  if (pass === config.senhaMestre) role = 'gm';
  else if (pass === config.senhaJogadores) role = 'player';
  if (!role) return res.status(401).json({ erro: 'Senha errada.' });
  const known = state.users[name.toLowerCase()];
  if (known && known.role !== role) {
    return res.status(409).json({ erro: `O nome "${known.name}" já é usado por ${known.role === 'gm' ? 'um mestre' : 'um jogador'}. Use outro nome.` });
  }
  res.json({ token: sign(name, role), name, role });
});

const MIME_EXT = { 'image/png': 'png', 'image/jpeg': 'jpg', 'image/webp': 'webp', 'image/gif': 'gif' };
app.post('/api/upload', express.raw({ type: Object.keys(MIME_EXT), limit: '20mb' }), (req, res) => {
  const user = verify(req.get('x-token'));
  if (!user) return res.status(401).json({ erro: 'Faça login de novo.' });
  const ext = MIME_EXT[req.get('content-type')];
  if (!ext || !Buffer.isBuffer(req.body) || !req.body.length) return res.status(400).json({ erro: 'Envie uma imagem PNG, JPG, WEBP ou GIF.' });
  const file = `${Date.now().toString(36)}-${id()}.${ext}`;
  fs.writeFileSync(path.join(UPLOADS, file), req.body);
  res.json({ url: `/uploads/${file}` });
});

const server = http.createServer(app);
const wss = new WebSocketServer({ server, path: '/ws', maxPayload: 256 * 1024 });

// ---------- Clientes conectados ----------
const clients = new Set();

function send(ws, msg) {
  if (ws.readyState === 1) ws.send(JSON.stringify(msg));
}
function onlineList() {
  const seen = new Map();
  for (const c of clients) if (c.user) seen.set(c.user.name.toLowerCase(), { name: c.user.name, role: c.user.role, color: colorFor(c.user.name) });
  return [...seen.values()];
}
function broadcastUsers() {
  const online = onlineList();
  const known = Object.values(state.users);
  for (const c of clients) if (c.user) send(c, { t: 'users', online, known });
}
function broadcastBoard() {
  for (const c of clients) if (c.user) send(c, { t: 'board', board: F.filterBoard(state, c.user) });
}
function broadcastToken(tok) {
  for (const c of clients) {
    if (!c.user) continue;
    const t = F.filterToken(tok, c.user);
    if (t) send(c, { t: 'tok', token: t });
  }
}
function pushChat(msg) {
  msg.id = id();
  msg.ts = Date.now();
  state.chat.push(msg);
  if (state.chat.length > 300) state.chat.splice(0, state.chat.length - 300);
  store.save();
  for (const c of clients) if (c.user && F.canSeeMessage(msg, c.user)) send(c, { t: 'chat', msg });
}
function system(text, extra = {}) {
  pushChat({ type: 'system', from: 'Mesa', text, ...extra });
}

// ---------- Chat e comandos ----------
function gmNames() {
  return Object.values(state.users).filter((u) => u.role === 'gm').map((u) => u.name);
}

function rollToChat(user, expr, opts = {}) {
  const r = dice.roll(expr);
  pushChat({
    type: 'roll', from: user.name, color: colorFor(user.name),
    roll: r, label: opts.label || '', damage: !!opts.damage, gmOnly: !!opts.gmOnly,
  });
  return r;
}

function splitComment(text) {
  const i = text.indexOf('#');
  if (i < 0) return [text.trim(), ''];
  return [text.slice(0, i).trim(), text.slice(i + 1).trim()];
}

function handleChat(ws, user, text) {
  text = String(text || '').slice(0, 1000).trim();
  if (!text) return;
  const cmd = text.match(/^\/(\w+)\s*([\s\S]*)$/);
  if (!cmd) {
    pushChat({ type: 'text', from: user.name, color: colorFor(user.name), text });
    return;
  }
  const name = cmd[1].toLowerCase();
  const rest = cmd[2];
  try {
    if (name === 'r' || name === 'roll' || name === 'gr' || name === 'dano' || name === 'd') {
      const [expr, label] = splitComment(rest);
      const damage = name === 'dano' || name === 'd' || /\bdano\b/i.test(label);
      rollToChat(user, expr, { label, damage, gmOnly: name === 'gr' });
    } else if (name === 'me' || name === 'eu') {
      if (rest.trim()) pushChat({ type: 'me', from: user.name, color: colorFor(user.name), text: rest.trim() });
    } else if (name === 'w' || name === 'sussurro') {
      const m = rest.match(/^("([^"]+)"|(\S+))\s+([\s\S]+)$/);
      if (!m) return send(ws, { t: 'error', text: 'Use: /w nome mensagem (nome com espaço vai entre aspas).' });
      const target = (m[2] || m[3]).trim();
      let to;
      if (/^(mestre|gm)$/i.test(target)) to = gmNames();
      else {
        const u = state.users[target.toLowerCase()];
        if (!u) return send(ws, { t: 'error', text: `Ninguém chamado "${target}" entrou na mesa ainda.` });
        to = [u.name];
      }
      if (!to.length) return send(ws, { t: 'error', text: 'Nenhum mestre entrou na mesa ainda.' });
      pushChat({ type: 'whisper', from: user.name, color: colorFor(user.name), to, text: m[4].trim() });
    } else if (name === 'limpar' && user.role === 'gm') {
      state.chat.length = 0;
      store.save();
      for (const c of clients) if (c.user) send(c, { t: 'chatReset', chat: [] });
      system('O mestre limpou o chat.');
    } else {
      send(ws, { t: 'error', text: `Comando /${name} não existe. Digite /ajuda para ver a lista.` });
    }
  } catch (e) {
    if (e instanceof dice.DiceError) send(ws, { t: 'error', text: e.message });
    else throw e;
  }
}

// ---------- Tokens ----------
function cleanToken(src, user, base = {}) {
  const t = { ...base };
  if ('name' in src) t.name = str(src.name, 40) || 'Token';
  if ('x' in src) t.x = num(src.x, 0, -500, 1500);
  if ('y' in src) t.y = num(src.y, 0, -500, 1500);
  if ('size' in src) t.size = num(src.size, 1, 0.5, 10);
  if ('color' in src) t.color = /^#[0-9a-f]{6}$/i.test(src.color) ? src.color : '#8e44ad';
  if ('img' in src) t.img = typeof src.img === 'string' && /^\/uploads\/[\w.-]+$/.test(src.img) ? src.img : '';
  if ('hp' in src) t.hp = src.hp === '' || src.hp == null ? null : num(src.hp, 0, -9999, 9999);
  if ('hpMax' in src) t.hpMax = src.hpMax === '' || src.hpMax == null ? null : num(src.hpMax, 0, 0, 9999);
  if (user.role === 'gm') {
    if ('owner' in src) t.owner = str(src.owner, 24);
    if ('hidden' in src) t.hidden = !!src.hidden;
    if ('notes' in src) t.notes = str(src.notes, 2000);
  }
  return t;
}

function handleToken(ws, user, msg) {
  if (msg.t === 'token.add') {
    const t = cleanToken({ name: 'Token', x: 0, y: 0, size: 1, color: colorFor(user.name), img: '', hp: null, hpMax: null, ...msg.token }, user);
    t.id = id();
    if (user.role !== 'gm') { t.owner = user.name; t.hidden = false; }
    t.owner = t.owner || '';
    t.hidden = !!t.hidden;
    if (Object.keys(state.tokens).length >= 500) return send(ws, { t: 'error', text: 'Limite de 500 tokens.' });
    state.tokens[t.id] = t;
    store.save();
    broadcastBoard();
    return;
  }
  const tok = state.tokens[msg.id];
  if (!tok) return;
  if (!F.canEditToken(user, tok)) return send(ws, { t: 'error', text: 'Esse token não é seu.' });
  if (msg.t === 'token.move') {
    tok.x = num(msg.x, tok.x, -500, 1500);
    tok.y = num(msg.y, tok.y, -500, 1500);
    store.save();
    broadcastToken(tok);
  } else if (msg.t === 'token.update') {
    Object.assign(tok, cleanToken(msg.patch || {}, user));
    store.save();
    broadcastBoard();
  } else if (msg.t === 'token.delete') {
    delete state.tokens[tok.id];
    for (const c of state.combat.list) if (c.tokenId === tok.id) {
      c.tokenId = null; c.hp = tok.hp; c.hpMax = tok.hpMax;
    }
    store.save();
    broadcastBoard();
  }
}

// ---------- Combate / iniciativa ----------
const CONDICOES = ['atordoado', 'sangrando', 'queimando', 'envenenado', 'lento', 'caído', 'camuflado', 'amedrontado'];

function combatant(cid) {
  return state.combat.list.find((c) => c.id === cid);
}
function canControl(user, c) {
  return user.role === 'gm' || F.sameName(F.combatantHp(c, state.tokens).owner, user.name);
}
function rollInit(user, c) {
  const info = F.combatantHp(c, state.tokens);
  const mod = num(c.mod, 0);
  const expr = state.combat.formula + (mod ? (mod > 0 ? `+${mod}` : `${mod}`) : '');
  const r = dice.roll(expr);
  c.init = r.total;
  pushChat({
    type: 'roll', from: user.name, color: colorFor(user.name), roll: r,
    // Iniciativa de NPC fica só com o mestre (não entrega o modificador).
    label: `Iniciativa: ${c.name}`, gmOnly: info.hidden || !info.owner,
  });
}
function sortCombat() {
  const cur = state.combat.list[state.combat.turn];
  state.combat.list.sort((a, b) => (b.init ?? -999) - (a.init ?? -999) || (b.mod || 0) - (a.mod || 0));
  if (cur) state.combat.turn = Math.max(0, state.combat.list.indexOf(cur));
}
function setHp(c, value) {
  const tok = c.tokenId && state.tokens[c.tokenId];
  if (tok) tok.hp = value; else c.hp = value;
}

function handleCombat(ws, user, msg) {
  const cb = state.combat;
  const gm = user.role === 'gm';
  const deny = () => send(ws, { t: 'error', text: 'Só o mestre controla o combate.' });
  switch (msg.t) {
    case 'combat.add': {
      if (msg.tokenId) {
        const tok = state.tokens[msg.tokenId];
        if (!tok) return;
        if (!gm && !F.sameName(tok.owner, user.name)) return deny();
        if (cb.list.some((c) => c.tokenId === tok.id)) return send(ws, { t: 'error', text: `${tok.name} já está no combate.` });
        cb.list.push({ id: id(), name: tok.name, tokenId: tok.id, init: null, mod: num(msg.mod, 0, -50, 50), conditions: [] });
      } else {
        if (!gm) return deny();
        cb.list.push({
          id: id(), name: str(msg.name, 40) || 'Inimigo', tokenId: null, owner: '',
          hp: msg.hp === '' || msg.hp == null ? null : num(msg.hp, 0, -9999, 9999),
          hpMax: msg.hp === '' || msg.hp == null ? null : num(msg.hp, 0, 0, 9999),
          hidden: !!msg.hidden, init: null, mod: num(msg.mod, 0, -50, 50), conditions: [],
        });
      }
      break;
    }
    case 'combat.roll': {
      const targets = msg.id === 'npcs' ? cb.list.filter((c) => !F.combatantHp(c, state.tokens).owner && c.init == null)
        : msg.id === 'all' ? cb.list : [combatant(msg.id)].filter(Boolean);
      if ((msg.id === 'npcs' || msg.id === 'all') && !gm) return deny();
      for (const c of targets) {
        if (!canControl(user, c)) return deny();
        rollInit(user, c);
      }
      sortCombat();
      break;
    }
    case 'combat.set': {
      const c = combatant(msg.id);
      if (!c) return;
      if (!gm) return deny();
      if ('init' in msg) c.init = msg.init === '' || msg.init == null ? null : num(msg.init, 0, -99, 999);
      if ('mod' in msg) c.mod = num(msg.mod, 0, -50, 50);
      if ('name' in msg) c.name = str(msg.name, 40) || c.name;
      if ('hidden' in msg && !c.tokenId) c.hidden = !!msg.hidden;
      if ('init' in msg) sortCombat();
      break;
    }
    case 'combat.hp': {
      const c = combatant(msg.id);
      if (!c) return;
      if (!canControl(user, c)) return deny();
      const info = F.combatantHp(c, state.tokens);
      if (info.hp == null) return send(ws, { t: 'error', text: `${c.name} não tem vida definida.` });
      const delta = Math.trunc(num(msg.delta, 0, -9999, 9999));
      let hp = info.hp + delta;
      if (info.hpMax != null && delta > 0) hp = Math.min(hp, info.hpMax);
      setHp(c, hp);
      break;
    }
    case 'combat.cond': {
      const c = combatant(msg.id);
      if (!c) return;
      if (!canControl(user, c)) return deny();
      if (msg.remove != null) {
        c.conditions.splice(num(msg.remove, -1), 1);
      } else {
        const name = str(msg.name, 30).toLowerCase();
        if (!name) return;
        const rounds = msg.rounds === '' || msg.rounds == null ? null : num(msg.rounds, 1, 1, 99);
        const ex = c.conditions.find((k) => k.name === name);
        if (ex) ex.rounds = rounds; else c.conditions.push({ name, rounds });
      }
      break;
    }
    case 'combat.remove': {
      if (!gm) return deny();
      const i = cb.list.findIndex((c) => c.id === msg.id);
      if (i < 0) return;
      cb.list.splice(i, 1);
      if (cb.turn >= cb.list.length) cb.turn = 0;
      else if (i < cb.turn) cb.turn--;
      break;
    }
    case 'combat.next':
    case 'combat.prev': {
      if (!gm) return deny();
      if (!cb.list.length) return;
      if (cb.round === 0) {
        cb.round = 1; cb.turn = 0;
        system(`⚔️ O combate começou! Rodada 1.`);
      } else if (msg.t === 'combat.next') {
        cb.turn++;
        if (cb.turn >= cb.list.length) {
          cb.turn = 0;
          cb.round++;
          // Condições com contador perdem 1 rodada; as que zeram saem.
          const ended = [];
          for (const c of cb.list) {
            c.conditions = c.conditions.filter((k) => {
              if (k.rounds == null) return true;
              k.rounds--;
              if (k.rounds <= 0) { ended.push(`${c.name}: ${k.name}`); return false; }
              return true;
            });
          }
          system(`Rodada ${cb.round}.${ended.length ? ` Acabou: ${ended.join(', ')}.` : ''}`);
        }
      } else {
        cb.turn--;
        if (cb.turn < 0) {
          if (cb.round > 1) { cb.round--; cb.turn = cb.list.length - 1; } else cb.turn = 0;
        }
      }
      const cur = cb.list[cb.turn];
      if (cur && !F.combatantHp(cur, state.tokens).hidden) system(`É a vez de ${cur.name}.`, { turn: true });
      break;
    }
    case 'combat.end': {
      if (!gm) return deny();
      cb.list = []; cb.round = 0; cb.turn = 0;
      system('O combate terminou.');
      break;
    }
    case 'combat.formula': {
      if (!gm) return deny();
      const f = str(msg.formula, 40) || '1d20';
      dice.parse(f); // valida
      cb.formula = f;
      break;
    }
    default: return;
  }
  store.save();
  broadcastBoard();
}

// ---------- Cena ----------
function handleScene(ws, user, msg) {
  if (user.role !== 'gm') return send(ws, { t: 'error', text: 'Só o mestre muda a cena.' });
  const p = msg.patch || {};
  const s = state.scene;
  if ('name' in p) s.name = str(p.name, 60);
  if ('cols' in p) s.cols = Math.round(num(p.cols, 30, 1, 300));
  if ('rows' in p) s.rows = Math.round(num(p.rows, 20, 1, 300));
  if ('bg' in p) s.bg = typeof p.bg === 'string' && /^\/uploads\/[\w.-]+$/.test(p.bg) ? p.bg : '';
  if ('gridColor' in p) s.gridColor = /^#[0-9a-f]{6}$/i.test(p.gridColor) ? p.gridColor : '#000000';
  if ('gridOpacity' in p) s.gridOpacity = num(p.gridOpacity, 0.35, 0, 1);
  store.save();
  broadcastBoard();
}

// ---------- WebSocket ----------
wss.on('connection', (ws) => {
  ws.user = null;
  clients.add(ws);
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });

  ws.on('message', (raw) => {
    let msg;
    try { msg = JSON.parse(raw); } catch { return; }
    if (!msg || typeof msg.t !== 'string') return;

    if (msg.t === 'hello') {
      const user = verify(msg.token);
      if (!user) return send(ws, { t: 'auth', ok: false });
      const key = user.name.toLowerCase();
      const known = state.users[key];
      if (known && known.role !== user.role) return send(ws, { t: 'auth', ok: false });
      ws.user = user;
      if (!known) { state.users[key] = { name: user.name, role: user.role, color: colorFor(user.name) }; store.save(); }
      send(ws, {
        t: 'welcome',
        me: { ...user, color: colorFor(user.name) },
        board: F.filterBoard(state, user),
        chat: F.filterChat(state.chat, user),
        condicoes: CONDICOES,
        lan: user.role === 'gm' ? lanUrls() : undefined,
      });
      broadcastUsers();
      return;
    }
    const user = ws.user;
    if (!user) return;
    try {
      if (msg.t === 'chat') handleChat(ws, user, msg.text);
      else if (msg.t.startsWith('token.')) handleToken(ws, user, msg);
      else if (msg.t.startsWith('combat.')) handleCombat(ws, user, msg);
      else if (msg.t === 'scene.update') handleScene(ws, user, msg);
      else if (msg.t === 'ping') {
        const p = { t: 'ping', x: num(msg.x), y: num(msg.y), from: user.name, color: colorFor(user.name) };
        for (const c of clients) if (c.user) send(c, p);
      }
    } catch (e) {
      if (e instanceof dice.DiceError) send(ws, { t: 'error', text: e.message });
      else { console.error(e); send(ws, { t: 'error', text: 'Erro no servidor: ' + e.message }); }
    }
  });

  ws.on('close', () => {
    clients.delete(ws);
    broadcastUsers();
  });
});

// Derruba conexões mortas (celular que fechou a aba sem avisar, etc.)
setInterval(() => {
  for (const ws of clients) {
    if (!ws.isAlive) { ws.terminate(); continue; }
    ws.isAlive = false;
    ws.ping();
  }
}, 30000).unref();

function lanUrls() {
  const out = [];
  for (const list of Object.values(os.networkInterfaces())) {
    for (const a of list || []) {
      if (a.family === 'IPv4' && !a.internal) out.push(`http://${a.address}:${PORT}`);
    }
  }
  return out;
}

server.listen(PORT, '0.0.0.0', () => {
  const urls = lanUrls();
  const line = '='.repeat(60);
  console.log(`\n${line}\n  ESTREMERE VTT — a mesa está aberta!\n${line}`);
  console.log(`\n  Você (neste PC):      http://localhost:${PORT}`);
  if (urls.length) {
    console.log('  Jogadores na sua rede (Wi-Fi/cabo):');
    for (const u of urls) console.log(`                        ${u}`);
  } else {
    console.log('  (Não achei o IP da rede local. Confira se o PC está conectado.)');
  }
  console.log(`\n  Senha do MESTRE:     ${config.senhaMestre}`);
  console.log(`  Senha dos JOGADORES: ${config.senhaJogadores}`);
  console.log(`  (Para trocar, edite data/config.json e reinicie.)\n${line}\n`);
});

function shutdown() {
  store.flush();
  process.exit(0);
}
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
