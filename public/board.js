// Mapa em canvas: grade, fundo, tokens, zoom, arrastar, régua e ping.
import { S, send, canEditToken } from './app.js';

const CELL = 64; // pixels por casa no zoom 1

export function createBoard(canvas, { onSelect }) {
  const ctx = canvas.getContext('2d');
  const wrap = canvas.parentElement;
  const distEl = document.getElementById('dist');
  const cam = { x: 0, y: 0, z: 1 }; // x,y = canto superior esquerdo em px do mundo
  let board = { scene: {}, tokens: {} };
  let tool = 'move';
  let selected = null;
  let bgImg = null;
  let bgSrc = '';
  const imgCache = new Map();
  const pings = [];
  let drag = null;   // { kind: 'token'|'pan'|'ruler'|'pinch', ... }
  const pointers = new Map();
  let dirty = true;
  let fitted = false;
  let lastSend = 0;

  // ---------- Coordenadas ----------
  const toWorld = (sx, sy) => ({ x: (sx / cam.z + cam.x) / CELL, y: (sy / cam.z + cam.y) / CELL });
  const toScreen = (wx, wy) => ({ x: (wx * CELL - cam.x) * cam.z, y: (wy * CELL - cam.y) * cam.z });
  function localPos(e) {
    const r = canvas.getBoundingClientRect();
    return { x: e.clientX - r.left, y: e.clientY - r.top };
  }

  function resize() {
    const dpr = window.devicePixelRatio || 1;
    const r = wrap.getBoundingClientRect();
    canvas.width = Math.max(1, Math.round(r.width * dpr));
    canvas.height = Math.max(1, Math.round(r.height * dpr));
    dirty = true;
  }
  new ResizeObserver(() => { resize(); if (!fitted) fit(); }).observe(wrap);

  function fit() {
    const r = wrap.getBoundingClientRect();
    if (!r.width || !r.height) return;
    const cols = board.scene.cols || 30;
    const rows = board.scene.rows || 20;
    let z = Math.min(r.width / (cols * CELL), r.height / (rows * CELL)) * 0.95;
    if (r.width < 600) z = Math.max(z, 0.45); // no celular, prefere tokens legíveis

    cam.z = Math.min(2, Math.max(0.15, z));
    cam.x = (cols * CELL) / 2 - r.width / 2 / cam.z;
    cam.y = (rows * CELL) / 2 - r.height / 2 / cam.z;
    fitted = true;
    dirty = true;
  }

  function image(src) {
    if (!src) return null;
    let img = imgCache.get(src);
    if (!img) {
      img = new Image();
      img.onload = () => { dirty = true; };
      img.src = src;
      imgCache.set(src, img);
    }
    return img.complete && img.naturalWidth ? img : null;
  }

  // ---------- Desenho ----------
  function draw() {
    const dpr = window.devicePixelRatio || 1;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.fillStyle = '#0d0a08';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.setTransform(dpr * cam.z, 0, 0, dpr * cam.z, -cam.x * cam.z * dpr, -cam.y * cam.z * dpr);

    const s = board.scene;
    const W = (s.cols || 30) * CELL;
    const H = (s.rows || 20) * CELL;

    // Fundo
    ctx.fillStyle = '#2a2219';
    ctx.fillRect(0, 0, W, H);
    if (s.bg !== bgSrc) { bgSrc = s.bg; bgImg = null; }
    if (bgSrc) bgImg = image(bgSrc);
    if (bgImg) ctx.drawImage(bgImg, 0, 0, W, H);

    // Grade
    ctx.globalAlpha = s.gridOpacity ?? 0.35;
    ctx.strokeStyle = s.gridColor || '#000';
    ctx.lineWidth = 1 / cam.z;
    ctx.beginPath();
    for (let x = 0; x <= W; x += CELL) { ctx.moveTo(x, 0); ctx.lineTo(x, H); }
    for (let y = 0; y <= H; y += CELL) { ctx.moveTo(0, y); ctx.lineTo(W, y); }
    ctx.stroke();
    ctx.globalAlpha = 1;
    ctx.strokeStyle = '#d4a94a55';
    ctx.lineWidth = 2 / cam.z;
    ctx.strokeRect(0, 0, W, H);

    // Tokens (menores por cima)
    const combat = board.combat || { list: [] };
    const turnTok = combat.turnId ? (combat.list.find((c) => c.id === combat.turnId) || {}).tokenId : null;
    const list = Object.values(board.tokens).sort((a, b) => (b.size || 1) - (a.size || 1));
    for (const t of list) drawToken(t, t.id === selected, t.id === turnTok);

    // Régua
    if (drag && drag.kind === 'ruler') {
      const a = drag.from, b = drag.to;
      ctx.strokeStyle = '#f0cc73';
      ctx.lineWidth = 3 / cam.z;
      ctx.setLineDash([10 / cam.z, 6 / cam.z]);
      ctx.beginPath();
      ctx.moveTo(a.x * CELL, a.y * CELL);
      ctx.lineTo(b.x * CELL, b.y * CELL);
      ctx.stroke();
      ctx.setLineDash([]);
    }

    // Pings
    const now = performance.now();
    for (let i = pings.length - 1; i >= 0; i--) {
      const p = pings[i];
      const k = (now - p.t) / 1600;
      if (k >= 1) { pings.splice(i, 1); continue; }
      for (let j = 0; j < 3; j++) {
        const kk = (k + j * 0.18) % 1;
        ctx.strokeStyle = p.color;
        ctx.globalAlpha = 1 - kk;
        ctx.lineWidth = 4 / cam.z;
        ctx.beginPath();
        ctx.arc(p.x * CELL, p.y * CELL, (8 + kk * 60) / Math.sqrt(cam.z), 0, Math.PI * 2);
        ctx.stroke();
      }
      ctx.globalAlpha = 1;
      ctx.fillStyle = '#fff';
      ctx.font = `bold ${13 / cam.z}px system-ui`;
      ctx.textAlign = 'center';
      ctx.fillText(p.from, p.x * CELL, p.y * CELL - 30 / cam.z);
    }
    if (pings.length) dirty = true;
  }

  function drawToken(t, isSel, isTurn) {
    const size = (t.size || 1) * CELL;
    const x = t.x * CELL, y = t.y * CELL;
    const cx = x + size / 2, cy = y + size / 2, r = size / 2 - 3;
    ctx.save();
    if (t.hidden) ctx.globalAlpha = 0.5;

    if (isTurn) {
      ctx.shadowColor = '#f0cc73';
      ctx.shadowBlur = 18;
    }
    ctx.beginPath();
    ctx.arc(cx, cy, r, 0, Math.PI * 2);
    ctx.fillStyle = t.color || '#8e44ad';
    ctx.fill();
    ctx.shadowBlur = 0;
    const img = image(t.img);
    if (img) {
      ctx.save();
      ctx.clip();
      const k = Math.max((r * 2) / img.naturalWidth, (r * 2) / img.naturalHeight);
      const w = img.naturalWidth * k, h = img.naturalHeight * k;
      ctx.drawImage(img, cx - w / 2, cy - h / 2, w, h);
      ctx.restore();
    } else {
      ctx.fillStyle = '#fff';
      ctx.font = `bold ${Math.round(r)}px Georgia, serif`;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText((t.name || '?').trim().charAt(0).toUpperCase(), cx, cy + 1);
    }
    ctx.lineWidth = isSel ? 4 : 2;
    ctx.strokeStyle = isSel ? '#f0cc73' : (isTurn ? '#d4a94a' : '#000a');
    ctx.beginPath();
    ctx.arc(cx, cy, r, 0, Math.PI * 2);
    ctx.stroke();

    // Barra de vida (só quando a pessoa pode ver o número)
    if (typeof t.hp === 'number' && typeof t.hpMax === 'number' && t.hpMax > 0) {
      const k = Math.max(0, Math.min(1, t.hp / t.hpMax));
      const bw = size * 0.8, bh = Math.max(5, size * 0.08);
      const bx = x + size * 0.1, by = y + size - bh;
      ctx.fillStyle = '#000c';
      ctx.fillRect(bx - 1, by - 1, bw + 2, bh + 2);
      ctx.fillStyle = k > 0.5 ? '#6aa84f' : k > 0.25 ? '#d4a020' : '#c0533f';
      ctx.fillRect(bx, by, bw * k, bh);
    }

    // Nome
    const fs = Math.max(11, 12 / cam.z);
    ctx.font = `600 ${fs}px system-ui, sans-serif`;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'top';
    const label = t.hidden ? `👁‍🗨 ${t.name}` : (t.hpStatus && t.hpStatus !== 'ileso' ? `${t.name} (${t.hpStatus})` : t.name);
    const tw = ctx.measureText(label).width;
    ctx.fillStyle = '#000b';
    ctx.fillRect(cx - tw / 2 - 4, y + size + 2, tw + 8, fs + 4);
    ctx.fillStyle = '#f3e7cf';
    ctx.fillText(label, cx, y + size + 4);
    ctx.restore();
  }

  function loop() {
    if (dirty) { dirty = false; draw(); }
    requestAnimationFrame(loop);
  }
  requestAnimationFrame(loop);

  // ---------- Interação ----------
  function tokenAt(w) {
    const list = Object.values(board.tokens).sort((a, b) => (a.size || 1) - (b.size || 1));
    for (const t of list) {
      const s = t.size || 1;
      const dx = w.x - (t.x + s / 2), dy = w.y - (t.y + s / 2);
      if (dx * dx + dy * dy <= (s / 2) ** 2) return t;
    }
    return null;
  }

  function showDist(sx, sy, text) {
    distEl.textContent = text;
    distEl.style.left = `${sx}px`;
    distEl.style.top = `${sy}px`;
    distEl.classList.remove('hidden');
  }
  // Distância em casas (diagonal conta como 1 casa, igual na mesa física).
  const cells = (a, b) => Math.max(Math.abs(a.x - b.x), Math.abs(a.y - b.y));
  const fmtCells = (n) => {
    const v = Math.round(n * 10) / 10;
    return `${String(v).replace('.', ',')} ${v === 1 ? 'casa' : 'casas'}`;
  };

  canvas.addEventListener('pointerdown', (e) => {
    canvas.setPointerCapture(e.pointerId);
    const p = localPos(e);
    pointers.set(e.pointerId, p);
    if (pointers.size === 2) {
      const [a, b] = [...pointers.values()];
      drag = { kind: 'pinch', d0: Math.hypot(a.x - b.x, a.y - b.y), z0: cam.z, cam0: { ...cam }, mid: { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 } };
      distEl.classList.add('hidden');
      return;
    }
    const w = toWorld(p.x, p.y);
    if (e.button === 1 || e.button === 2) {
      drag = { kind: 'pan', sx: p.x, sy: p.y, cam0: { ...cam } };
      return;
    }
    if (tool === 'ruler') {
      drag = { kind: 'ruler', from: w, to: w };
      return;
    }
    const t = tokenAt(w);
    if (t) {
      if (t.id !== selected) onSelect(t.id);
      if (canEditToken(t)) {
        drag = { kind: 'token', id: t.id, off: { x: w.x - t.x, y: w.y - t.y }, start: { x: t.x, y: t.y }, moved: false };
        canvas.classList.add('dragging');
      }
      return;
    }
    drag = { kind: 'pan', sx: p.x, sy: p.y, cam0: { ...cam }, click: true };
    canvas.classList.add('dragging');
  });

  canvas.addEventListener('pointermove', (e) => {
    if (!pointers.has(e.pointerId)) return;
    const p = localPos(e);
    pointers.set(e.pointerId, p);
    if (!drag) return;
    if (drag.kind === 'pinch' && pointers.size >= 2) {
      const [a, b] = [...pointers.values()];
      const d = Math.hypot(a.x - b.x, a.y - b.y);
      const z = Math.min(4, Math.max(0.15, drag.z0 * d / drag.d0));
      const mid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
      const wx = drag.mid.x / drag.cam0.z + drag.cam0.x;
      const wy = drag.mid.y / drag.cam0.z + drag.cam0.y;
      cam.z = z;
      cam.x = wx - mid.x / z;
      cam.y = wy - mid.y / z;
      dirty = true;
    } else if (drag.kind === 'pan') {
      if (Math.abs(p.x - drag.sx) + Math.abs(p.y - drag.sy) > 4) drag.click = false;
      cam.x = drag.cam0.x - (p.x - drag.sx) / cam.z;
      cam.y = drag.cam0.y - (p.y - drag.sy) / cam.z;
      dirty = true;
    } else if (drag.kind === 'ruler') {
      drag.to = toWorld(p.x, p.y);
      const a = { x: Math.floor(drag.from.x), y: Math.floor(drag.from.y) };
      const b = { x: Math.floor(drag.to.x), y: Math.floor(drag.to.y) };
      showDist(p.x, p.y, fmtCells(cells(a, b)));
      dirty = true;
    } else if (drag.kind === 'token') {
      const t = board.tokens[drag.id];
      if (!t) { drag = null; return; }
      const w = toWorld(p.x, p.y);
      let nx = w.x - drag.off.x, ny = w.y - drag.off.y;
      if (!e.shiftKey) { nx = Math.round(nx); ny = Math.round(ny); }
      if (nx !== t.x || ny !== t.y) {
        t.x = nx; t.y = ny; drag.moved = true; dirty = true;
        const now = performance.now();
        if (now - lastSend > 60) { lastSend = now; send({ t: 'token.move', id: t.id, x: nx, y: ny }); }
      }
      showDist(p.x, p.y, fmtCells(cells(drag.start, t)));
    }
  });

  function endPointer(e) {
    pointers.delete(e.pointerId);
    canvas.classList.remove('dragging');
    if (!drag) return;
    if (drag.kind === 'pinch') { if (pointers.size === 0) drag = null; return; }
    if (drag.kind === 'token') {
      const t = board.tokens[drag.id];
      if (t && drag.moved) send({ t: 'token.move', id: t.id, x: t.x, y: t.y });
    } else if (drag.kind === 'pan' && drag.click) {
      onSelect(null);
    }
    drag = null;
    distEl.classList.add('hidden');
    dirty = true;
  }
  canvas.addEventListener('pointerup', endPointer);
  canvas.addEventListener('pointercancel', endPointer);
  canvas.addEventListener('contextmenu', (e) => e.preventDefault());

  canvas.addEventListener('wheel', (e) => {
    e.preventDefault();
    const p = localPos(e);
    const before = { x: p.x / cam.z + cam.x, y: p.y / cam.z + cam.y };
    const factor = Math.exp(-e.deltaY * (e.ctrlKey ? 0.01 : 0.0015));
    cam.z = Math.min(4, Math.max(0.15, cam.z * factor));
    cam.x = before.x - p.x / cam.z;
    cam.y = before.y - p.y / cam.z;
    dirty = true;
  }, { passive: false });

  canvas.addEventListener('dblclick', (e) => {
    const p = localPos(e);
    const w = toWorld(p.x, p.y);
    send({ t: 'ping', x: w.x, y: w.y });
  });

  resize();

  return {
    setBoard(b) {
      const first = !board.scene.cols;
      const keepDrag = drag && drag.kind === 'token' ? board.tokens[drag.id] : null;
      board = b;
      // Não deixa uma atualização do servidor "puxar" o token que estou arrastando.
      if (keepDrag && board.tokens[keepDrag.id]) Object.assign(board.tokens[keepDrag.id], { x: keepDrag.x, y: keepDrag.y });
      if (first) fit();
      dirty = true;
    },
    updateToken(t) {
      if (drag && drag.kind === 'token' && drag.id === t.id) return;
      board.tokens[t.id] = t;
      dirty = true;
    },
    setSelected(id) { selected = id; dirty = true; },
    setTool(t) { tool = t; canvas.classList.toggle('ruler', t === 'ruler'); },
    ping(x, y, color, from) { pings.push({ x, y, color, from, t: performance.now() }); dirty = true; },
    centerCell() {
      const r = wrap.getBoundingClientRect();
      const w = toWorld(r.width / 2, r.height / 2);
      return { x: Math.floor(w.x), y: Math.floor(w.y) };
    },
    focus(id) {
      const t = board.tokens[id];
      if (!t) return;
      const r = wrap.getBoundingClientRect();
      const s = t.size || 1;
      cam.x = (t.x + s / 2) * CELL - r.width / 2 / cam.z;
      cam.y = (t.y + s / 2) * CELL - r.height / 2 / cam.z;
      dirty = true;
    },
    fit,
    toScreen,
  };
}
