import com.embedded.blueserial.SerialProtocol;
import com.embedded.blueserial.DriveControlMath;

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
        check(DriveControlMath.heldSpeed(0) == 18, "initial button speed");
        check(DriveControlMath.heldSpeed(1000) > DriveControlMath.heldSpeed(300), "press duration increases speed");
        check(DriveControlMath.heldSpeed(1600) == 100, "full speed after hold");
        check(DriveControlMath.heldSpeed(3000) == 100, "speed capped at 100");
        check(DriveControlMath.buttonAxis(false,0,false,0,2000) == 0, "released button zeros speed");
        check(DriveControlMath.buttonAxis(true,1000,false,0,1000) == 18, "forward button output");
        check(DriveControlMath.buttonAxis(false,0,true,1000,2600) == -100, "reverse button output");
        check(DriveControlMath.buttonAxis(true,1000,true,1000,2600) == 0, "opposing buttons cancel");
        int x = DriveControlMath.buttonAxis(true,0,false,0,800);
        int y = DriveControlMath.buttonAxis(true,0,false,0,1100);
        check(SerialProtocol.twoAxis("J,{x},{y}",x,y).equals("J,"+x+","+y), "buttons share joystick wire format");
        check(SerialProtocol.twoAxis("J,{x},{y}",0,0).equals("J,0,0"), "urgent stop uses same protocol");
        System.out.println("20 protocol checks passed");
    }
}
