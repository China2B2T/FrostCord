package net.md_5.bungee;

import java.util.LinkedHashMap;

public class Firewall {
    private static LinkedHashMap<String, Integer> blocked;

    public static void tickViolation(String address, int point) {
        // TODO: Add a timer
        if (blocked.containsKey ( address )) {
            int vl = blocked.get ( address ) + point;
            blocked.put ( address, vl );
        } else {
            blocked.put ( address, point );
        }
    }

    public static boolean isBlocked(String address) {
        // TODO: Add specified violation
        if (blocked.get ( address ) >= 60) {
            return true;
        }

        return false;
    }
}
