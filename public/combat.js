// Tabela de iniciativa / combate.
import { S, send, esc, isGM, sameName, toast, board, selectToken } from './app.js';

const $ = (s, el = document) => el.querySelector(s);

function canControl(c) {
  return isGM() || sameName(c.owner, S.me.name);
}

function hpHtml(c) {
  if (typeof c.hp === 'number' && typeof c.hpMax === 'number' && c.hpMax > 0) {
    const k = Math.max(0, Math.min(1, c.hp / c.hpMax));
    const cls = k > 0.5 ? '' : k > 0.25 ? 'mid' : 'low';
    return `<span class="hpbar"><i class="${cls}" style="width:${k * 100}%"></i></span><span>${c.hp}/${c.hpMax}</span>`;
  }
  if (typeof c.hp === 'number') return `<span>Vida ${c.hp}</span>`;
  if (c.hpStatus) return `<span class="status ${esc(c.hpStatus.replace(' ', '-'))}">${esc(c.hpStatus)}</span>`;
  return '<span class="status">vida não definida</span>';
}

function rowHtml(c, current) {
  const ctl = canControl(c);
  const gm = isGM();
  const tok = c.tokenId ? S.board.tokens[c.tokenId] : null;
  const color = tok ? tok.color : '#777';
  const init = gm
    ? `<input type="number" data-act="init" value="${c.init ?? ''}" title="Iniciativa" aria-label="Iniciativa de ${esc(c.name)}">`
    : (c.init ?? '—');
  const conds = c.conditions.map((k, i) =>
    `<span class="cond" ${ctl ? `data-act="cond-rm" data-i="${i}" title="Clique para tirar"` : ''}>${esc(k.name)}${k.rounds != null ? ` (${k.rounds})` : ''}</span>`).join('');
  const condAdd = ctl ? `<span class="cond-add">
      <select data-act="cond-name"><option value="">+ condição</option>${S.condicoes.map((n) => `<option>${esc(n)}</option>`).join('')}</select>
      <input type="number" min="1" max="99" placeholder="rod." data-act="cond-rounds" title="Rodadas (vazio = até tirar)">
    </span>` : '';
  const hpctl = ctl && typeof c.hp === 'number' ? `<span class="hpctl">
      <input type="number" min="0" value="" placeholder="qtd" data-act="amt" aria-label="Quantidade">
      <button class="dmg" data-act="dmg" title="Dano">−</button>
      <button class="heal" data-act="heal" title="Cura">+</button>
    </span>` : '';
  return `<li class="cb ${current ? 'current' : ''} ${c.hidden ? 'hidden-entry' : ''}" data-id="${esc(c.id)}">
    <div class="init">${init}</div>
    <div class="name" data-act="focus" title="${c.tokenId ? 'Mostrar no mapa' : ''}"><span class="dot" style="background:${esc(color)}"></span>${c.hidden ? '👁‍🗨 ' : ''}${esc(c.name)}${gm && c.mod ? ` <small>(${c.mod > 0 ? '+' : ''}${c.mod})</small>` : ''}</div>
    <div class="acts">
      ${ctl ? '<button data-act="roll" title="Rolar iniciativa">🎲</button>' : ''}
      ${gm ? '<button data-act="remove" title="Tirar do combate">×</button>' : ''}
    </div>
    <div class="hpline">${hpHtml(c)}${hpctl}</div>
    ${conds || condAdd ? `<div class="conds">${conds}${condAdd}</div>` : ''}
  </li>`;
}

let pending = false;
export function renderCombat() {
  const list = $('#cb-list');
  // Não redesenha enquanto a pessoa digita dentro da lista.
  if (list.contains(document.activeElement) && document.activeElement.tagName === 'INPUT') { pending = true; return; }
  pending = false;
  const cb = S.board.combat;
  list.innerHTML = cb.list.map((c) => rowHtml(c, c.id === cb.turnId)).join('');
  $('#cb-empty').classList.toggle('hidden', cb.list.length > 0);
  $('#round-num').textContent = cb.round || '—';
  const badge = $('#round-badge');
  badge.textContent = cb.round ? `R${cb.round}` : '';
  badge.classList.toggle('hidden', !cb.round);
  const f = $('#cb-formula');
  if (document.activeElement !== f) f.value = cb.formula || '1d20';
  $('#cb-next').textContent = cb.round ? 'Próximo ▶' : 'Começar ▶';
  const cur = list.querySelector('.current');
  if (cur) cur.scrollIntoView({ block: 'nearest' });
}

export function setupCombat() {
  const list = $('#cb-list');
  list.addEventListener('focusout', () => setTimeout(() => { if (pending) renderCombat(); }, 0));

  list.addEventListener('click', (e) => {
    const el = e.target.closest('[data-act]');
    if (!el) return;
    const id = el.closest('.cb').dataset.id;
    const c = S.board.combat.list.find((x) => x.id === id);
    const act = el.dataset.act;
    if (act === 'roll') send({ t: 'combat.roll', id });
    else if (act === 'remove') send({ t: 'combat.remove', id });
    else if (act === 'cond-rm') send({ t: 'combat.cond', id, remove: Number(el.dataset.i) });
    else if (act === 'dmg' || act === 'heal') {
      const amtEl = el.closest('.hpctl').querySelector('[data-act=amt]');
      const amt = Math.abs(parseInt(amtEl.value, 10));
      if (!amt) { amtEl.focus(); return toast('Digite quanto de dano ou cura.'); }
      amtEl.value = '';
      amtEl.blur();
      send({ t: 'combat.hp', id, delta: act === 'dmg' ? -amt : amt });
    } else if (act === 'focus' && c && c.tokenId && S.board.tokens[c.tokenId]) {
      board.focus(c.tokenId);
      selectToken(c.tokenId);
    }
  });

  list.addEventListener('change', (e) => {
    const el = e.target;
    const row = el.closest('.cb');
    if (!row) return;
    const id = row.dataset.id;
    if (el.dataset.act === 'init') send({ t: 'combat.set', id, init: el.value });
    if (el.dataset.act === 'cond-name' && el.value) {
      const rounds = row.querySelector('[data-act=cond-rounds]').value;
      send({ t: 'combat.cond', id, name: el.value, rounds });
      el.value = '';
    }
  });
  list.addEventListener('keydown', (e) => {
    if (e.key !== 'Enter') return;
    const el = e.target;
    if (el.dataset.act === 'amt') {
      // Enter = dano; Shift+Enter = cura
      el.closest('.hpctl').querySelector(e.shiftKey ? '.heal' : '.dmg').click();
    } else if (el.dataset.act === 'init') el.blur();
  });

  $('#cb-next').addEventListener('click', () => send({ t: 'combat.next' }));
  $('#cb-prev').addEventListener('click', () => send({ t: 'combat.prev' }));
  $('#cb-roll-npcs').addEventListener('click', () => send({ t: 'combat.roll', id: 'npcs' }));
  $('#cb-end').addEventListener('click', () => {
    const b = $('#cb-end');
    if (b.dataset.confirm) { send({ t: 'combat.end' }); delete b.dataset.confirm; b.textContent = 'Encerrar'; return; }
    b.dataset.confirm = '1';
    b.textContent = 'Certeza? Clique de novo';
    setTimeout(() => { delete b.dataset.confirm; b.textContent = 'Encerrar'; }, 3000);
  });
  $('#cb-formula').addEventListener('change', (e) => send({ t: 'combat.formula', formula: e.target.value }));
  const addSelected = () => {
    if (!S.selected) return toast('Clique num token do mapa primeiro.');
    send({ t: 'combat.add', tokenId: S.selected });
  };
  $('#cb-add-sel').addEventListener('click', addSelected);
  $('#cb-join').addEventListener('click', addSelected);
  $('#cb-add').addEventListener('submit', (e) => {
    e.preventDefault();
    const f = e.target.elements;
    send({ t: 'combat.add', name: f.name.value, hp: f.hp.value, mod: f.mod.value, hidden: f.hidden.checked });
    e.target.reset();
    f.name.focus();
  });
}
