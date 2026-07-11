package com.example.mafia.classes;

public class ChatMessage {
    private String uid;
    private String name;
    private String text;
    private String photoBase64;
    private long timestamp;
    private int dayNumber;
    /**
     * true — сообщение от мёртвого игрока (ghost chat).
     * Живые игроки его не видят; мёртвые видят с особым оформлением.
     */
    private boolean isGhost;

    public ChatMessage() {}

    public ChatMessage(String uid, String name, String text,
                       String photoBase64, int dayNumber, boolean isGhost) {
        this.uid         = uid;
        this.name        = name;
        this.text        = text;
        this.photoBase64 = photoBase64;
        this.dayNumber   = dayNumber;
        this.timestamp   = System.currentTimeMillis();
        this.isGhost     = isGhost;
    }

    /** Обратная совместимость — живой чат. */
    public ChatMessage(String uid, String name, String text, String photoBase64, int dayNumber) {
        this(uid, name, text, photoBase64, dayNumber, false);
    }

    public String getUid()          { return uid; }
    public String getName()         { return name; }
    public String getText()         { return text; }
    public String getPhotoBase64()  { return photoBase64; }
    public long getTimestamp()      { return timestamp; }
    public int getDayNumber()       { return dayNumber; }
    public boolean isGhost()        { return isGhost; }

    public void setUid(String uid)               { this.uid = uid; }
    public void setName(String name)             { this.name = name; }
    public void setText(String text)             { this.text = text; }
    public void setPhotoBase64(String p)         { this.photoBase64 = p; }
    public void setTimestamp(long t)             { this.timestamp = t; }
    public void setDayNumber(int d)              { this.dayNumber = d; }
    public void setGhost(boolean ghost)          { this.isGhost = ghost; }
}