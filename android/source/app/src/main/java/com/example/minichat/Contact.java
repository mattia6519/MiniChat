package com.example.minichat;

final class Contact {
    final int id;
    final String name;
    final String phone;
    final boolean online;

    Contact(int id, String name, String phone, boolean online) {
        this.id = id;
        this.name = name;
        this.phone = phone;
        this.online = online;
    }

    @Override
    public String toString() {
        return name + " · " + (online ? "online" : "offline");
    }
}
