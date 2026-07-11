package com.example.mafia.Adapters;

import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.drawable.GradientDrawable;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PlayerActionAdapter с поддержкой Premium кастомизации:
 *  — цветная рамка карточки (cardBorderColor)
 *  — произвольный эмодзи-бейдж (avatarBadge)
 *  — цветной ник (nicknameColor)
 *  — фон карточки: цвет или фото (cardBackground) — применяется только
 *    когда карточка в нейтральном состоянии (нет голосов/выбора),
 *    т.к. красный/зелёный индикатор голосования важнее эстетики.
 *  — детальные голоса для Premium (voterNames)
 */
public class PlayerActionAdapter extends RecyclerView.Adapter<PlayerActionAdapter.PlayerViewHolder> {

    private List<Player> players = new ArrayList<>();
    private final String currentUserId;
    private OnPlayerClickListener listener;
    private boolean interactionEnabled = true;
    private boolean selfSelectionAllowed = false;

    private Map<String, Integer> voteCounts = new HashMap<>();
    private Map<String, List<String>> voteDetails = new HashMap<>();
    private boolean showVoteDetails = false;

    private String selectedPlayerId = null;

    public interface OnPlayerClickListener {
        void onPlayerClick(Player player);
    }

    public PlayerActionAdapter(String currentUserId) {
        this.currentUserId = currentUserId;
    }

    public void setPlayers(List<Player> players) {
        this.players = players != null ? players : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void setVoteCounts(Map<String, Integer> counts) {
        this.voteCounts = counts != null ? counts : new HashMap<>();
        notifyDataSetChanged();
    }

    public void setVoteDetails(Map<String, List<String>> details) {
        this.voteDetails = details != null ? details : new HashMap<>();
        notifyDataSetChanged();
    }

    public void setShowVoteDetails(boolean show) {
        this.showVoteDetails = show;
        notifyDataSetChanged();
    }

    public void setSelectedPlayer(String playerId) {
        this.selectedPlayerId = playerId;
        notifyDataSetChanged();
    }

    public void setInteractionEnabled(boolean enabled) {
        this.interactionEnabled = enabled;
        notifyDataSetChanged();
    }

    public void setSelfSelectionAllowed(boolean allowed) {
        this.selfSelectionAllowed = allowed;
        notifyDataSetChanged();
    }

    public void setOnPlayerClickListener(OnPlayerClickListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public PlayerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_player_action, parent, false);
        return new PlayerViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PlayerViewHolder holder, int position) {
        holder.bind(players.get(position));
    }

    @Override
    public int getItemCount() { return players.size(); }

    class PlayerViewHolder extends RecyclerView.ViewHolder {
        final CardView playerCard;
        final ImageView playerAvatar;
        final TextView playerNameText;
        final TextView playerRoleText;
        final View deadOverlay;
        TextView badgeText;
        ImageView playerCardBgImage;   // фото-фон (Premium), может отсутствовать в старом layout

        PlayerViewHolder(@NonNull View itemView) {
            super(itemView);
            playerCard        = itemView.findViewById(R.id.playerCard);
            playerAvatar      = itemView.findViewById(R.id.playerAvatar);
            playerNameText    = itemView.findViewById(R.id.playerNameText);
            playerRoleText    = itemView.findViewById(R.id.playerRoleText);
            deadOverlay       = itemView.findViewById(R.id.deadOverlay);
            badgeText         = itemView.findViewById(R.id.playerBadgeText);
            playerCardBgImage = itemView.findViewById(R.id.playerCardBgImage);
        }

        void bind(Player player) {
            // ── Имя ──────────────────────────────────────────────────────────
            playerNameText.setText(player.getName());
            if (player.isPremium() && player.getNicknameColor() != null
                    && !player.getNicknameColor().isEmpty()) {
                try {
                    playerNameText.setTextColor(Color.parseColor(player.getNicknameColor()));
                } catch (Exception e) {
                    playerNameText.setTextColor(Color.WHITE);
                }
            } else {
                playerNameText.setTextColor(Color.WHITE);
            }

            // ── Фото аватарки ────────────────────────────────────────────────
            String photoBase64 = player.getPhotoUrl();
            if (photoBase64 != null && !photoBase64.isEmpty()) {
                try {
                    byte[] bytes = android.util.Base64.decode(photoBase64, android.util.Base64.DEFAULT);
                    playerAvatar.clearColorFilter();
                    Glide.with(itemView.getContext())
                            .load(bytes)
                            .transform(new CircleCrop())
                            .placeholder(R.drawable.ic_player_avatar1)
                            .error(R.drawable.ic_player_avatar1)
                            .into(playerAvatar);
                } catch (Exception e) {
                    playerAvatar.setImageResource(R.drawable.ic_player_avatar1);
                    playerAvatar.setColorFilter(ContextCompat.getColor(
                            itemView.getContext(), R.color.icon_tint));
                }
            } else {
                playerAvatar.setImageResource(R.drawable.ic_player_avatar1);
                playerAvatar.setColorFilter(ContextCompat.getColor(
                        itemView.getContext(), R.color.icon_tint));
            }

            // ── Бейдж Premium (произвольный эмодзи) или звезда хоста ──────────
            if (badgeText != null) {
                if (player.isPremium() && player.getAvatarBadge() != null
                        && !player.getAvatarBadge().isEmpty()) {
                    // avatarBadge теперь хранит сам эмодзи, введённый пользователем
                    badgeText.setText(normalizeBadge(player.getAvatarBadge()));
                    badgeText.setVisibility(View.VISIBLE);
                } else if (player.isHost()) {
                    badgeText.setText("⭐");
                    badgeText.setVisibility(View.VISIBLE);
                } else {
                    badgeText.setVisibility(View.GONE);
                }
            }

            // ── Голоса / выбор / Premium-фон ───────────────────────────────────
            int votes     = voteCounts.getOrDefault(player.getId(), 0);
            boolean isSelected = player.getId().equals(selectedPlayerId);
            boolean isMe       = player.getId().equals(currentUserId);
            boolean canClick   = interactionEnabled
                    && player.isAlive()
                    && (!isMe || selfSelectionAllowed);

            // Состояние "нейтральное" — можно применить Premium-фон/фото.
            // Голосование/выбор всегда важнее — Premium-фон в эти моменты скрываем,
            // чтобы не терять читаемость игровых сигналов.
            boolean neutralState = !isSelected && votes == 0 && !(isMe && selfSelectionAllowed);

            if (isSelected) {
                clearPremiumBg();
                playerCard.setCardBackgroundColor(Color.rgb(40, 40, 120));
                setCardBorder(playerCard, "#2828FF", 6);
            } else if (votes > 0) {
                clearPremiumBg();
                int red   = Math.min(255, 60 + votes * 55);
                int green = Math.max(0, 20 - votes * 5);
                int blue  = Math.max(0, 20 - votes * 5);
                playerCard.setCardBackgroundColor(Color.rgb(red, green, blue));
                applyPremiumBorder(player, playerCard);
            } else if (isMe && selfSelectionAllowed) {
                clearPremiumBg();
                playerCard.setCardBackgroundColor(Color.rgb(20, 80, 30));
                applyPremiumBorder(player, playerCard);
            } else {
                applyPremiumBorder(player, playerCard);
                if (!applyPremiumBackground(player)) {
                    playerCard.setCardBackgroundColor(ContextCompat.getColor(
                            itemView.getContext(), R.color.card_background));
                }
            }

            // ── Счётчик / детали голосов ────────────────────────────────────────
            if (votes > 0) {
                if (showVoteDetails && voteDetails.containsKey(player.getId())) {
                    List<String> names = voteDetails.get(player.getId());
                    String label = "🗳 " + votes + "\n" + joinNames(names);
                    playerRoleText.setText(label);
                    playerRoleText.setVisibility(View.VISIBLE);
                    playerRoleText.setTextColor(0xFFFFD700);
                    playerRoleText.setTextSize(9f);
                } else {
                    playerRoleText.setText("🗳 " + votes);
                    playerRoleText.setVisibility(View.VISIBLE);
                    playerRoleText.setTextColor(Color.WHITE);
                    playerRoleText.setTextSize(11f);
                }
            } else if (isMe && selfSelectionAllowed) {
                playerRoleText.setText("💊 вы");
                playerRoleText.setVisibility(View.VISIBLE);
                playerRoleText.setTextColor(Color.rgb(100, 220, 100));
                playerRoleText.setTextSize(11f);
            } else {
                playerRoleText.setVisibility(View.GONE);
            }

            // ── Мёртвый оверлей ───────────────────────────────────────────────
            if (deadOverlay != null) {
                deadOverlay.setVisibility(player.isAlive() ? View.GONE : View.VISIBLE);
            }

            float alpha = !player.isAlive() ? 1.0f
                    : (!interactionEnabled) ? 0.35f
                    : (isMe && !selfSelectionAllowed) ? 0.45f : 1.0f;
            itemView.setAlpha(alpha);

            itemView.setOnClickListener(v -> {
                if (listener != null && canClick) listener.onPlayerClick(player);
            });
        }

        /** Применяет Premium рамку, если есть; иначе сбрасывает на невидимую. */
        private void applyPremiumBorder(Player player, CardView card) {
            if (player.isPremium() && player.getCardBorderColor() != null
                    && !player.getCardBorderColor().isEmpty()) {
                setCardBorder(card, player.getCardBorderColor(), 5);
            } else {
                setCardBorder(card, "#1A1A1A", 1);
            }
        }

        /**
         * Применяет Premium-фон (цвет или фото) в нейтральном состоянии.
         * @return true если Premium-фон применён (тогда стандартный фон не нужен)
         */
        private boolean applyPremiumBackground(Player player) {
            if (!player.isPremium()) { clearPremiumBg(); return false; }
            String bg = player.getCardBackground();
            if (bg == null || bg.isEmpty()) { clearPremiumBg(); return false; }

            if (bg.startsWith("img:") && playerCardBgImage != null) {
                try {
                    byte[] bytes = android.util.Base64.decode(bg.substring(4), android.util.Base64.DEFAULT);
                    playerCardBgImage.setVisibility(View.VISIBLE);
                    playerCardBgImage.setColorFilter(grayscaleFilter());
                    playerCardBgImage.setAlpha(player.getCardBgOpacity() / 100f);
                    Glide.with(itemView.getContext()).load(bytes).centerCrop()
                            .into(playerCardBgImage);
                    playerCard.setCardBackgroundColor(Color.TRANSPARENT);
                    return true;
                } catch (Exception e) {
                    clearPremiumBg();
                    return false;
                }
            } else if (bg.startsWith("#")) {
                clearPremiumBg();
                try {
                    playerCard.setCardBackgroundColor(Color.parseColor(bg));
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
            clearPremiumBg();
            return false;
        }

        private void clearPremiumBg() {
            if (playerCardBgImage != null) playerCardBgImage.setVisibility(View.GONE);
        }

        /**
         * Ч/б фильтр применяется на клиенте как подстраховка — на случай если
         * в базе осталось старое фото, сохранённое до перехода на серверную
         * grayscale-конвертацию (см. PremiumCustomizeActivity.toGrayscale()).
         */
        private ColorMatrixColorFilter grayscaleFilter() {
            ColorMatrix matrix = new ColorMatrix();
            matrix.setSaturation(0f);
            return new ColorMatrixColorFilter(matrix);
        }

        private void setCardBorder(CardView card, String hexColor, int widthDp) {
            try {
                GradientDrawable gd = new GradientDrawable();
                gd.setColor(Color.TRANSPARENT);
                gd.setCornerRadius(20f);
                gd.setStroke(widthDp * 3, Color.parseColor(hexColor));
                card.setForeground(gd);
            } catch (Exception ignored) {}
        }

        /** Поддержка старых значений (до перехода на свободный ввод эмодзи). */
        private String normalizeBadge(String badge) {
            if (badge == null || badge.isEmpty()) return "👑";
            switch (badge) {
                case "crown":   return "👑";
                case "diamond": return "💎";
                case "fire":    return "🔥";
                case "skull":   return "💀";
                case "moon":    return "🌙";
                default:        return badge;
            }
        }
    }

    private String joinNames(List<String> names) {
        if (names == null || names.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(names.get(i));
        }
        return sb.toString();
    }
}