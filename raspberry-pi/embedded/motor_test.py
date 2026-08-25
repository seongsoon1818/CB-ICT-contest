from time import sleep
from gpiozero import Motor, OutputDevice

motor_a = Motor(forward=17, backward=27, pwm=False)
motor_b = Motor(forward=23, backward=24, pwm=False)
standby_pin = OutputDevice(22, initial_value=False)

try:
    standby_pin.on()
    sleep(0.01)
    motor_a.forward()       
    motor_b.forward()
    sleep(1)
finally:
    motor_a.stop()
    motor_b.stop()
    standby_pin.off()
    motor_a.close()
    motor_b.close()
    standby_pin.close()