package dev.bilicraft.director.protocol;

public final class Protocol {
    public static final int MAGIC = 0x42434452;
    public static final int VERSION = 4;
    public static final String CHANNEL = "bilicraftdirector:director";
    public static final int MAX_PACKET_BYTES = 30_000;
    public static final int MAX_PLAYERS = 128;
    public static final int MAX_SCENES = 64;
    public static final int MIN_FOLLOW_SECONDS = 180;
    public static final int MAX_FOLLOW_SECONDS = 480;
    public static final int MAX_MAP_EXCLUSIONS = 64;

    private Protocol() {
    }
}
