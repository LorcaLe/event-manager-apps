package com.greenwich.eventmanager.adapter;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.greenwich.eventmanager.R;
import com.greenwich.eventmanager.model.Event;
import com.greenwich.eventmanager.util.EventFormat;
import com.greenwich.eventmanager.util.EventStatus;
import com.greenwich.eventmanager.util.UiUtils;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * Shows events as cards in a RecyclerView.
 *
 * Uses ListAdapter + DiffUtil: when a new list is submitted, only the cards
 * that actually changed are redrawn (with animation), instead of refreshing
 * the whole list with notifyDataSetChanged().
 *
 * Time-based parts of a card (status, countdown, progress) depend on "now", which
 * the Activity passes with setNow() before submitting a list.
 *
 * Multi-select: a long press starts selection mode (handled by the Activity);
 * selected cards get a primary outline and a check mark over the date block.
 */
public class EventAdapter extends ListAdapter<Event, EventAdapter.EventViewHolder> {

    /** Callback so the Activity decides what happens when a card is tapped. */
    public interface OnEventClickListener {
        void onEventClick(Event event);
    }

    /** Long press: the Activity starts multi-select mode. */
    public interface OnEventLongClickListener {
        void onEventLongClick(Event event);
    }

    private final OnEventClickListener listener;
    private final OnEventLongClickListener longClickListener;
    private long now = System.currentTimeMillis();
    private Set<Long> selectedIds = Collections.emptySet();

    public EventAdapter(OnEventClickListener listener, OnEventLongClickListener longClickListener) {
        super(DIFF_CALLBACK);
        this.listener = listener;
        this.longClickListener = longClickListener;
    }

    /** Ids of the selected events (empty = not in selection mode). Re-binds the cards. */
    public void setSelectedIds(Set<Long> ids) {
        this.selectedIds = ids;
        notifyItemRangeChanged(0, getItemCount());
    }

    /** Time used for statuses / countdowns. Visible cards are re-bound to show it. */
    public void setNow(long now) {
        this.now = now;
        notifyItemRangeChanged(0, getItemCount());
    }

    private static final DiffUtil.ItemCallback<Event> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<Event>() {
                @Override
                public boolean areItemsTheSame(@NonNull Event a, @NonNull Event b) {
                    return a.getId() == b.getId();
                }

                @Override
                public boolean areContentsTheSame(@NonNull Event a, @NonNull Event b) {
                    // updatedAt changes on every edit of the event or its sessions
                    return a.getUpdatedAt() == b.getUpdatedAt()
                            && a.getSessionCount() == b.getSessionCount()
                            && a.isDirty() == b.isDirty()
                            && Objects.equals(a.getStatus(), b.getStatus())
                            && Objects.equals(a.getStatusNote(), b.getStatusNote());
                }
            };

    @NonNull
    @Override
    public EventViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_event, parent, false);
        return new EventViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull EventViewHolder holder, int position) {
        Event event = getItem(position);
        holder.bind(event, now, selectedIds.contains(event.getId()), listener, longClickListener);
    }

    // ---------------------------------------------------------------------

    static class EventViewHolder extends RecyclerView.ViewHolder {

        private final MaterialCardView card;
        private final View dateBlock;
        private final TextView tvMonth, tvDay, tvName, tvStatusBadge, tvTime, tvLocation,
                tvOrganiser, tvProgressLabel, tvReason, tvType, tvSessionCount, tvSyncStatus;
        private final View rowProgress;
        private final View ivSelected;
        private final LinearProgressIndicator progress;

        EventViewHolder(@NonNull View itemView) {
            super(itemView);
            card = (MaterialCardView) itemView;
            dateBlock = itemView.findViewById(R.id.dateBlock);
            ivSelected = itemView.findViewById(R.id.ivSelected);
            tvMonth = itemView.findViewById(R.id.tvMonth);
            tvDay = itemView.findViewById(R.id.tvDay);
            tvName = itemView.findViewById(R.id.tvEventName);
            tvStatusBadge = itemView.findViewById(R.id.tvStatusBadge);
            tvTime = itemView.findViewById(R.id.tvEventTime);
            tvLocation = itemView.findViewById(R.id.tvEventLocation);
            tvOrganiser = itemView.findViewById(R.id.tvEventOrganiser);
            rowProgress = itemView.findViewById(R.id.rowProgress);
            progress = itemView.findViewById(R.id.progressEvent);
            tvProgressLabel = itemView.findViewById(R.id.tvProgressLabel);
            tvReason = itemView.findViewById(R.id.tvReason);
            tvType = itemView.findViewById(R.id.tvType);
            tvSessionCount = itemView.findViewById(R.id.tvSessionCount);
            tvSyncStatus = itemView.findViewById(R.id.tvSyncStatus);
        }

        void bind(Event event, long now, boolean selected, OnEventClickListener listener,
                  OnEventLongClickListener longClickListener) {
            Context ctx = itemView.getContext();
            String status = EventStatus.displayStatus(event, now);

            // Date block + badge coloured by status
            tvMonth.setText(EventFormat.monthShort(event.getStartDate()));
            tvDay.setText(EventFormat.dayOfMonth(event.getStartDate()));
            UiUtils.tintStatus(dateBlock, status);
            int fg = ContextCompat.getColor(ctx, UiUtils.statusFg(status));
            tvMonth.setTextColor(fg);
            tvDay.setTextColor(fg);
            UiUtils.bindStatusBadge(tvStatusBadge, status);

            tvName.setText(event.getName());

            // "28/11 – 30/11 · 09:00 – 17:00 · in 2 days"
            String when = ctx.getString(R.string.format_meta_join,
                    EventFormat.formatShortDates(ctx, event), EventFormat.formatTimes(ctx, event));
            if (EventStatus.UPCOMING.equals(status)) {
                when = ctx.getString(R.string.format_meta_join, when,
                        EventFormat.relativeStart(ctx, event, now));
            }
            tvTime.setText(when);
            tvLocation.setText(event.getLocation());
            tvOrganiser.setText(event.getOrganiser());

            // Progress while the event is running
            boolean live = EventStatus.ONGOING.equals(status);
            rowProgress.setVisibility(live ? View.VISIBLE : View.GONE);
            if (live) {
                progress.setProgressCompat(Math.round(EventStatus.progress(event, now) * 1000), false);
                tvProgressLabel.setText(EventFormat.countdown(ctx, event, now));
            }

            // Reason for postponed / cancelled events
            boolean changed = Event.statusNeedsReason(status);
            boolean hasReason = changed && !TextUtils.isEmpty(event.getStatusNote());
            tvReason.setVisibility(hasReason ? View.VISIBLE : View.GONE);
            if (hasReason) {
                tvReason.setText(ctx.getString(R.string.format_reason, event.getStatusNote()));
                UiUtils.tintStatus(tvReason, status);
            }

            // Meta row
            tvType.setText(event.getType());
            boolean draft = EventStatus.DRAFT.equals(status);
            if (draft) {
                tvSessionCount.setText(R.string.card_hidden_from_users);
            } else {
                int count = event.getSessionCount();
                tvSessionCount.setText(ctx.getResources()
                        .getQuantityString(R.plurals.session_count, count, count));
            }
            boolean pending = event.isDirty();
            tvSyncStatus.setText(pending ? R.string.status_not_synced : R.string.status_synced);
            tvSyncStatus.setTextColor(ContextCompat.getColor(ctx,
                    pending ? R.color.status_pending : R.color.status_synced));

            // Selection state (multi-select delete)
            ivSelected.setVisibility(selected ? View.VISIBLE : View.GONE);
            float density = ctx.getResources().getDisplayMetrics().density;
            card.setStrokeWidth(Math.round(density * (selected ? 2 : 1)));
            card.setStrokeColor(ContextCompat.getColor(ctx, selected ? R.color.md_primary
                    : draft ? R.color.card_stroke_draft : R.color.card_stroke));
            card.setCardBackgroundColor(selected
                    ? ContextCompat.getColor(ctx, R.color.md_primary_container)
                    : com.google.android.material.color.MaterialColors.getColor(card,
                            com.google.android.material.R.attr.colorSurface));
            itemView.setAlpha(EventStatus.ENDED.equals(status) && !selected ? 0.75f : 1f);
            itemView.setActivated(selected);

            itemView.setOnClickListener(v -> listener.onEventClick(event));
            itemView.setOnLongClickListener(v -> {
                longClickListener.onEventLongClick(event);
                return true;
            });
        }
    }
}
