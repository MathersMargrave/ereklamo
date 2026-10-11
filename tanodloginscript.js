// Sends the login form to the Java server (Main.java) at /api/login.
// The server checks the credentials and sets a secure session cookie.

document.addEventListener('DOMContentLoaded', function () {
    const loginForm = document.getElementById('loginForm');
    const errorMessage = document.getElementById('errorMessage');
    const loginContainer = document.getElementById('loginContainer');
    const passwordInput = document.getElementById('password');
    const togglePass = document.getElementById('togglePass');
    const submitButton = loginForm.querySelector('button[type="submit"]');

    function showError(text) {
        errorMessage.textContent = text;
        errorMessage.classList.add('show');
        loginContainer.classList.remove('shake');
        void loginContainer.offsetWidth; // restart the shake animation
        loginContainer.classList.add('shake');
    }

    // Show / hide the password
    togglePass.addEventListener('click', function () {
        const hidden = passwordInput.type === 'password';
        passwordInput.type = hidden ? 'text' : 'password';
        togglePass.textContent = hidden ? 'Hide' : 'Show';
        togglePass.setAttribute('aria-label', hidden ? 'Hide password' : 'Show password');
        togglePass.setAttribute('aria-pressed', String(hidden));
        passwordInput.focus();
    });

    loginForm.addEventListener('submit', async function (e) {
        e.preventDefault();
        errorMessage.classList.remove('show');

        // Opened by double-clicking the file? Then there is no server behind it.
        if (window.location.protocol === 'file:') {
            showError('This page was opened as a file. Start the server with "java Main.java" and open http://localhost:8080/index.html instead.');
            return;
        }

        const tanodNumber = document.getElementById('tanodNumber').value.trim();
        const password = passwordInput.value;

        submitButton.disabled = true;
        submitButton.textContent = 'Signing in...';

        try {
            let res;
            try {
                res = await fetch('/api/login', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ tanodNumber, password })
                });
            } catch (err) {
                showError('Cannot reach the server. Make sure "java Main.java" is running in your terminal.');
                return;
            }

            let data = null;
            try {
                data = await res.json();
            } catch (err) {
                showError('This address is not served by Main.java (got HTTP ' + res.status +
                    '). Open http://localhost:8080/index.html instead of using Live Server or another tool.');
                return;
            }

            if (res.ok) {
                window.location.href = 'tanod.html';
            } else {
                showError(data.error || 'Login failed. Please try again.');
            }
        } finally {
            submitButton.disabled = false;
            submitButton.textContent = 'Login';
        }
    });
});
