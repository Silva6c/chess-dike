package com.xqdk.chess.utils;

import java.io.File;
import java.io.FileFilter;
import java.util.regex.Pattern;

public class CpuInfo {

    // https://stackoverflow.com/questions/10133570/availableprocessors-returns-1-for-dualcore-phones
    public static int getCoresCount() {
        class CpuFilter implements FileFilter {
            @Override
            public boolean accept(final File pathname) {
                return Pattern.matches("cpu[0-9]+", pathname.getName());
            }
        }
        try {
            final File dir = new File("/sys/devices/system/cpu/");
            final File[] files = dir.listFiles(new CpuFilter());
            return files.length;
        } catch (final Exception e) {
            return Math.max(1, Runtime.getRuntime().availableProcessors());
        }
    }
}
