// Handles the complaint form. After submitting, an animated ticket pops up.
// - If the Java server is running, the ticket is saved and its real number is shown.
// - If the server can't be reached (e.g. the page was opened as a file), the
//   ticket still animates in "demo mode" so you can present the page anywhere.

document.addEventListener('DOMContentLoaded', function () {
    const form = document.getElementById('complaintForm');
    const toast = document.getElementById('popupToast');
    const MAX_FILE = 5 * 1024 * 1024; // 5 MB

    // ---------- error toast ----------
    let toastTimer;
    function showToast(message) {
        toast.textContent = message;
        toast.classList.add('error', 'show');
        clearTimeout(toastTimer);
        toastTimer = setTimeout(function () { toast.classList.remove('show'); }, 6000);
    }

    // ---------- ticket ----------
    // Fills in the ticket text, then points the address at #ticketOverlay.
    // The showing, the animation and the closing are all done by CSS (:target).
    function showTicket(ticketNo, details, demo) {
        document.getElementById('ticketNumber').textContent = ticketNo;
        document.getElementById('tName').textContent = details.name;
        document.getElementById('tCategory').textContent = details.category;
        document.getElementById('tLocation').textContent = details.location;
        document.getElementById('tDate').textContent = new Date().toLocaleString('en-PH', {
            month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit'
        });
        document.getElementById('ticketNote').textContent =
            demo ? 'Demo mode: this ticket was not saved to the server.' : '';
        window.location.hash = 'ticketOverlay';
    }

    // A refresh while the ticket is open would show an empty ticket, so start closed.
    if (window.location.hash === '#ticketOverlay') history.replaceState(null, '', '#complaint');

    function demoTicketNo() {
        return 'ER-' + String(Math.floor(Math.random() * 900000) + 100000);
    }

    // Reads a file and returns just the base64 text (no "data:..." prefix)
    function toBase64(file) {
        return new Promise(function (resolve, reject) {
            const reader = new FileReader();
            reader.onload = function () { resolve(String(reader.result).split(',')[1] || ''); };
            reader.onerror = function () { reject(new Error('Could not read the file')); };
            reader.readAsDataURL(file);
        });
    }

    // ---------- submit ----------
    form.addEventListener('submit', async function (e) {
        e.preventDefault();

        const button = form.querySelector('button[type="submit"]');
        button.disabled = true;
        button.textContent = 'Submitting...';

        const details = {
            name: document.getElementById('name').value.trim(),
            contact: document.getElementById('Contact').value.trim(),
            category: document.getElementById('category').value,
            location: document.getElementById('location').value.trim(),
            description: document.getElementById('description').value.trim()
        };

        try {
            const file = document.getElementById('Evidence').files[0];
            if (file && file.size > MAX_FILE) {
                showToast('Evidence file must be 5 MB or smaller.');
                return;
            }

            // No server behind this page (opened as a file): just show the demo ticket
            if (window.location.protocol === 'file:') {
                showTicket(demoTicketNo(), details, true);
                form.reset();
                return;
            }

            const payload = Object.assign({}, details, {
                evidenceName: file ? file.name : '',
                evidenceData: file ? await toBase64(file) : ''
            });

            let res;
            try {
                res = await fetch('/api/tickets', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(payload)
                });
            } catch (netErr) {
                // Server not running: still show the ticket in demo mode
                showTicket(demoTicketNo(), details, true);
                form.reset();
                return;
            }

            let data = null;
            try { data = await res.json(); } catch (jsonErr) { /* not our server */ }

            if (res.ok && data && data.ticketNo) {
                showTicket(data.ticketNo, details, false);
                form.reset();
            } else if (data && data.error) {
                showToast(data.error);                 // real validation error from the server
            } else {
                showTicket(demoTicketNo(), details, true);   // some other web server (no API)
                form.reset();
            }
        } catch (err) {
            showToast('Something went wrong. Please try again.');
        } finally {
            button.disabled = false;
            button.textContent = 'Submit Ticket';
        }
    });
});
