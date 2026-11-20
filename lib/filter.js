'use strict';
// Filtro de permissões: o servidor é a fonte da verdade e cada pessoa só
// recebe o que pode ver. Tudo que vai para um jogador passa por aqui.

function sameName(a, b) {
  return !!a && !!b && String(a).toLowerCase() === String(b).toLowerCase();
}

function hpStatus(hp, max) {
  if (typeof hp !== 'number' || typeof max !== 'number' || max <= 0) return null;
  if (hp <= 0) return 'caído';
  if (hp >= max) return 'ileso';
  if (hp > max / 2) return 'ferido';
  return 'muito ferido';
}

// Personagem de jogador (tem dono): a vida aparece para todo mundo.
// NPC/inimigo (sem dono): o jogador só vê o estado.
function canSeeHp(user, owner) {
  return user.role === 'gm' || !!owner;
}

function canEditToken(user, token) {
  return user.role === 'gm' || sameName(token.owner, user.name);
}

function filterToken(token, user) {
  if (user.role === 'gm') return { ...token };
  if (token.hidden) return null;
  const out = { ...token };
  if (!canSeeHp(user, token.owner)) {
    out.hpStatus = hpStatus(token.hp, token.hpMax);
    delete out.hp;
    delete out.hpMax;
  }
  delete out.notes; // anotações do mestre
  return out;
}

function filterTokens(tokens, user) {
  const out = {};
  for (const id of Object.keys(tokens || {})) {
    const t = filterToken(tokens[id], user);
    if (t) out[id] = t;
  }
  return out;
}

// Vida de um combatente: se estiver vinculado a um token, a vida é a do token.
function combatantHp(c, tokens) {
  const tok = c.tokenId && tokens ? tokens[c.tokenId] : null;
  if (tok) return { hp: tok.hp, hpMax: tok.hpMax, owner: tok.owner || c.owner, hidden: !!(tok.hidden || c.hidden) };
  return { hp: c.hp, hpMax: c.hpMax, owner: c.owner, hidden: !!c.hidden };
}

function filterCombat(combat, tokens, user) {
  const list = [];
  let turnId = combat.list[combat.turn] ? combat.list[combat.turn].id : null;
  for (const c of combat.list) {
    const info = combatantHp(c, tokens);
    if (user.role !== 'gm' && info.hidden) continue;
    const out = { ...c, hp: info.hp, hpMax: info.hpMax, owner: info.owner, hidden: info.hidden };
    if (!canSeeHp(user, info.owner)) {
      out.hpStatus = hpStatus(info.hp, info.hpMax);
      delete out.hp;
      delete out.hpMax;
    }
    if (user.role !== 'gm') delete out.mod; // modificador de NPC não vaza
    list.push(out);
  }
  // Se o turno atual é de alguém oculto, o jogador não vê de quem é.
  if (user.role !== 'gm' && !list.some((c) => c.id === turnId)) turnId = null;
  return { round: combat.round, turnId, formula: combat.formula, list };
}

function canSeeMessage(msg, user) {
  if (user.role === 'gm') {
    // O mestre vê tudo, menos sussurros entre jogadores que não o incluem.
    if (msg.to && msg.to.length && !msg.gmSees) return sameName(msg.from, user.name) || msg.to.some((n) => sameName(n, user.name));
    return true;
  }
  if (msg.gmOnly) return sameName(msg.from, user.name);
  if (msg.to && msg.to.length) return sameName(msg.from, user.name) || msg.to.some((n) => sameName(n, user.name));
  return true;
}

function filterChat(chat, user) {
  return chat.filter((m) => canSeeMessage(m, user));
}

function filterBoard(state, user) {
  return {
    scene: { ...state.scene },
    tokens: filterTokens(state.tokens, user),
    combat: filterCombat(state.combat, state.tokens, user),
  };
}

module.exports = {
  sameName, hpStatus, canEditToken, filterToken, filterTokens, filterCombat,
  canSeeMessage, filterChat, filterBoard, combatantHp,
};
