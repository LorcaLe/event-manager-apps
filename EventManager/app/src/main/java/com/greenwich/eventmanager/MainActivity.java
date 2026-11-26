package com.greenwich.eventmanager;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;
import com.greenwich.eventmanager.adapter.EventAdapter;
import com.greenwich.eventmanager.data.DatabaseHelper;
import com.greenwich.eventmanager.data.SyncManager;
import com.greenwich.eventmanager.data.SyncWorker;
import com.greenwich.eventmanager.model.Event;
import com.greenwich.eventmanager.util.DateUtils;
import com.greenwich.eventmanager.util.UiUtils;

import java.util.List;

/**
 * Home screen: lists every event with its session count (feature c) and searches
 * them (feature d).
 *  - Quick search (toolbar): name starts with / description contains the text.
 *  - Advanced search (bottom sheet): events on a date and/or by an organiser.
 * All criteria combine; active filters are shown as chips that can be removed.
 */
public class MainActivity extends AppCompatActivity {

    private DatabaseHelper db;
    private EventAdapter adapter;

    private MaterialToolbar toolbar;
    private RecyclerView rvEvents;
    private View emptyState, filterBar;
    private TextView tvEmptyTitle, tvEmptyMessage;
    private ChipGroup chipGroupFilters;
    private ExtendedFloatingActionButton fabAddEvent;
    private LinearProgressIndicator syncProgress;
    private boolean syncing;

    // Current search criteria (null / "" = not used)
    private String keyword = "";
    private String filterDateIso;
    private String filterOrganiser;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        UiUtils.applyInsets(findViewById(R.id.main), false);

        db = DatabaseHelper.getInstance(this);

        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        rvEvents = findViewById(R.id.rvEvents);
        emptyState = findViewById(R.id.emptyState);
        tvEmptyTitle = findViewById(R.id.tvEmptyTitle);
        tvEmptyMessage = findViewById(R.id.tvEmptyMessage);
        filterBar = findViewById(R.id.filterBar);
        chipGroupFilters = findViewById(R.id.chipGroupFilters);
        fabAddEvent = findViewById(R.id.fabAddEvent);
        syncProgress = findViewById(R.id.syncProgress);

        adapter = new EventAdapter(this::openEventDetail);
        rvEvents.setAdapter(adapter);

        fabAddEvent.setOnClickListener(v -> openAddEvent());
        shrinkFabOnScroll();
        refreshWhenBackgroundSyncFinishes();
    }

    /** Reload every time the screen becomes visible, e.g. after adding/editing an event. */
    @Override
    protected void onResume() {
        super.onResume();
        loadEvents();
    }

    // =====================================================================
    // Toolbar: quick search + advanced search
    // =====================================================================

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);

        MenuItem searchItem = menu.findItem(R.id.action_search);
        SearchView searchView = (SearchView) searchItem.getActionView();
        if (searchView == null) return true;

        searchView.setQueryHint(getString(R.string.hint_search_events));
        searchView.setMaxWidth(Integer.MAX_VALUE);   // use the full toolbar width

        // Filter the list on every key press
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                searchView.clearFocus();             // close the keyboard, keep results
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                keyword = newText.trim();
                loadEvents();
                return true;
            }
        });

        // Closing the search box clears the keyword
        searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionExpand(@NonNull MenuItem item) {
                return true;
            }

            @Override
            public boolean onMenuItemActionCollapse(@NonNull MenuItem item) {
                keyword = "";
                loadEvents();
                return true;
            }
        });
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_advanced_search) {
            showAdvancedSearch();
            return true;
        } else if (id == R.id.action_sync) {
            startSync(false);
            return true;
        } else if (id == R.id.action_upload_all) {
            startSync(true);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // =====================================================================
    // List
    // =====================================================================

    private void loadEvents() {
        boolean filtering = !keyword.isEmpty() || filterDateIso != null || filterOrganiser != null;
        List<Event> events = filtering
                ? db.findEvents(keyword, filterDateIso, filterOrganiser)
                : db.getAllEvents();
        adapter.submitList(events);

        updateFilterChips();
        toolbar.setSubtitle(filtering
                ? getResources().getQuantityString(R.plurals.events_found, events.size(), events.size())
                : null);

        // Different empty message for "no events at all" and "no search results"
        boolean isEmpty = events.isEmpty();
        emptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        rvEvents.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        tvEmptyTitle.setText(filtering ? R.string.empty_search_title : R.string.empty_events_title);
        tvEmptyMessage.setText(filtering ? R.string.empty_search_message : R.string.empty_events_message);
    }

    /** One chip per advanced-search filter; ✕ removes it, tapping it reopens the sheet. */
    private void updateFilterChips() {
        chipGroupFilters.removeAllViews();
        if (filterDateIso != null) {
            addFilterChip(getString(R.string.chip_filter_date, DateUtils.isoToDisplay(filterDateIso)),
                    () -> filterDateIso = null);
        }
        if (filterOrganiser != null) {
            addFilterChip(getString(R.string.chip_filter_organiser, filterOrganiser),
                    () -> filterOrganiser = null);
        }
        filterBar.setVisibility(chipGroupFilters.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    private void addFilterChip(String text, Runnable clearFilter) {
        Chip chip = new Chip(this);
        chip.setText(text);
        chip.setCloseIconVisible(true);
        chip.setCloseIconContentDescription(getString(R.string.action_clear));
        chip.setOnCloseIconClickListener(v -> {
            clearFilter.run();
            loadEvents();
        });
        chip.setOnClickListener(v -> showAdvancedSearch());
        chipGroupFilters.addView(chip);
    }

    // =====================================================================
    // Advanced search (bottom sheet)
    // =====================================================================

    private void showAdvancedSearch() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View sheet = getLayoutInflater().inflate(R.layout.sheet_advanced_search, null);
        dialog.setContentView(sheet);

        TextInputLayout tilDate = sheet.findViewById(R.id.tilFilterDate);
        TextInputLayout tilOrganiser = sheet.findViewById(R.id.tilFilterOrganiser);
        MaterialAutoCompleteTextView actvOrganiser = sheet.findViewById(R.id.actvFilterOrganiser);

        // Suggest the organisers that already exist, but still allow typing part of a name
        actvOrganiser.setSimpleItems(db.getOrganisers().toArray(new String[0]));

        // Start from the filters currently applied
        UiUtils.setText(tilDate, filterDateIso == null ? "" : DateUtils.isoToDisplay(filterDateIso));
        UiUtils.setText(tilOrganiser, filterOrganiser);

        View.OnClickListener pickDate = v -> showFilterDatePicker(tilDate);
        tilDate.setEndIconOnClickListener(pickDate);
        EditText dateField = tilDate.getEditText();
        if (dateField != null) dateField.setOnClickListener(pickDate);

        Runnable apply = () -> {
            String date = UiUtils.text(tilDate);
            String organiser = UiUtils.text(tilOrganiser);
            filterDateIso = date.isEmpty() ? null : DateUtils.displayToIso(date);
            filterOrganiser = organiser.isEmpty() ? null : organiser;
            dialog.dismiss();
            loadEvents();
        };

        sheet.findViewById(R.id.btnApplyFilters).setOnClickListener(v -> apply.run());
        actvOrganiser.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                apply.run();
                return true;
            }
            return false;
        });
        sheet.findViewById(R.id.btnClearFilters).setOnClickListener(v -> {
            UiUtils.setText(tilDate, "");
            UiUtils.setText(tilOrganiser, "");
        });

        dialog.show();
    }

    private void showFilterDatePicker(TextInputLayout target) {
        String currentIso = DateUtils.displayToIso(UiUtils.text(target));
        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.title_pick_date)
                .setSelection(currentIso != null
                        ? DateUtils.isoToUtcMillis(currentIso)
                        : MaterialDatePicker.todayInUtcMilliseconds())
                .build();
        picker.addOnPositiveButtonClickListener(selection ->
                UiUtils.setText(target, DateUtils.isoToDisplay(DateUtils.utcMillisToIso(selection))));
        picker.show(getSupportFragmentManager(), "filter_date_picker");
    }

    // =====================================================================
    // Cloud sync (feature e)
    // =====================================================================

    /**
     * @param uploadAll false = only events changed since the last sync,
     *                  true  = every event ("upload all events at once")
     */
    private void startSync(boolean uploadAll) {
        if (syncing) return;
        setSyncing(true);
        SyncManager.syncAsync(this, uploadAll, new SyncManager.Callback() {
            @Override
            public void onSuccess(SyncManager.Result result) {
                if (isDestroyed()) return;
                setSyncing(false);
                loadEvents();                        // "Not synced" labels turn into "Synced"
                showSnackbar(result.nothingToDo()
                        ? getString(R.string.sync_up_to_date)
                        : getString(R.string.sync_done, result.uploaded, result.deleted), null);
            }

            @Override
            public void onError(SyncManager.SyncException error) {
                if (isDestroyed()) return;
                setSyncing(false);
                showSnackbar(getString(error.noNetwork ? R.string.sync_no_network : R.string.sync_failed),
                        () -> startSync(uploadAll));
            }
        });
    }

    private void setSyncing(boolean value) {
        syncing = value;
        syncProgress.setVisibility(value ? View.VISIBLE : View.GONE);
    }

    /** Snackbar above the FAB, with an optional Retry button. */
    private void showSnackbar(String message, Runnable retry) {
        Snackbar bar = Snackbar.make(findViewById(R.id.main), message, Snackbar.LENGTH_LONG)
                .setAnchorView(fabAddEvent);
        if (retry != null) bar.setAction(R.string.action_retry, v -> retry.run());
        bar.show();
    }

    /** When the background worker finishes a sync, refresh the "Synced" labels. */
    private void refreshWhenBackgroundSyncFinishes() {
        WorkManager.getInstance(this)
                .getWorkInfosForUniqueWorkLiveData(SyncWorker.UNIQUE_NAME)
                .observe(this, infos -> {
                    for (WorkInfo info : infos) {
                        if (info.getState() == WorkInfo.State.SUCCEEDED) {
                            loadEvents();
                            return;
                        }
                    }
                });
    }

    // =====================================================================
    // Navigation
    // =====================================================================

    private void openAddEvent() {
        startActivity(new Intent(this, EventFormActivity.class));
    }

    private void openEventDetail(Event event) {
        Intent intent = new Intent(this, EventDetailActivity.class);
        intent.putExtra(EventDetailActivity.EXTRA_EVENT_ID, event.getId());
        startActivity(intent);
    }

    /** Collapse the FAB to an icon while scrolling down, expand it when scrolling up. */
    private void shrinkFabOnScroll() {
        rvEvents.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy > 0 && fabAddEvent.isExtended()) {
                    fabAddEvent.shrink();
                } else if (dy < 0 && !fabAddEvent.isExtended()) {
                    fabAddEvent.extend();
                }
            }
        });
    }
}