(generation) => {
    const key = '__getJobsBossInbox';
    window[key]?.stop();

    const chatPath = /^\/web\/geek\/chat\/?$/;
    const unreadName = /^未读(?:消息|会话)?(?:[（(]?(\d{1,5})(\+)?[）)]?)?$/;
    const badgeNumber = /^[（(]?(\d{1,5})(\+)?[）)]?$/;
    const visible = node => node && node.getClientRects().length > 0 && getComputedStyle(node).visibility === 'visible';
    const compact = text => (text || '').replace(/\s+/g, '');
    const labelSelector = '.label-list li, .label-list button, .label-list [role="tab"], .chat-user [role="tab"]';
    const blockedHeading = /^(?:安全验证|请完成安全验证|请登录后继续|登录已失效|当前IP地址可能存在异常访问行为|会话列表加载失败|网络连接失败)[。！]?$/;
    const loadingSelector = '.loading, .is-loading, .chat-loading, [aria-busy="true"]';
    const emptyText = /^(?:(?:暂无|没有|还没有)未读(?:消息|会话|沟通)|(?:暂无|还没有)(?:消息|会话|沟通))[。！]?$/;
    const labelOf = tab => tab.querySelector('.label-name') || tab;
    const renderedText = node => compact(node.innerText ?? node.textContent);

    function unreadTab() {
        if (!chatPath.test(location.pathname)) return null;
        if (Array.from(document.querySelectorAll('h1, h2, [role="dialog"] .title, .chat-user .error-tip, .user-list .error-tip'))
            .some(node => visible(node) && blockedHeading.test(renderedText(node)))) return null;
        return Array.from(document.querySelectorAll(labelSelector)).find(tab => {
            const label = labelOf(tab);
            return visible(label) && unreadName.test(renderedText(label));
        }) || null;
    }

    function measure() {
        const tab = unreadTab();
        if (!tab) return null;
        const badges = Array.from(tab.querySelectorAll('i, .badge, .unread-count'))
            .filter(visible).map(renderedText).filter(Boolean);
        const match = badges.map(text => text.match(badgeNumber)).find(Boolean) || renderedText(tab).match(unreadName);
        if (match?.[1]) return [Number(match[1]), Boolean(match[2])];
        if (badges.length || !match) return null;

        // 空未读视图可移除或隐藏列表容器。用已加载的筛选栏和角标判断零值。
        const panels = Array.from(document.querySelectorAll('.chat-user, .user-list, .user-list-content'));
        if (panels.some(panel => [panel, ...panel.querySelectorAll(loadingSelector)]
            .some(node => node.matches(loadingSelector) && visible(node)))) return null;
        const list = document.querySelector('.user-list-content');
        const hasConversations = visible(list) && list.querySelector('.friend-content, [role="listitem"]');
        const hasEmptyState = panels.some(panel =>
            Array.from(panel.querySelectorAll('.empty, .empty-list, .empty-state, .no-data, .chat-empty, [role="status"]'))
                .some(node => visible(node) && emptyText.test(renderedText(node))));
        const settled = document.readyState === 'complete' && performance.now() - startedAt >= 3_000;
        return hasConversations || hasEmptyState || settled ? [0, false] : null;
    }

    const startedAt = performance.now();
    let stopped = false;
    let scheduled = false;
    let previous;
    let lastKnownUnread;
    let refreshVersion = 0;
    let refreshPending = false;
    let checkingRefresh = false;
    let startedSwitch = false;

    function cancelRefresh() {
        refreshPending = false;
        refreshVersion++;
    }

    function selectUnread(tab = unreadTab()) {
        if (!tab || tab.matches(':disabled, [aria-disabled="true"]')) return false;
        cancelRefresh();
        tab.click();
        return true;
    }

    function canRefresh(unread) {
        // 点开最后一条会话时角标会先清零，等用户离开消息页再清理列表。
        return refreshPending && unread === 0 && (document.hidden || !document.hasFocus());
    }

    function report(heartbeat = false, allowRefresh = true) {
        if (stopped) return;
        const result = measure();
        const unread = result?.[0];
        if (unread !== undefined && unread !== lastKnownUnread) {
            refreshPending = allowRefresh && unread === 0 && lastKnownUnread > 0;
            lastKnownUnread = unread;
            refreshVersion++;
        }
        const checkRefresh = allowRefresh && !checkingRefresh && canRefresh(unread);
        const current = result ? result.join(':') : 'unknown';
        if (!heartbeat && current === previous && !checkRefresh) return;
        previous = current;
        const version = refreshVersion;
        if (checkRefresh) checkingRefresh = true;
        void window.__getJobsReportBossUnread(generation, result?.[0] ?? -1, result?.[1] ?? false)
            .then(active => {
                if (checkRefresh && active && !stopped && version === refreshVersion && canRefresh(measure()?.[0])) selectUnread();
            }).catch(() => {})
            .finally(() => { if (checkRefresh) checkingRefresh = false; });
    }
    const observer = new MutationObserver(() => {
        if (scheduled) return;
        scheduled = true;
        queueMicrotask(() => { scheduled = false; report(); });
    });
    observer.observe(document.documentElement, { childList: true, subtree: true, characterData: true, attributes: true,
        attributeFilter: ['class', 'style', 'hidden', 'aria-busy'] });
    const settle = setTimeout(() => report(true), 3_100);
    const heartbeat = setInterval(() => report(true), 25_000);
    function reportVisibility() { report(!document.hidden); }
    function reportOnBlur() { queueMicrotask(() => report()); }
    function onFilterClick(event) {
        if (event.isTrusted && event.target instanceof Element && event.target.closest(labelSelector)) cancelRefresh();
    }
    document.addEventListener('visibilitychange', reportVisibility);
    window.addEventListener('blur', reportOnBlur);
    document.addEventListener('click', onFilterClick, true);
    window[key] = {
        openUnread() {
            const tab = unreadTab();
            if (!tab) return false;
            const selected = '.selected, .active, [aria-selected="true"]';
            if (tab.matches(selected) || labelOf(tab).matches(selected)) return true;
            if (!startedSwitch && selectUnread(tab)) {
                startedSwitch = true;
                report(true, false);
            }
            return false;
        },
        refresh() {
            if (!selectUnread()) return false;
            report(true, false);
            return true;
        },
        stop() {
            stopped = true;
            observer.disconnect();
            clearTimeout(settle);
            clearInterval(heartbeat);
            document.removeEventListener('visibilitychange', reportVisibility);
            window.removeEventListener('blur', reportOnBlur);
            document.removeEventListener('click', onFilterClick, true);
            delete window[key];
        },
    };
    report();
}
