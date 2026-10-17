import json
from decimal import Decimal, ROUND_HALF_UP
import os

# Read the authorized input dataset
input_path = "/data/input/c7faa803f3e5fbaff8644df60840b2d77632a4eec5c4e7faa96a2bcfa6c30c4c/input.jsonl"

measurements = []

with open(input_path, 'r', encoding='UTF-8') as f:
    for line in f:
        record = json.loads(line.strip())
        # Extract measurement value and convert to Decimal for precision
        measurement_str = record.get("measurement", "")
        measurement = Decimal(measurement_str)
        measurements.append(measurement)

record_count = len(measurements)

# Sort measurements for median calculation
sorted_measurements = sorted(measurements)

# Calculate median
n = len(sorted_measurements)
if n % 2 == 1:
    # Odd count: middle value
    median = sorted_measurements[n // 2]
else:
    # Even count: average of two middle values
    mid1 = sorted_measurements[n // 2 - 1]
    mid2 = sorted_measurements[n // 2]
    median = (mid1 + mid2) / 2

# Format median as decimal string
median_str = str(median)

# Create output directory if it doesn't exist
os.makedirs("/workspace/output/results", exist_ok=True)

# Write rows.jsonl with single object
output_row = {
    "statistic": median_str,
    "recordCount": record_count
}

with open("/workspace/output/results/rows.jsonl", 'w', encoding='UTF-8') as f:
    f.write(json.dumps(output_row) + '\n')

print(f"Processed {record_count} records")
print(f"Median: {median_str}")
print(f"Output written to /workspace/output/results/rows.jsonl")