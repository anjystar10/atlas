// Atlas Voice (atlasvoice.js on the main process, the engine choice in renderer/voice.js, the phone relay helper).
import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

globalThis.window = globalThis.window || { atlasHost: { read: async () => null, write: async () => true } };
const require = createRequire(import.meta.url);
const av = require('../atlasvoice.js');
const voice = await import('../renderer/voice.js');

test('reads nvidia-smi', () => {
  assert.deepEqual(av.parseGpus('NVIDIA GeForce RTX 4060 Laptop GPU, 8188\n'), [{ name: 'NVIDIA GeForce RTX 4060 Laptop GPU', vramMb: 8188 }]);
  assert.deepEqual(av.parseGpus('NVIDIA GeForce GTX 1650, 4096\r\nNVIDIA RTX A2000, 12288\r\n').map((g) => g.vramMb), [4096, 12288]);
  assert.deepEqual(av.parseGpus(''), []);
  assert.deepEqual(av.parseGpus("'nvidia-smi' is not recognized as an internal or external command"), []);
});

test('picks the model that fits the card', () => {
  assert.equal(av.pickModel(8188).id, 'Qwen/Qwen3-TTS-12Hz-1.7B-Base');
  assert.equal(av.pickModel(6144).size, '1.7B');            // exactly 6 GB: the larger model
  assert.equal(av.pickModel(6143).size, '0.6B');
  assert.equal(av.pickModel(4096).id, 'Qwen/Qwen3-TTS-12Hz-0.6B-Base');
  assert.equal(av.pickModel(3072).size, '0.6B');
  assert.equal(av.pickModel(2048), null);                    // too small to be worth it
});

test('finds a Python Qwen3-TTS can use', () => {
  assert.deepEqual(av.pythonVersion('Python 3.12.4'), [3, 12]);
  assert.ok(av.pythonOk([3, 12])); assert.ok(av.pythonOk([3, 10]));
  assert.ok(!av.pythonOk([3, 13])); assert.ok(!av.pythonOk([3, 9])); assert.ok(!av.pythonOk(null));
  assert.equal(av.pythonVersion('No installed Python found!'), null);
});

test('the install plan: own environment, CUDA PyTorch, Qwen3-TTS, then the chosen model', () => {
  const plan = av.installPlan({ cmd: 'py', args: ['-3.12'] }, 'C:/Atlas/atlas-voice/venv', av.pickModel(8188));
  assert.equal(plan.length, 5);
  assert.deepEqual(plan[0].args.slice(0, 3), ['-3.12', '-m', 'venv']);
  assert.ok(plan[2].args.includes('--index-url') && plan[2].args.some((a) => /cu12\d/.test(a)));
  assert.ok(plan[3].args.includes('qwen-tts'));
  assert.match(plan[4].args.join(' '), /1\.7B-Base/);
  assert.match(plan[4].label, /1\.7B/);
  for (const s of plan.slice(1)) assert.match(s.cmd, /venv/);   // everything after the first step runs inside Atlas's own environment
});

test('reads the voice server WAV', () => {
  const pcm = Buffer.from([1, 0, 255, 127, 0, 128]);
  const h = Buffer.alloc(44); h.write('RIFF', 0); h.writeUInt32LE(36 + pcm.length, 4); h.write('WAVE', 8); h.write('fmt ', 12);
  h.writeUInt32LE(16, 16); h.writeUInt16LE(1, 20); h.writeUInt16LE(1, 22); h.writeUInt32LE(24000, 24); h.writeUInt32LE(48000, 28);
  h.writeUInt16LE(2, 32); h.writeUInt16LE(16, 34); h.write('data', 36); h.writeUInt32LE(pcm.length, 40);
  const r = av.wavPcm(Buffer.concat([h, pcm]));
  assert.equal(r.rate, 24000); assert.deepEqual([...r.pcm], [...pcm]);
  assert.throws(() => av.wavPcm(Buffer.from('not audio at all, clearly not a wav file at all.....')), /not a WAV/);
  const stereo = Buffer.concat([h, pcm]); stereo.writeUInt16LE(2, 22);
  assert.throws(() => av.wavPcm(stereo), /16-bit mono/);
});

test('Atlas Voice speaks first whenever it runs, unless another voice was picked while it was there', () => {
  assert.equal(voice.chooseEngine(['atlas', 'gemini'], '', false), 'atlas');
  assert.equal(voice.chooseEngine(['atlas', 'gemini'], 'gemini', false), 'atlas');      // picked before Atlas Voice existed
  assert.equal(voice.chooseEngine(['atlas', 'gemini'], 'gemini', true), 'gemini');      // picked while it was there: respected
  assert.equal(voice.chooseEngine(['atlas', 'gemini'], 'atlas', true), 'atlas');
  assert.equal(voice.chooseEngine(['gemini', 'openai'], 'atlas', false), 'gemini');     // laptop voice off: the cloud chain as before
  assert.equal(voice.chooseEngine(['elevenlabs', 'gemini'], 'gemini', false), 'gemini');
  assert.equal(voice.chooseEngine([], 'atlas', false), null);                           // Windows' voice
  assert.equal(voice.ENGINES[0], 'atlas');
  assert.equal(voice.engineName('atlas'), 'Atlas Voice');
});

test('the language goes to Qwen3-TTS by name', () => {
  assert.equal(voice.languageName('en'), 'English');
  assert.equal(voice.languageName('fr-FR'), 'French');
  assert.equal(voice.languageName('spanish'), 'Spanish');
  assert.equal(voice.languageName('yo'), 'English');   // not one of its ten: English
  assert.equal(voice.languageName(null), 'English');
});
