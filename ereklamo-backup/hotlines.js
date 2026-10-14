// Emergency hotlines for Taguig City.
// Put <div data-hotlines></div> on any page, load hotlines.css + hotlines.js,
// and the 911 banner, the agency cards and the copy buttons are built for you.
// To change a number, edit the lists below (one place for every page).
//
// Source: City of Taguig "Peace and Order" page (https://www.taguig.gov.ph/peace-and-order/).
// Numbers can change, so check that page before presenting.

(function () {
    'use strict';

    var ICONS = {
        phone: '<path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"/>',
        shield: '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>',
        flame: '<path d="M8.5 14.5A2.5 2.5 0 0 0 11 12c0-1.38-.5-2-1-3-1.072-2.143-.224-4.054 2-6 .5 2.5 2 4.9 4 6.5 2 1.6 3 3.5 3 5.5a7 7 0 1 1-14 0c0-1.153.433-2.294 1-3a2.5 2.5 0 0 0 2.5 2.5z"/>',
        monitor: '<rect x="2" y="3" width="20" height="14" rx="2"/><path d="M8 21h8M12 17v4"/>',
        lifebuoy: '<circle cx="12" cy="12" r="10"/><circle cx="12" cy="12" r="4"/><path d="m4.93 4.93 4.24 4.24M14.83 9.17l4.24-4.24M14.83 14.83l4.24 4.24M9.17 14.83l-4.24 4.24"/>',
        cross: '<path d="M9 3h6v6h6v6h-6v6H9v-6H3V9h6z"/>',
        car: '<path d="M19 17h2c.6 0 1-.4 1-1v-3c0-.9-.7-1.7-1.5-1.9C18.7 10.6 16 10 16 10s-1.3-1.4-2.2-2.3c-.5-.4-1.1-.7-1.8-.7H5c-.6 0-1.1.4-1.4.9l-1.4 2.9A3.7 3.7 0 0 0 2 12v4c0 .6.4 1 1 1h2"/><circle cx="7" cy="17" r="2"/><path d="M9 17h6"/><circle cx="17" cy="17" r="2"/>',
        copy: '<rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>',
        check: '<path d="M20 6 9 17l-5-5"/>'
    };

    var NATIONAL = {
        name: 'National Emergency Hotline',
        desc: 'Police, fire and medical help anywhere in the Philippines. Stay on the line and give your exact address.',
        number: '911'
    };

    // tone = colour of the card: blue, red, green, teal, indigo, amber
    // numbers = plain text, or { n: 'number', k: 'label' } to set your own label
    var HOTLINES = [
        {
            name: 'Taguig Command Center', icon: 'monitor', tone: 'indigo',
            desc: 'The city hub for accidents, crimes, fires and medical emergencies. It sends responders to you.',
            numbers: ['(02) 8789-3200']
        },
        {
            name: 'Taguig Rescue', icon: 'lifebuoy', tone: 'green',
            desc: 'City rescue and disaster response.',
            numbers: ['0919-070-3112']
        },
        {
            name: 'Doctor-on-Call', icon: 'cross', tone: 'teal',
            desc: 'Reach one of the city\u2019s on-call doctors for medical concerns.',
            numbers: ['0919-079-9112']
        },
        {
            name: 'Taguig PNP', icon: 'shield', tone: 'blue',
            desc: 'Crimes, threats and public safety concerns.',
            numbers: ['(02) 8642-3582', '0998-598-7932', { n: '117', k: 'PNP national' }]
        },
        {
            name: 'Taguig BFP', icon: 'flame', tone: 'red',
            desc: 'Fires, hazardous conditions and technical rescue.',
            numbers: ['(02) 8837-0740', '(02) 8837-4496', '0906-211-0919']
        },
        {
            name: 'R.E.A.C.T. Roadside Assistance', icon: 'car', tone: 'amber',
            desc: 'Roadside emergency assistance.',
            numbers: ['(02) 8640-7006', '0929-631-5924']
        }
    ];

    // ---------- helpers ----------
    function esc(s) {
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
            .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    function svg(name, cls) {
        return '<svg class="' + (cls || '') + '" viewBox="0 0 24 24" fill="none" stroke="currentColor" ' +
            'stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
            ICONS[name] + '</svg>';
    }

    function digits(n) { return n.replace(/\D/g, ''); }

    function telHref(n) { return 'tel:' + digits(n); }

    function kindOf(n) {
        var d = digits(n);
        if (d.length <= 3) return 'Hotline';
        if (d.indexOf('09') === 0) return 'Mobile';
        return 'Landline';
    }

    // ---------- markup ----------
    function numberRow(agency, num) {
        var n = typeof num === 'string' ? num : num.n;
        var k = typeof num === 'string' ? kindOf(n) : num.k;
        return '<div class="hl-row">' +
            '<a class="hl-num" href="' + telHref(n) + '" aria-label="Call ' + esc(agency) + ' at ' + esc(n) + '">' +
                svg('phone', 'hl-phone') +
                '<span class="hl-digits">' + esc(n) + '</span>' +
                '<span class="hl-kind">' + esc(k) + '</span>' +
            '</a>' +
            '<button type="button" class="hl-copy" data-copy="' + esc(n) + '" aria-label="Copy ' + esc(n) + '">' +
                svg('copy', 'hl-ico-copy') + svg('check', 'hl-ico-check') +
            '</button>' +
        '</div>';
    }

    function card(h) {
        return '<article class="hl-card hl-' + h.tone + '">' +
            '<div class="hl-head">' +
                '<div class="hl-icon">' + svg(h.icon) + '</div>' +
                '<div><h3>' + esc(h.name) + '</h3><p>' + esc(h.desc) + '</p></div>' +
            '</div>' +
            '<div class="hl-numbers">' +
                h.numbers.map(function (num) { return numberRow(h.name, num); }).join('') +
            '</div>' +
        '</article>';
    }

    function featured() {
        return '<div class="hl-featured">' +
            '<div class="hl-pulse"><span class="hl-pulse-core">' + svg('phone') + '</span></div>' +
            '<div class="hl-featured-text">' +
                '<span class="hl-live"><i></i>Available 24/7</span>' +
                '<h3>' + esc(NATIONAL.name) + '</h3>' +
                '<p>' + esc(NATIONAL.desc) + '</p>' +
            '</div>' +
            '<a class="hl-featured-call" href="' + telHref(NATIONAL.number) + '" aria-label="Call ' + esc(NATIONAL.number) + '">' +
                svg('phone') + '<span>Call ' + esc(NATIONAL.number) + '</span>' +
            '</a>' +
        '</div>';
    }

    var NOTE = '<p class="hl-note">Numbers come from the City of Taguig\u2019s published list and can change. ' +
        'Check the latest at <a href="https://www.taguig.gov.ph/peace-and-order/" target="_blank" rel="noopener">taguig.gov.ph</a>. ' +
        'The complaint form is for non-emergency concerns. If someone is in danger, call right away instead of filing a ticket.</p>';

    // ---------- toast + copy ----------
    var toast, toastTimer;

    function showToast(text) {
        if (!toast) {
            toast = document.createElement('div');
            toast.className = 'hl-toast';
            toast.setAttribute('role', 'status');
            toast.setAttribute('aria-live', 'polite');
            document.body.appendChild(toast);
        }
        toast.textContent = text;
        void toast.offsetWidth; // let the transition restart
        toast.classList.add('show');
        clearTimeout(toastTimer);
        toastTimer = setTimeout(function () { toast.classList.remove('show'); }, 2200);
    }

    function copyText(text) {
        if (navigator.clipboard && window.isSecureContext) {
            return navigator.clipboard.writeText(text);
        }
        return new Promise(function (resolve, reject) {
            var ta = document.createElement('textarea');
            ta.value = text;
            ta.setAttribute('readonly', '');
            ta.style.cssText = 'position:fixed;opacity:0;top:0;left:0';
            document.body.appendChild(ta);
            ta.select();
            var ok = false;
            try { ok = document.execCommand('copy'); } catch (e) { /* handled below */ }
            document.body.removeChild(ta);
            ok ? resolve() : reject(new Error('copy failed'));
        });
    }

    function bindCopy(root) {
        root.addEventListener('click', function (e) {
            var btn = e.target.closest('.hl-copy');
            if (!btn) return;
            var number = btn.getAttribute('data-copy');
            copyText(number).then(function () {
                btn.classList.add('copied');
                showToast('Copied ' + number);
                setTimeout(function () { btn.classList.remove('copied'); }, 1800);
            }).catch(function () {
                showToast('Could not copy. Press and hold the number instead.');
            });
        });
    }

    // ---------- reveal cards as they scroll into view ----------
    function reveal(root) {
        var cards = root.querySelectorAll('.hl-card');
        if (!('IntersectionObserver' in window)) return;   // cards simply stay visible
        root.classList.add('hl-ready');
        var obs = new IntersectionObserver(function (entries) {
            var k = 0;
            entries.forEach(function (entry) {
                if (!entry.isIntersecting) return;
                entry.target.style.setProperty('--d', (k++ * 80) + 'ms');
                entry.target.classList.add('in');
                obs.unobserve(entry.target);
            });
        }, { threshold: 0.15 });
        Array.prototype.forEach.call(cards, function (c) { obs.observe(c); });
    }

    // ---------- start ----------
    document.addEventListener('DOMContentLoaded', function () {
        var roots = document.querySelectorAll('[data-hotlines]');
        Array.prototype.forEach.call(roots, function (root) {
            root.innerHTML = featured() +
                '<div class="hl-grid">' + HOTLINES.map(card).join('') + '</div>' + NOTE;
            bindCopy(root);
            reveal(root);
        });
    });
})();
