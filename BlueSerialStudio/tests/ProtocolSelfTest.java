import com.embedded.blueserial.SerialProtocol;

/** JVM-only smoke tests; run without Android SDK or Bluetooth hardware. */
public final class ProtocolSelfTest {
    private static void check(boolean value, String title) {
        if (!value) throw new AssertionError(title);
    }
    public static void main(String[] args) {
        check(SerialProtocol.appendSuffix("LED_ON",0).equals("LED_ON"), "none");
        check(SerialProtocol.appendSuffix("LED_ON",1).equals("LED_ON\n"), "LF");
        check(SerialProtocol.appendSuffix("LED_ON",2).equals("LED_ON\r\n"), "CRLF");
        check(SerialProtocol.appendSuffix("LED_ON",3).equals("LED_ON\r"), "CR");
        check(SerialProtocol.appendSuffix("LED_ON",2).getBytes(java.nio.charset.StandardCharsets.UTF_8).length == 8, "CRLF bytes");
        check(SerialProtocol.twoAxis("J,{x},{y}",-50,90).equals("J,-50,90"), "independent axes");
        check(SerialProtocol.twoAxis("J,{x},{y}",0,90).equals("J,0,90"), "right released");
        check(SerialProtocol.twoAxis("J,{x},{y}",-50,0).equals("J,-50,0"), "left released");
        check(SerialProtocol.twoAxis("J,{x},{y}",0,0).equals("J,0,0"), "both released");
        check(SerialProtocol.twoAxis("x={x};y={y}",25,-75).equals("x=25;y=-75"), "custom format");
        System.out.println("10 protocol checks passed");
    }
}
