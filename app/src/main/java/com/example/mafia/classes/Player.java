package com.example.mafia.classes;

public class Player {
    private String id;
    private String name;
    private String role;
    private boolean isAlive;
    private boolean isReady;
    private boolean isHost;
    private String photoUrl;

    private boolean isPremium;
    private String cardBorderColor;
    private String avatarBadge;
    private String nicknameColor;
    private String cardBackground;
    private int cardBgOpacity = 100;
    private boolean resurrected;

    public Player() {
        this.isAlive = true;
        this.isReady = false;
        this.isHost = false;
    }

    public Player(String id, String name) {
        this.id = id;
        this.name = name;
        this.isAlive = true;
        this.isReady = false;
        this.isHost = false;
        this.role = "civilian";
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public boolean isAlive() {
        return isAlive;
    }

    public void setAlive(boolean alive) {
        this.isAlive = alive;
    }

    public boolean isReady() {
        return isReady;
    }

    public void setReady(boolean ready) {
        this.isReady = ready;
    }

    public boolean isHost() {
        return isHost;
    }

    public void setHost(boolean host) {
        this.isHost = host;
    }

    public String getPhotoUrl() {
        return photoUrl;
    }

    public void setPhotoUrl(String u) {
        this.photoUrl = u;
    }

    public boolean isPremium() {
        return isPremium;
    }

    public void setPremium(boolean p) {
        this.isPremium = p;
    }

    public String getCardBorderColor() {
        return cardBorderColor;
    }

    public void setCardBorderColor(String c) {
        this.cardBorderColor = c;
    }

    public String getAvatarBadge() {
        return avatarBadge;
    }

    public void setAvatarBadge(String b) {
        this.avatarBadge = b;
    }

    public String getNicknameColor() {
        return nicknameColor;
    }

    public void setNicknameColor(String c) {
        this.nicknameColor = c;
    }

    public String getCardBackground() {
        return cardBackground;
    }

    public void setCardBackground(String bg) {
        this.cardBackground = bg;
    }

    public int getCardBgOpacity() {
        return cardBgOpacity;
    }

    public void setCardBgOpacity(int opacity) {
        this.cardBgOpacity = opacity;
    }

    public boolean isResurrected() {
        return resurrected;
    }

    public void setResurrected(boolean r) {
        this.resurrected = r;
    }
}