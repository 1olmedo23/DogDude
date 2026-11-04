// Shared navbar behavior. Load only on pages not using legacy custom.js.
document.addEventListener('DOMContentLoaded', () => {
    document.querySelectorAll('.confirm-logout').forEach(button => {
        button.addEventListener('click', (event) => {
            if (!window.confirm('Are you sure you want to log out?')) {
                event.preventDefault();
            }
        });
    });
});
