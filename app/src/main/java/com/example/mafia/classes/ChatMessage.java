package com.example.mafia.classes;

public class ChatMessage {
    private String uid;
    private String name;
    private String text;
    private String photoBase64;
    private long timestamp;
    private int dayNumber;

    public ChatMessage() {}

    public ChatMessage(String uid, String name, String text, String photoBase64, int dayNumber) {
        this.uid = uid;
        this.name = name;
        this.text = text;
        this.photoBase64 = photoBase64;
        this.dayNumber = dayNumber;
        this.timestamp = System.currentTimeMillis();
    }

    public String getUid()          { return uid; }
    public String getName()         { return name; }
    public String getText()         { return text; }
    public String getPhotoBase64()  { return photoBase64; }
    public long getTimestamp()      { return timestamp; }
    public int getDayNumber()       { return dayNumber; }

    public void setUid(String uid)               { this.uid = uid; }
    public void setName(String name)             { this.name = name; }
    public void setText(String text)             { this.text = text; }
    public void setPhotoBase64(String p)         { this.photoBase64 = p; }
    public void setTimestamp(long t)             { this.timestamp = t; }
    public void setDayNumber(int d)              { this.dayNumber = d; }
}