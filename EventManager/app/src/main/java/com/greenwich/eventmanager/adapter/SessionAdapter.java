package com.greenwich.eventmanager.adapter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.greenwich.eventmanager.R;
import com.greenwich.eventmanager.model.Session;
import com.greenwich.eventmanager.util.DateUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows an event's sessions. For multi-day events a "Day N · date" heading is
 * inserted before the first session of each day, so the list has two row types.
 */
public class SessionAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface OnSessionClickListener {
        void onSessionClick(Session session);
    }

    public interface OnSessionQrClickListener {
        void onSessionQrClick(Session session);
    }

    private static final int VIEW_HEADER = 0;
    private static final int VIEW_SESSION = 1;

    /** A day heading row. */
    private static final class DayHeader {
        final int dayNumber;
        final String dateIso;

        DayHeader(int dayNumber, String dateIso) {
            this.dayNumber = dayNumber;
            this.dateIso = dateIso;
        }
    }

    /** Mixed list of DayHeader and Session objects, in display order. */
    private final List<Object> rows = new ArrayList<>();
    private final OnSessionClickListener listener;
    private final OnSessionQrClickListener qrListener;

    public SessionAdapter(OnSessionClickListener listener, OnSessionQrClickListener qrListener) {
        this.listener = listener;
        this.qrListener = qrListener;
    }

    /**
     * @param sessions      already sorted by date then start time (the database does this)
     * @param eventStartIso used to number the days (Day 1 = event start date)
     * @param groupByDay    true for multi-day events
     */
    @SuppressLint("NotifyDataSetChanged")  // small list; headers change with the data
    public void setSessions(List<Session> sessions, String eventStartIso, boolean groupByDay) {
        rows.clear();
        String currentDate = null;
        for (Session s : sessions) {
            if (groupByDay && !s.getSessionDate().equals(currentDate)) {
                currentDate = s.getSessionDate();
                int day = DateUtils.daysBetweenInclusive(eventStartIso, currentDate);
                rows.add(new DayHeader(day, currentDate));
            }
            rows.add(s);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position) instanceof DayHeader ? VIEW_HEADER : VIEW_SESSION;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_HEADER) {
            return new HeaderHolder(inflater.inflate(R.layout.item_session_header, parent, false));
        }
        return new SessionHolder(inflater.inflate(R.layout.item_session, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object row = rows.get(position);
        if (holder instanceof HeaderHolder) {
            ((HeaderHolder) holder).bind((DayHeader) row);
        } else {
            ((SessionHolder) holder).bind((Session) row, listener, qrListener);
        }
    }

    // ---------------------------------------------------------------------

    static class HeaderHolder extends RecyclerView.ViewHolder {
        private final TextView tvDayHeader;

        HeaderHolder(@NonNull View itemView) {
            super(itemView);
            tvDayHeader = itemView.findViewById(R.id.tvDayHeader);
        }

        void bind(DayHeader header) {
            tvDayHeader.setText(itemView.getContext().getString(R.string.format_day_header,
                    header.dayNumber, DateUtils.isoToDisplay(header.dateIso)));
        }
    }

    static class SessionHolder extends RecyclerView.ViewHolder {
        private final TextView tvStartTime, tvEndTime, tvName, tvId, tvType, tvSpeaker, tvRoom;
        private final View rowMeta;
        private final View btnQr;

        SessionHolder(@NonNull View itemView) {
            super(itemView);
            tvStartTime = itemView.findViewById(R.id.tvStartTime);
            tvEndTime = itemView.findViewById(R.id.tvEndTime);
            tvName = itemView.findViewById(R.id.tvSessionName);
            tvId = itemView.findViewById(R.id.tvSessionId);
            tvType = itemView.findViewById(R.id.tvSessionType);
            tvSpeaker = itemView.findViewById(R.id.tvSessionSpeaker);
            tvRoom = itemView.findViewById(R.id.tvSessionRoom);
            rowMeta = itemView.findViewById(R.id.rowSessionMeta);
            btnQr = itemView.findViewById(R.id.btnSessionQr);
        }

        void bind(Session s, OnSessionClickListener listener, OnSessionQrClickListener qrListener) {
            Context ctx = itemView.getContext();

            tvStartTime.setText(s.getStartTime());
            boolean hasEnd = !TextUtils.isEmpty(s.getEndTime());
            tvEndTime.setVisibility(hasEnd ? View.VISIBLE : View.GONE);
            tvEndTime.setText(s.getEndTime());

            tvName.setText(s.getName());
            tvId.setText(ctx.getString(R.string.format_session_id, s.getId()));
            tvType.setText(s.getType());

            // Speaker on the left, room on the right; hide whichever is empty
            boolean hasSpeaker = !TextUtils.isEmpty(s.getSpeaker());
            boolean hasRoom = !TextUtils.isEmpty(s.getRoom());
            tvSpeaker.setText(hasSpeaker ? ctx.getString(R.string.format_speaker, s.getSpeaker()) : "");
            tvRoom.setText(hasRoom ? ctx.getString(R.string.format_room, s.getRoom()) : "");
            tvRoom.setVisibility(hasRoom ? View.VISIBLE : View.GONE);
            rowMeta.setVisibility(hasSpeaker || hasRoom ? View.VISIBLE : View.GONE);

            itemView.setOnClickListener(v -> listener.onSessionClick(s));
            btnQr.setOnClickListener(v -> qrListener.onSessionQrClick(s));
        }
    }
}