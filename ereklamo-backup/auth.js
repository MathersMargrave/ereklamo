// Add <script src="auth.js"></script> to the <head> of tanod.html.
// Asks the Java server whether you are logged in; if not, back to login.
// Also wires up the Logout link.

(async function () {
    try {
        const res = await fetch('/api/session');
        if (!res.ok) throw new Error('not logged in');
    } catch (e) {
        window.location.href = 'tanodlogin.html';
    }
})();

document.addEventListener('DOMContentLoaded', function () {
    const logout = document.querySelector('a.logout');
    if (!logout) return;
    logout.addEventListener('click', async function (e) {
        e.preventDefault();
        await fetch('/api/logout', { method: 'POST' });
        window.location.href = 'index.html';
    });
});
