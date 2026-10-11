// Tanod dashboard: loads the profile and the tickets from the Java server.
// New complaints show up automatically (the page refreshes the list every 30 seconds).

(function () {
    function el(tag, className, text) {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (text !== undefined) node.textContent = text;   // textContent keeps complaint text safe
        return node;
    }

    async function api(url, options) {
        const res = await fetch(url, options);
        if (res.status === 401) {
            window.location.href = 'tanodlogin.html';
            throw new Error('unauthorized');
        }
        return res;
    }

    function line(label, value) {
        const p = el('p');
        p.appendChild(el('strong', null, label + ': '));
        p.appendChild(document.createTextNode(value));
        return p;
    }

    function ticketCard(t, canResolve) {
        const card = el('div', 'task');
        card.dataset.category = t.category;   // lets the CSS colour the left edge by category

        const top = el('div', 'task-top');
        top.appendChild(el('h3', null, t.ticketNo + ' · ' + t.category));
        top.appendChild(el('span', t.status === 'Resolved' ? 'pending resolved' : 'pending', t.status));
        card.appendChild(top);

        const meta = el('div', 'task-meta');
        meta.appendChild(line('Complainant', t.name));
        meta.appendChild(line('Contact', t.contact));
        meta.appendChild(line('Location', t.location));
        meta.appendChild(line('Submitted', t.createdAt));
        card.appendChild(meta);

        const desc = line('Description', t.description);
        desc.classList.add('task-desc');
        card.appendChild(desc);

        if (t.evidence) {
            const p = el('p');
            p.appendChild(el('strong', null, 'Evidence: '));
            const a = el('a', 'evidence-link', t.evidenceName || 'View file');
            a.href = '/api/evidence/' + encodeURIComponent(t.evidence);
            a.target = '_blank';
            a.rel = 'noopener';
            p.appendChild(a);
            card.appendChild(p);
        }

        if (t.status === 'Resolved' && t.handledBy) {
            card.appendChild(line('Resolved by', t.handledBy + ' (' + t.handledAt + ')'));
        }

        if (canResolve) {
            const btn = el('button', 'action-btn', 'Mark as Resolved');
            btn.type = 'button';
            btn.addEventListener('click', async function () {
                btn.disabled = true;
                btn.textContent = 'Saving...';
                try {
                    const res = await api('/api/tickets/' + t.id + '/status', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ status: 'Resolved' })
                    });
                    if (!res.ok) throw new Error('failed');
                    await loadTickets();
                } catch (err) {
                    if (err.message === 'unauthorized') return;
                    btn.disabled = false;
                    btn.textContent = 'Mark as Resolved';
                    alert('Could not update the ticket. Please try again.');
                }
            });
            card.appendChild(btn);
        }
        return card;
    }

    function fill(listId, countId, tickets, canResolve, emptyText) {
        const list = document.getElementById(listId);
        document.getElementById(countId).textContent = tickets.length ? '(' + tickets.length + ')' : '';
        list.replaceChildren();
        if (!tickets.length) {
            list.appendChild(el('div', 'empty', emptyText));
            return;
        }
        tickets.forEach(function (t) { list.appendChild(ticketCard(t, canResolve)); });
    }

    async function loadTickets() {
        try {
            const results = await Promise.all([
                api('/api/tickets?status=Pending'),
                api('/api/tickets?status=Resolved')
            ]);
            const pending = await results[0].json();
            const resolved = await results[1].json();
            fill('pendingList', 'pendingCount', pending, true, 'No pending tickets right now. New complaints appear here automatically.');
            fill('resolvedList', 'resolvedCount', resolved, false, 'No resolved tickets yet.');
            document.getElementById('statPending').textContent = pending.length;
            document.getElementById('statResolved').textContent = resolved.length;
        } catch (err) {
            if (err.message === 'unauthorized') return;
            const list = document.getElementById('pendingList');
            list.replaceChildren(el('div', 'empty', 'Could not load tickets. Is the server running?'));
        }
    }

    async function loadProfile() {
        try {
            const res = await api('/api/session');
            const u = await res.json();
            document.getElementById('profileName').textContent = u.name;
            document.getElementById('profileNumber').textContent = u.tanodNumber;
            document.getElementById('profilePosition').textContent = u.position || '-';
            document.getElementById('profileDepartment').textContent = u.department || '-';
            document.getElementById('profileEmail').textContent = u.email || '-';

            const pic = document.getElementById('profilePicture');
            pic.replaceChildren();
            if (u.photo) {
                const img = el('img');
                img.src = u.photo;
                img.alt = 'Profile picture';
                img.onerror = function () { pic.replaceChildren(document.createTextNode(u.name.charAt(0))); };
                pic.appendChild(img);
            } else {
                pic.appendChild(document.createTextNode(u.name.charAt(0)));
            }
        } catch (err) { /* redirected to login */ }
    }

    // Highlights the sidebar link of the section you are looking at
    function initScrollSpy() {
        if (!('IntersectionObserver' in window)) return;
        const links = Array.prototype.slice.call(document.querySelectorAll('.sidebar .nav a[href^="#"]'));
        const byId = {};
        links.forEach(function (a) { byId[a.getAttribute('href').slice(1)] = a; });

        function setActive(id) {
            links.forEach(function (a) { a.classList.toggle('active', a === byId[id]); });
        }

        const observer = new IntersectionObserver(function (entries) {
            entries.forEach(function (entry) {
                if (entry.isIntersecting) setActive(entry.target.id);
            });
        }, { rootMargin: '-20% 0px -65% 0px' });

        Object.keys(byId).forEach(function (id) {
            const section = document.getElementById(id);
            if (section) observer.observe(section);
        });
    }

    document.addEventListener('DOMContentLoaded', function () {
        loadProfile();
        loadTickets();
        initScrollSpy();
        setInterval(loadTickets, 30000);
    });
})();
