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
import com.google.android.material.datepicker.CalendarConstraints;
import com.google.android.material.datepicker.CompositeDateValidator;
import com.google.android.material.datepicker.DateValidatorPointBackward;
import com.google.android.material.datepicker.DateValidatorPointForward;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;
import com.greenwich.eventmanager.data.DatabaseHelper;
import com.greenwich.eventmanager.model.Event;
import com.greenwich.eventmanager.model.Session;
import com.greenwich.eventmanager.util.DateUtils;
import com.greenwich.eventmanager.util.EventFormat;
import com.greenwich.eventmanager.util.UiUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.greenwich.eventmanager.util.UiUtils.emptyToNull;
import static com.greenwich.eventmanager.util.UiUtils.onTextChanged;
import static com.greenwich.eventmanager.util.UiUtils.setText;
import static com.greenwich.eventmanager.util.UiUtils.text;

/**
 * Add / edit / delete a session of an event (feature b).
 *
 * Pass EXTRA_EVENT_ID (required) and EXTRA_SESSION_ID (only when editing).
 *  - The date can only be picked within the event's start..end dates.
 *  - End time is suggested as start + 1 hour until the user picks their own.
 *  - Saving warns if the room is already used by another session at that time.
 */
public class SessionFormActivity extends AppCompatActivity {

    public static final String EXTRA_EVENT_ID = "extra_event_id";
    public static final String EXTRA_SESSION_ID = "extra_session_id";

    private static final int DEFAULT_DURATION_MINUTES = 60;

    private DatabaseHelper db;
    private Event event;
    private Session editingSession;      // null when adding

    private boolean formChanged;
    private boolean autoFilling;
    private boolean endTimeSetByUser;

    private TextInputLayout tilName, tilDate, tilStartTime, tilEndTime, tilType, tilOtherType,
            tilSpeaker, tilRoom, tilDescription;
    private MaterialAutoCompleteTextView actvType;

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
        actvType.setSimpleItems(Session.TYPES);
        ((TextView) findViewById(R.id.tvEventName)).setText(
                event.getName() + " · " + EventFormat.formatDates(this, event));

        setupToolbar();
        setupDateField();
        setupTimeFields();
        setupTypeField();

        if (editingSession != null) {
            endTimeSetByUser = true;   // keep the saved end time (even if it was empty)
            fillForm(editingSession);
        }

        setupChangeTracking();   // after filling, so loaded values don't count as changes
        setupBackHandling();
        findViewById(R.id.btnSaveSession).setOnClickListener(v -> onSaveClicked());
    }

    private void bindViews() {
        tilName = findViewById(R.id.tilSessionName);
        tilDate = findViewById(R.id.tilSessionDate);
        tilStartTime = findViewById(R.id.tilStartTime);
        tilEndTime = findViewById(R.id.tilEndTime);
        tilType = findViewById(R.id.tilSessionType);
        tilOtherType = findViewById(R.id.tilOtherType);
        tilSpeaker = findViewById(R.id.tilSpeaker);
        tilRoom = findViewById(R.id.tilRoom);
        tilDescription = findViewById(R.id.tilDescription);
        actvType = findViewById(R.id.actvSessionType);
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
        setText(tilDate, DateUtils.isoToDisplay(s.getSessionDate()));
        setText(tilStartTime, s.getStartTime());
        setText(tilEndTime, s.getEndTime());
        if (Arrays.asList(Session.TYPES).contains(s.getType())) {
            setText(tilType, s.getType());
        } else {                                   // custom type typed under "Others"
            setText(tilType, Session.TYPE_OTHERS);
            setText(tilOtherType, s.getType());
        }
        updateOtherTypeVisibility();
        setText(tilSpeaker, s.getSpeaker());
        setText(tilRoom, s.getRoom());
        setText(tilDescription, s.getDescription());
    }

    // =====================================================================
    // Date: limited to the event's own dates
    // =====================================================================

    private void setupDateField() {
        String start = DateUtils.isoToDisplay(event.getStartDate());
        if (!event.isMultiDay()) {
            // Only one possible day: fill it in and lock the field
            setText(tilDate, start);
            tilDate.setEnabled(false);
            tilDate.setHelperText(getString(R.string.helper_session_date_single));
            return;
        }
        tilDate.setHelperText(getString(R.string.helper_session_date_range,
                start, DateUtils.isoToDisplay(event.getEndDate())));
        tilDate.setEndIconOnClickListener(v -> showDatePicker());
        EditText field = tilDate.getEditText();
        if (field != null) field.setOnClickListener(v -> showDatePicker());
    }

    private void showDatePicker() {
        long first = DateUtils.isoToUtcMillis(event.getStartDate());
        long last = DateUtils.isoToUtcMillis(event.getEndDate());

        // Only days from the event start to the event end can be selected
        List<CalendarConstraints.DateValidator> rules = new ArrayList<>();
        rules.add(DateValidatorPointForward.from(first));
        rules.add(DateValidatorPointBackward.before(last));
        CalendarConstraints constraints = new CalendarConstraints.Builder()
                .setStart(first)
                .setEnd(last)
                .setValidator(CompositeDateValidator.allOf(rules))
                .build();

        String currentIso = DateUtils.displayToIso(text(tilDate));
        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.title_pick_date)
                .setCalendarConstraints(constraints)
                .setSelection(currentIso != null ? DateUtils.isoToUtcMillis(currentIso) : first)
                .build();
        picker.addOnPositiveButtonClickListener(selection ->
                setText(tilDate, DateUtils.isoToDisplay(DateUtils.utcMillisToIso(selection))));
        picker.show(getSupportFragmentManager(), "session_date_picker");
    }

    // =====================================================================
    // Times: picked from a clock; end time suggested as start + 1 hour
    // =====================================================================

    private void setupTimeFields() {
        setupTimeField(tilStartTime);
        setupTimeField(tilEndTime);

        onTextChanged(tilStartTime, () -> {
            String start = text(tilStartTime);
            if (DateUtils.isValidTime(start) && !endTimeSetByUser) {
                autoFilling = true;
                setText(tilEndTime, DateUtils.addMinutesCapped(start, DEFAULT_DURATION_MINUTES));
                autoFilling = false;
            }
        });
        onTextChanged(tilEndTime, () -> {
            if (!autoFilling) endTimeSetByUser = !text(tilEndTime).isEmpty();
        });
    }

    private void setupTimeField(TextInputLayout til) {
        // Default the clock to the event's start hour, e.g. 09:00
        int defaultHour = Integer.parseInt(event.getStartTime().substring(0, 2));
        til.setEndIconOnClickListener(v ->
                UiUtils.showTimePicker(this, til, defaultHour));
        EditText field = til.getEditText();
        if (field != null) {
            field.setOnClickListener(v ->
                    UiUtils.showTimePicker(this, til, defaultHour));
        }
    }

    // =====================================================================
    // Type: "Others" reveals a free-text field
    // =====================================================================

    private void setupTypeField() {
        actvType.setOnItemClickListener((p, v, pos, id) -> updateOtherTypeVisibility());
    }

    private void updateOtherTypeVisibility() {
        boolean isOther = Session.TYPE_OTHERS.equals(text(tilType));
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

        // Extra feature: warn if the room is double-booked, but let the admin decide
        List<Session> clashes = db.findRoomClashes(session);
        if (clashes.isEmpty()) {
            save(session);
        } else {
            showRoomClashDialog(session, clashes);
        }
    }

    private Session validateForm() {
        TextInputLayout firstError = null;

        String name = text(tilName);
        if (name.isEmpty()) {
            firstError = showError(tilName, getString(R.string.error_session_name_required), firstError);
        }

        String dateIso = DateUtils.displayToIso(text(tilDate));
        if (dateIso == null) {
            firstError = showError(tilDate, getString(R.string.error_session_date_required), firstError);
        } else if (!DateUtils.isWithinRange(dateIso, event.getStartDate(), event.getEndDate())) {
            firstError = showError(tilDate, getString(R.string.error_session_date_range,
                    DateUtils.isoToDisplay(event.getStartDate()),
                    DateUtils.isoToDisplay(event.getEndDate())), firstError);
        }

        String start = text(tilStartTime);
        if (!DateUtils.isValidTime(start)) {
            firstError = showError(tilStartTime, getString(R.string.error_time_required), firstError);
        }

        String end = text(tilEndTime);
        if (!end.isEmpty() && DateUtils.isValidTime(start) && !DateUtils.isTimeBefore(start, end)) {
            firstError = showError(tilEndTime, getString(R.string.error_end_time_before_start), firstError);
        }

        String type = text(tilType);
        if (!Arrays.asList(Session.TYPES).contains(type)) {
            firstError = showError(tilType, getString(R.string.error_session_type_required), firstError);
        } else if (Session.TYPE_OTHERS.equals(type) && !text(tilOtherType).isEmpty()) {
            type = text(tilOtherType);
        }

        if (firstError != null) {
            EditText field = firstError.getEditText();
            if (field != null) field.requestFocus();
            return null;
        }

        Session s = new Session();
        if (editingSession != null) {            // keep the same ID and cloud key
            s.setId(editingSession.getId());
            s.setUuid(editingSession.getUuid());
        }
        s.setEventId(event.getId());
        s.setName(name);
        s.setSessionDate(dateIso);
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

    private void showRoomClashDialog(Session session, List<Session> clashes) {
        StringBuilder list = new StringBuilder();
        for (Session c : clashes) {
            list.append("• ").append(c.getName()).append(" (")
                    .append(EventFormat.formatTimeRange(this, c.getStartTime(), c.getEndTime()))
                    .append(")\n");
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.room_clash_title)
                .setMessage(getString(R.string.room_clash_message,
                        session.getRoom(), list.toString().trim()))
                .setPositiveButton(R.string.action_save_anyway, (d, w) -> save(session))
                .setNegativeButton(R.string.action_change, (d, w) -> {
                    EditText room = tilRoom.getEditText();
                    if (room != null) room.requestFocus();
                })
                .show();
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
        TextInputLayout[] fields = {tilName, tilDate, tilStartTime, tilEndTime, tilType,
                tilOtherType, tilSpeaker, tilRoom, tilDescription};
        for (TextInputLayout til : fields) {
            onTextChanged(til, () -> {
                formChanged = true;
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