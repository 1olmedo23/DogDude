let currentBookingDate = new Date();
let currentInvoiceWeekStart = getLastCompletedWeekStart(); // Monday of last completed week
let paidEmailsForWeek = new Set();

// ---------- Helpers ----------
function formatCurrency(n) {
    const num = Number(n || 0);
    return num.toLocaleString('en-US', { style: 'currency', currency: 'USD' });
}
function toISODate(d) { return new Date(d.getTime() - d.getTimezoneOffset()*60000).toISOString().slice(0,10); }

async function fetchCapacityRibbonFor(dateObj) {
    // 0) Only update when the Bookings tab is active and the ribbon exists
    const ribbon = document.getElementById('capacityRibbon');
    const bookingsPaneActive = document.querySelector('#bookings.tab-pane.active.show');
    if (!ribbon || !bookingsPaneActive) return;

    // 1) Fetch
    const iso = toISODate(dateObj);
    if (!iso) return;

    const res = await fetch(`/admin/bookings/capacity?date=${iso}`, {
        headers: { 'Accept': 'application/json' }
    });
    if (!res.ok) return;

    const c = await res.json();

    // 2) Setter helper (no-throw if element is missing)
    const set = (id, v) => {
        const el = document.getElementById(id);
        if (!el) return;
        // optional pretty formatting for integers
        el.textContent = (v ?? '—');
    };

    // 3) Populate
    set('capDaycare',      c.daycare);
    set('capDaycareCap',   c.daycareCap);
    set('capBoarding',     c.boarding);
    set('capBoardingCap',  c.boardingCap);
    set('capTotal',        c.total);
    set('capTotalCap',     c.totalCap);
    set('capEmergency',    c.emergencyUsed);
    set('capEmergencyCap', c.emergencyCap);
}
// ---- Legacy shim: we now handle alert timing elsewhere;
function autoDismissAlerts() { /* no-op on purpose */ }


// Auto-dismiss ALL alerts by default, EXCEPT those marked permanent.
// - Default delay: 4000ms
// - Override per alert with data-autoclose="7000" (ms) or data-autoclose (uses default)
// - Prevent closing by adding data-permanent or the class .alert-static
document.addEventListener('DOMContentLoaded', function () {
    const allAlerts = document.querySelectorAll('.alert');

    allAlerts.forEach(el => {
        // Skip permanent/sticky banners
        if (el.hasAttribute('data-permanent') || el.classList.contains('alert-static')) return;

        // Read delay (ms) or fall back to default
        const msRaw = el.getAttribute('data-autoclose');
        const delay = msRaw && !isNaN(+msRaw) ? parseInt(msRaw, 10) : 4000;

        setTimeout(() => {
            try {
                if (!document.body.contains(el)) return;
                // Re-check permanence in case it was toggled later
                if (el.hasAttribute('data-permanent') || el.classList.contains('alert-static')) return;

                if (window.bootstrap?.Alert) {
                    window.bootstrap.Alert.getOrCreateInstance(el).close();
                } else {
                    el.style.display = 'none';
                }
            } catch (_) { /* no-op */ }
        }, delay);
    });
});

// ---------- Bookings (Admin) ----------
function groupAndRenderAdminBookings(rows) {
    const tbody = document.getElementById('bookingTableBody');
    if (!tbody) return;

    tbody.innerHTML = '';

    const csrfInput = document.querySelector('input[name="_csrf"]');
    const csrfToken = csrfInput ? csrfInput.value : '';

    // NEW: include After Hours as its own group
    const groups = {
        'Daycare (6 AM - 3 PM)': [],
        'Daycare (6 AM - 8 PM)': [],
        'Daycare After Hours (6 AM - 11 PM)': [], // NEW
        'Boarding': []
    };

    // Group rows by service
    rows.forEach(r => {
        const svc = (r.serviceType || '').toLowerCase();
        if (svc.includes('daycare') && r.serviceType.includes('6 AM - 3 PM')) {
            groups['Daycare (6 AM - 3 PM)'].push(r);
        } else if (svc.includes('daycare') && r.serviceType.includes('6 AM - 8 PM')) {
            groups['Daycare (6 AM - 8 PM)'].push(r);
        } else if (svc.includes('daycare') && svc.includes('after hours')) {
            groups['Daycare After Hours (6 AM - 11 PM)'].push(r); // NEW
        } else if (svc.includes('boarding')) {
            groups['Boarding'].push(r);
        }
    });

    // Render groups in a stable order
    const renderOrder = [
        'Daycare (6 AM - 3 PM)',
        'Daycare (6 AM - 8 PM)',
        'Daycare After Hours (6 AM - 11 PM)', // NEW
        'Boarding'
    ];

    for (const title of renderOrder) {
        const list = groups[title];

        // Group header row
        tbody.insertAdjacentHTML('beforeend',
            `<tr class="table-light"><td colspan="6" class="fw-bold">${title}</td></tr>`);

        if (!list || list.length === 0) {
            tbody.insertAdjacentHTML('beforeend',
                `<tr><td colspan="6" class="text-muted">No bookings.</td></tr>`);
            continue;
        }

        // Sort by time (nulls last)
        list.sort((a, b) => (a.time || '').localeCompare(b.time || ''));

        list.forEach(b => {
            // status badge
            let badgeHtml = '';
            if (b.status && b.status.toUpperCase() === 'CANCELED') {
                badgeHtml = ` <span class="badge bg-danger ms-1">Canceled</span>`;
            } else if (b.paid) {
                badgeHtml = ` <span class="badge bg-success ms-1">Paid</span>`;
            } else if (b.wantsAdvancePay && b.advanceEligible) {
                badgeHtml = ` <span class="badge bg-info text-dark ms-1" title="Customer opted to pay in advance">Prepay</span>`;
            }

            // price chip (quoted rate if present)
            const priceTag = (b.liveAmount != null)
                ? ` <span class="text-muted ms-1">(${formatCurrency(b.liveAmount)})</span>`
                : (b.quotedRateAtLock
                    ? ` <span class="text-muted ms-1">(${formatCurrency(b.quotedRateAtLock)})</span>`
                    : '');

            // After Hours label add-on (always show $90 Flat)
            const isAfterHours = (b.serviceType || '').toLowerCase().includes('after hours');
            const afterHoursChip = isAfterHours
                ? ` <span class="badge bg-info text-dark ms-1">$90 Flat</span>`
                : '';

            const markPaidForm = (!b.paid && (!b.status || b.status.toUpperCase() !== 'CANCELED')) ? `
<form method="POST" action="/admin/bookings/mark-paid/${b.id}" class="d-inline ms-2"
      onsubmit="return confirm('Mark this booking as PAID?');">
  ${csrfToken ? `<input type="hidden" name="_csrf" value="${csrfToken}">` : ''}
  <button class="btn btn-sm btn-outline-success btn-mark-paid">Mark Paid (day)</button>
</form>` : '';

            // NEW: dog ×N badge (only when > 1)
            const dogBadge = (b.dogCount && b.dogCount > 1)
                ? ` <span class="badge bg-secondary-subtle text-secondary ms-1">×${b.dogCount}</span>`
                : '';

            const row = `
<tr>
  <td>${b.customerName}</td>
  <td>${b.dogName || 'N/A'}${dogBadge}</td>
  <td>${b.serviceType}${afterHoursChip}${badgeHtml}${priceTag}${markPaidForm}</td>
  <td>${b.time || ''}</td>
  <td>${b.status || ''}</td>
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
    fetchCapacityRibbonFor(currentBookingDate);
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

// Reusable helper: persistent Bootstrap Collapse with button label swap
// opts: { collapseId, buttonId, storageKey, applyOnMaxWidth (number or null) }
window.setupPersistentCollapse = function(opts){
    try {
        var el = document.getElementById(opts.collapseId);
        var btn = document.getElementById(opts.buttonId);
        if (!el || !btn || !window.bootstrap || !bootstrap.Collapse) return;

        var usePersistence = true;
        if (typeof opts.applyOnMaxWidth === 'number') {
            var w = window.innerWidth || document.documentElement.clientWidth;
            usePersistence = (w <= opts.applyOnMaxWidth);
        }

        // We control collapse programmatically; don't auto-toggle on click via data-attrs.
        var collapse = bootstrap.Collapse.getOrCreateInstance(el, { toggle: false });

        function setBtnLabel() {
            // If visible, say "Hide"; if collapsed, say "View"
            btn.textContent = el.classList.contains('show') ? 'Hide' : 'View';
        }

        function show() { collapse.show(); }
        function hide() { collapse.hide(); }

        // Initial state: on first visit show it; thereafter honor localStorage
        if (usePersistence) {
            var saved = localStorage.getItem(opts.storageKey);
            if (saved === null && opts.defaultCollapsed) {
                hide(); // first visit, default hidden
            } else if (saved === 'true') {
                hide();
            } else {
                show();
            }
        } else {
            // Desktop/tablet: always shown as designed; let d-md-block handle display
            show();
        }

        // Wire button click to toggle
        btn.addEventListener('click', function(e){
            e.preventDefault();
            el.classList.contains('show') ? hide() : show();
        });

        // Keep label + persistence in sync
        el.addEventListener('shown.bs.collapse', function(){
            if (usePersistence) localStorage.setItem(opts.storageKey, 'false');
            setBtnLabel();
        });
        el.addEventListener('hidden.bs.collapse', function(){
            if (usePersistence) localStorage.setItem(opts.storageKey, 'true');
            setBtnLabel();
        });

        // Set initial label
        setBtnLabel();
    } catch (e) {
        // fail safe: no-op
        console && console.warn && console.warn('setupPersistentCollapse error:', e);
    }
};
