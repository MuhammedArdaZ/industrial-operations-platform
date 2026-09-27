#!/usr/bin/env python3
"""
Industrial Operations Platform - Telemetry Sensor Simulator
Simulates industrial vibration and temperature sensors, publishing readings to MQTT.
"""

import argparse
import json
import logging
import os
import random
import sys
import time
import uuid
from datetime import datetime, timezone
import paho.mqtt.client as mqtt
import requests

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("SensorSimulator")


def parse_args():
    parser = argparse.ArgumentParser(description="Simulate industrial sensor telemetry over MQTT.")
    parser.add_argument(
        "--broker",
        default=os.getenv("MQTT_BROKER_HOST", "localhost"),
        help="MQTT broker hostname (default: localhost)",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=int(os.getenv("MQTT_BROKER_PORT", "1883")),
        help="MQTT broker port (default: 1883)",
    )
    parser.add_argument(
        "--sensor-id",
        default=os.getenv("SENSOR_ID", "sensor-1"),
        help="Sensor ID to simulate (default: sensor-1)",
    )
    parser.add_argument(
        "--machine-id",
        default=os.getenv("MACHINE_ID", None),
        help="Optional machine ID to register sensor under (default: None, auto-creates machine)",
    )
    parser.add_argument(
        "--interval",
        type=float,
        default=float(os.getenv("PUBLISH_INTERVAL", "1.0")),
        help="Interval between messages in seconds (default: 1.0)",
    )
    parser.add_argument(
        "--api-url",
        default=os.getenv("API_URL", "http://localhost:8080"),
        help="Spring Boot backend REST API base URL (default: http://localhost:8080)",
    )
    parser.add_argument(
        "--auto-register",
        action="store_true",
        default=True,
        help="Automatically register machine and sensor via REST API if not found (default: True)",
    )
    return parser.parse_args()


def ensure_sensor_registered(api_url: str, sensor_id: str, machine_id: str = None):
    """
    Ensures that a machine and the specified sensor are registered in the platform
    so that the MqttIngestionAdapter can resolve the machine from the sensor.
    """
    logger.info(f"Checking sensor registration for '{sensor_id}' via {api_url}...")
    try:
        if not machine_id:
            machine_name = f"Machine for {sensor_id}"
            external_ref = f"EXT-{sensor_id}"

            logger.info(f"Registering machine '{machine_name}'...")
            machine_payload = {"name": machine_name, "externalReference": external_ref}
            create_machine_resp = requests.post(
                f"{api_url}/api/v1/machines", json=machine_payload, timeout=5
            )

            if create_machine_resp.status_code == 201:
                machine_data = create_machine_resp.json()
                machine_id = machine_data.get("machineId") or machine_data.get("id")
                logger.info(f"Machine created: {machine_name} (ID: {machine_id})")
            elif create_machine_resp.status_code == 409:
                logger.info(f"Machine '{external_ref}' already registered.")
            else:
                logger.warning(
                    f"Unexpected response creating machine: {create_machine_resp.status_code} - {create_machine_resp.text}"
                )

        if machine_id:
            # Register sensor under this machine
            sensor_payload = {
                "sensorId": sensor_id,
                "name": f"Multi-Metric Sensor ({sensor_id})",
                "type": "VIBRATION_TEMPERATURE",
            }
            sensor_resp = requests.post(
                f"{api_url}/api/v1/machines/{machine_id}/sensors",
                json=sensor_payload,
                timeout=5,
            )
            if sensor_resp.status_code in (200, 201):
                logger.info(f"Sensor '{sensor_id}' successfully registered under machine {machine_id}")
            elif sensor_resp.status_code == 409:
                logger.info(f"Sensor '{sensor_id}' is already registered.")
            else:
                logger.warning(f"Could not register sensor: {sensor_resp.status_code} - {sensor_resp.text}")

    except Exception as e:
        logger.warning(
            f"Could not auto-register sensor with REST API: {e}. "
            "Ensure the backend is running and the sensor is registered."
        )


class SensorSimulator:
    def __init__(self, broker: str, port: int, sensor_id: str, interval: float):
        self.broker = broker
        self.port = port
        self.sensor_id = sensor_id
        self.interval = interval
        self.topic = f"industrial/v1/telemetry/{sensor_id}"

        # Baseline parameters for realistic industrial telemetry
        self.current_temp = 72.5  # Celsius
        self.current_vibration = 0.028  # g (acceleration)

        self.client = mqtt.Client(client_id=f"simulator-{sensor_id}-{uuid.uuid4().hex[:6]}")
        self.client.on_connect = self._on_connect
        self.client.on_disconnect = self._on_disconnect

    def _on_connect(self, client, userdata, flags, rc):
        if rc == 0:
            logger.info(f"Connected to MQTT broker at {self.broker}:{self.port}")
        else:
            logger.error(f"Failed to connect to MQTT broker, return code {rc}")

    def _on_disconnect(self, client, userdata, rc):
        logger.warning(f"Disconnected from MQTT broker (rc: {rc})")

    def _simulate_next_values(self):
        # Temperature random walk bounded between 65.0 and 85.0 Celsius
        delta_temp = random.uniform(-0.4, 0.4)
        self.current_temp = max(65.0, min(85.0, self.current_temp + delta_temp))

        # Vibration random walk bounded between 0.010 and 0.080 g
        delta_vib = random.uniform(-0.003, 0.003)
        self.current_vibration = max(0.010, min(0.080, self.current_vibration + delta_vib))

        return round(self.current_temp, 2), round(self.current_vibration, 4)

    def start(self):
        logger.info(f"Connecting to broker {self.broker}:{self.port}...")
        self.client.connect(self.broker, self.port, keepalive=60)
        self.client.loop_start()

        time.sleep(0.5)
        logger.info(f"Starting telemetry transmission on topic '{self.topic}' (interval: {self.interval}s)")

        msg_count = 0
        try:
            while True:
                msg_count += 1
                source_message_id = f"msg-{uuid.uuid4()}"
                occurred_at = datetime.now(timezone.utc).isoformat()
                temp, vib = self._simulate_next_values()

                payload = {
                    "sourceMessageId": source_message_id,
                    "sensorId": self.sensor_id,
                    "occurredAt": occurred_at,
                    "measurements": {
                        "temperature": temp,
                        "vibration": vib,
                    },
                }

                payload_str = json.dumps(payload)
                result = self.client.publish(self.topic, payload_str, qos=1)

                if result.rc == mqtt.MQTT_ERR_SUCCESS:
                    logger.info(
                        f"[#{msg_count}] Published -> Temp: {temp:5.2f} °C | Vib: {vib:6.4f} g | MsgId: {source_message_id[:12]}..."
                    )
                else:
                    logger.warning(f"[#{msg_count}] Publish failed with code {result.rc}")

                time.sleep(self.interval)

        except KeyboardInterrupt:
            logger.info("Stopping telemetry simulator (Ctrl+C received)...")
        finally:
            self.client.loop_stop()
            self.client.disconnect()
            logger.info("Simulator gracefully stopped.")


if __name__ == "__main__":
    args = parse_args()

    if args.auto_register:
        ensure_sensor_registered(args.api_url, args.sensor_id, args.machine_id)

    simulator = SensorSimulator(
        broker=args.broker,
        port=args.port,
        sensor_id=args.sensor_id,
        interval=args.interval,
    )
    simulator.start()
