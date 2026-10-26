#include "../stm32_sample/blue_serial_drive_mixer.h"
#include <assert.h>
#include <stdio.h>
int main(void) {
    int l, r;
    BlueSerial_MixWheels(0,0,&l,&r); assert(l==0 && r==0);
    BlueSerial_MixWheels(0,75,&l,&r); assert(l==75 && r==75);
    BlueSerial_MixWheels(50,80,&l,&r); assert(l==80 && r==40);
    BlueSerial_MixWheels(-50,80,&l,&r); assert(l==40 && r==80);
    BlueSerial_MixWheels(100,80,&l,&r); assert(l==80 && r==0);
    BlueSerial_MixWheels(50,-80,&l,&r); assert(l==-40 && r==-80);
    BlueSerial_MixWheels(-50,-80,&l,&r); assert(l==-80 && r==-40);
    BlueSerial_MixWheels(60,0,&l,&r); assert(l==60 && r==-60);
    BlueSerial_MixWheels(-60,0,&l,&r); assert(l==-60 && r==60);
    BlueSerial_MixWheels(150,150,&l,&r); assert(l==100 && r==0);
    puts("10 wheel-mixer checks passed");
    return 0;
}
