#ifndef BLUE_SERIAL_PROTOCOL_H
#define BLUE_SERIAL_PROTOCOL_H

#include <stdint.h>

/* STM32F103 + StdPeriphLib. USART1: PA9 (TX), PA10 (RX). */
void BlueSerial_Init(uint32_t uart_baud);
void BlueSerial_Poll(void);  /* Call often in main while(1). */
void BlueSerial_Tick1ms(void); /* Call from an existing 1 ms timer / SysTick. */
void BlueSerial_SendPlot(int ch1, int ch2, int ch3); /* Sends: a,b,c\n */

/* Override these weak hooks with your TB6612 and servo hardware implementations. */
void BlueSerial_Motors(int left_speed, int right_speed); /* -100 .. +100 */
void BlueSerial_Servo(int angle);                      /* 0 .. 180 */
void BlueSerial_Aux(int value);
void BlueSerial_Beep(void);

#endif
