package com.greenwich.eventmanager;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
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
import com.greenwich.eventmanager.model.Session;
import com.greenwich.eventmanager.util.DateUtils;
import com.greenwich.eventmanager.util.EventFormat;
import com.greenwich.eventmanager.util.EventStatus;
import com.greenwich.eventmanager.util.UiUtils;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;

/**
 * Home screen (admin): a dashboard (totals, live, upcoming, needs attention, next up),
 * a status filter, and every event with its session count (feature c) and search (feature d).
 *  - Quick search (toolbar): name starts with / description contains the text.
 *  - Advanced search (bottom sheet): events on a date and/or by an organiser.
 *  - Status chips (All / Live / Upcoming / Draft / Postponed / Cancelled / Ended) filter
 *    the search results in Java, after the database query.
 * All criteria combine; active filters are shown as chips that can be removed.
 *  - Multi-select: long press a card, tap others to add them, then Delete (confirmed).
 */
public class MainActivity extends AppCompatActivity {

    /** Pseudo-status for the "All" chip. */
    private static final String FILTER_ALL = "ALL";
    /** Pseudo-status for "Needs attention": drafts and events not yet synced. */
    private static final String FILTER_ATTENTION = "ATTENTION";
    /** Order of the status chips. */
    private static final String[] FILTERS = {
            FILTER_ALL, FILTER_ATTENTION, EventStatus.ONGOING, EventStatus.UPCOMING, EventStatus.DRAFT,
            EventStatus.POSTPONED, EventStatus.CANCELLED, EventStatus.ENDED
    };
    private static final long REFRESH_MS = 60_000L;   // countdowns update every minute
    private static final long WEEK_MS = 7L * 24 * 60 * 60 * 1000;

    private DatabaseHelper db;
    private EventAdapter adapter;
    private AppBarLayout appBar;
    private ChipGroup chipGroupStatus;
    private final Map<String, Chip> statusChips = new LinkedHashMap<>();
    private String statusFilter = FILTER_ALL;

    // Dashboard
    private View statTotal, statLive, statUpcoming, statAttention, cardNextUp;
    private TextView tvNextUpCountdown, tvNextUpName, tvNextUpMeta, tvNextUpSub;
    private Event nextUpEvent;

    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTick = new Runnable() {
        @Override
        public void run() {
            loadEvents();
            refreshHandler.postDelayed(this, REFRESH_MS);
        }
    };

    private MaterialToolbar toolbar;
    private RecyclerView rvEvents;
    private View emptyState, filterBar;
    private TextView tvEmptyTitle, tvEmptyMessage;
    private ChipGroup chipGroupFilters;
    private ExtendedFloatingActionButton fabAddEvent;
    private LinearProgressIndicator syncProgress;
    private boolean syncing;

    // Multi-select delete
    private final Set<Long> selectedIds = new LinkedHashSet<>();
    private boolean selectionMode;
    private List<Event> shownEvents = new ArrayList<>();
    private View selectionBar;
    private TextView tvSelectionCount;
    private final OnBackPressedCallback exitSelectionOnBack = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            exitSelectionMode();
        }
    };

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
        appBar = findViewById(R.id.appBar);
        chipGroupStatus = findViewById(R.id.chipGroupStatus);
        bindDashboard();
        setupStatusChips();

        adapter = new EventAdapter(this::onEventClick, this::onEventLongClick);
        rvEvents.setAdapter(adapter);
        setupSelectionBar();

        fabAddEvent.setOnClickListener(v -> openAddEvent());
        shrinkFabOnScroll();
        refreshWhenBackgroundSyncFinishes();
    }

    /** Reload every time the screen becomes visible, e.g. after adding/editing an event. */
    @Override
    protected void onResume() {
        super.onResume();
        refreshHandler.post(refreshTick);     // loads now, then every minute
    }

    @Override
    protected void onPause() {
        super.onPause();
        refreshHandler.removeCallbacks(refreshTick);
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
                appBar.setExpanded(true, true);   // keep the search box in view
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
        long now = System.currentTimeMillis();
        List<Event> all = db.getAllEvents();
        boolean searching = !keyword.isEmpty() || filterDateIso != null || filterOrganiser != null;
        List<Event> found = searching ? db.findEvents(keyword, filterDateIso, filterOrganiser) : all;

        updateDashboard(all, now);

        // Status chips: counts reflect the current search, then the selected chip filters it
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String f : FILTERS) counts.put(f, 0);
        List<Event> shown = new ArrayList<>();
        for (Event e : found) {
            String status = EventStatus.displayStatus(e, now);
            counts.put(status, counts.get(status) + 1);
            boolean attention = needsAttention(e, status);
            if (attention) counts.put(FILTER_ATTENTION, counts.get(FILTER_ATTENTION) + 1);
            if (FILTER_ALL.equals(statusFilter)
                    || statusFilter.equals(status)
                    || (FILTER_ATTENTION.equals(statusFilter) && attention)) {
                shown.add(e);
            }
        }
        counts.put(FILTER_ALL, found.size());
        updateStatusChips(counts);

        shownEvents = shown;
        if (selectionMode) {
            // Drop selected events that are no longer listed (filtered out, deleted elsewhere)
            Set<Long> visibleIds = new LinkedHashSet<>();
            for (Event e : shown) visibleIds.add(e.getId());
            selectedIds.retainAll(visibleIds);
            if (selectedIds.isEmpty()) {
                exitSelectionMode();
            } else {
                updateSelectionUi();
            }
        }

        adapter.setNow(now);
        adapter.submitList(shown);

        updateFilterChips();
        toolbar.setSubtitle(searching
                ? getResources().getQuantityString(R.plurals.events_found, found.size(), found.size())
                : getString(R.string.dashboard_caption));

        // Different empty message for "no events at all", "no search results" and "no such status"
        boolean isEmpty = shown.isEmpty();
        emptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        rvEvents.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        if (searching && found.isEmpty()) {
            tvEmptyTitle.setText(R.string.empty_search_title);
            tvEmptyMessage.setText(R.string.empty_search_message);
        } else if (!found.isEmpty()) {
            tvEmptyTitle.setText(R.string.empty_filter_title);
            tvEmptyMessage.setText(R.string.empty_filter_message);
        } else {
            tvEmptyTitle.setText(R.string.empty_events_title);
            tvEmptyMessage.setText(R.string.empty_events_message);
        }
    }

    // =====================================================================
    // Dashboard
    // =====================================================================

    private void bindDashboard() {
        statTotal = findViewById(R.id.statTotal);
        statLive = findViewById(R.id.statLive);
        statUpcoming = findViewById(R.id.statUpcoming);
        statAttention = findViewById(R.id.statAttention);
        cardNextUp = findViewById(R.id.cardNextUp);
        tvNextUpCountdown = findViewById(R.id.tvNextUpCountdown);
        tvNextUpName = findViewById(R.id.tvNextUpName);
        tvNextUpMeta = findViewById(R.id.tvNextUpMeta);
        tvNextUpSub = findViewById(R.id.tvNextUpSub);

        setupStat(statTotal, R.string.dash_total_events, 0);
        setupStat(statLive, R.string.dash_happening_now, R.color.status_live_accent);
        setupStat(statUpcoming, R.string.dash_upcoming, R.color.status_upcoming_accent);
        setupStat(statAttention, R.string.dash_needs_attention, 0);

        // Tapping a number card selects the matching status chip
        statLive.setOnClickListener(v -> selectStatusFilter(EventStatus.ONGOING));
        statUpcoming.setOnClickListener(v -> selectStatusFilter(EventStatus.UPCOMING));
        statTotal.setOnClickListener(v -> selectStatusFilter(FILTER_ALL));
        statAttention.setOnClickListener(v -> selectStatusFilter(FILTER_ATTENTION));
        cardNextUp.setOnClickListener(v -> {
            if (nextUpEvent != null) openEventDetail(nextUpEvent);
        });
    }

    /** Label + optional coloured dot; the value colour matches the dot. */
    private void setupStat(View card, int labelRes, int accentColorRes) {
        ((TextView) card.findViewById(R.id.statLabel)).setText(labelRes);
        View dot = card.findViewById(R.id.statDot);
        TextView value = card.findViewById(R.id.statValue);
        if (accentColorRes != 0) {
            int color = ContextCompat.getColor(this, accentColorRes);
            dot.setVisibility(View.VISIBLE);
            dot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
            value.setTextColor(color);
        }
    }

    private void setStat(View card, int value, String sub) {
        ((TextView) card.findViewById(R.id.statValue)).setText(String.valueOf(value));
        ((TextView) card.findViewById(R.id.statSub)).setText(sub);
    }

    /** Numbers are computed over every event, independent of search / status filter. */
    private void updateDashboard(List<Event> all, long now) {
        int totalSessions = 0, live = 0, liveSessions = 0, upcoming = 0, thisWeek = 0;
        int drafts = 0, notSynced = 0, attention = 0;
        nextUpEvent = null;

        for (Event e : all) {
            totalSessions += e.getSessionCount();
            String status = EventStatus.displayStatus(e, now);
            if (EventStatus.ONGOING.equals(status)) {
                live++;
                for (Session s : db.getSessionsForEvent(e.getId())) {
                    if (EventStatus.ONGOING.equals(EventStatus.sessionTimeStatus(s, now))) {
                        liveSessions++;
                    }
                }
            } else if (EventStatus.UPCOMING.equals(status)) {
                upcoming++;
                long start = EventStatus.startMillis(e);
                if (start - now <= WEEK_MS) thisWeek++;
                if (nextUpEvent == null || start < EventStatus.startMillis(nextUpEvent)) {
                    nextUpEvent = e;
                }
            }
            boolean draft = EventStatus.DRAFT.equals(status);
            if (draft) drafts++;
            if (e.isDirty()) notSynced++;
            if (needsAttention(e, status)) attention++;
        }

        setStat(statTotal, all.size(),
                getResources().getQuantityString(R.plurals.session_count, totalSessions, totalSessions));
        setStat(statLive, live,
                getResources().getQuantityString(R.plurals.sessions_live, liveSessions, liveSessions));
        setStat(statUpcoming, upcoming,
                getResources().getQuantityString(R.plurals.upcoming_this_week, thisWeek, thisWeek));
        setStat(statAttention, attention, getString(R.string.format_meta_join,
                getResources().getQuantityString(R.plurals.draft_count, drafts, drafts),
                getResources().getQuantityString(R.plurals.not_synced_count, notSynced, notSynced)));
        ((TextView) statAttention.findViewById(R.id.statValue)).setTextColor(ContextCompat.getColor(this,
                attention > 0 ? R.color.status_postponed_accent : R.color.status_synced));

        // Next up
        cardNextUp.setVisibility(nextUpEvent == null ? View.GONE : View.VISIBLE);
        if (nextUpEvent != null) {
            Event e = nextUpEvent;
            tvNextUpCountdown.setText(EventFormat.countdown(this, e, now));
            tvNextUpName.setText(e.getName());
            tvNextUpMeta.setText(getString(R.string.format_meta_join,
                    getString(R.string.format_meta_join,
                            EventFormat.formatShortDates(this, e), EventFormat.formatTimes(this, e)),
                    e.getLocation()));
            int count = e.getSessionCount();
            tvNextUpSub.setText(getString(R.string.format_meta_join,
                    getResources().getQuantityString(R.plurals.sessions_planned, count, count),
                    getString(e.isDirty() ? R.string.sync_state_not_synced : R.string.sync_state_synced)));
        }
    }

    // =====================================================================
    // Status filter chips
    // =====================================================================

    private void setupStatusChips() {
        for (String filter : FILTERS) {
            Chip chip = (Chip) getLayoutInflater().inflate(R.layout.item_filter_chip,
                    chipGroupStatus, false);
            chip.setId(View.generateViewId());
            chip.setTag(filter);
            chipGroupStatus.addView(chip);
            statusChips.put(filter, chip);
        }
        statusChips.get(FILTER_ALL).setChecked(true);
        chipGroupStatus.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            Chip checked = group.findViewById(checkedIds.get(0));
            statusFilter = (String) checked.getTag();
            loadEvents();
        });
    }

    private void selectStatusFilter(String filter) {
        Chip chip = statusChips.get(filter);
        if (chip == null) return;
        chip.setVisibility(View.VISIBLE);   // a chip with count 0 is hidden; show it once selected
        chip.setChecked(true);
    }

    /** Drafts (hidden from users) and events with changes not yet uploaded. */
    private static boolean needsAttention(Event e, String displayStatus) {
        return EventStatus.DRAFT.equals(displayStatus) || e.isDirty();
    }

    /** "Live 1", "Draft 2"... Empty statuses are hidden (except All and the selected one). */
    private void updateStatusChips(Map<String, Integer> counts) {
        for (Map.Entry<String, Chip> entry : statusChips.entrySet()) {
            String filter = entry.getKey();
            Chip chip = entry.getValue();
            int count = counts.get(filter);
            String label;
            if (FILTER_ALL.equals(filter)) {
                label = getString(R.string.filter_all);
            } else if (FILTER_ATTENTION.equals(filter)) {
                label = getString(R.string.dash_needs_attention);
            } else {
                label = EventFormat.statusLabel(this, filter);
            }
            chip.setText(getString(R.string.format_filter_chip, label, count));
            boolean visible = FILTER_ALL.equals(filter) || count > 0 || filter.equals(statusFilter);
            chip.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
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

    // =====================================================================
    // Multi-select delete
    // =====================================================================

    private void setupSelectionBar() {
        selectionBar = findViewById(R.id.selectionBar);
        tvSelectionCount = findViewById(R.id.tvSelectionCount);
        findViewById(R.id.btnCancelSelection).setOnClickListener(v -> exitSelectionMode());
        findViewById(R.id.btnSelectAll).setOnClickListener(v -> {
            for (Event e : shownEvents) selectedIds.add(e.getId());
            updateSelectionUi();
        });
        findViewById(R.id.btnDeleteSelected).setOnClickListener(v -> confirmDeleteSelected());
        getOnBackPressedDispatcher().addCallback(this, exitSelectionOnBack);
    }

    /** Tap: open the event, or (in selection mode) select / unselect it. */
    private void onEventClick(Event event) {
        if (selectionMode) {
            toggleSelection(event);
        } else {
            openEventDetail(event);
        }
    }

    /** Long press: start selection mode with this event, or toggle it if already selecting. */
    private void onEventLongClick(Event event) {
        if (!selectionMode) {
            selectionMode = true;
            exitSelectionOnBack.setEnabled(true);
            fabAddEvent.hide();
            selectionBar.setVisibility(View.VISIBLE);
        }
        toggleSelection(event);
    }

    private void toggleSelection(Event event) {
        if (!selectedIds.remove(event.getId())) {
            selectedIds.add(event.getId());
        }
        if (selectedIds.isEmpty()) {
            exitSelectionMode();
        } else {
            updateSelectionUi();
        }
    }

    private void updateSelectionUi() {
        int n = selectedIds.size();
        tvSelectionCount.setText(getResources().getQuantityString(R.plurals.selection_count, n, n));
        adapter.setSelectedIds(new LinkedHashSet<>(selectedIds));
    }

    private void exitSelectionMode() {
        selectionMode = false;
        selectedIds.clear();
        exitSelectionOnBack.setEnabled(false);
        selectionBar.setVisibility(View.GONE);
        fabAddEvent.show();
        adapter.setSelectedIds(new LinkedHashSet<>());
    }

    /** Destructive: always confirm, and say how many sessions go with the events. */
    private void confirmDeleteSelected() {
        int events = selectedIds.size();
        int sessions = 0;
        for (Event e : shownEvents) {
            if (selectedIds.contains(e.getId())) sessions += e.getSessionCount();
        }
        String message = sessions == 0
                ? getString(R.string.delete_event_message_no_sessions)
                : getResources().getQuantityString(R.plurals.delete_events_message, sessions, sessions);

        new MaterialAlertDialogBuilder(this)
                .setTitle(getResources().getQuantityString(R.plurals.delete_events_title, events, events))
                .setMessage(message)
                .setPositiveButton(R.string.action_delete, (d, w) -> {
                    db.deleteEvents(new ArrayList<>(selectedIds));
                    exitSelectionMode();
                    loadEvents();
                    showSnackbar(getResources().getQuantityString(
                            R.plurals.msg_events_deleted, events, events), null);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
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