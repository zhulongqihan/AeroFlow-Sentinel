/* Project rendering policy. DOMPurify is vendored separately with its license. */
(function (global) {
    'use strict';
    const tags = ['p', 'br', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'ul', 'ol', 'li',
        'blockquote', 'pre', 'code', 'strong', 'em', 'del', 's', 'hr', 'table',
        'thead', 'tbody', 'tfoot', 'tr', 'th', 'td', 'a', 'img', 'span', 'kbd',
        'sub', 'sup', 'details', 'summary'];
    const attributes = ['href', 'title', 'src', 'alt', 'class', 'colspan', 'rowspan', 'align'];
    const escapeText = value => String(value).replace(/[&<>"']/g, character => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    })[character]);
    const safeUrl = (value, image) => {
        try {
            const url = new URL(value, global.location.href);
            return (image ? ['http:', 'https:'] : ['http:', 'https:', 'mailto:']).includes(url.protocol);
        } catch { return false; }
    };

    function render(content) {
        const source = content == null ? '' : String(content);
        if (!source) return '';
        if (!global.marked?.parse || !global.DOMPurify?.isSupported) return escapeText(source);
        try {
            // Sanitize AFTER parsing; no provider/model/history HTML bypasses this policy.
            const fragment = global.DOMPurify.sanitize(global.marked.parse(source, { gfm: true, breaks: true }), {
                ALLOWED_TAGS: tags,
                ALLOWED_ATTR: attributes,
                ALLOW_DATA_ATTR: false,
                ALLOW_ARIA_ATTR: false,
                SANITIZE_NAMED_PROPS: true,
                RETURN_DOM_FRAGMENT: true
            });
            fragment.querySelectorAll('a[href], img[src]').forEach(node => {
                const image = node.tagName === 'IMG';
                const name = image ? 'src' : 'href';
                const value = node.getAttribute(name);
                if (!safeUrl(value, image)) { node.removeAttribute(name); return; }
                if (image) {
                    node.setAttribute('loading', 'lazy');
                    node.setAttribute('referrerpolicy', 'no-referrer');
                } else {
                    const target = new URL(value, global.location.href);
                    if (target.protocol !== 'mailto:' && target.origin !== global.location.origin) {
                        node.setAttribute('target', '_blank');
                        node.setAttribute('rel', 'noopener noreferrer');
                    }
                }
            });
            fragment.querySelectorAll('[class]').forEach(node => {
                const classes = node.className.split(/\s+/)
                    .filter(name => /^(?:language-[\w-]+|hljs(?:-[\w-]+)?)$/.test(name));
                if (classes.length) node.setAttribute('class', classes.join(' '));
                else node.removeAttribute('class');
            });
            const holder = global.document.createElement('div');
            holder.appendChild(fragment);
            return holder.innerHTML;
        } catch {
            // Fail closed even if a parser/sanitizer load or upgrade fails.
            return escapeText(source);
        }
    }
    global.AeroFlowMarkdown = Object.freeze({ render });
})(window);
