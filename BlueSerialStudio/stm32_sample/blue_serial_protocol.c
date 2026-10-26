#include "blue_serial_protocol.h"
#include "blue_serial_drive_mixer.h"
#include "stm32f10x.h"
#include "misc.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define RX_BUFFER_SIZE 256
#define MAX_LINE 96

static volatile uint8_t rx_ring[RX_BUFFER_SIZE];
static volatile uint16_t rx_head = 0, rx_tail = 0;
static volatile uint32_t tick_ms = 0;
static uint32_t last_motion_ms = 0;
static int motion_active = 0;

/* Weak default functions intentionally DO NOT move motors.
   Implement these in your motor/servo drivers and override them. */
__attribute__((weak)) void BlueSerial_Motors(int left, int right) { (void)left; (void)right; }
__attribute__((weak)) void BlueSerial_Servo(int angle) { (void)angle; }
__attribute__((weak)) void BlueSerial_Aux(int value) { (void)value; }
__attribute__((weak)) void BlueSerial_Beep(void) { }

static int clamp(int v, int lo, int hi) {
    if (v < lo) return lo;
    if (v > hi) return hi;
    return v;
}

void BlueSerial_Init(uint32_t baud) {
    GPIO_InitTypeDef gpio;
    USART_InitTypeDef usart;
    NVIC_InitTypeDef nvic;

    RCC_APB2PeriphClockCmd(RCC_APB2Periph_GPIOA | RCC_APB2Periph_GPIOC |
                            RCC_APB2Periph_USART1 | RCC_APB2Periph_AFIO, ENABLE);
    /* Blue Pill on-board LED PC13 is active LOW. Change if your board differs. */
    gpio.GPIO_Pin = GPIO_Pin_13;
    gpio.GPIO_Mode = GPIO_Mode_Out_PP;
    gpio.GPIO_Speed = GPIO_Speed_2MHz;
    GPIO_Init(GPIOC, &gpio);
    GPIO_SetBits(GPIOC, GPIO_Pin_13);

    gpio.GPIO_Pin = GPIO_Pin_9;
    gpio.GPIO_Mode = GPIO_Mode_AF_PP;
    gpio.GPIO_Speed = GPIO_Speed_50MHz;
    GPIO_Init(GPIOA, &gpio);
    gpio.GPIO_Pin = GPIO_Pin_10;
    gpio.GPIO_Mode = GPIO_Mode_IN_FLOATING;
    GPIO_Init(GPIOA, &gpio);

    USART_StructInit(&usart);
    usart.USART_BaudRate = baud;
    usart.USART_Mode = USART_Mode_Rx | USART_Mode_Tx;
    USART_Init(USART1, &usart);
    USART_ITConfig(USART1, USART_IT_RXNE, ENABLE);
    nvic.NVIC_IRQChannel = USART1_IRQn;
    nvic.NVIC_IRQChannelPreemptionPriority = 2;
    nvic.NVIC_IRQChannelSubPriority = 0;
    nvic.NVIC_IRQChannelCmd = ENABLE;
    NVIC_Init(&nvic);
    USART_Cmd(USART1, ENABLE);
}

/* IMPORTANT: only one USART1_IRQHandler may exist per project.
   If you already have one, copy the RX ring write into that handler instead. */
void USART1_IRQHandler(void) {
    if (USART_GetITStatus(USART1, USART_IT_RXNE) != RESET) {
        uint8_t byte = (uint8_t)USART_ReceiveData(USART1);
        uint16_t next = (uint16_t)((rx_head + 1) % RX_BUFFER_SIZE);
        if (next != rx_tail) { rx_ring[rx_head] = byte; rx_head = next; }
    }
}

static void uart_puts(const char *s) {
    while (*s) {
        while (USART_GetFlagStatus(USART1, USART_FLAG_TXE) == RESET) { }
        USART_SendData(USART1, (uint16_t)(uint8_t)*s++);
    }
}
void BlueSerial_SendPlot(int a, int b, int c) {
    char line[64];
    snprintf(line, sizeof(line), "%d,%d,%d\n", a, b, c);
    uart_puts(line);
}

static void drive(int left, int right) {
    BlueSerial_Motors(clamp(left, -100, 100), clamp(right, -100, 100));
    last_motion_ms = tick_ms;
    motion_active = 1;
}

static void parse_line(char *s) {
    int x, y, v;
    if (strcmp(s, "LED_ON") == 0) GPIO_ResetBits(GPIOC, GPIO_Pin_13);
    else if (strcmp(s, "LED_OFF") == 0) GPIO_SetBits(GPIOC, GPIO_Pin_13);
    else if (strcmp(s, "F") == 0) drive(65, 65);
    else if (strcmp(s, "B") == 0) drive(-65, -65);
    else if (strcmp(s, "L") == 0) drive(-50, 50);
    else if (strcmp(s, "R") == 0) drive(50, -50);
    else if (strcmp(s, "S") == 0) { BlueSerial_Motors(0, 0); motion_active = 0; }
    else if (strcmp(s, "BEEP") == 0) BlueSerial_Beep();
    else if (sscanf(s, "J,%d,%d", &x, &y) == 2) {
        /* A single mixed state prevents forward/turn command conflicts.
           While moving, the inside wheel slows without suddenly reversing. */
        int left, right;
        BlueSerial_MixWheels(x, y, &left, &right);
        drive(left, right);
    }
    else if (sscanf(s, "PWM,%d", &v) == 1) {
        /* Use equal left/right speed as a simple standalone example. */
        v = clamp(v, 0, 100); drive(v, v);
    }
    else if (sscanf(s, "SERVO,%d", &v) == 1) BlueSerial_Servo(clamp(v, 0, 180));
    else if (sscanf(s, "AUX,%d", &v) == 1) BlueSerial_Aux(v);
}

void BlueSerial_Poll(void) {
    static char line[MAX_LINE];
    static unsigned n = 0;
    while (rx_tail != rx_head) {
        uint8_t byte = rx_ring[rx_tail];
        rx_tail = (uint16_t)((rx_tail + 1) % RX_BUFFER_SIZE);
        if (byte == '\n' || byte == '\r') {
            if (n > 0) { line[n] = '\0'; parse_line(line); n = 0; }
        } else if (n < sizeof(line) - 1) line[n++] = (char)byte;
        else n = 0; /* Drop overflowing command instead of overrunning memory. */
    }
}

void BlueSerial_Tick1ms(void) {
    tick_ms++;
    if (motion_active && (uint32_t)(tick_ms - last_motion_ms) > 400u) {
        /* Fail safe: lost connection => stop driving. */
        BlueSerial_Motors(0, 0);
        motion_active = 0;
    }
}
