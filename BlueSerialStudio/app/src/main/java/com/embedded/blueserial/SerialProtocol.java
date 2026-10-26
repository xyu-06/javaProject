package com.embedded.blueserial;

/** Stable pure-Java protocol primitives, independent of Bluetooth and Android UI. */
public final class SerialProtocol {
    private SerialProtocol() { }
    public static String suffix(int selection) {
        switch (selection) {
            case 0: return "";
            case 2: return "\r\n";
            case 3: return "\r";
            default: return "\n";
        }
    }
    public static String appendSuffix(String payload, int selection) {
        return payload + suffix(selection);
    }
    /** x is steering (right joystick), y is throttle (left joystick). */
    public static String twoAxis(String format, int steeringX, int throttleY) {
        return format.replace("{x}", Integer.toString(steeringX))
                .replace("{y}", Integer.toString(throttleY));
    }
}
