package com.example.mafia.Adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.example.mafia.R;
import com.example.mafia.classes.Room;
import com.google.firebase.auth.FirebaseAuth;
import java.util.List;

public class RoomAdapter extends RecyclerView.Adapter<RoomAdapter.RoomViewHolder> {

    private List<Room> rooms;
    private OnJoinClickListener onJoinClickListener;
    private OnRoomDeleteClickListener onRoomDeleteClickListener;

    public interface OnJoinClickListener {
        void onJoinClick(Room room);
    }

    public interface OnRoomDeleteClickListener {
        void onRoomDeleteClick(Room room);
    }

    // Оставляем для обратной совместимости (больше не используется для клика по карточке)
    public interface OnRoomClickListener {
        void onRoomClick(Room room);
    }

    public void setOnRoomClickListener(OnRoomClickListener listener) {
        // теперь клик по карточке ничего не делает — только кнопка "Присоединиться"
    }

    public void setOnJoinClickListener(OnJoinClickListener listener) {
        this.onJoinClickListener = listener;
    }

    public void setOnRoomDeleteClickListener(OnRoomDeleteClickListener listener) {
        this.onRoomDeleteClickListener = listener;
    }

    public void setRooms(List<Room> rooms) {
        this.rooms = rooms;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public RoomViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_room, parent, false);
        return new RoomViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RoomViewHolder holder, int position) {
        Room room = rooms.get(position);
        holder.bind(room);

        holder.joinButton.setOnClickListener(v -> {
            if (onJoinClickListener != null) {
                onJoinClickListener.onJoinClick(room);
            }
        });

        holder.deleteButton.setOnClickListener(v -> {
            if (onRoomDeleteClickListener != null) {
                onRoomDeleteClickListener.onRoomDeleteClick(room);
            }
        });
    }

    @Override
    public int getItemCount() {
        return rooms != null ? rooms.size() : 0;
    }

    static class RoomViewHolder extends RecyclerView.ViewHolder {
        TextView roomNameText;
        TextView creatorNameText;
        TextView playersText;
        TextView codeText;
        TextView statusText;
        TextView deleteButton;
        TextView lockIcon;
        Button joinButton;

        public RoomViewHolder(@NonNull View itemView) {
            super(itemView);
            roomNameText    = itemView.findViewById(R.id.roomNameText);
            creatorNameText = itemView.findViewById(R.id.roomCreatorText);
            playersText     = itemView.findViewById(R.id.roomPlayersText);
            codeText        = itemView.findViewById(R.id.roomCodeText);
            statusText      = itemView.findViewById(R.id.roomStatusText);
            deleteButton    = itemView.findViewById(R.id.deleteButton);
            lockIcon        = itemView.findViewById(R.id.lockIcon);
            joinButton      = itemView.findViewById(R.id.joinRoomButton);
        }

        public void bind(Room room) {
            roomNameText.setText(room.getName());
            creatorNameText.setText("Создатель: " + room.getCreatorName());
            playersText.setText("Игроков: " + room.getCurrentPlayers() + "/" + room.getMaxPlayers());

            // Приватная комната — показываем замок, код не светим
            if (room.isPrivate()) {
                lockIcon.setVisibility(View.VISIBLE);
                codeText.setText("Закрытая комната");
            } else {
                lockIcon.setVisibility(View.GONE);
                codeText.setText("Код: " + room.getCode());
            }

            if (statusText != null) {
                boolean full = room.getCurrentPlayers() >= room.getMaxPlayers();
                statusText.setText(full ? "Заполнена" : "Ожидание игроков");
            }

            // Кнопку удаления показываем только создателю
            String uid = FirebaseAuth.getInstance().getCurrentUser() != null
                    ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
            deleteButton.setVisibility(
                    room.getCreatorId() != null && room.getCreatorId().equals(uid)
                            ? View.VISIBLE : View.GONE);
        }
    }
}
