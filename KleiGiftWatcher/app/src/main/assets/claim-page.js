(function () {
    // Read only visible text. Never inspect input values, cookies or storage.
    function visible(el) {
        var s = window.getComputedStyle(el), r = el.getBoundingClientRect();
        return s.display !== 'none' && s.visibility !== 'hidden' && r.width > 0 && r.height > 0;
    }
    var host = location.hostname.toLowerCase();
    if (host !== 'accounts.klei.com') return 'INTERMEDIATE';
    var gift = /^\/(creatorlink|link)\//.test(location.pathname);
    var text = ((document.body && document.body.innerText) || '').toLowerCase();
    var password = Array.from(document.querySelectorAll('input[type=password]')).some(visible);
    var loginForm = password || Array.from(document.querySelectorAll('form')).some(function (f) {
        return visible(f) && /\/(login|signin|sign-in|authenticate)(\/|\?|$)/i.test(f.getAttribute('action') || '');
    });
    // An unrelated path alone says nothing about authentication.
    if (!gift) return loginForm ? 'LOGIN' : 'INTERMEDIATE';
    if (window.__kleiExpectedPath && location.pathname !== window.__kleiExpectedPath) return 'INTERMEDIATE';
    // Challenge/congestion pages must never become permanent authentication failures.
    if (/checking your browser|verify you are human|just a moment|too many requests|service unavailable|temporarily unavailable|排队|稍后重试|访问过于频繁|人机验证/.test(text)) return 'BUSY';
    if (loginForm) return 'LOGIN';
    // Someone else's redemption is different from redemption by this account.
    if (/claimed by (?:another|someone|somebody)|(?:another|someone|somebody) (?:else )?has (?:already )?claimed|已被(?:其他人|他人|别人)领取|被其他玩家领取/.test(text)) return 'TAKEN';
    if (/successfully claimed|claimed successfully|you have (?:already )?claimed|reward claimed|领取成功|已成功领取|兑换成功/.test(text)) return 'ALREADY';
    if (/expired|no longer available|sold out|out of stock|gift has been claimed|already (?:been )?claimed|已失效|已领完|已过期|已经被领取|已被领取/.test(text)) return 'UNAVAILABLE';
    var buttons = Array.from(document.querySelectorAll('button, input[type=submit], a[role=button], a'));
    var b = buttons.find(function (x) {
        var label = (x.innerText || (x.tagName === 'INPUT' ? x.value : '') || x.getAttribute('aria-label') || '').trim();
        return /^(claim|claim reward|claim item|claim your reward|claim your gift|redeem|redeem reward|领取|立即领取|领取奖励|兑换|获取奖励)$/i.test(label) && !x.disabled && visible(x);
    });
    if (b) {
        if (window.__kleiDoClick === true) { b.click(); return 'CLICKED'; }
        return 'READY';
    }
    // Login words in a footer are not proof that the session has expired.
    var loginLink = buttons.some(function (x) {
        var label = (x.innerText || '').trim();
        return /^(log in|login|sign in|登录|登录科雷账号)$/i.test(label) && visible(x);
    });
    if (loginLink && /(?:please (?:log in|sign in)|sign in|log in|login) to (?:claim|redeem)|(?:请先登录|需要登录|登录科雷账号).{0,20}(?:领取|兑换)/.test(text)) return 'LOGIN';
    return 'WAIT';
})()
