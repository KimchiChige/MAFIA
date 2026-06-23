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
    private OnRoomClickListener onRoomClickListener;
    private OnRoomDeleteClickListener onRoomDeleteClickListener;

    public interface OnRoomClickListener {
        void onRoomClick(Room room);
    }

    public interface OnRoomDeleteClickListener {
        void onRoomDeleteClick(Room room);
    }

    public void setOnRoomClickListener(OnRoomClickListener listener) {
        this.onRoomClickListener = listener;
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

        holder.itemView.setOnClickListener(v -> {
            if (onRoomClickListener != null) {
                onRoomClickListener.onRoomClick(room);
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
        TextView deleteButton;

        public RoomViewHolder(@NonNull View itemView) {
            super(itemView);
            roomNameText = itemView.findViewById(R.id.roomNameText);
            creatorNameText = itemView.findViewById(R.id.roomCreatorText);
            playersText = itemView.findViewById(R.id.roomPlayersText);
            codeText = itemView.findViewById(R.id.roomCodeText);
            deleteButton = itemView.findViewById(R.id.deleteButton);
        }

        public void bind(Room room) {
            roomNameText.setText(room.getName());
            creatorNameText.setText("Создатель: " + room.getCreatorName());
            playersText.setText("Игроков: " + room.getCurrentPlayers() + "/" + room.getMaxPlayers());
            codeText.setText("Код: " + room.getCode());

            // Показываем кнопку удаления только для создателя комнаты
            String currentUserId = FirebaseAuth.getInstance().getCurrentUser() != null ?
                    FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

            if (room.getCreatorId() != null && room.getCreatorId().equals(currentUserId)) {
                deleteButton.setVisibility(View.VISIBLE);
            } else {
                deleteButton.setVisibility(View.GONE);
            }
        }
    }
}