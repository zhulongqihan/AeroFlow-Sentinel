const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');
const staticDir = path.resolve(__dirname, '../../main/resources/static');

test('missing sanitizer or parser fails closed to escaped text', () => {
    for (const dependency of [{}, { marked: { parse: () => '<script>unsafe()</script>' } }]) {
        const window = { ...dependency };
        vm.runInNewContext(fs.readFileSync(path.join(staticDir, 'safe-markdown.js'), 'utf8'), { window, URL });
        assert.equal(window.AeroFlowMarkdown.render('<img src=x onerror=unsafe()> & text'),
            '&lt;img src=x onerror=unsafe()&gt; &amp; text');
    }
});

test('messages and reports share renderer; no direct marked parser remains in app', () => {
    const app = fs.readFileSync(path.join(staticDir, 'app.js'), 'utf8');
    assert.doesNotMatch(app, /marked\.parse\s*\(/);
    assert.match(app, /AeroFlowMarkdown\.render\(content\)/);
    for (const name of ['handleStreamComplete', 'addMessage', 'updateAIOpsMessage', 'addAIOpsMessage', 'renderRuntimeReport']) {
        const position = app.indexOf(`    ${name}(`);
        assert.notEqual(position, -1, name);
        const next = app.indexOf('\n    }', position);
        assert.match(app.slice(position, next), /this\.renderMarkdown\(/, name);
    }
});

test('vendored assets match fixed provenance hashes and runtime loading order', () => {
    const manifest = JSON.parse(fs.readFileSync(path.join(staticDir, 'vendor/manifest.json'), 'utf8'));
    for (const asset of manifest) {
        const digest = crypto.createHash('sha256').update(fs.readFileSync(path.join(staticDir, 'vendor', asset.file))).digest('hex');
        assert.equal(digest, asset.sha256, asset.file);
    }
    const html = fs.readFileSync(path.join(staticDir, 'index.html'), 'utf8');
    assert.ok(html.indexOf('vendor/dompurify-3.4.16.min.js') < html.indexOf('safe-markdown.js'));
    assert.ok(html.indexOf('safe-markdown.js') < html.indexOf('app.js?'));
});
