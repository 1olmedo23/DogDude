document.addEventListener("DOMContentLoaded", function () {
    document.querySelectorAll(".confirm-logout").forEach(link => {
        link.addEventListener("click", function (e) {
            if (!confirm("Are you sure you want to log out?")) {
                e.preventDefault();
            }
        });
    });
});