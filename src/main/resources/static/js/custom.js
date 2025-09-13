let currentBookingDate = new Date();
let currentInvoiceWeekStart = getLastCompletedWeekStart(); // Monday of last completed week
let paidEmailsForWeek = new Set();

// ---------- Helpers ----------
function formatCurrency(n) {
    const num = Number(n || 0);
    return num.toLocaleString('en-US', { style: 'currency', currency: 'USD' });
}

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
                let badgeHtml = '';
                if (b.status && b.status.toUpperCase() === 'CANCELED') {
                    badgeHtml = ` <span class="badge bg-danger ms-1">Canceled</span>`;
                } else if (b.paid) {
                    badgeHtml = ` <span class="badge bg-success ms-1">Paid</span>`;
                } else if (b.wantsAdvancePay && b.advanceEligible) {
                    badgeHtml = ` <span class="badge bg-info text-dark ms-1" title="Customer opted to pay in advance">Prepay</span>`;
                }

                // Show "Mark Paid (day)" only if not canceled and not paid
                const showMarkDayPaid = !b.paid && (!b.status || b.status.toUpperCase() !== 'CANCELED');
                const markPaidForm = showMarkDayPaid ? `
<form method="POST" action="/admin/bookings/mark-paid/${b.id}" class="d-inline ms-2"
      onsubmit="return confirm('Mark this booking as PAID?');">
  ${csrfToken ? `<input type="hidden" name="_csrf" value="${csrfToken}">` : ''}
  <button class="btn btn-sm btn-outline-success">Mark Paid (day)</button>
</form>` : '';

                // Cancel button only when APPROVED and not paid
                const cancelCellHtml = (b.status === 'APPROVED' && !b.paid) ? `
      <form method="POST" action="/admin/bookings/cancel/${b.id}">
        ${csrfToken ? `<input type="hidden" name="_csrf" value="${csrfToken}">` : ''}
        <button class="btn btn-danger-custom btn-sm cancel-booking-btn">Cancel</button>
      </form>` : '';

                const row = `
<tr>
  <td>${b.customerName}</td>
  <td>${b.dogName || 'N/A'}</td>
  <td>${b.serviceType}${badgeHtml}${markPaidForm}</td>
  <td>${b.time || ''}</td>
  <td>${b.status}</td>
  <td>${cancelCellHtml}</td>
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

function setWeekStartFromDate(d) {
    const date = new Date(d);
    const day = date.getDay(); // 0..6
    const monday = new Date(date);
    const diffToMonday = (day === 0 ? -6 : 1 - day);
    monday.setDate(date.getDate() + diffToMonday);
    monday.setHours(0,0,0,0);
    return monday;
}

function updateBookingDateDisplay() {
    const el = document.getElementById('bookingDateDisplay');
    if (el) el.textContent = currentBookingDate.toDateString();

    currentInvoiceWeekStart = setWeekStartFromDate(currentBookingDate);
    updateInvoiceWeekRangeDisplay();
    fetchWeeklyInvoices();   // load invoice rows (and rebuild paidEmailsForWeek if you still use it)
    fetchBookings();         // then render bookings
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
            // optional: keep this for any legacy UI cue, but not required anymore
            paidEmailsForWeek = new Set(rows.filter(r => r.paid).map(r => r.customerEmail));

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
    document.querySelectorAll(".confirm-logout").forEach(link => {
        link.addEventListener("click", function (e) {
            if (!confirm("Are you sure you want to log out?")) e.preventDefault();
        });
    });

    autoDismissAlerts();

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

    updateBookingDateDisplay();
    updateInvoiceWeekRangeDisplay();
    fetchWeeklyInvoices();
});
