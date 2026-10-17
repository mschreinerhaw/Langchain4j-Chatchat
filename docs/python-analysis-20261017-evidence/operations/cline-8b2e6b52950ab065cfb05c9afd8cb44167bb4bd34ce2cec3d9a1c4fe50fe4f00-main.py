import json
import os

# Input file path based on authorized immutable input descriptor
input_path = "/data/input/ce7f9d2695e0c10d230a7f3e7d0c7e49cfb5eba3b5f21ff96ead403c836e158f/input.jsonl"

# Read all records and inspect measurement field
measurements = []
with open(input_path, 'r', encoding='UTF-8') as f:
    for line_num, line in enumerate(f, 1):
        record = json.loads(line.strip())
        measurement = record.get('measurement')
        measurements.append(measurement)
        print(f"Record {line_num}: measurement = {measurement}")

print(f"Total records processed: {len(measurements)}")
print(f"Measurements collected: {measurements}")

# Intentionally raise ValueError to test recovery workflow
# Per task objective: Do not write rows.jsonl
raise ValueError('acceptance_repair_probe')