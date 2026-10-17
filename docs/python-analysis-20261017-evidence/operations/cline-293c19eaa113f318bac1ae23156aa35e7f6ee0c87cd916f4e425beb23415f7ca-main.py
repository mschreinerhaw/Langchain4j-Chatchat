import json
from decimal import Decimal, ROUND_HALF_UP
import os

# Read input data
input_path = "/data/input/ca22e202e11cf2c194127072b516c99565bfaf1a0d7cbccc5e5dc2b0d8414294/input.jsonl"

# Read all records
records = []
with open(input_path, 'r', encoding='UTF-8') as f:
    for line in f:
        line = line.strip()
        if line:
            records.append(json.loads(line))

# Inspect data structure
print(f"Number of records: {len(records)}")
print(f"Keys in first record: {list(records[0].keys()) if records else 'N/A'}")

# Extract measurement values
measurements = []
for i, record in enumerate(records):
    measurement_str = record.get('measurement')
    print(f"Record {i}: measurement = '{measurement_str}' (type: {type(measurement_str).__name__})")
    if measurement_str is not None:
        # Convert to Decimal for exact calculation
        measurements.append(Decimal(measurement_str))

print(f"\nExtracted measurements: {measurements}")
print(f"Number of measurements: {len(measurements)}")

# Sort measurements
sorted_measurements = sorted(measurements)
print(f"Sorted measurements: {sorted_measurements}")

# Calculate median for 4 values (average of 2nd and 3rd values, 0-indexed: positions 1 and 2)
n = len(sorted_measurements)
if n == 4:
    # Median is average of 2nd and 3rd values (indices 1 and 2)
    median = (sorted_measurements[1] + sorted_measurements[2]) / Decimal('2')
    print(f"\nMedian calculation: ({sorted_measurements[1]} + {sorted_measurements[2]}) / 2 = {median}")
elif n % 2 == 0:
    # General even case
    mid = n // 2
    median = (sorted_measurements[mid - 1] + sorted_measurements[mid]) / Decimal('2')
else:
    # Odd case
    median = sorted_measurements[n // 2]

# Format as decimal string with 2 decimal places
median_str = str(median.quantize(Decimal('0.01'), rounding=ROUND_HALF_UP))
print(f"Median as decimal string: '{median_str}'")

# Ensure output directory exists
os.makedirs('/workspace/output/results', exist_ok=True)

# Write result to rows.jsonl
output_path = '/workspace/output/results/rows.jsonl'
with open(output_path, 'w', encoding='UTF-8') as f:
    result_row = {"statistic": median_str}
    f.write(json.dumps(result_row) + '\n')

print(f"\nWritten to {output_path}")
print(f"Output row: {result_row}")