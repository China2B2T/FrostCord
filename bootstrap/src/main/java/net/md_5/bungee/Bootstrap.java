package net.md_5.bungee;

public class Bootstrap {

    public static void main(String[] args) throws Exception {
        if (Float.parseFloat ( System.getProperty ( "java.class.version" ) ) < 52.0) {
            System.err.println ( "Please use Java 8 or above!" );
            return;
        }

        BungeeCordLauncher.main ( args );
    }
}
