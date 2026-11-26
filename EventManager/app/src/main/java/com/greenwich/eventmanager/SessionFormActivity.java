package com.greenwich.eventmanager;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputLayout;
import com.greenwich.eventmanager.data.DatabaseHelper;
import com.greenwich.eventmanager.model.Event;
import com.greenwich.eventmanager.model.Session;
import com.greenwich.eventmanager.util.DateUtils;
import com.greenwich.eventmanager.util.EventFormat;
import com.greenwich.eventmanager.util.EventStatus;
import com.greenwich.eventmanager.util.UiUtils;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static com.greenwich.eventmanager.util.UiUtils.emptyToNull;
import static com.greenwich.eventmanager.util.UiUtils.onTextChanged;
import static com.greenwich.eventmanager.util.UiUtils.setText;
import static com.greenwich.eventmanager.util.UiUtils.text;

/**
 * Add / edit / delete a session of an event (feature b).
 *
 * Pass EXTRA_EVENT_ID (required) and EXTRA_SESSION_ID (only when editing).
 *  - The day is picked from chips, one per day of the event, so it is always in range.
 *  - A new session starts when the last session of that day ends (or when the event
 *    starts); the end time is suggested as start + 1 hour until the user picks their own.
 *  - If the room is already used at that time, a warning appears under Room straight
 *    away; the admin can still save.
 */
public class SessionFormActivity extends AppCompatActivity {

    public static final String EXTRA_EVENT_ID = "extra_event_id";
    public static final String EXTRA_SESSION_ID = "extra_session_id";

    private static final int DEFAULT_DURATION_MINUTES = 60;
    private static final int LATEST_SUGGESTED_START = 23 * 60;   // don't suggest after 23:00

    private DatabaseHelper db;
    private Event event;
    private Session editingSession;      // null when adding

    private boolean formChanged;
    private boolean autoFilling;
    private boolean startTimeSetByUser;
    private boolean endTimeSetByUser;
    private String selectedDateIso;

    private TextInputLayout tilName, tilStartTime, tilEndTime, tilOtherType,
            tilSpeaker, tilRoom, tilDescription;
    private ChipGroup chipGroupType, chipGroupDays;
    private TextView tvTypeError, tvSessionDuration, tvEventHours, tvRoomClash;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_session_form);
        UiUtils.applyInsets(findViewById(R.id.main), true);

        db = DatabaseHelper.getInstance(this);
        event = db.getEvent(getIntent().getLongExtra(EXTRA_EVENT_ID, -1));
        if (event == null) {
            finish();
            return;
        }
        long sessionId = getIntent().getLongExtra(EXTRA_SESSION_ID, -1);
        if (sessionId != -1) {
            editingSession = db.getSession(sessionId);
            if (editingSession == null) {
                finish();
                return;
            }
        }

        bindViews();
        showEventContext();
        setupToolbar();
        setupTypeChips();
        setupTimeFields();

        if (editingSession != null) {
            startTimeSetByUser = true;
            endTimeSetByUser = true;   // keep the saved end time (even if it was empty)
            fillForm(editingSession);
        }
        setupDayChips();              // after fillForm: selects the session's day (or a default)

        setupLiveHints();
        setupChangeTracking();        // after filling, so loaded values don't count as changes
        setupBackHandling();
        findViewById(R.id.btnSaveSession).setOnClickListener(v -> onSaveClicked());
    }

    private void bindViews() {
        tilName = findViewById(R.id.tilSessionName);
        tilStartTime = findViewById(R.id.tilStartTime);
        tilEndTime = findViewById(R.id.tilEndTime);
        tilOtherType = findViewById(R.id.tilOtherType);
        tilSpeaker = findViewById(R.id.tilSpeaker);
        tilRoom = findViewById(R.id.tilRoom);
        tilDescription = findViewById(R.id.tilDescription);
        chipGroupType = findViewById(R.id.chipGroupType);
        chipGroupDays = findViewById(R.id.chipGroupDays);
        tvTypeError = findViewById(R.id.tvTypeError);
        tvSessionDuration = findViewById(R.id.tvSessionDuration);
        tvEventHours = findViewById(R.id.tvEventHours);
        tvRoomClash = findViewById(R.id.tvRoomClash);
    }

    /** "Adding to" card: date block, event name and dates. */
    private void showEventContext() {
        ((TextView) findViewById(R.id.tvCtxMonth)).setText(EventFormat.monthShort(event.getStartDate()));
        ((TextView) findViewById(R.id.tvCtxDay)).setText(EventFormat.dayOfMonth(event.getStartDate()));
        ((TextView) findViewById(R.id.tvCtxName)).setText(event.getName());
        ((TextView) findViewById(R.id.tvCtxDates)).setText(EventFormat.formatShortDates(this, event));
        tvEventHours.setText(getString(R.string.format_event_hours, EventFormat.formatTimes(this, event)));
    }

    private void setupToolbar() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        if (editingSession == null) {
            toolbar.setTitle(R.string.title_add_session);
        } else {
            toolbar.setTitle(getString(R.string.title_edit_session, editingSession.getId()));
            toolbar.inflateMenu(R.menu.menu_session_form);
            toolbar.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == R.id.action_delete_session) {
                    confirmDelete();
                    return true;
                }
                return false;
            });
        }
    }

    private void fillForm(Session s) {
        setText(tilName, s.getName());
        selectedDateIso = s.getSessionDate();
        setText(tilStartTime, s.getStartTime());
        setText(tilEndTime, s.getEndTime());
        if (Arrays.asList(Session.TYPES).contains(s.getType())) {
            checkTypeChip(s.getType());
        } else {                                   // custom type typed under "Others"
            checkTypeChip(Session.TYPE_OTHERS);
            setText(tilOtherType, s.getType());
        }
        updateOtherTypeVisibility();
        setText(tilSpeaker, s.getSpeaker());
        setText(tilRoom, s.getRoom());
        setText(tilDescription, s.getDescription());
    }

    // =====================================================================
    // Day: one chip per day of the event
    // =====================================================================

    private void setupDayChips() {
        long first = EventStatus.toMillis(event.getStartDate(), null);
        int days = DateUtils.daysBetweenInclusive(event.getStartDate(), event.getEndDate());
        String today = DateUtils.todayIso();
        if (selectedDateIso == null) {
            // New session: today if the event is running, otherwise its first day
            selectedDateIso = DateUtils.isWithinRange(today, event.getStartDate(), event.getEndDate())
                    ? today : event.getStartDate();
        }

        SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        SimpleDateFormat shortDay = new SimpleDateFormat("EEE dd", Locale.US);
        SimpleDateFormat longDay = new SimpleDateFormat("EEE dd MMM", Locale.US);
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(first);
        for (int i = 1; i <= days; i++) {
            Date date = c.getTime();
            String iso = isoFormat.format(date);
            Chip chip = (Chip) getLayoutInflater().inflate(R.layout.item_type_chip, chipGroupDays, false);
            chip.setId(View.generateViewId());
            chip.setTag(iso);
            chip.setText(days == 1 ? longDay.format(date)
                    : getString(R.string.format_day_chip, i, shortDay.format(date)));
            chipGroupDays.addView(chip);
            if (iso.equals(selectedDateIso)) chipGroupDays.check(chip.getId());
            c.add(Calendar.DAY_OF_MONTH, 1);
        }

        chipGroupDays.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            selectedDateIso = (String) group.findViewById(checkedIds.get(0)).getTag();
            formChanged = true;
            if (!startTimeSetByUser) suggestStartTime();
            updateRoomClash();
        });

        if (editingSession == null) suggestStartTime();
    }

    /**
     * New session: start when the latest session of that day ends, so sessions follow
     * each other; with no session yet, start when the event starts.
     */
    private void suggestStartTime() {
        int startMinutes = toMinutes(event.getStartTime());
        for (Session s : db.getSessionsForEvent(event.getId())) {
            if (!s.getSessionDate().equals(selectedDateIso)) continue;
            int end = s.getEndTime() != null && DateUtils.isValidTime(s.getEndTime())
                    ? toMinutes(s.getEndTime())
                    : toMinutes(s.getStartTime()) + DEFAULT_DURATION_MINUTES;
            startMinutes = Math.max(startMinutes, end);
        }
        startMinutes = Math.min(startMinutes, LATEST_SUGGESTED_START);
        autoFilling = true;
        setText(tilStartTime, String.format(Locale.US, "%02d:%02d", startMinutes / 60, startMinutes % 60));
        autoFilling = false;
    }

    private static int toMinutes(String hhmm) {
        return Integer.parseInt(hhmm.substring(0, 2)) * 60 + Integer.parseInt(hhmm.substring(3, 5));
    }

    // =====================================================================
    // Times: picked from a clock; end time suggested as start + 1 hour
    // =====================================================================

    private void setupTimeFields() {
        setupTimeField(tilStartTime);
        setupTimeField(tilEndTime);

        onTextChanged(tilStartTime, () -> {
            if (!autoFilling) startTimeSetByUser = true;
            String start = text(tilStartTime);
            if (DateUtils.isValidTime(start) && !endTimeSetByUser) {
                boolean wasAutoFilling = autoFilling;
                autoFilling = true;
                setText(tilEndTime, DateUtils.addMinutesCapped(start, DEFAULT_DURATION_MINUTES));
                autoFilling = wasAutoFilling;
            }
        });
        onTextChanged(tilEndTime, () -> {
            if (!autoFilling) endTimeSetByUser = !text(tilEndTime).isEmpty();
        });
    }

    private void setupTimeField(TextInputLayout til) {
        // Default the clock to the event's start hour, e.g. 09:00
        int defaultHour = Integer.parseInt(event.getStartTime().substring(0, 2));
        EditText field = til.getEditText();
        if (field != null) {
            field.setOnClickListener(v -> UiUtils.showTimePicker(this, til, defaultHour));
        }
    }

    // =====================================================================
    // Live hints: session length + room clash
    // =====================================================================

    private void setupLiveHints() {
        TextInputLayout[] fields = {tilStartTime, tilEndTime, tilRoom};
        for (TextInputLayout til : fields) {
            onTextChanged(til, () -> {
                updateDuration();
                updateRoomClash();
            });
        }
        updateDuration();
        updateRoomClash();
    }

    /** "1 h", "45 min", "1 h 30 min" (no end time = the default 60 minutes). */
    private void updateDuration() {
        String start = text(tilStartTime);
        String end = text(tilEndTime);
        if (!DateUtils.isValidTime(start)) {
            tvSessionDuration.setVisibility(View.GONE);
            return;
        }
        int minutes = DateUtils.isValidTime(end) ? toMinutes(end) - toMinutes(start)
                : DEFAULT_DURATION_MINUTES;
        if (minutes <= 0) {
            tvSessionDuration.setVisibility(View.GONE);
            return;
        }
        String label;
        if (minutes < 60) {
            label = getString(R.string.duration_minutes, minutes);
        } else if (minutes % 60 == 0) {
            label = getString(R.string.duration_hours, minutes / 60);
        } else {
            label = getString(R.string.duration_hours_minutes, minutes / 60, minutes % 60);
        }
        tvSessionDuration.setText(label);
        tvSessionDuration.setVisibility(View.VISIBLE);
    }

    /** Warns under Room as soon as another session uses it at the same time. */
    private void updateRoomClash() {
        Session probe = buildProbe();
        List<Session> clashes = probe == null ? null : db.findRoomClashes(probe);
        if (clashes == null || clashes.isEmpty()) {
            tvRoomClash.setVisibility(View.GONE);
            return;
        }
        Session first = clashes.get(0);
        String range = EventFormat.formatTimeRange(this, first.getStartTime(), first.getEndTime());
        int others = clashes.size() - 1;
        tvRoomClash.setText(others == 0
                ? getString(R.string.room_clash_inline, probe.getRoom(), range, first.getName())
                : getResources().getQuantityString(R.plurals.room_clash_inline_more, others,
                        probe.getRoom(), range, first.getName(), others));
        tvRoomClash.setVisibility(View.VISIBLE);
    }

    /** Session with only the fields the clash check needs, or null if they are incomplete. */
    private Session buildProbe() {
        String room = text(tilRoom);
        String start = text(tilStartTime);
        if (room.isEmpty() || selectedDateIso == null || !DateUtils.isValidTime(start)) return null;
        Session s = new Session();
        s.setId(editingSession == null ? 0 : editingSession.getId());
        s.setEventId(event.getId());
        s.setRoom(room);
        s.setSessionDate(selectedDateIso);
        s.setStartTime(start);
        String end = text(tilEndTime);
        s.setEndTime(DateUtils.isValidTime(end) ? end : null);
        return s;
    }

    // =====================================================================
    // Type: chips; "Others" reveals a free-text field
    // =====================================================================

    private void setupTypeChips() {
        for (String type : Session.TYPES) {
            Chip chip = (Chip) getLayoutInflater().inflate(R.layout.item_type_chip, chipGroupType, false);
            chip.setId(View.generateViewId());
            chip.setText(type);
            chip.setTag(type);
            chipGroupType.addView(chip);
        }
        chipGroupType.setOnCheckedStateChangeListener((group, checkedIds) -> {
            formChanged = true;
            tvTypeError.setVisibility(View.GONE);
            updateOtherTypeVisibility();
        });
    }

    private String selectedType() {
        int id = chipGroupType.getCheckedChipId();
        View chip = id == View.NO_ID ? null : chipGroupType.findViewById(id);
        return chip == null ? null : (String) chip.getTag();
    }

    private void checkTypeChip(String type) {
        for (int i = 0; i < chipGroupType.getChildCount(); i++) {
            View child = chipGroupType.getChildAt(i);
            if (type.equals(child.getTag())) {
                chipGroupType.check(child.getId());
                return;
            }
        }
    }

    private void updateOtherTypeVisibility() {
        boolean isOther = Session.TYPE_OTHERS.equals(selectedType());
        tilOtherType.setVisibility(isOther ? View.VISIBLE : View.GONE);
        if (!isOther) setText(tilOtherType, "");
    }

    // =====================================================================
    // Validate and save
    // =====================================================================

    private void onSaveClicked() {
        Session session = validateForm();
        if (session == null) {
            Snackbar.make(findViewById(R.id.main), R.string.error_fix_fields,
                    Snackbar.LENGTH_SHORT).show();
            return;
        }
        // A room clash is already shown under Room; it does not block saving
        save(session);
    }

    private Session validateForm() {
        TextInputLayout firstError = null;

        String name = text(tilName);
        if (name.isEmpty()) {
            firstError = showError(tilName, getString(R.string.error_session_name_required), firstError);
        }

        // The day chips only offer the event's days; this is a safety net
        boolean dateOk = selectedDateIso != null
                && DateUtils.isWithinRange(selectedDateIso, event.getStartDate(), event.getEndDate());

        String start = text(tilStartTime);
        if (!DateUtils.isValidTime(start)) {
            firstError = showError(tilStartTime, getString(R.string.error_time_required), firstError);
        }

        String end = text(tilEndTime);
        if (!end.isEmpty() && DateUtils.isValidTime(start) && !DateUtils.isTimeBefore(start, end)) {
            firstError = showError(tilEndTime, getString(R.string.error_end_time_before_start), firstError);
        }

        String type = selectedType();
        boolean typeMissing = type == null;
        tvTypeError.setVisibility(typeMissing ? View.VISIBLE : View.GONE);
        if (Session.TYPE_OTHERS.equals(type) && !text(tilOtherType).isEmpty()) {
            type = text(tilOtherType);
        }

        if (firstError != null || typeMissing || !dateOk) {
            if (firstError != null) {
                EditText field = firstError.getEditText();
                if (field != null) field.requestFocus();
            }
            return null;
        }

        Session s = new Session();
        if (editingSession != null) {            // keep the same ID and cloud key
            s.setId(editingSession.getId());
            s.setUuid(editingSession.getUuid());
        }
        s.setEventId(event.getId());
        s.setName(name);
        s.setSessionDate(selectedDateIso);
        s.setStartTime(start);
        s.setEndTime(emptyToNull(end));
        s.setType(type);
        s.setSpeaker(emptyToNull(text(tilSpeaker)));
        s.setRoom(emptyToNull(text(tilRoom)));
        s.setDescription(emptyToNull(text(tilDescription)));
        return s;
    }

    private TextInputLayout showError(TextInputLayout til, String message, TextInputLayout first) {
        til.setError(message);
        return first != null ? first : til;
    }

    private void save(Session session) {
        boolean isEdit = session.getId() > 0;
        if (isEdit) {
            db.updateSession(session);
        } else {
            db.insertSession(session);
        }
        Toast.makeText(this, isEdit ? R.string.msg_session_updated : R.string.msg_session_saved,
                Toast.LENGTH_SHORT).show();
        finish();
    }

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_session_title)
                .setMessage(getString(R.string.delete_session_message, editingSession.getName()))
                .setPositiveButton(R.string.action_delete, (d, w) -> {
                    db.deleteSession(editingSession);
                    Toast.makeText(this, R.string.msg_session_deleted, Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // =====================================================================
    // Unsaved changes
    // =====================================================================

    private void setupChangeTracking() {
        TextInputLayout[] fields = {tilName, tilStartTime, tilEndTime,
                tilOtherType, tilSpeaker, tilRoom, tilDescription};
        for (TextInputLayout til : fields) {
            onTextChanged(til, () -> {
                if (!autoFilling) formChanged = true;
                til.setError(null);
            });
        }
    }

    private void setupBackHandling() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!formChanged) {
                    finish();
                    return;
                }
                new MaterialAlertDialogBuilder(SessionFormActivity.this)
                        .setTitle(R.string.discard_title)
                        .setMessage(R.string.discard_message)
                        .setPositiveButton(R.string.action_discard, (d, w) -> finish())
                        .setNegativeButton(R.string.action_keep_editing, null)
                        .show();
            }
        });
    }
}
