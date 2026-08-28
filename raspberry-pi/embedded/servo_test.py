#!/usr/bin/env python3
"""Test a continuous-rotation servo on BCM GPIO19 at low speed."""

from time import sleep

from gpiozero import Servo


SERVO_PIN = 19
SERVO_LEFT_VALUE = -0.1
SERVO_RIGHT_VALUE = 0.1
SERVO_RUN_SECONDS = 0.1
BETWEEN_TESTS_SECONDS = 1.0


def run_direction(servo: Servo, name: str, value: float) -> None:
    print(
        f"{name} start: value={value:+.1f}, "
        f"duration={SERVO_RUN_SECONDS:.1f}s"
    )
    servo.value = value
    sleep(SERVO_RUN_SECONDS)
    servo.detach()
    print(f"{name} stop: PWM detached")


def main() -> None:
    servo = Servo(SERVO_PIN, initial_value=None)
    try:
        run_direction(servo, "LEFT", SERVO_LEFT_VALUE)
        sleep(BETWEEN_TESTS_SECONDS)
        run_direction(servo, "RIGHT", SERVO_RIGHT_VALUE)
    finally:
        servo.detach()
        servo.close()
        print("Servo test finished: GPIO released")


if __name__ == "__main__":
    main()
