package org.skypulse;


import org.skypulse.config.ConfigLoader;
import org.skypulse.config.Configuration;


public class Main {
    public static void main(String[] args) {
        // 1️ Load encrypted configuration (decrypted in memory)
        String configPath = (args.length > 0) ? args[0] : "config.xml";
        Configuration cfg = ConfigLoader.loadConfig(configPath);


    }
}
