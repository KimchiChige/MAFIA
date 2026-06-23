package com.example.mafia.Adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.example.mafia.R;
import com.example.mafia.classes.Player;
import java.util.List;

public class RoomPlayersAdapter extends RecyclerView.Adapter<RoomPlayersAdapter.PlayerViewHolder> {

    private List<Player> players;
    private String currentUserId;
    private OnPlayerReadyClickListener listener;

    public interface OnPlayerReadyClickListener {
        void onPlayerReadyClick(Player player);
    }

    public RoomPlayersAdapter(String currentUserId) {
        this.currentUserId = currentUserId;
    }

    public void setPlayers(List<Player> players) {
        this.players = players;
        notifyDataSetChanged();
    }

    public void setOnPlayerReadyClickListener(OnPlayerReadyClickListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public PlayerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_room_player, parent, false);
        return new PlayerViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PlayerViewHolder holder, int position) {
        Player player = players.get(position);
        holder.bind(player);
    }

    @Override
    public int getItemCount() {
        return players != null ? players.size() : 0;
    }

    class PlayerViewHolder extends RecyclerView.ViewHolder {
        CardView playerCard;
        ImageView playerAvatar;
        TextView playerNameText;
        ImageView hostStar;  // Вместо hostBadge
        ImageView notReadyIcon;
        ImageView readyIcon;

        public PlayerViewHolder(@NonNull View itemView) {
            super(itemView);
            playerCard = itemView.findViewById(R.id.playerCard);
            playerAvatar = itemView.findViewById(R.id.playerAvatar);
            playerNameText = itemView.findViewById(R.id.playerNameText);
            hostStar = itemView.findViewById(R.id.hostStar);  // Новая звезда
            notReadyIcon = itemView.findViewById(R.id.notReadyIcon);
            readyIcon = itemView.findViewById(R.id.readyIcon);
        }

        public void bind(Player player) {
            playerNameText.setText(player.getName());

            String photoBase64 = player.getPhotoUrl();
            if (photoBase64 != null && !photoBase64.isEmpty()) {
                try {
                    byte[] photoBytes = android.util.Base64.decode(photoBase64, android.util.Base64.DEFAULT);
                    playerAvatar.clearColorFilter();
                    Glide.with(itemView.getContext())
                            .load(photoBytes)
                            .transform(new CircleCrop())
                            .placeholder(R.drawable.ic_player_avatar1)
                            .error(R.drawable.ic_player_avatar1)
                            .into(playerAvatar);
                } catch (Exception e) {
                    playerAvatar.setImageResource(R.drawable.ic_player_avatar1);
                    playerAvatar.setColorFilter(
                            androidx.core.content.ContextCompat.getColor(
                                    itemView.getContext(), R.color.icon_tint));
                }
            } else {
                playerAvatar.setImageResource(R.drawable.ic_player_avatar1);
                playerAvatar.setColorFilter(
                        androidx.core.content.ContextCompat.getColor(
                                itemView.getContext(), R.color.icon_tint));
            }

            // Показываем статус готовности через иконки
            if (player.isReady()) {
                notReadyIcon.setVisibility(View.GONE);
                readyIcon.setVisibility(View.VISIBLE);
            } else {
                notReadyIcon.setVisibility(View.VISIBLE);
                readyIcon.setVisibility(View.GONE);
            }

            // Показываем звезду хоста (вместо бейджа)
            if (player.isHost()) {
                hostStar.setVisibility(View.VISIBLE);
            } else {
                hostStar.setVisibility(View.GONE);
            }

            // Убираем фон для всех
            itemView.setBackgroundColor(
                    ContextCompat.getColor(itemView.getContext(), android.R.color.transparent));

            // Красная рамка у всех игроков
            playerCard.setForeground(
                    ContextCompat.getDrawable(itemView.getContext(), R.drawable.player_card_border)
            );

            // Клик для отметки готовности (только для себя)
            itemView.setOnClickListener(v -> {
                if (listener != null && player.getId().equals(currentUserId)) {
                    listener.onPlayerReadyClick(player);
                }
            });
        }
    }
}