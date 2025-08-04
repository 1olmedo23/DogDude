let currentBookingDate = new Date();

function fetchBookings() {
    const dateStr = currentBookingDate.toISOString().split('T')[0];
    fetch(`/admin/bookings?date=${dateStr}`)
        .then(res => res.json())
        .then(data => {
            const tbody = document.getElementById('bookingTableBody');
            tbody.innerHTML = '';
            data.forEach(b => {
                const row = `
    <tr>
        <td>${b.customer.username}</td>
        <td>${b.dogName || 'N/A'}</td>
        <td>${b.serviceType}</td>
        <td>${b.time}</td>
        <td>${b.status}</td>
        <td>
            ${b.status === 'APPROVED' ? `
                <form method="POST" action="/admin/bookings/cancel/${b.id}">
                    <input type="hidden" name="_csrf" value="${document.querySelector('input[name="_csrf"]').value}">
                    <button class="btn btn-danger-custom btn-sm cancel-booking-btn">Cancel</button>
                </form>
            ` : ''}
        </td>
    </tr>`;
                tbody.insertAdjacentHTML('beforeend', row);
            });

            // Reattach cancel confirmation after table rebuild
            attachCancelConfirm();
        });
}

function updateBookingDateDisplay() {
    document.getElementById('bookingDateDisplay').textContent =
        currentBookingDate.toDateString();
    fetchBookings();
}

function cancelHandler(e) {
    if (!confirm("Are you sure you want to cancel this booking?")) {
        e.preventDefault();
    }
}

function attachCancelConfirm() {
    document.querySelectorAll('.cancel-booking-btn').forEach(button => {
        button.removeEventListener('click', cancelHandler); // prevent duplicates
        button.addEventListener('click', cancelHandler);
    });
}

document.addEventListener("DOMContentLoaded", () => {
    // Logout confirmation
    document.querySelectorAll(".confirm-logout").forEach(link => {
        link.addEventListener("click", function (e) {
            if (!confirm("Are you sure you want to log out?")) {
                e.preventDefault();
            }
        });
    });

    // Attach cancel confirmation to existing buttons (customer page)
    attachCancelConfirm();

    // Booking tab controls
    document.getElementById('prevDayBtn')?.addEventListener('click', () => {
        currentBookingDate.setDate(currentBookingDate.getDate() - 1);
        updateBookingDateDisplay();
    });

    document.getElementById('nextDayBtn')?.addEventListener('click', () => {
        currentBookingDate.setDate(currentBookingDate.getDate() + 1);
        updateBookingDateDisplay();
    });

    document.getElementById('todayBtn')?.addEventListener('click', () => {
        currentBookingDate = new Date();
        updateBookingDateDisplay();
    });

    // Initial load
    updateBookingDateDisplay();
    //attachCancelConfirm();
});