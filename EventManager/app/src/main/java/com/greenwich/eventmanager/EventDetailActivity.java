package com.greenwich.eventmanager;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.graphics.Typeface;
import android.content.res.ColorStateList;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputLayout;
import com.greenwich.eventmanager.adapter.SessionAdapter;
import com.greenwich.eventmanager.data.DatabaseHelper;
import com.greenwich.eventmanager.data.SyncManager;
import com.greenwich.eventmanager.model.Event;
import com.greenwich.eventmanager.model.Session;
import com.google.zxing.WriterException;
import com.greenwich.eventmanager.util.DateUtils;
import com.greenwich.eventmanager.util.DetailRows;
import com.greenwich.eventmanager.util.EventFormat;
import com.greenwich.eventmanager.util.EventStatus;
import com.greenwich.eventmanager.util.IcsBuilder;
import com.greenwich.eventmanager.util.QrUtils;
import com.greenwich.eventmanager.util.ShareUtils;
import com.greenwich.eventmanager.util.UiUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Shows one event and its sessions (features b, c, d).
 *  - Status card: badge, countdown / "Day N of M", progress, start/end and the reason
 *    for postponed / cancelled events. "Change status" opens a bottom sheet.
 *  - Toolbar: Edit event, Delete event (deletes its sessions too).
 *  - "Open in Maps" opens the location in Google Maps or any maps app.
 *  - Sessions are listed by date and time, grouped by day for multi-day events,
 *    and can be searched by name or description.
 */
public class EventDetailActivity extends AppCompatActivity {

    public static final String EXTRA_EVENT_ID = "extra_event_id";
    private static final int QR_SIZE_PX = 720;
    private static final long REFRESH_MS = 60_000L;

    private DatabaseHelper db;
    private long eventId;
    private Event event;

    private MaterialToolbar toolbar;
    private LinearLayout detailContainer;
    private TextView tvSessionsHeader, tvSessionsEmpty;
    private TextInputLayout tilSearchSessions;
    private SessionAdapter sessionAdapter;
    private NestedScrollView scrollView;
    private RecyclerView rvSessions;
    private ExtendedFloatingActionButton fabAddSession;

    // Title block + status card
    private TextView tvDetailType, tvPublishState, tvDetailName, tvDetailStatus,
            tvDetailCountdown, tvDetailStart, tvDetailEnd, tvDetailReason;
    private LinearProgressIndicator progressDetail;

    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTick = new Runnable() {
        @Override
        public void run() {
            if (event != null) {
                showStatus();
                sessionAdapter.setNow(System.currentTimeMillis());
            }
            refreshHandler.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_event_detail);
        // Include the keyboard so the content shrinks above it instead of being covered
        UiUtils.applyInsets(findViewById(R.id.main), true, this::onKeyboardVisibilityChanged);

        db = DatabaseHelper.getInstance(this);
        eventId = getIntent().getLongExtra(EXTRA_EVENT_ID, -1);
        if (eventId == -1) {
            finish();
            return;
        }

        toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.menu_event_detail);
        toolbar.setOnMenuItemClickListener(this::onMenuItemClick);

        detailContainer = findViewById(R.id.detailContainer);
        tvDetailType = findViewById(R.id.tvDetailType);
        tvPublishState = findViewById(R.id.tvPublishState);
        tvDetailName = findViewById(R.id.tvDetailName);
        tvDetailStatus = findViewById(R.id.tvDetailStatus);
        tvDetailCountdown = findViewById(R.id.tvDetailCountdown);
        tvDetailStart = findViewById(R.id.tvDetailStart);
        tvDetailEnd = findViewById(R.id.tvDetailEnd);
        tvDetailReason = findViewById(R.id.tvDetailReason);
        progressDetail = findViewById(R.id.progressDetail);
        tvSessionsHeader = findViewById(R.id.tvSessionsHeader);
        tvSessionsEmpty = findViewById(R.id.tvSessionsEmpty);
        tilSearchSessions = findViewById(R.id.tilSearchSessions);

        scrollView = findViewById(R.id.scrollView);
        fabAddSession = findViewById(R.id.fabAddSession);

        sessionAdapter = new SessionAdapter(this::openEditSession, this::showSessionQr);
        rvSessions = findViewById(R.id.rvSessions);
        rvSessions.setAdapter(sessionAdapter);

        // Feature d: filter sessions as the user types
        UiUtils.onTextChanged(tilSearchSessions, this::loadSessions);
        setupSearchBehaviour();

        fabAddSession.setOnClickListener(v -> openAddSession());
        findViewById(R.id.btnOpenMap).setOnClickListener(v -> openInMaps());
        findViewById(R.id.btnChangeStatus).setOnClickListener(v -> showChangeStatusSheet());
    }

    /** Reload when coming back from editing the event or a session. */
    @Override
    protected void onResume() {
        super.onResume();
        event = db.getEvent(eventId);
        if (event == null) {          // deleted elsewhere
            finish();
            return;
        }
        showEvent();
        loadSessions();
        refreshHandler.postDelayed(refreshTick, REFRESH_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        refreshHandler.removeCallbacks(refreshTick);
    }

    private void showEvent() {
        tvDetailName.setText(event.getName());
        tvDetailType.setText(event.getType());
        showStatus();

        detailContainer.removeAllViews();
        DetailRows.add(detailContainer, R.string.label_dates, EventFormat.formatDates(this, event));
        DetailRows.add(detailContainer, R.string.label_time, EventFormat.formatTimes(this, event));
        DetailRows.add(detailContainer, R.string.label_location, event.getLocation());
        DetailRows.add(detailContainer, R.string.label_organiser, event.getOrganiser());
        DetailRows.add(detailContainer, R.string.label_description, event.getDescription());
        DetailRows.add(detailContainer, R.string.label_email, event.getContactEmail());
        DetailRows.add(detailContainer, R.string.label_phone, event.getContactPhone());
    }

    /** Title-block state line and the status card (re-run every minute). */
    private void showStatus() {
        long now = System.currentTimeMillis();
        String status = EventStatus.displayStatus(event, now);

        // "Published · Synced" (manual status + sync state)
        tvPublishState.setText(getString(R.string.format_meta_join,
                EventFormat.statusLabel(this, event.getStatus()),
                getString(event.isDirty() ? R.string.status_not_synced : R.string.status_synced)));
        tvPublishState.setTextColor(ContextCompat.getColor(this,
                event.isDirty() ? R.color.status_pending : R.color.status_synced));

        // Badge: "HAPPENING NOW" reads better than "LIVE" on the detail screen
        if (EventStatus.ONGOING.equals(status)) {
            UiUtils.bindStatusBadge(tvDetailStatus, status, getString(R.string.badge_happening_now));
        } else {
            UiUtils.bindStatusBadge(tvDetailStatus, status);
        }
        tvDetailCountdown.setText(EventFormat.countdown(this, event, now));

        progressDetail.setIndicatorColor(ContextCompat.getColor(this, UiUtils.statusAccent(status)));
        progressDetail.setProgressCompat(Math.round(EventStatus.progress(event, now) * 1000), false);
        boolean timed = !EventStatus.DRAFT.equals(status) && !EventStatus.CANCELLED.equals(status);
        progressDetail.setVisibility(timed ? View.VISIBLE : View.GONE);

        long start = EventStatus.startMillis(event);
        long end = EventStatus.endMillis(event);
        boolean started = now >= start;
        boolean ended = now >= end;
        tvDetailStart.setText(getString(started ? R.string.format_started_at : R.string.format_starts_at,
                EventFormat.formatDayDateTime(start)));
        tvDetailEnd.setText(getString(ended ? R.string.format_ended_at : R.string.format_ends_at,
                EventFormat.formatDayDateTime(end)));

        boolean hasReason = Event.statusNeedsReason(status) && !TextUtils.isEmpty(event.getStatusNote());
        tvDetailReason.setVisibility(hasReason ? View.VISIBLE : View.GONE);
        if (hasReason) {
            tvDetailReason.setText(getString(R.string.format_reason, event.getStatusNote()));
            UiUtils.tintStatus(tvDetailReason, status);
        }
    }

    // =====================================================================
    // Change status (bottom sheet)
    // =====================================================================

    private void showChangeStatusSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View sheet = getLayoutInflater().inflate(R.layout.sheet_change_status, null);
        dialog.setContentView(sheet);

        ((TextView) sheet.findViewById(R.id.tvSheetSubtitle)).setText(getString(
                R.string.format_meta_join, event.getName(), EventFormat.formatShortDates(this, event)));

        RadioGroup group = sheet.findViewById(R.id.rgStatus);
        TextInputLayout tilReason = sheet.findViewById(R.id.tilReason);
        int[] ids = {R.id.rbDraft, R.id.rbPublished, R.id.rbPostponed, R.id.rbCancelled};
        int[] descs = {R.string.status_desc_draft, R.string.status_desc_published,
                R.string.status_desc_postponed, R.string.status_desc_cancelled};
        for (int i = 0; i < ids.length; i++) {
            MaterialRadioButton rb = sheet.findViewById(ids[i]);
            String status = Event.STATUSES[i];
            rb.setTag(status);
            rb.setText(optionText(status, getString(descs[i])));
            // Radio circle in the status accent colour (primary for Published)
            int accent = Event.STATUS_PUBLISHED.equals(status)
                    ? ContextCompat.getColor(this, R.color.md_primary)
                    : ContextCompat.getColor(this, UiUtils.statusAccent(status));
            rb.setButtonTintList(ColorStateList.valueOf(accent));
            if (status.equals(event.getStatus())) rb.setChecked(true);
        }

        UiUtils.setText(tilReason, event.getStatusNote());
        Runnable updateReason = () -> {
            boolean needs = Event.statusNeedsReason(checkedStatus(group));
            tilReason.setVisibility(needs ? View.VISIBLE : View.GONE);
            if (!needs) tilReason.setError(null);
        };
        updateReason.run();
        group.setOnCheckedChangeListener((g, id) -> updateReason.run());
        UiUtils.onTextChanged(tilReason, () -> tilReason.setError(null));

        sheet.findViewById(R.id.btnSheetCancel).setOnClickListener(v -> dialog.dismiss());
        sheet.findViewById(R.id.btnSheetSave).setOnClickListener(v -> {
            String status = checkedStatus(group);
            String reason = UiUtils.text(tilReason);
            if (status == null) return;
            if (Event.statusNeedsReason(status) && reason.isEmpty()) {
                tilReason.setError(getString(R.string.error_status_reason_required));
                return;
            }
            db.updateEventStatus(eventId, status, reason);
            dialog.dismiss();
            event = db.getEvent(eventId);
            showStatus();
            syncNow();
        });
        dialog.show();
    }

    /** "Postponed" in bold, then the description on a second, smaller grey line. */
    private CharSequence optionText(String status, String description) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(EventFormat.statusLabel(this, status));
        sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int start = sb.length();
        sb.append('\n').append(description);
        sb.setSpan(new RelativeSizeSpan(0.86f), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(ContextCompat.getColor(this, R.color.text_secondary)),
                start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private static String checkedStatus(RadioGroup group) {
        View checked = group.findViewById(group.getCheckedRadioButtonId());
        return checked == null ? null : (String) checked.getTag();
    }

    /** Upload straight away so users see the new status; offline it syncs later. */
    private void syncNow() {
        SyncManager.syncAsync(this, false, new SyncManager.Callback() {
            @Override
            public void onSuccess(SyncManager.Result result) {
                if (isDestroyed()) return;
                event = db.getEvent(eventId);
                if (event != null) showStatus();
                showSnackbar(R.string.msg_status_updated);
            }

            @Override
            public void onError(SyncManager.SyncException error) {
                if (isDestroyed()) return;
                showSnackbar(R.string.msg_status_saved_offline);
            }
        });
    }

    private void loadSessions() {
        if (event == null) return;
        String query = UiUtils.text(tilSearchSessions);
        List<Session> sessions = query.isEmpty()
                ? db.getSessionsForEvent(eventId)
                : db.searchSessions(eventId, query);

        // Sessions of a draft / postponed / cancelled event are never shown as "live"
        sessionAdapter.setShowLive(Event.STATUS_PUBLISHED.equals(event.getStatus()));
        sessionAdapter.setNow(System.currentTimeMillis());
        sessionAdapter.setSessions(sessions, event.getStartDate(), event.isMultiDay());

        int total = event.getSessionCount();
        tvSessionsHeader.setText(getString(R.string.section_sessions_count, total));
        // No point showing a search box when there is nothing to search
        tilSearchSessions.setVisibility(total == 0 ? View.GONE : View.VISIBLE);

        if (sessions.isEmpty()) {
            tvSessionsEmpty.setVisibility(View.VISIBLE);
            tvSessionsEmpty.setText(query.isEmpty()
                    ? getString(R.string.empty_sessions)
                    : getString(R.string.empty_sessions_search, query));
        } else {
            tvSessionsEmpty.setVisibility(View.GONE);
        }
    }

    // =====================================================================
    // Search box behaviour with the on-screen keyboard
    // =====================================================================

    /**
     * When the keyboard opens for the search box, the page scrolls so that the
     * "Sessions (n)" heading and the search box sit at the top of the screen.
     * The results then appear between the search box and the keyboard.
     *
     * This is triggered by the keyboard opening (not by focus), because:
     *  - only then is the final screen height known, and
     *  - the "clear text" button of TextInputLayout uses the field's focus listener itself.
     */
    private void setupSearchBehaviour() {
        EditText searchField = tilSearchSessions.getEditText();
        if (searchField == null) return;

        // "Search" key on the keyboard: just close the keyboard, keep the results
        searchField.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard();
                return true;
            }
            return false;
        });
    }

    /** The FAB would float above the keyboard and cover the results, so hide it. */
    private void onKeyboardVisibilityChanged(boolean keyboardOpen) {
        EditText searchField = tilSearchSessions.getEditText();
        boolean searching = searchField != null && searchField.hasFocus();

        if (keyboardOpen) {
            fabAddSession.hide();
            if (searching) pinSearchToTop();
        } else {
            fabAddSession.show();
            if (searchField != null) searchField.clearFocus();
            if (UiUtils.text(tilSearchSessions).isEmpty()) {
                rvSessions.setMinimumHeight(0);   // back to normal
            }
        }
    }

    private void pinSearchToTop() {
        // Keep the list at least one screen tall, so few (or no) results don't
        // shorten the page and drag the search box back down to the keyboard
        rvSessions.setMinimumHeight(scrollView.getHeight());
        // Run after the layout has been resized for the keyboard
        scrollView.post(() -> scrollView.smoothScrollTo(0, tvSessionsHeader.getTop()));
    }

    private void hideKeyboard() {
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .hide(WindowInsetsCompat.Type.ime());
    }

    // =====================================================================
    // Actions
    // =====================================================================

    private boolean onMenuItemClick(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_edit_event) {
            Intent intent = new Intent(this, EventFormActivity.class);
            intent.putExtra(EventFormActivity.EXTRA_EVENT_ID, eventId);
            startActivity(intent);
            return true;
        } else if (id == R.id.action_export_schedule) {
            exportSchedule();
            return true;
        } else if (id == R.id.action_delete_event) {
            confirmDeleteEvent();
            return true;
        }
        return false;
    }

    /** Destructive action: always ask first and say what else will be deleted. */
    private void confirmDeleteEvent() {
        int count = event.getSessionCount();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_event_title)
                .setMessage(count == 0
                        ? getString(R.string.delete_event_message_no_sessions)
                        : getResources().getQuantityString(
                        R.plurals.delete_event_message, count, count))
                .setPositiveButton(R.string.action_delete, (d, w) -> {
                    db.deleteEvent(eventId);
                    Toast.makeText(this, R.string.msg_event_deleted, Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /**
     * Opens the location in a maps app. Uses the saved coordinates when available
     * (exact pin), otherwise lets the maps app search for the address text.
     */
    private void openInMaps() {
        Uri uri;
        if (event.hasCoordinates()) {
            String latLng = event.getLatitude() + "," + event.getLongitude();
            uri = Uri.parse("geo:" + latLng + "?q=" + latLng
                    + "(" + Uri.encode(event.getName()) + ")");
        } else {
            uri = Uri.parse("geo:0,0?q=" + Uri.encode(event.getLocation()));
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            Snackbar.make(findViewById(R.id.main), R.string.error_no_maps_app,
                    Snackbar.LENGTH_LONG).show();
        }
    }

    // =====================================================================
    // Extra features: calendar export + session QR code
    // =====================================================================

    /** Writes every session to a .ics calendar file and opens the share sheet. */
    private void exportSchedule() {
        String ics = IcsBuilder.scheduleAsCalendar(event, db.getSessionsForEvent(eventId));
        try {
            Uri uri = ShareUtils.writeToCache(this,
                    ShareUtils.safeFileName(event.getName()) + ".ics",
                    ics.getBytes(StandardCharsets.UTF_8));
            ShareUtils.share(this, uri, "text/calendar", getString(R.string.title_share_schedule));
        } catch (IOException e) {
            showSnackbar(R.string.error_export_failed);
        }
    }

    /** Bottom sheet with the session's QR code (scan -> add to calendar). */
    private void showSessionQr(Session session) {
        Bitmap qr;
        try {
            qr = QrUtils.generate(IcsBuilder.sessionAsVEvent(event, session), QR_SIZE_PX);
        } catch (WriterException e) {
            showSnackbar(R.string.error_export_failed);
            return;
        }

        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View sheet = getLayoutInflater().inflate(R.layout.sheet_session_qr, null);
        dialog.setContentView(sheet);

        ((TextView) sheet.findViewById(R.id.tvQrTitle)).setText(session.getName());
        ((TextView) sheet.findViewById(R.id.tvQrSubtitle)).setText(getString(
                R.string.format_dates_with_days,
                DateUtils.isoToDisplay(session.getSessionDate()),
                EventFormat.formatTimeRange(this, session.getStartTime(), session.getEndTime())));
        ((ImageView) sheet.findViewById(R.id.ivQr)).setImageBitmap(qr);

        sheet.findViewById(R.id.btnShareQr).setOnClickListener(v -> {
            try {
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                qr.compress(Bitmap.CompressFormat.PNG, 100, png);
                Uri uri = ShareUtils.writeToCache(this,
                        "QR_" + ShareUtils.safeFileName(session.getName()) + ".png",
                        png.toByteArray());
                ShareUtils.share(this, uri, "image/png", getString(R.string.title_share_qr));
            } catch (IOException e) {
                showSnackbar(R.string.error_export_failed);
            }
        });
        dialog.show();
    }

    private void showSnackbar(int messageRes) {
        Snackbar.make(findViewById(R.id.main), messageRes, Snackbar.LENGTH_LONG).show();
    }

    private void openAddSession() {
        Intent intent = new Intent(this, SessionFormActivity.class);
        intent.putExtra(SessionFormActivity.EXTRA_EVENT_ID, eventId);
        startActivity(intent);
    }

    private void openEditSession(Session session) {
        Intent intent = new Intent(this, SessionFormActivity.class);
        intent.putExtra(SessionFormActivity.EXTRA_EVENT_ID, eventId);
        intent.putExtra(SessionFormActivity.EXTRA_SESSION_ID, session.getId());
        startActivity(intent);
    }
}