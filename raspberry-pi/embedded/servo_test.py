#!/usr/bin/env python3
"""MG90S camera-pan servo interactive test for BirdGuard.

Wiring:
  - MG90S orange signal wire -> Raspberry Pi GPIO19 (physical pin 35)
  - MG90S red wire           -> external 4.8-6V battery supply
  - MG90S brown wire         -> battery negative and Raspberry Pi GND

Run:
  python3 servo_test.py
"""

from __future__ import annotations

from gpiozero import AngularServo


SERVO_PIN = 19  # BCM GPIO number, physical pin 35
STEP_DEGREES = 5
MIN_ANGLE = -60
MAX_ANGLE = 60


def clamp(value: int, lower: int, upper: int) -> int:
    return max(lower, min(value, upper))


def main() -> None:
    servo = AngularServo(
        SERVO_PIN,
        initial_angle=0,
        min_angle=-90,
        max_angle=90,
        min_pulse_width=1 / 1000,
        max_pulse_width=2 / 1000,
        frame_width=20 / 1000,
    )
    current_angle = 0

    print("MG90S servo test started.")
    print("a: left 5 degrees | d: right 5 degrees | c: center | p: position | q: quit")
    print(f"Safe range: {MIN_ANGLE} to {MAX_ANGLE} degrees")

    try:
        while True:
            command = input("> ").strip().lower()

            if command == "a":
                current_angle = clamp(
                    current_angle - STEP_DEGREES,
                    MIN_ANGLE,
                    MAX_ANGLE,
                )
                servo.angle = current_angle
                print(f"Moved left. Current angle: {current_angle} degrees")
            elif command == "d":
                current_angle = clamp(
                    current_angle + STEP_DEGREES,
                    MIN_ANGLE,
                    MAX_ANGLE,
                )
                servo.angle = current_angle
                print(f"Moved right. Current angle: {current_angle} degrees")
            elif command == "c":
                current_angle = 0
                servo.angle = current_angle
                print("Moved to center: 0 degrees")
            elif command == "p":
                print(f"Current angle: {current_angle} degrees")
            elif command in {"q", "quit", "exit"}:
                break
            elif command:
                print("Unknown command. Use a, d, c, p, or q.")
    except KeyboardInterrupt:
        print("\nStopped by keyboard interrupt.")
    finally:
        # Stop PWM output before releasing the GPIO pin.
        servo.close()
        print("Servo test ended.")


if __name__ == "__main__":
    main()
