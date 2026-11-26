package com.greenwich.eventmanager;

import android.database.sqlite.SQLiteException;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.IntentCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.snackbar.Snackbar;
import com.greenwich.eventmanager.data.DatabaseHelper;
import com.greenwich.eventmanager.model.Event;
import com.greenwich.eventmanager.util.DetailRows;
import com.greenwich.eventmanager.util.EventFormat;

/**
 * Shows the validated event back to the user before saving (feature a).
 *
 *  - Edit : closes this screen; the form underneath still holds every value.
 *  - Save : inserts (new) or updates (existing) the event, then returns RESULT_OK
 *           so the form closes as well.
 */
public class EventConfirmActivity extends AppCompatActivity {

    public static final String EXTRA_EVENT = "extra_event";

    private Event event;
    private LinearLayout detailContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_event_confirm);
        applyInsets();

        event = IntentCompat.getSerializableExtra(getIntent(), EXTRA_EVENT, Event.class);
        if (event == null) {
            finish();
            return;
        }

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        detailContainer = findViewById(R.id.detailContainer);
        showDetails();

        findViewById(R.id.btnEdit).setOnClickListener(v -> finish());
        findViewById(R.id.btnSave).setOnClickListener(v -> saveEvent());
    }

    private void showDetails() {
        addRow(R.string.label_name, event.getName());
        addRow(R.string.label_dates, EventFormat.formatDates(this, event));
        addRow(R.string.label_time, EventFormat.formatTimes(this, event));
        addRow(R.string.label_location, event.getLocation());
        addRow(R.string.label_organiser, event.getOrganiser());
        addRow(R.string.label_type, event.getType());
        addRow(R.string.label_description, event.getDescription());
        addRow(R.string.label_email, event.getContactEmail());
        addRow(R.string.label_phone, event.getContactPhone());
    }

    /** Adds one label/value row; empty optional values show "Not provided" in grey italics. */
    private void addRow(@StringRes int labelRes, String value) {
        DetailRows.add(detailContainer, labelRes, value);
    }

    private void saveEvent() {
        DatabaseHelper db = DatabaseHelper.getInstance(this);
        boolean isEdit = event.getId() > 0;
        try {
            if (isEdit) {
                db.updateEvent(event);
            } else {
                db.insertEvent(event);
            }
        } catch (SQLiteException e) {
            Snackbar.make(findViewById(R.id.main), R.string.error_save_failed,
                    Snackbar.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(this, isEdit ? R.string.msg_event_updated : R.string.msg_event_saved,
                Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    private void applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }
}