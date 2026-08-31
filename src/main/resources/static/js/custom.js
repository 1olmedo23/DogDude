function bookingDateFromUrl() {
    const value = new URLSearchParams(window.location.search).get('date');

    if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) {
        return new Date();
    }

    const [year, month, day] = value.split('-').map(Number);
    const parsed = new Date(year, month - 1, day);

    return Number.isNaN(parsed.getTime()) ? new Date() : parsed;
}

let currentBookingDate = bookingDateFromUrl();
let currentInvoiceWeekStart = getLastCompletedWeekStart();
let paidEmailsForWeek = new Set();

// ---------- Helpers ----------
function isoFromLocalDate(d) {
    // local Date -> "YYYY-MM-DD" using local components (no UTC shift)
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${day}`;
}

function formatCurrency(n) {
    const num = Number(n || 0);
    return num.toLocaleString('en-US', { style: 'currency', currency: 'USD' });
}

function formatBookingTime(value) {
    if (!value) return '—';

    const clean = String(value).split('.')[0];
    const parts = clean.split(':');

    if (parts.length >= 2) {
        return `${parts[0]}:${parts[1]}`;
    }

    return clean;
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
// - Default delay: 20000ms
// - Override per alert with data-autoclose="7000" (ms) or data-autoclose (uses default)
// - Prevent closing by adding data-permanent or the class .alert-static
document.addEventListener('DOMContentLoaded', function () {
    const allAlerts = document.querySelectorAll('.alert');

    allAlerts.forEach(el => {
        if (el.hasAttribute('data-permanent') || el.classList.contains('alert-static')) return;

        const msRaw = el.getAttribute('data-autoclose');
        const delay = msRaw && !isNaN(+msRaw) ? parseInt(msRaw, 10) : 20000;

        setTimeout(() => {
            try {
                if (!document.body.contains(el)) return;
                if (el.hasAttribute('data-permanent') || el.classList.contains('alert-static')) return;

                if (window.bootstrap?.Alert) {
                    window.bootstrap.Alert.getOrCreateInstance(el).close();
                } else {
                    el.style.display = 'none';
                }
            } catch (_) {
                /* no-op */
            }
        }, delay);
    });
});

function escapeHtml(value) {
    return String(value ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function escapeAttr(value) {
    return escapeHtml(value);
}

// ---------- Bookings (Admin) ----------
function groupAndRenderAdminBookings(rows) {
    const container = document.getElementById('bookingServiceGroups');
    if (!container) return;

    container.innerHTML = '';

    const csrfHook = document.getElementById('csrf-hook');
    const csrfName = csrfHook?.dataset?.name || '_csrf';
    const csrfToken = csrfHook?.dataset?.token || '';

    const adjustmentOptions = [
        -100, -95, -90, -85, -80, -75, -70, -65, -60, -55,-50,
        -45, -40, -35, -30, -25, -20, -15, -10, -5,
        0,
        5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 95, 100
    ];

    const groups = {
        'Daycare (6 AM - 3 PM)': [],
        'Daycare (6 AM - 8 PM)': [],
        'Daycare After Hours (6 AM - 11 PM)': [],
        'Boarding': []
    };

    rows.forEach(r => {
        const svc = (r.serviceType || '').toLowerCase();

        if (svc.includes('daycare') && (r.serviceType || '').includes('6 AM - 3 PM')) {
            groups['Daycare (6 AM - 3 PM)'].push(r);
        } else if (svc.includes('daycare') && (r.serviceType || '').includes('6 AM - 8 PM')) {
            groups['Daycare (6 AM - 8 PM)'].push(r);
        } else if (svc.includes('daycare') && svc.includes('after hours')) {
            groups['Daycare After Hours (6 AM - 11 PM)'].push(r);
        } else if (svc.includes('boarding')) {
            groups['Boarding'].push(r);
        }
    });

    const renderOrder = [
        'Daycare (6 AM - 3 PM)',
        'Daycare (6 AM - 8 PM)',
        'Daycare After Hours (6 AM - 11 PM)',
        'Boarding'
    ];

    renderOrder.forEach((title, serviceIndex) => {
        let serviceHeaderClass = 'bg-white';

        switch (title) {
            case 'Daycare (6 AM - 3 PM)':
                serviceHeaderClass = '';
                serviceHeaderStyle = 'background-color: #086dd133;';
                break;

            case 'Daycare (6 AM - 8 PM)':
                serviceHeaderClass = '';
                serviceHeaderStyle = 'background-color: #1887f533;';
                break;

            case 'Daycare After Hours (6 AM - 11 PM)':
                serviceHeaderClass = '';
                serviceHeaderStyle = 'background-color: #086dd133;';
                break;

            case 'Boarding':
                serviceHeaderClass = '';
                serviceHeaderStyle = 'background-color: #1887f533;';
                break;
        }
        const list = groups[title] || [];
        const serviceCollapseId = `bookingServiceCollapse${serviceIndex}`;

        list.sort((a, b) => (a.time || '').localeCompare(b.time || ''));

        const countBadgeClass = list.length > 0 ? 'text-bg-primary' : 'text-bg-secondary';

        const bookingsHtml = list.length === 0
            ? `<div class="text-muted p-3">No bookings for this service.</div>`
            : list.map((b, bookingIndex) => {
                const detailsId = `bookingDetails${serviceIndex}_${b.id || bookingIndex}`;

                const isCanceled = b.status && b.status.toUpperCase() === 'CANCELED';
                const isApproved = b.status && b.status.toUpperCase() === 'APPROVED';

                const bookingBg = bookingIndex % 2 === 0
                    ? 'bg-white'
                    : 'bg-light';

                const statusBadge = isCanceled
                    ? `<span class="badge text-bg-danger">Canceled</span>`
                    : `<span class="badge text-bg-success">Booked</span>`;

                const paidBadge = b.paid
                    ? `<span class="badge text-bg-success">Paid</span>`
                    : `<span class="badge text-bg-secondary">Unpaid</span>`;

                const prepayBadge = (!b.paid && b.wantsAdvancePay && b.advanceEligible)
                    ? `<span class="badge text-bg-info">Prepay</span>`
                    : '';

                const dogBadge = (b.dogCount && b.dogCount > 1)
                    ? `<span class="badge text-bg-secondary ms-1">×${b.dogCount}</span>`
                    : '';

                const amount = b.liveAmount != null
                    ? formatCurrency(b.liveAmount)
                    : (b.quotedRateAtLock ? formatCurrency(b.quotedRateAtLock) : '—');

                const adjAmount = Number(b.manualAdjustmentAmount || 0);
                const hasAdjustment = adjAmount !== 0;
                const adjReason = (b.manualAdjustmentReason || '').trim();
                const adjSign = adjAmount > 0 ? '+' : '';

                const adjustmentSummary = hasAdjustment
                    ? `<span class="badge ${adjAmount > 0 ? 'text-bg-warning' : 'text-bg-secondary'}">
                            Adjustment: ${adjSign}${formatCurrency(adjAmount)}
                       </span>`
                    : `<span class="text-muted">No price adjustment</span>`;

                const optionsHtml = adjustmentOptions.map(v => {
                    const selected = (v === adjAmount) ? 'selected' : '';
                    const label = v > 0 ? `+${v}` : `${v}`;
                    return `<option value="${v}" ${selected}>${label}</option>`;
                }).join('');

                const adjustmentForm = !isCanceled ? `
                    <form method="POST" action="/admin/bookings/adjust/${b.id}" class="admin-adjust-form mt-3">
                        ${csrfToken ? `<input type="hidden" name="${csrfName}" value="${csrfToken}">` : ''}
                        <input type="hidden" name="date" value="${isoFromLocalDate(currentBookingDate)}">
                        <div class="d-flex align-items-end gap-3 flex-wrap mt-2">
                            <div>
                                <label class="form-label small mb-1">Adjust</label>
                                <select name="amount"
                                        class="form-select form-select-sm"
                                        style="width:90px;">
                                    ${optionsHtml}
                                </select>
                            </div>

                            <div>
                                <label class="form-label small mb-1">Message</label>
                                <input type="text"
                                       name="reason"
                                       maxlength="120"
                                       class="form-control form-control-sm"
                                       style="width:220px;"
                                       placeholder="Late pickup fee"
                                       value="${escapeAttr(adjReason)}">
                            </div>

                            <div>
                                <button type="submit"
                                        class="btn btn-outline-primary btn-sm px-3">
                                    Save
                                </button>
                            </div>
                            
                        </div>
                    </form>
                ` : '';

                const markPaidForm = (!b.paid && !isCanceled) ? `
                    <form method="POST" action="/admin/bookings/mark-paid/${b.id}" class="d-inline"
                          onsubmit="return confirm('Mark this booking as PAID?');">
                        ${csrfToken ? `<input type="hidden" name="${csrfName}" value="${csrfToken}">` : ''}
                        <input type="hidden" name="date" value="${isoFromLocalDate(currentBookingDate)}">
                        <button class="btn btn-outline-success btn-sm">Mark Paid</button>
                    </form>
                ` : '';

                const revertPaidForm = (b.paid && !isCanceled) ? `
                    <form method="POST" action="/admin/bookings/revert-paid/${b.id}" class="d-inline"
                          onsubmit="return confirm('Revert this booking back to unpaid?');">
                        ${csrfToken ? `<input type="hidden" name="${csrfName}" value="${csrfToken}">` : ''}
                        <input type="hidden" name="date" value="${isoFromLocalDate(currentBookingDate)}">
                        <button class="btn btn-outline-danger btn-sm">Revert Paid</button>
                    </form>
                ` : '';

                const cancelForm = isApproved ? `
                    <form method="POST" action="/admin/bookings/cancel/${b.id}" class="d-inline">
                        ${csrfToken ? `<input type="hidden" name="${csrfName}" value="${csrfToken}">` : ''}
                        <input type="hidden" name="date" value="${isoFromLocalDate(currentBookingDate)}">
                        <button class="btn btn-danger-custom btn-sm cancel-booking-btn">Cancel</button>
                    </form>
                ` : '';

                return `
                    <div class="border rounded mb-2 ${bookingBg}">
                        <button class="btn w-100 text-start p-3"
                                type="button"
                                data-bs-toggle="collapse"
                                data-bs-target="#${detailsId}"
                                aria-expanded="false"
                                aria-controls="${detailsId}">
                            <div class="d-flex justify-content-between align-items-start gap-3 flex-wrap">
                                <div>
                                    <div class="fw-semibold">
                                        ${escapeHtml(b.customerName || 'Customer')}
                                    </div>
                                    <div class="text-muted small">
                                        ${escapeHtml(b.dogName || 'N/A')}${dogBadge}
                                    </div>
                                </div>

                                <div class="text-end">
                                    <div class="fw-semibold">${escapeHtml(formatBookingTime(b.time))}</div>
                                    <div class="mt-1 d-flex gap-1 justify-content-end flex-wrap">
                                        <span class="badge text-bg-light text-dark border">Price: ${amount}</span>
                                        ${statusBadge}
                                        ${paidBadge}
                                        ${prepayBadge}
                                    </div>
                                </div>
                            </div>
                        </button>

                        <div id="${detailsId}" class="collapse">
                            <div class="border-top p-3">
                                <div class="row g-3">
                                    <div class="col-md-4">
                                        <div class="text-muted small">Customer</div>
                                        <div class="fw-semibold">${escapeHtml(b.customerName || '—')}</div>
                                    </div>

                                    <div class="col-md-4">
                                        <div class="text-muted small">Dog</div>
                                        <div class="fw-semibold">${escapeHtml(b.dogName || 'N/A')}${dogBadge}</div>
                                    </div>

                                    <div class="col-md-4">
                                        <div class="text-muted small">Price</div>
                                        <div class="fw-semibold">${amount}</div>
                                    </div>

                                    <div class="col-md-4">
                                        <div class="text-muted small">Adjustment</div>
                                        <div>${adjustmentSummary}</div>
                                        ${adjReason ? `<div class="text-muted small mt-1">${escapeHtml(adjReason)}</div>` : ''}
                                    </div>
                                </div>

                                ${adjustmentForm}

                                <div class="d-flex gap-2 flex-wrap mt-3">
                                    ${markPaidForm}
                                    ${revertPaidForm}
                                    ${cancelForm}
                                </div>
                            </div>
                        </div>
                    </div>
                `;
            }).join('');

        const serviceHtml = `
            <div class="card shadow-sm">
                <button class="card-header ${serviceHeaderClass} btn w-100 text-start"
                        style="${serviceHeaderStyle}"
                        type="button"
                        data-bs-toggle="collapse"
                        data-bs-target="#${serviceCollapseId}"
                        aria-expanded="true"
                        aria-controls="${serviceCollapseId}">
                    <div class="d-flex justify-content-between align-items-center gap-3">
                        <div>
                            <h5 class="mb-1">${escapeHtml(title)}</h5>
                            
                        </div>
                        <span class="badge ${countBadgeClass}">${list.length}</span>
                    </div>
                </button>

                <div id="${serviceCollapseId}" class="collapse show">
                    <div class="card-body">
                        ${bookingsHtml}
                    </div>
                </div>
            </div>
        `;

        container.insertAdjacentHTML('beforeend', serviceHtml);
    });

    attachCancelConfirm();
}

function fetchBookings() {
    const dateStr = isoFromLocalDate(currentBookingDate);
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
    return isoFromLocalDate(d);
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

function highlightBookingDetails() {
    const card = document.querySelector('.booking-flow-card');
    if (!card) return;

    card.classList.add('is-active');

    //  remove highlight after a few seconds
   // setTimeout(() => {
        //card.classList.remove('is-active');
    //}, 4000);
}

document.addEventListener('click', function(e) {
    if (e.target.closest('.svc-btn')) {
        highlightBookingDetails();
    }
});
