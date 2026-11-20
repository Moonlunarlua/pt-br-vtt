'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { roll, parse, DiceError } = require('../lib/dice');

// rng que devolve uma sequência fixa
const seq = (...vals) => { let i = 0; return () => vals[i++]; };

test('soma e subtrai dados e números', () => {
  const r = roll('2d6+3-1d4', seq(4, 5, 2));
  assert.strictEqual(r.total, 4 + 5 + 3 - 2);
  assert.strictEqual(r.expr, '2d6+3-1d4');
});

test('dados incomuns do Estremere: d2, d3, d5', () => {
  for (const s of [2, 3, 5]) {
    for (let i = 0; i < 200; i++) {
      const r = roll(`1d${s}`);
      assert.ok(r.total >= 1 && r.total <= s, `d${s} deu ${r.total}`);
    }
  }
  assert.strictEqual(roll('2d5', seq(5, 5)).total, 10);
});

test('kh e kl descartam os dados certos', () => {
  const kh = roll('4d6kh3', seq(1, 6, 3, 5));
  assert.strictEqual(kh.total, 14);
  assert.deepStrictEqual(kh.parts[0].rolls.map((r) => r.dropped), [true, false, false, false]);
  const kl = roll('2d20kl1', seq(17, 4));
  assert.strictEqual(kl.total, 4);
});

test('vantagem e desvantagem por palavra', () => {
  const v = roll('1d20+2 vant', seq(3, 18));
  assert.strictEqual(v.total, 20);
  assert.strictEqual(v.expr, '2d20kh1+2');
  const d = roll('1d20 desv', seq(3, 18));
  assert.strictEqual(d.total, 3);
});

test('20 natural só conta dado mantido', () => {
  assert.strictEqual(roll('1d20', seq(20)).nat20, true);
  assert.strictEqual(roll('2d20kl1', seq(20, 5)).nat20, false);
});

test('dano máximo: todos os dados de dano no máximo', () => {
  assert.strictEqual(roll('2d4', seq(4, 4)).maxDamage, true);
  assert.strictEqual(roll('2d4', seq(4, 3)).maxDamage, false);
  assert.strictEqual(roll('1d6+1d4', seq(6, 4)).maxDamage, true);
  assert.strictEqual(roll('1d20+3', seq(20)).maxDamage, false);
});

test('erros claros em português', () => {
  assert.throws(() => parse('abc'), DiceError);
  assert.throws(() => parse('1d1'), DiceError);
  assert.throws(() => parse('101d6'), DiceError);
  assert.throws(() => parse('2d6kh3'), DiceError);
  assert.throws(() => parse('2d6 vant'), DiceError);
  assert.throws(() => parse('1d20 2'), DiceError);
});
