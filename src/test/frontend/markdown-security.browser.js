// Run with playwright-cli run-code --filename src/test/frontend/markdown-security.browser.js.
async (page) => {
    await page.route('http://127.0.0.1:18767/**', route => route.fulfill({
        contentType: 'text/html', body: '<!doctype html><html><head></head><body><main id="messages"></main><section id="report"></section></body></html>'
    }));
    await page.goto('http://127.0.0.1:18767/security-fixtures');
    for (const script of ['vendor/marked-11.1.1.umd.js', 'vendor/dompurify-3.4.16.min.js', 'safe-markdown.js', 'app.js']) {
        await page.addScriptTag({ path: `src/main/resources/static/${script}` });
    }
    return await page.evaluate(async () => {
        const failures = [];
        const check = (condition, name) => { if (!condition) failures.push(name); };
        window.__xss = 0;
        const fixtures = [
            '<img src=x onerror="window.__xss=1">',
            '<script>window.__xss=1</script>',
            '<svg><g onload="window.__xss=1"></g></svg>',
            '<math><mtext><table><mglyph><style><!--</style><img title="--><img src=x onerror=window.__xss=1>">',
            '[click](javascript:window.__xss=1)',
            '<a href="jav&#x61;script:window.__xss=1">click</a>',
            '[click](data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==)',
            '<img src="data:image/svg+xml,%3Csvg%20onload%3Dalert(1)%3E">',
            '<iframe srcdoc="<script>window.__xss=1</script>"></iframe>',
            '<form id="location"><input name="href" value="javascript:window.__xss=1"></form>',
            '<details open ontoggle="window.__xss=1"><summary>open</summary>text</details>',
            '<p style="position:fixed" class="loading-overlay" onclick="window.__xss=1">text</p>'
        ];
        const app = Object.create(AeroFlowSentinelApp.prototype);
        app.chatMessages = document.querySelector('#messages');
        app.agentRuntimeReportContent = document.querySelector('#report');
        app.currentChatHistory = [];
        app.recordActivity = () => {};
        app.syncSessionStatus = () => {};
        app.scrollToBottom = () => {};
        app.updateCurrentChatHistory = () => {};
        app.renderChatHistory = () => {};
        app.isCurrentChatFromHistory = false;
        const inspect = (node, label) => {
            check(!node.querySelector('script,svg,math,iframe,object,embed,form,input,style,template'), `${label}: forbidden element`);
            for (const element of node.querySelectorAll('*')) {
                for (const attribute of element.attributes) {
                    check(!/^on/i.test(attribute.name) && !['style', 'id', 'name', 'srcdoc'].includes(attribute.name), `${label}: forbidden attribute`);
                }
            }
            for (const element of node.querySelectorAll('[href],[src]')) {
                const value = element.getAttribute('href') || element.getAttribute('src');
                check(!/^(?:javascript|data|vbscript):/i.test(value), `${label}: unsafe URL`);
            }
        };
        for (const [index, payload] of fixtures.entries()) {
            for (const [name, action] of [
                ['assistant', () => app.addMessage('assistant', payload, false, false)],
                ['history', () => app.addMessage('assistant', payload, false, false)],
                ['stream-final', () => { const node = app.addMessage('assistant', '', true, false); app.handleStreamComplete(node, payload); return node; }],
                ['aiops-final', () => app.addAIOpsMessage(payload, [])],
                ['aiops-update', () => { const node = app.addMessage('assistant', '', true, false); app.updateAIOpsMessage(node, payload, []); return node; }],
                ['runtime-report', () => { app.renderRuntimeReport(payload); return app.agentRuntimeReportContent; }]
            ]) {
                const node = action();
                inspect(node.querySelector('.message-content') || node, `${index}/${name}`);
            }
            // Unfinished stream chunks must be safe too, not just the final answer.
            const node = document.createElement('div');
            for (let length = 1; length <= payload.length; length += 7) {
                node.innerHTML = app.renderMarkdown(payload.slice(0, length));
                inspect(node, `${index}/prefix-${length}`);
            }
        }
        const normal = '# 诊断报告\n\n| 接口 | 状态 |\n| --- | --- |\n| 搜索 | 正常 |\n\n```js\nconst sample = "<script>text only</script>";\n```\n\n[参考](https://example.com/runbook)';
        app.renderRuntimeReport(normal);
        check(!!app.agentRuntimeReportContent.querySelector('table tbody td'), 'normal table preserved');
        check(app.agentRuntimeReportContent.querySelector('pre code')?.textContent.includes('<script>text only</script>'), 'code text preserved');
        const link = app.agentRuntimeReportContent.querySelector('a');
        check(link?.target === '_blank' && link.rel.includes('noopener') && link.rel.includes('noreferrer'), 'external link protection');
        check(window.__xss === 0, 'no fixture executed');
        if (failures.length) throw new Error(JSON.stringify(failures));
        return { fixtureCount: fixtures.length, entryPoints: 6, prefixChecks: true, normalTable: true, normalCode: true,
            dompurifyVersion: DOMPurify.version, failures };
    });
}
