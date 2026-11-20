'use strict';
// Rolador de dados. Toda rolagem acontece no servidor.
// Suporta: NdM (qualquer M de 2 a 1000, inclusive d2, d3, d5), somas e
// subtrações, números fixos, kh/kl (manter maiores/menores),
// "vant"/"desv" (vantagem/desvantagem num d20) e d% (=d100).

const crypto = require('crypto');

const MAX_DICE = 100;
const MAX_SIDES = 1000;

class DiceError extends Error {}

function randomDie(sides, rng) {
  return rng ? rng(sides) : crypto.randomInt(1, sides + 1);
}

// Converte o texto numa lista de termos { sign, kind: 'dice'|'num', ... }
function parse(expr) {
  if (typeof expr !== 'string') throw new DiceError('Expressão vazia.');
  let src = expr.toLowerCase().replace(/\s+/g, ' ').trim();
  if (!src) throw new DiceError('Expressão vazia.');

  // Vantagem/desvantagem por palavra-chave: "1d20+3 vant" -> 2d20kh1+3
  let mode = null;
  src = src.replace(/\b(vant(agem)?|adv)\b/g, () => { mode = 'kh'; return ''; });
  src = src.replace(/\b(desv(antagem)?|dis)\b/g, () => { mode = 'kl'; return ''; });
  src = src.replace(/\s*([+-])\s*/g, '$1').trim();
  if (!src) throw new DiceError('Expressão vazia.');
  if (/\s/.test(src)) throw new DiceError(`Não entendi "${src}". Separe os termos com + ou -. Exemplo: 1d20+2.`);

  const terms = [];
  const re = /([+-]?)(?:(\d*)d(\d+|%)(?:(kh|kl)(\d*))?|(\d+))/y;
  let pos = 0;
  while (pos < src.length) {
    re.lastIndex = pos;
    const m = re.exec(src);
    if (!m || m[0] === '' || (terms.length > 0 && !m[1])) {
      throw new DiceError(`Não entendi "${src.slice(pos)}". Exemplo: 1d20+2, 2d6-1, 2d20kh1, 1d20 vant.`);
    }
    const sign = m[1] === '-' ? -1 : 1;
    if (m[6] !== undefined) {
      terms.push({ sign, kind: 'num', value: parseInt(m[6], 10) });
    } else {
      const n = m[2] === '' ? 1 : parseInt(m[2], 10);
      const sides = m[3] === '%' ? 100 : parseInt(m[3], 10);
      let keep = null;
      if (m[4]) keep = { mode: m[4], count: m[5] === '' ? 1 : parseInt(m[5], 10) };
      if (n < 1 || n > MAX_DICE) throw new DiceError(`Quantidade de dados deve ser de 1 a ${MAX_DICE}.`);
      if (sides < 2 || sides > MAX_SIDES) throw new DiceError(`O dado precisa ter de 2 a ${MAX_SIDES} lados.`);
      if (keep && (keep.count < 1 || keep.count > n)) throw new DiceError('O kh/kl precisa manter entre 1 e a quantidade de dados.');
      terms.push({ sign, kind: 'dice', n, sides, keep });
    }
    pos = re.lastIndex;
  }

  if (mode) {
    // Aplica no primeiro d20 sem kh/kl
    const t = terms.find((x) => x.kind === 'dice' && x.sides === 20 && x.n === 1 && !x.keep);
    if (!t) throw new DiceError('Vantagem/desvantagem precisa de um 1d20 na expressão.');
    t.n = 2;
    t.keep = { mode, count: 1 };
  }
  if (terms.filter((t) => t.kind === 'dice').reduce((a, t) => a + t.n, 0) > MAX_DICE) {
    throw new DiceError(`No máximo ${MAX_DICE} dados por rolagem.`);
  }
  return terms;
}

function formatTerms(terms) {
  return terms.map((t, i) => {
    const s = t.sign < 0 ? '-' : (i > 0 ? '+' : '');
    if (t.kind === 'num') return s + t.value;
    return `${s}${t.n}d${t.sides}${t.keep ? t.keep.mode + t.keep.count : ''}`;
  }).join('');
}

// rng opcional (para testes): (sides) => inteiro 1..sides
function roll(expr, rng) {
  const terms = parse(expr);
  let total = 0;
  let nat20 = false;
  let nat1 = false;
  let damageDice = 0;
  let damageAtMax = 0;
  const parts = terms.map((t) => {
    if (t.kind === 'num') {
      total += t.sign * t.value;
      return { sign: t.sign, kind: 'num', value: t.value };
    }
    const rolls = [];
    for (let i = 0; i < t.n; i++) rolls.push({ v: randomDie(t.sides, rng), dropped: false });
    if (t.keep) {
      const order = rolls.map((r, i) => i).sort((a, b) => (t.keep.mode === 'kh' ? rolls[b].v - rolls[a].v : rolls[a].v - rolls[b].v));
      order.slice(t.keep.count).forEach((i) => { rolls[i].dropped = true; });
    }
    const kept = rolls.filter((r) => !r.dropped);
    const subtotal = kept.reduce((a, r) => a + r.v, 0);
    total += t.sign * subtotal;
    if (t.sides === 20) {
      if (kept.some((r) => r.v === 20)) nat20 = true;
      if (kept.some((r) => r.v === 1)) nat1 = true;
    } else if (t.sign > 0) {
      damageDice += kept.length;
      damageAtMax += kept.filter((r) => r.v === t.sides).length;
    }
    return { sign: t.sign, kind: 'dice', n: t.n, sides: t.sides, keep: t.keep, rolls, subtotal };
  });
  return {
    expr: formatTerms(terms),
    total,
    parts,
    nat20,
    nat1,
    // "Dano máximo": todos os dados (fora d20) no valor máximo.
    maxDamage: damageDice > 0 && damageAtMax === damageDice,
  };
}

module.exports = { roll, parse, formatTerms, DiceError };
