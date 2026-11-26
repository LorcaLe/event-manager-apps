package com.greenwich.eventmanager;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Patterns;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.Filter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.datepicker.CalendarConstraints;
import com.google.android.material.datepicker.DateValidatorPointForward;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;
import com.greenwich.eventmanager.data.DatabaseHelper;
import com.greenwich.eventmanager.model.Event;
import com.greenwich.eventmanager.util.DateUtils;
import com.greenwich.eventmanager.util.EventFormat;
import com.greenwich.eventmanager.util.EventStatus;
import com.greenwich.eventmanager.util.LocationHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Add / edit an event (feature a).
 *
 * Flow: fill in the form -> "Review & publish" or "Save as draft" -> validation ->
 *       EventConfirmActivity shows the details -> Save (or Edit to come back here).
 *
 * Status: a new event is either a draft (hidden from users) or published. Postponing /
 * cancelling is done later from the event page ("Change status"); editing a published,
 * postponed or cancelled event keeps its status.
 *
 * Pass EXTRA_EVENT_ID to edit an existing event; omit it to add a new one.
 *
 * Usability helpers:
 *  - End date / end time are filled in automatically from the start date / time
 *    (sensible defaults), until the user changes them by hand.
 *  - Event type is picked from chips; "Other" reveals a field to name the type.
 *  - Under the dates, a summary shows the length ("3 days") and when it starts.
 *  - Email and phone are checked as soon as the user leaves the field, then
 *    re-checked live while they correct it.
 */
public class EventFormActivity extends AppCompatActivity {

    public static final String EXTRA_EVENT_ID = "extra_event_id";

    /** Optional phone: digits with an optional leading +, 9 to 15 digits long. */
    private static final String PHONE_REGEX = "^\\+?\\d{9,15}$";

    /** Default length suggested for the end time. */
    private static final int DEFAULT_DURATION_MINUTES = 120;

    // Location search: wait until the user pauses typing (debounce), then ask the geocoder
    private static final long LOCATION_SEARCH_DELAY_MS = 400;
    private static final int LOCATION_MIN_CHARS = 3;
    private static final int LOCATION_MAX_RESULTS = 5;

    private DatabaseHelper db;
    private Event editingEvent;          // null when adding a new event
    private boolean formChanged;         // used to warn before discarding input

    // Auto-fill bookkeeping
    private boolean autoFilling;         // true while the app (not the user) sets a field
    private boolean endDateSetByUser;    // stop auto-filling once the user picks their own
    private boolean endTimeSetByUser;

    // Live validation starts after the user has left the field once
    private boolean emailTouched;
    private boolean phoneTouched;

    // Map coordinates of the location (filled by the location search, next step)
    private Double latitude;
    private Double longitude;

    private TextInputLayout tilName, tilStartDate, tilEndDate, tilTime, tilEndTime, tilLocation,
            tilOrganiser, tilOtherType, tilDescription, tilEmail, tilPhone;
    private MaterialAutoCompleteTextView actvLocation;
    private ChipGroup chipGroupType;
    private TextView tvTypeError, tvDuration, tvRelative, tvFormNote;
    private View rowWhenSummary;
    private MaterialButton btnSaveDraft, btnReview;

    // Location suggestions
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private final Runnable searchRunnable = this::runLocationSearch;
    private SuggestionAdapter locationAdapter;
    private List<LocationHelper.Place> locationResults = new ArrayList<>();

    /** Asks for location permission, then fetches the current position if granted. */
    private final ActivityResultLauncher<String[]> locationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    granted -> {
                        if (Boolean.TRUE.equals(granted.get(Manifest.permission.ACCESS_FINE_LOCATION))
                                || Boolean.TRUE.equals(granted.get(Manifest.permission.ACCESS_COARSE_LOCATION))) {
                            fetchCurrentLocation();
                        } else {
                            showSnackbar(R.string.error_location_permission);
                        }
                    });

    /** When the confirmation screen saves the event, close this form too. */
    private final ActivityResultLauncher<Intent> confirmLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    finish();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_event_form);
        applyInsets();

        db = DatabaseHelper.getInstance(this);
        bindViews();
        setupTypeChips();

        // Edit mode?
        long eventId = getIntent().getLongExtra(EXTRA_EVENT_ID, -1);
        if (eventId != -1) {
            editingEvent = db.getEvent(eventId);
            if (editingEvent == null) {      // event no longer exists
                finish();
                return;
            }
            fillForm(editingEvent);
            // Existing values were chosen by the user: never overwrite them
            endDateSetByUser = true;
            endTimeSetByUser = true;
        }

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(editingEvent == null ? R.string.title_add_event : R.string.title_edit_event);
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        setupPickers();
        setupAutoFill();
        if (editingEvent == null) {
            applyDefaultDateTime();   // before change tracking: defaults are not "user changes"
        }
        setupWhenSummary();
        setupFieldValidationOnLeave();
        setupChangeTracking();   // after fillForm, so pre-filled values don't count as changes
        setupLocationField();
        setupBackHandling();

        setupActionButtons();
    }

    private void bindViews() {
        tilName = findViewById(R.id.tilName);
        tilStartDate = findViewById(R.id.tilStartDate);
        tilEndDate = findViewById(R.id.tilEndDate);
        tilTime = findViewById(R.id.tilTime);
        tilEndTime = findViewById(R.id.tilEndTime);
        tilLocation = findViewById(R.id.tilLocation);
        tilOrganiser = findViewById(R.id.tilOrganiser);
        tilOtherType = findViewById(R.id.tilOtherType);
        tilDescription = findViewById(R.id.tilDescription);
        tilEmail = findViewById(R.id.tilEmail);
        tilPhone = findViewById(R.id.tilPhone);
        actvLocation = findViewById(R.id.actvLocation);
        chipGroupType = findViewById(R.id.chipGroupType);
        tvTypeError = findViewById(R.id.tvTypeError);
        rowWhenSummary = findViewById(R.id.rowWhenSummary);
        tvDuration = findViewById(R.id.tvDuration);
        tvRelative = findViewById(R.id.tvRelative);
        tvFormNote = findViewById(R.id.tvFormNote);
        btnSaveDraft = findViewById(R.id.btnSaveDraft);
        btnReview = findViewById(R.id.btnReview);
    }

    private void fillForm(Event e) {
        setText(tilName, e.getName());
        setText(tilStartDate, DateUtils.isoToDisplay(e.getStartDate()));
        setText(tilEndDate, DateUtils.isoToDisplay(e.getEndDate()));
        setText(tilTime, e.getStartTime());
        setText(tilEndTime, e.getEndTime());
        setText(tilLocation, e.getLocation());
        setText(tilOrganiser, e.getOrganiser());

        // A custom type is stored as its own text: show it as "Other" + the custom name
        if (Arrays.asList(Event.TYPES).contains(e.getType())) {
            checkTypeChip(e.getType());
        } else {
            checkTypeChip(Event.TYPE_OTHER);
            setText(tilOtherType, e.getType());
        }
        updateOtherTypeVisibility();

        setText(tilDescription, e.getDescription());
        setText(tilEmail, e.getContactEmail());
        setText(tilPhone, e.getContactPhone());
        latitude = e.getLatitude();
        longitude = e.getLongitude();
    }

    // =====================================================================
    // Validation (feature a)
    // =====================================================================

    /** @param status status the event gets if saved (draft or published / unchanged) */
    private void onReviewClicked(String status) {
        Event event = validateForm(status);
        if (event == null) {
            Snackbar.make(findViewById(R.id.main), R.string.error_fix_fields,
                    Snackbar.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, EventConfirmActivity.class);
        intent.putExtra(EventConfirmActivity.EXTRA_EVENT, event);
        confirmLauncher.launch(intent);
    }

    /**
     * Checks every field, shows an error under each invalid one and focuses the first.
     * Returns the Event built from the form, or null if anything is invalid.
     */
    private Event validateForm(String status) {
        TextInputLayout firstError = null;

        // --- Required: name
        String name = text(tilName);
        if (name.isEmpty()) {
            firstError = showError(tilName, getString(R.string.error_name_required), firstError);
        }

        // --- Required: start date in DD/MM/YYYY
        String startText = text(tilStartDate);
        String startIso = null;
        if (startText.isEmpty()) {
            firstError = showError(tilStartDate, getString(R.string.error_start_date_required), firstError);
        } else {
            startIso = DateUtils.displayToIso(startText);
            if (startIso == null) {
                firstError = showError(tilStartDate, getString(R.string.error_date_format), firstError);
            }
        }

        // --- End date (empty = same as start), must not be before the start date
        String endText = text(tilEndDate);
        String endIso = null;
        if (!endText.isEmpty()) {
            endIso = DateUtils.displayToIso(endText);
            if (endIso == null) {
                firstError = showError(tilEndDate, getString(R.string.error_date_format), firstError);
            } else if (startIso != null && endIso.compareTo(startIso) < 0) {
                firstError = showError(tilEndDate, getString(R.string.error_end_before_start), firstError);
            }
        }

        // --- Required: start time
        String startTime = text(tilTime);
        if (!DateUtils.isValidTime(startTime)) {
            firstError = showError(tilTime, getString(R.string.error_time_required), firstError);
        }

        // --- Optional: end time; on a one-day event it must be after the start time
        String endTime = text(tilEndTime);
        boolean sameDay = endIso == null || endIso.equals(startIso);
        if (!endTime.isEmpty() && DateUtils.isValidTime(startTime) && sameDay
                && !DateUtils.isTimeBefore(startTime, endTime)) {
            firstError = showError(tilEndTime, getString(R.string.error_end_time_before_start), firstError);
        }

        // --- Required: location, organiser
        String location = text(tilLocation);
        if (location.isEmpty()) {
            firstError = showError(tilLocation, getString(R.string.error_location_required), firstError);
        }
        String organiser = text(tilOrganiser);
        if (organiser.isEmpty()) {
            firstError = showError(tilOrganiser, getString(R.string.error_organiser_required), firstError);
        }

        // --- Required: type, one of the chips
        String type = selectedType();
        boolean typeMissing = type == null;
        tvTypeError.setVisibility(typeMissing ? View.VISIBLE : View.GONE);
        if (Event.TYPE_OTHER.equals(type) && !text(tilOtherType).isEmpty()) {
            type = text(tilOtherType);   // store the user's own type name
        }

        // --- Optional: email and phone format (only checked if something was entered)
        if (!checkEmail()) {
            firstError = firstError != null ? firstError : tilEmail;
        }
        if (!checkPhone()) {
            firstError = firstError != null ? firstError : tilPhone;
        }

        // --- Database checks, only once the individual fields are valid
        if (firstError == null) {
            long excludeId = editingEvent == null ? -1 : editingEvent.getId();
            if (db.isDuplicateEvent(name, startIso, excludeId)) {
                firstError = showError(tilName, getString(R.string.error_duplicate_event), null);
            } else if (editingEvent != null) {
                // Editing dates must not leave existing sessions outside the event
                String effectiveEnd = endIso == null ? startIso : endIso;
                int outside = db.countSessionsOutsideRange(editingEvent.getId(), startIso, effectiveEnd);
                if (outside > 0) {
                    firstError = showError(tilEndDate,
                            getString(R.string.error_sessions_outside_range, outside), null);
                }
            }
        }

        if (firstError != null || typeMissing) {
            if (firstError != null) {
                EditText field = firstError.getEditText();
                if (field != null) field.requestFocus();
            } else {
                chipGroupType.requestFocus();
            }
            return null;
        }

        // --- All valid: build the Event (keep id + uuid when editing)
        Event event = new Event();
        if (editingEvent != null) {
            event.setId(editingEvent.getId());
            event.setUuid(editingEvent.getUuid());
        }
        event.setName(name);
        event.setStartDate(startIso);
        event.setEndDate(endIso == null ? startIso : endIso);
        event.setStartTime(startTime);
        event.setEndTime(emptyToNull(endTime));
        event.setLocation(location);
        event.setLatitude(latitude);
        event.setLongitude(longitude);
        event.setOrganiser(organiser);
        event.setType(type);
        event.setDescription(emptyToNull(text(tilDescription)));
        event.setContactEmail(emptyToNull(text(tilEmail)));
        event.setContactPhone(emptyToNull(text(tilPhone)));
        event.setStatus(status);
        // Postponed / cancelled keep their reason when the details are edited
        event.setStatusNote(editingEvent != null && Event.statusNeedsReason(status)
                ? editingEvent.getStatusNote() : null);
        return event;
    }

    /** Email is optional; if something is typed it must look like name@domain.tld. */
    private boolean checkEmail() {
        String email = text(tilEmail);
        boolean ok = email.isEmpty() || Patterns.EMAIL_ADDRESS.matcher(email).matches();
        tilEmail.setError(ok ? null : getString(R.string.error_email_format));
        return ok;
    }

    /** Phone is optional; spaces, dashes and brackets are ignored when checking. */
    private boolean checkPhone() {
        String phone = text(tilPhone);
        boolean ok = phone.isEmpty() || phone.replaceAll("[\\s\\-()]", "").matches(PHONE_REGEX);
        tilPhone.setError(ok ? null : getString(R.string.error_phone_format));
        return ok;
    }

    /** Shows the error under the field and returns the first field that had an error. */
    private TextInputLayout showError(TextInputLayout til, String message, TextInputLayout first) {
        til.setError(message);
        return first != null ? first : til;
    }

    /** Email/phone: validate when the user leaves the field, not while they are still typing. */
    private void setupFieldValidationOnLeave() {
        EditText email = tilEmail.getEditText();
        if (email != null) {
            email.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) {
                    emailTouched = true;
                    checkEmail();
                }
            });
        }
        EditText phone = tilPhone.getEditText();
        if (phone != null) {
            phone.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) {
                    phoneTouched = true;
                    checkPhone();
                }
            });
        }
    }

    // =====================================================================
    // Buttons: draft vs publish
    // =====================================================================

    /**
     * New event or draft: "Save as draft" + "Review & publish".
     * Published / postponed / cancelled event: one "Review changes" button that keeps
     * the status (it is changed from the event page).
     */
    private void setupActionButtons() {
        boolean canChooseDraft = editingEvent == null
                || Event.STATUS_DRAFT.equals(editingEvent.getStatus());
        if (canChooseDraft) {
            btnSaveDraft.setOnClickListener(v -> onReviewClicked(Event.STATUS_DRAFT));
            btnReview.setText(R.string.action_review_publish);
            btnReview.setOnClickListener(v -> onReviewClicked(Event.STATUS_PUBLISHED));
            tvFormNote.setText(R.string.form_draft_note);
        } else {
            String keep = editingEvent.getStatus();
            btnSaveDraft.setVisibility(View.GONE);
            btnReview.setText(R.string.action_review_changes);
            btnReview.setOnClickListener(v -> onReviewClicked(keep));
            tvFormNote.setText(getString(R.string.form_edit_note, EventFormat.statusLabel(this, keep)));
        }
    }

    // =====================================================================
    // Event type: chips; "Other" reveals a free-text field
    // =====================================================================

    private void setupTypeChips() {
        for (String type : Event.TYPES) {
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

    /** The chosen value from Event.TYPES, or null if no chip is selected. */
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
        boolean isOther = Event.TYPE_OTHER.equals(selectedType());
        tilOtherType.setVisibility(isOther ? View.VISIBLE : View.GONE);
        if (!isOther) {
            setText(tilOtherType, "");
        }
    }

    // =====================================================================
    // Auto-fill: end date = start date, end time = start time + 2 hours
    // =====================================================================

    private void setupAutoFill() {
        onTextChanged(tilStartDate, () -> {
            String startIso = DateUtils.displayToIso(text(tilStartDate));
            if (startIso != null && !endDateSetByUser) {
                autoFill(tilEndDate, DateUtils.isoToDisplay(startIso));
            }
        });

        onTextChanged(tilTime, () -> {
            String start = text(tilTime);
            if (DateUtils.isValidTime(start) && !endTimeSetByUser) {
                autoFill(tilEndTime, DateUtils.addMinutesCapped(start, DEFAULT_DURATION_MINUTES));
            }
        });

        // Any change the app did not make itself means the user chose a value.
        // Clearing the field hands control back to auto-fill.
        onTextChanged(tilEndDate, () -> {
            if (!autoFilling) endDateSetByUser = !text(tilEndDate).isEmpty();
        });
        onTextChanged(tilEndTime, () -> {
            if (!autoFilling) endTimeSetByUser = !text(tilEndTime).isEmpty();
        });
    }

    // =====================================================================
    // Summary under the dates: "3 days · Starts in 2 d 4 h"
    // =====================================================================

    /**
     * New event: start at the next whole hour today (tomorrow 09:00 if it is already late),
     * so the date / time pills show real values to tap instead of empty boxes.
     * Setting the start fills the end automatically (same day, +2 hours).
     */
    private void applyDefaultDateTime() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        int nextHour = c.get(java.util.Calendar.HOUR_OF_DAY) + 1;
        if (nextHour > 21) {                       // too late to start today
            c.add(java.util.Calendar.DAY_OF_MONTH, 1);
            nextHour = 9;
        }
        String dateIso = String.format(Locale.US, "%04d-%02d-%02d",
                c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH));
        setText(tilStartDate, DateUtils.isoToDisplay(dateIso));
        setText(tilTime, String.format(Locale.US, "%02d:00", nextHour));
    }

    private void setupWhenSummary() {
        TextInputLayout[] fields = {tilStartDate, tilEndDate, tilTime, tilEndTime};
        for (TextInputLayout til : fields) {
            onTextChanged(til, this::updateWhenSummary);
        }
        updateWhenSummary();
    }

    private void updateWhenSummary() {
        String startIso = DateUtils.displayToIso(text(tilStartDate));
        if (startIso == null) {
            rowWhenSummary.setVisibility(View.GONE);
            return;
        }
        String endIso = DateUtils.displayToIso(text(tilEndDate));
        if (endIso == null || endIso.compareTo(startIso) < 0) endIso = startIso;

        // A temporary event, only to reuse the status / countdown rules
        Event preview = new Event();
        preview.setStartDate(startIso);
        preview.setEndDate(endIso);
        String start = text(tilTime);
        String end = text(tilEndTime);
        preview.setStartTime(DateUtils.isValidTime(start) ? start : null);
        preview.setEndTime(DateUtils.isValidTime(end) ? end : null);

        int days = DateUtils.daysBetweenInclusive(startIso, endIso);
        tvDuration.setText(getResources().getQuantityString(R.plurals.day_count, days, days));

        long now = System.currentTimeMillis();
        boolean past = EventStatus.ENDED.equals(EventStatus.timeStatus(preview, now));
        tvRelative.setText(past ? getString(R.string.form_in_the_past)
                : EventFormat.countdown(this, preview, now));
        tvRelative.setTextColor(ContextCompat.getColor(this,
                past ? R.color.status_postponed_accent : R.color.text_secondary));
        rowWhenSummary.setVisibility(View.VISIBLE);
    }

    /** Sets a field on the user's behalf without marking it as "chosen by the user". */
    private void autoFill(TextInputLayout til, String value) {
        autoFilling = true;
        setText(til, value);
        autoFilling = false;
    }

    // =====================================================================
    // Location: address suggestions + current location (extra feature f)
    // =====================================================================

    private void setupLocationField() {
        locationAdapter = new SuggestionAdapter(this, new ArrayList<>());
        actvLocation.setAdapter(locationAdapter);

        // Typing: search again once the user pauses (debounce), not on every key press
        onTextChanged(tilLocation, () -> {
            searchHandler.removeCallbacks(searchRunnable);
            if (!autoFilling && actvLocation.hasFocus()
                    && text(tilLocation).length() >= LOCATION_MIN_CHARS) {
                searchHandler.postDelayed(searchRunnable, LOCATION_SEARCH_DELAY_MS);
            }
        });

        // Picking a suggestion: the text is already filled in, keep its coordinates
        actvLocation.setOnItemClickListener((parent, view, position, id) -> {
            searchHandler.removeCallbacks(searchRunnable);
            if (position < locationResults.size()) {
                LocationHelper.Place place = locationResults.get(position);
                setCoordinates(place.lat, place.lng);
            }
        });

        tilLocation.setEndIconOnClickListener(v -> useCurrentLocation());
        updateLocationHelper();
    }

    private void runLocationSearch() {
        String query = text(tilLocation);
        LocationHelper.searchPlaces(this, query, LOCATION_MAX_RESULTS, results -> {
            // Ignore late answers: screen closed, or the user has typed something else
            if (isFinishing() || isDestroyed() || !query.equals(text(tilLocation))) return;

            locationResults = results;
            locationAdapter.clear();
            for (LocationHelper.Place place : results) {
                locationAdapter.add(place.label);
            }
            if (!results.isEmpty() && actvLocation.hasFocus()) {
                actvLocation.showDropDown();
            }
        });
    }

    private void useCurrentLocation() {
        boolean granted = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            fetchCurrentLocation();
        } else {
            locationPermissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION});
        }
    }

    @SuppressLint("MissingPermission")  // permission is checked in useCurrentLocation()
    private void fetchCurrentLocation() {
        showSnackbar(R.string.helper_location_finding);

        LocationServices.getFusedLocationProviderClient(this)
                .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener(this, location -> {
                    if (location == null) {          // location is switched off
                        onLocationFailed();
                        return;
                    }
                    double lat = location.getLatitude();
                    double lng = location.getLongitude();
                    LocationHelper.reverseGeocode(this, lat, lng, address -> {
                        if (isFinishing() || isDestroyed()) return;
                        // No address found: fall back to the raw coordinates
                        String label = address != null
                                ? LocationHelper.format(address)
                                : String.format(Locale.US, "%.5f, %.5f", lat, lng);
                        autoFill(tilLocation, label);
                        setCoordinates(lat, lng);
                    });
                })
                .addOnFailureListener(this, e -> onLocationFailed());
    }

    private void onLocationFailed() {
        updateLocationHelper();
        showSnackbar(R.string.error_location_unavailable);
    }

    private void setCoordinates(Double lat, Double lng) {
        latitude = lat;
        longitude = lng;
        updateLocationHelper();
    }

    /**
     * No helper line under Location (keeps the rows even): the pin icon turns green
     * when a map position is attached, and stays the primary colour otherwise.
     */
    private void updateLocationHelper() {
        boolean pinned = latitude != null;
        tilLocation.setStartIconTintList(android.content.res.ColorStateList.valueOf(
                pinned ? ContextCompat.getColor(this, R.color.status_live_accent)
                        : com.google.android.material.color.MaterialColors.getColor(tilLocation,
                        androidx.appcompat.R.attr.colorPrimary)));
        tilLocation.setStartIconContentDescription(pinned
                ? getString(R.string.helper_location_pinned) : null);
    }

    /**
     * Drop-down adapter that shows the geocoder results as they are.
     * The default ArrayAdapter filter would hide results that don't start with the
     * typed text (e.g. "greenwich uni" -> "Old Royal Naval College, ..."), so it is disabled.
     */
    private static class SuggestionAdapter extends ArrayAdapter<String> {
        private final List<String> items;

        SuggestionAdapter(Context context, List<String> items) {
            super(context, android.R.layout.simple_list_item_1, items);
            this.items = items;
        }

        @NonNull
        @Override
        public Filter getFilter() {
            return new Filter() {
                @Override
                protected FilterResults performFiltering(CharSequence constraint) {
                    FilterResults results = new FilterResults();
                    results.values = items;
                    results.count = items.size();
                    return results;
                }

                @Override
                protected void publishResults(CharSequence constraint, FilterResults results) {
                    notifyDataSetChanged();
                }
            };
        }
    }

    // =====================================================================
    // Pickers: let the user choose instead of type (fewer input errors)
    // =====================================================================

    private void setupPickers() {
        // Date pills are tapped to open the calendar (not typed)
        setTapAction(tilStartDate, () -> showDatePicker(tilStartDate, null));
        setTapAction(tilEndDate, () ->
                // End date picker cannot go before the chosen start date
                showDatePicker(tilEndDate, DateUtils.displayToIso(text(tilStartDate))));

        setupTimeField(tilTime);
        setupTimeField(tilEndTime);
    }

    private void setTapAction(TextInputLayout til, Runnable action) {
        EditText field = til.getEditText();
        if (field != null) field.setOnClickListener(v -> action.run());
    }

    private void setupTimeField(TextInputLayout til) {
        til.setEndIconOnClickListener(v -> showTimePicker(til));
        EditText field = til.getEditText();
        if (field != null) {
            field.setOnClickListener(v -> showTimePicker(til));
        }
    }

    /** @param minIso earliest selectable date (ISO), or null for no limit */
    private void showDatePicker(TextInputLayout target, String minIso) {
        MaterialDatePicker.Builder<Long> builder = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.title_pick_date);

        // Open on the date already typed, otherwise on the minimum date
        String currentIso = DateUtils.displayToIso(text(target));
        if (currentIso != null) {
            builder.setSelection(DateUtils.isoToUtcMillis(currentIso));
        } else if (minIso != null) {
            builder.setSelection(DateUtils.isoToUtcMillis(minIso));
        }

        if (minIso != null) {
            long min = DateUtils.isoToUtcMillis(minIso);
            builder.setCalendarConstraints(new CalendarConstraints.Builder()
                    .setStart(min)
                    .setValidator(DateValidatorPointForward.from(min))
                    .build());
        }

        MaterialDatePicker<Long> picker = builder.build();
        picker.addOnPositiveButtonClickListener(selection ->
                setText(target, DateUtils.isoToDisplay(DateUtils.utcMillisToIso(selection))));
        picker.show(getSupportFragmentManager(), "date_picker");
    }

    private void showTimePicker(TextInputLayout target) {
        int hour = 9;
        int minute = 0;
        String current = text(target);
        if (DateUtils.isValidTime(current)) {
            hour = Integer.parseInt(current.substring(0, 2));
            minute = Integer.parseInt(current.substring(3, 5));
        }

        MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(hour)
                .setMinute(minute)
                .setTitleText(R.string.title_pick_time)
                .build();
        picker.addOnPositiveButtonClickListener(v -> setText(target,
                String.format(Locale.US, "%02d:%02d", picker.getHour(), picker.getMinute())));
        picker.show(getSupportFragmentManager(), "time_picker");
    }

    // =====================================================================
    // Unsaved changes: clear errors while typing, warn before leaving
    // =====================================================================

    private void setupChangeTracking() {
        TextInputLayout[] fields = {tilName, tilStartDate, tilEndDate, tilTime, tilEndTime,
                tilLocation, tilOrganiser, tilOtherType, tilDescription, tilEmail, tilPhone};
        for (TextInputLayout til : fields) {
            onTextChanged(til, () -> {
                formChanged = true;
                if (til == tilEmail && emailTouched) {
                    checkEmail();           // live feedback while correcting
                } else if (til == tilPhone && phoneTouched) {
                    checkPhone();
                } else {
                    til.setError(null);     // the user is fixing it
                }
                if (til == tilLocation && !autoFilling) {
                    // Typed by hand: the old map coordinates no longer match
                    setCoordinates(null, null);
                }
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
                new MaterialAlertDialogBuilder(EventFormActivity.this)
                        .setTitle(R.string.discard_title)
                        .setMessage(R.string.discard_message)
                        .setPositiveButton(R.string.action_discard, (d, w) -> finish())
                        .setNegativeButton(R.string.action_keep_editing, null)
                        .show();
            }
        });
    }

    // =====================================================================
    // Small helpers
    // =====================================================================

    /** Runs the action after every text change in the field. */
    private static void onTextChanged(TextInputLayout til, Runnable action) {
        EditText et = til.getEditText();
        if (et == null) return;
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { action.run(); }
        });
    }

    private static String text(TextInputLayout til) {
        EditText et = til.getEditText();
        return (et == null || et.getText() == null) ? "" : et.getText().toString().trim();
    }

    private static void setText(TextInputLayout til, String value) {
        EditText et = til.getEditText();
        String v = value == null ? "" : value;
        if (et instanceof AutoCompleteTextView) {
            // false = don't open the suggestion list for text set by the app
            ((AutoCompleteTextView) et).setText(v, false);
        } else if (et != null) {
            et.setText(v);
        }
    }

    private void showSnackbar(int messageRes) {
        Snackbar.make(findViewById(R.id.main), messageRes, Snackbar.LENGTH_LONG).show();
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /** Keep content clear of the status bar, navigation bar and on-screen keyboard. */
    private void applyInsets() {
        View root = findViewById(R.id.main);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }
}