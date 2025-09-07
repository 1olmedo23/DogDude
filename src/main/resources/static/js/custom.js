let currentBookingDate = new Date();
let currentInvoiceWeekStart = getLastCompletedWeekStart(); // Monday of last completed week

// ---------- Helpers ----------
function formatCurrency(n) {
    const num = Number(n || 0);
    return num.toLocaleString('en-US', { style: 'currency', currency: 'USD' });
}

// Auto-dismiss any Bootstrap alert after a delay
function autoDismissAlerts(ms = 6000) {
    const alerts = document.querySelectorAll('.alert');
    alerts.forEach(alert => {
        alert.classList.add('fade', 'show');
        setTimeout(() => {
            alert.classList.remove('show');
            setTimeout(() => {
                if (alert && alert.parentNode) alert.parentNode.removeChild(alert);
            }, 400);
        }, ms);
    });
}

// ---------- Bookings (Admin) ----------
function groupAndRenderAdminBookings(rows) {
    const tbody = document.getElementById('bookingTableBody');
    if (!tbody) return;

    tbody.innerHTML = '';

    const csrfInput = document.querySelector('input[name="_csrf"]');
    const csrfToken = csrfInput ? csrfInput.value : '';

    const groups = {
        'Daycare (6 AM - 3 PM)': [],
        'Daycare (6 AM - 8 PM)': [],
        'Boarding': []
    };

    rows.forEach(r => {
        const svc = (r.serviceType || '').toLowerCase();
        if (svc.includes('daycare') && r.serviceType.includes('6 AM - 3 PM')) {
            groups['Daycare (6 AM - 3 PM)'].push(r);
        } else if (svc.includes('daycare') && r.serviceType.includes('6 AM - 8 PM')) {
            groups['Daycare (6 AM - 8 PM)'].push(r);
        } else if (svc.includes('boarding')) {
            groups['Boarding'].push(r);
        }
    });

    for (const [title, list] of Object.entries(groups)) {
        tbody.insertAdjacentHTML('beforeend', `<tr class="table-light"><td colspan="6" class="fw-bold">${title}</td></tr>`);
        if (list.length === 0) {
            tbody.insertAdjacentHTML('beforeend', `<tr><td colspan="6" class="text-muted">No bookings.</td></tr>`);
        } else {
            list.forEach(b => {
                const row = `
<tr>
  <td>${b.customerName}</td>
  <td>${b.dogName || 'N/A'}</td>
  <td>${b.serviceType}</td>
  <td>${b.time || ''}</td>
  <td>${b.status}</td>
  <td>
    ${b.status === 'APPROVED' ? `
      <form method="POST" action="/admin/bookings/cancel/${b.id}">
        ${csrfToken ? `<input type="hidden" name="_csrf" value="${csrfToken}">` : ''}
        <button class="btn btn-danger-custom btn-sm cancel-booking-btn">Cancel</button>
      </form>` : ''
                }
  </td>
</tr>`;
                tbody.insertAdjacentHTML('beforeend', row);
            });
        }
    }

    attachCancelConfirm();
}

function fetchBookings() {
    const dateStr = currentBookingDate.toISOString().split('T')[0];
    fetch(`/admin/bookings?date=${dateStr}`)
        .then(res => res.json())
        .then(data => groupAndRenderAdminBookings(data));
}

function updateBookingDateDisplay() {
    const el = document.getElementById('bookingDateDisplay');
    if (el) el.textContent = currentBookingDate.toDateString();
    fetchBookings();
}

function cancelHandler(e) {
    if (!confirm("Are you sure you want to cancel this booking?")) {
        e.preventDefault();
    }
}

function attachCancelConfirm() {
    document.querySelectorAll('.cancel-booking-btn').forEach(button => {
        button.removeEventListener('click', cancelHandler);
        button.addEventListener('click', cancelHandler);
    });
}

// ---------- Invoicing ----------
function getLastCompletedWeekStart() {
    const today = new Date();
    const day = today.getDay(); // Sun=0..Sat=6
    const monday = new Date(today);
    const diffToMonday = (day === 0 ? -6 : 1 - day);
    monday.setDate(today.getDate() + diffToMonday);
    monday.setDate(monday.getDate() - 7);
    monday.setHours(0,0,0,0);
    return monday;
}

function formatDateISO(d) {
    return d.toISOString().split('T')[0];
}

function getWeekEnd(start) {
    const end = new Date(start);
    end.setDate(start.getDate() + 6);
    end.setHours(23,59,59,999);
    return end;
}

function updateInvoiceWeekRangeDisplay() {
    const start = currentInvoiceWeekStart;
    const end = getWeekEnd(start);
    const el = document.getElementById('invoiceWeekRange');
    if (!el) return;
    el.textContent = `${start.toDateString()} – ${end.toDateString()}`;
}

function renderInvoiceTotals(rows) {
    const grand = rows.reduce((sum, r) => sum + Number(r.amount || 0), 0);
    const paidRows = rows.filter(r => r.paid);
    const unpaidRows = rows.filter(r => !r.paid);
    const paidTotal = paidRows.reduce((sum, r) => sum + Number(r.amount || 0), 0);
    const unpaidTotal = unpaidRows.reduce((sum, r) => sum + Number(r.amount || 0), 0);

    const elGrand = document.getElementById('invoiceGrandTotal');
    const elPaid = document.getElementById('invoicePaidTotal');
    const elUnpaid = document.getElementById('invoiceUnpaidTotal');

    if (elGrand) elGrand.textContent = formatCurrency(grand);
    if (elPaid) elPaid.textContent = formatCurrency(paidTotal);
    if (elUnpaid) elUnpaid.textContent = formatCurrency(unpaidTotal);

    const badgeCustomers = document.getElementById('badgeCustomers');
    const badgePaid = document.getElementById('badgePaid');
    const badgeUnpaid = document.getElementById('badgeUnpaid');
    if (badgeCustomers) badgeCustomers.textContent = `Customers: ${rows.length}`;
    if (badgePaid) badgePaid.textContent = `Paid: ${paidRows.length}`;
    if (badgeUnpaid) badgeUnpaid.textContent = `Unpaid: ${unpaidRows.length}`;
}

function fetchWeeklyInvoices() {
    const tbody = document.getElementById('invoiceTableBody');
    if (!tbody) return;

    const startISO = formatDateISO(currentInvoiceWeekStart);
    fetch(`/admin/invoices/weekly?start=${startISO}`)
        .then(res => res.json())
        .then(rows => {
            tbody.innerHTML = '';
            const csrfInput = document.querySelector('input[name="_csrf"]');
            const csrfToken = csrfInput ? csrfInput.value : '';

            rows.forEach(r => {
                const actionCell = r.paid
                    ? `<span class="badge bg-success">Paid</span>`
                    : `<form method="POST" action="/admin/invoices/mark-paid" onsubmit="return confirm('Mark this as PAID? This cannot be undone.');">
               ${csrfToken ? `<input type="hidden" name="_csrf" value="${csrfToken}">` : ''}
               <input type="hidden" name="email" value="${r.customerEmail}">
               <input type="hidden" name="start" value="${formatDateISO(currentInvoiceWeekStart)}">
               <button class="btn btn-custom btn-sm">Mark Paid</button>
             </form>`;

                const row = `
<tr>
  <td>${r.customerName}</td>
  <td>${r.dogName || 'N/A'}</td>
  <td>${r.customerEmail}</td>
  <td>${formatCurrency(r.amount)}</td>
  <td>${r.paid ? 'Yes' : 'No'}</td>
  <td>${actionCell}</td>
</tr>`;
                tbody.insertAdjacentHTML('beforeend', row);
            });

            renderInvoiceTotals(rows);
            autoDismissAlerts();
        });
}

document.addEventListener("DOMContentLoaded", () => {
    // Logout confirmation (only if you still use .confirm-logout anywhere)
    document.querySelectorAll(".confirm-logout").forEach(link => {
        link.addEventListener("click", function (e) {
            if (!confirm("Are you sure you want to log out?")) e.preventDefault();
        });
    });

    autoDismissAlerts();

    // Admin bookings controls
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

    // Invoicing controls
    document.getElementById('prevWeekBtn')?.addEventListener('click', () => {
        currentInvoiceWeekStart.setDate(currentInvoiceWeekStart.getDate() - 7);
        updateInvoiceWeekRangeDisplay();
        fetchWeeklyInvoices();
    });
    document.getElementById('nextWeekBtn')?.addEventListener('click', () => {
        currentInvoiceWeekStart.setDate(currentInvoiceWeekStart.getDate() + 7);
        updateInvoiceWeekRangeDisplay();
        fetchWeeklyInvoices();
    });

    // Initial loads (admin page)
    updateBookingDateDisplay();
    updateInvoiceWeekRangeDisplay();
    fetchWeeklyInvoices();
});
