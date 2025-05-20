package com.example.filemover;


import java.io.File;

public class PathSeperator {

    public static String[] seperate(String path){
        if(OSValidator.isWindows())
        {
            // String splitter = File.separator.replace("\\","\\\\");
            path = path.replaceAll("/","\\\\");
            String[] split = path.split("\\\\");
            return split;
        }else {
            return path.split(File.separator);
        }
    }
}

class OSValidator {

    private static String OS = System.getProperty("os.name").toLowerCase();

    public static boolean isWindows() {
        return OS.contains("win");
    }

    public static boolean isMac() {
        return OS.contains("mac");
    }

    public static boolean isUnix() {
        return (OS.contains("nix") || OS.contains("nux") || OS.contains("aix"));
    }

    public static boolean isSolaris() {
        return OS.contains("sunos");
    }

}

