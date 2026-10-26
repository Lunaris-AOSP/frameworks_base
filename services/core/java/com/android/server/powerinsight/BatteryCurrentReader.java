/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.powerinsight;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import java.util.function.Supplier;

final class BatteryCurrentReader {
    private static final String POWER_SUPPLY_PATH = "/sys/class/power_supply/";
    private static final String[] BATTERY_SOURCES = {"battery", "bms", "BAT0"};

    private final Supplier<Integer> mFrameworkReader;
    private final Function<String, Integer> mFileReader;
    private final int mDivisor;

    BatteryCurrentReader(Supplier<Integer> frameworkReader, int divisor) {
        this(frameworkReader, BatteryCurrentReader::readIntFromFile, divisor);
    }

    BatteryCurrentReader(Supplier<Integer> frameworkReader, Function<String, Integer> fileReader,
            int divisor) {
        mFrameworkReader = frameworkReader;
        mFileReader = fileReader;
        mDivisor = divisor != 0 ? divisor : 1000;
    }

    int readCurrentMa() {
        for (String source : BATTERY_SOURCES) {
            Integer current = mFileReader.apply(POWER_SUPPLY_PATH + source + "/current_now");
            if (isAvailable(current)) return current / mDivisor;
        }

        Integer current = mFrameworkReader.get();
        return isAvailable(current) ? current / 1000 : 0;
    }

    private static boolean isAvailable(Integer current) {
        return current != null && current != Integer.MIN_VALUE;
    }

    private static Integer readIntFromFile(String path) {
        try (FileInputStream stream = new FileInputStream(path)) {
            byte[] buffer = new byte[64];
            int length = stream.read(buffer);
            if (length <= 0) return null;
            return Integer.valueOf(new String(buffer, 0, length, StandardCharsets.UTF_8).trim());
        } catch (IOException | NumberFormatException | SecurityException e) {
            return null;
        }
    }
}
