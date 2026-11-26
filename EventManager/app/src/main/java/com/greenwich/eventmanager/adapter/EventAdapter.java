package com.greenwich.eventmanager.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.greenwich.eventmanager.R;
import com.greenwich.eventmanager.model.Event;
import com.greenwich.eventmanager.util.EventFormat;

/**
 * Shows events as cards in a RecyclerView.
 *
 * Uses ListAdapter + DiffUtil: when a new list is submitted, only the cards
 * that actually changed are redrawn (with animation), instead of refreshing
 * the whole list with notifyDataSetChanged().
 */
public class EventAdapter extends ListAdapter<Event, EventAdapter.EventViewHolder> {

    /** Callback so the Activity decides what happens when a card is tapped. */
    public interface OnEventClickListener {
        void onEventClick(Event event);
    }

    private final OnEventClickListener listener;

    public EventAdapter(OnEventClickListener listener) {
        super(DIFF_CALLBACK);
        this.listener = listener;
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
                            && a.isDirty() == b.isDirty();
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
        holder.bind(getItem(position), listener);
    }

    // ---------------------------------------------------------------------

    static class EventViewHolder extends RecyclerView.ViewHolder {

        private final TextView tvName;
        private final TextView tvSyncStatus;
        private final TextView tvDates;
        private final TextView tvLocation;
        private final TextView tvOrganiser;
        private final Chip chipType;
        private final TextView tvSessionCount;

        EventViewHolder(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tvEventName);
            tvSyncStatus = itemView.findViewById(R.id.tvSyncStatus);
            tvDates = itemView.findViewById(R.id.tvEventDates);
            tvLocation = itemView.findViewById(R.id.tvEventLocation);
            tvOrganiser = itemView.findViewById(R.id.tvEventOrganiser);
            chipType = itemView.findViewById(R.id.chipType);
            tvSessionCount = itemView.findViewById(R.id.tvSessionCount);
        }

        void bind(Event event, OnEventClickListener listener) {
            Context ctx = itemView.getContext();

            tvName.setText(event.getName());
            tvDates.setText(ctx.getString(R.string.format_date_time,
                    EventFormat.formatDates(ctx, event), EventFormat.formatTimes(ctx, event)));
            tvLocation.setText(ctx.getString(R.string.format_location, event.getLocation()));
            tvOrganiser.setText(ctx.getString(R.string.format_organiser, event.getOrganiser()));
            chipType.setText(event.getType());

            int count = event.getSessionCount();
            tvSessionCount.setText(ctx.getResources()
                    .getQuantityString(R.plurals.session_count, count, count));

            // Orange "Not synced" until the event has been uploaded to the cloud
            boolean pending = event.isDirty();
            tvSyncStatus.setText(pending ? R.string.status_not_synced : R.string.status_synced);
            tvSyncStatus.setTextColor(ContextCompat.getColor(ctx,
                    pending ? R.color.status_pending : R.color.status_synced));

            itemView.setOnClickListener(v -> listener.onEventClick(event));
        }
    }
}