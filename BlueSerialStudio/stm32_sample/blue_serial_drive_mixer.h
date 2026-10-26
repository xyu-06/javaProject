#ifndef BLUESERIAL_DRIVE_MIXER_H
#define BLUESERIAL_DRIVE_MIXER_H

/* Positive X = right turn, positive Y = forward. All values -100..100.
 * Curvature steering: while moving, both wheels keep the same travel direction.
 * With Y=0, X controls pivot in place. No hidden commands or motor polarity changes.
 */
static int BlueSerial_Clamp100(int n) { return n < -100 ? -100 : (n > 100 ? 100 : n); }
static void BlueSerial_MixWheels(int x, int y, int *left, int *right) {
    int magnitude, inner;
    x = BlueSerial_Clamp100(x);
    y = BlueSerial_Clamp100(y);
    if (y == 0) { *left = x; *right = -x; return; }
    magnitude = x < 0 ? -x : x;
    inner = ((y < 0 ? -y : y) * (100 - magnitude) + 50) / 100;
    if (x > 0) {
        if (y > 0) { *left = y; *right = inner; }
        else { *left = -inner; *right = y; }
    } else if (x < 0) {
        if (y > 0) { *left = inner; *right = y; }
        else { *left = y; *right = -inner; }
    } else { *left = y; *right = y; }
}
#endif
