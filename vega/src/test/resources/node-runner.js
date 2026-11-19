// Testovací dvojník Workeru z app.js (runJsTests): stejné require/test/expect a formát výstupu, spuštěno v Node.
// Používají ho jen JVM testy; v aplikaci běží testy ve WebView Workeru bez sítě.
const FILES = JSON.parse(require('fs').readFileSync(0, 'utf8'));
const out = []; const fmtv = v => typeof v === 'string' ? v : (() => { try { return JSON.stringify(v); } catch (e) { return String(v); } })();
const cons = { log: (...a) => out.push(a.map(fmtv).join(' ')), error: (...a) => out.push('E ' + a.map(fmtv).join(' ')), warn: (...a) => out.push('W ' + a.map(fmtv).join(' ')), info: (...a) => out.push(a.map(fmtv).join(' ')) };
const cache = {};
const dir = p => p.includes('/') ? p.slice(0, p.lastIndexOf('/')) : '';
function resolve(base, p) {
  const parts = (p.startsWith('/') ? [] : base.split('/').filter(Boolean)); for (const s of p.split('/')) { if (s === '..') parts.pop(); else if (s && s !== '.') parts.push(s); }
  const r = parts.join('/'); for (const c of [r, r + '.js', r + '/index.js', r + '.json']) if (c in FILES) return c; return null;
}
let tests = [];
const show = v => fmtv(v); const eq = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const expect = a => ({
  toBe: b => { if (!Object.is(a, b)) throw new Error('čekáno ' + show(b) + ', je ' + show(a)); },
  toEqual: b => { if (!eq(a, b)) throw new Error('čekáno ' + show(b) + ', je ' + show(a)); },
  toBeCloseTo: (b, d = 2) => { if (Math.abs(a - b) > Math.pow(10, -d) / 2) throw new Error('čekáno ≈' + b + ', je ' + a); },
  toBeTruthy: () => { if (!a) throw new Error('čekána pravda, je ' + show(a)); },
  toBeFalsy: () => { if (a) throw new Error('čekána nepravda, je ' + show(a)); },
  toContain: b => { if (!(a && a.includes && a.includes(b))) throw new Error(show(a) + ' neobsahuje ' + show(b)); },
  toThrow: () => { try { a(); } catch (e) { return; } throw new Error('čekána výjimka'); },
});
const test = (n, f) => tests.push([n, f]);
function load(path) {
  if (cache[path]) return cache[path].exports;
  const module = { exports: {} }; cache[path] = module;
  if (path.endsWith('.json')) { module.exports = JSON.parse(FILES[path]); return module.exports; }
  const req = p => { const r = resolve(dir(path), p); if (!r) throw new Error('Modul nenalezen: ' + p + ' (z ' + path + ')'); return load(r); };
  new Function('require', 'module', 'exports', '__filename', 'test', 'it', 'expect', 'console', 'describe', FILES[path])(req, module, module.exports, path, test, test, expect, cons, (n, f) => f());
  return module.exports;
}
(async () => {
  let pass = 0, fail = 0;
  const specs = Object.keys(FILES).filter(f => /\.test\.js$/.test(f) || /^test\/.*\.js$/.test(f)).sort();
  for (const s of specs) {
    tests = [];
    try { load(s); } catch (e) { fail++; out.push('✗ ' + s + ': ' + e.message); continue; }
    for (const [n, f] of tests) { try { await f(); pass++; out.push('✓ ' + s + ' › ' + n); } catch (e) { fail++; out.push('✗ ' + s + ' › ' + n + ': ' + e.message); } }
  }
  out.push(pass + ' prošlo, ' + fail + ' selhalo');
  process.stdout.write(JSON.stringify({ exitCode: fail || pass === 0 ? 1 : 0, output: out.join('\n'), command: specs.join(' ') }));
})();
