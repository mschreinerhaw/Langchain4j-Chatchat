# Parquet Data Analysis Report

**Analysis Time:** 2026-11-10 05:57:53  
**File Path:** /workspace/test.parquet


## 1. Basic Information

- **Total Rows:** 238,073
- **Total Columns:** 19
- **Memory Usage:** 48.86 MB

### Columns

1. `VendorID`
2. `tpep_pickup_datetime`
3. `tpep_dropoff_datetime`
4. `passenger_count`
5. `trip_distance`
6. `RatecodeID`
7. `store_and_fwd_flag`
8. `PULocationID`
9. `DOLocationID`
10. `payment_type`
11. `fare_amount`
12. `extra`
13. `mta_tax`
14. `tip_amount`
15. `tolls_amount`
16. `improvement_surcharge`
17. `total_amount`
18. `congestion_surcharge`
19. `airport_fee`


## 2. Field Types Analysis

| Column | Data Type | Unique Values | Sample Values |
|--------|-----------|---------------|---------------|
| VendorID | int64 | 3 | 1, 1, 1 |
| tpep_pickup_datetime | datetime64[us] | 218,123 | 2020-04-01 00:41:22, 2020-04-01 00:56:00, 2020-04-01 00:00:26 |
| tpep_dropoff_datetime | datetime64[us] | 218,251 | 2020-04-01 01:01:53, 2020-04-01 01:09:25, 2020-04-01 00:09:25 |
| passenger_count | float64 | 8 | 1.0, 1.0, 1.0 |
| trip_distance | float64 | 2,954 | 1.2, 3.4, 2.8 |
| RatecodeID | float64 | 7 | 1.0, 1.0, 1.0 |
| store_and_fwd_flag | object | 2 | N, N, N |
| PULocationID | int64 | 250 | 41, 95, 237 |
| DOLocationID | int64 | 259 | 24, 197, 137 |
| payment_type | int64 | 5 | 2, 1, 1 |
| fare_amount | float64 | 5,120 | 5.5, 12.5, 10.0 |
| extra | float64 | 74 | 0.5, 0.5, 3.0 |
| mta_tax | float64 | 4 | 0.5, 0.5, 0.5 |
| tip_amount | float64 | 1,445 | 0.0, 2.75, 1.0 |
| tolls_amount | float64 | 103 | 0.0, 0.0, 0.0 |
| improvement_surcharge | float64 | 3 | 0.3, 0.3, 0.3 |
| total_amount | float64 | 6,680 | 6.8, 16.55, 14.8 |
| congestion_surcharge | float64 | 3 | 0.0, 0.0, 2.5 |
| airport_fee | object | 0 |  |


## 3. Missing Values Analysis

- **Columns with Missing Values:** 5
- **Total Missing Cells:** 316,393
- **Overall Missing Percentage:** 6.9946%

### Missing Values by Column

| Column | Missing Count | Missing Percentage |
|--------|---------------|-------------------|
| passenger_count | 19,580 | 8.2244% |
| RatecodeID | 19,580 | 8.2244% |
| store_and_fwd_flag | 19,580 | 8.2244% |
| congestion_surcharge | 19,580 | 8.2244% |
| airport_fee | 238,073 | 100.0000% |


## 4. Duplicates Analysis

- **Duplicate Rows:** 0
- **Duplicate Percentage:** 0.0000%
- **Key Column Duplicates:** 15
- **Key Columns Checked:** VendorID, tpep_pickup_datetime, passenger_count, trip_distance, fare_amount


## 5. Numerical Columns Statistics


### VendorID

| Statistic | Value |
|-----------|-------|
| Count | 238,073 |
| Mean | 1.5922 |
| Std Dev | 0.4971 |
| Min | 1.0000 |
| 25th Percentile | 1.0000 |
| Median (50%) | 2.0000 |
| 75th Percentile | 2.0000 |
| Max | 6.0000 |
| Zeros | 0 |
| Negatives | 0 |
| Skewness | -0.1692 |
| Kurtosis | -0.1693 |
| Outliers (IQR) | 67 (0.03%) |


### passenger_count

| Statistic | Value |
|-----------|-------|
| Count | 218,493 |
| Mean | 1.2968 |
| Std Dev | 0.9836 |
| Min | 0.0000 |
| 25th Percentile | 1.0000 |
| Median (50%) | 1.0000 |
| 75th Percentile | 1.0000 |
| Max | 7.0000 |
| Zeros | 6,582 |
| Negatives | 0 |
| Skewness | 3.2924 |
| Kurtosis | 11.3564 |
| Outliers (IQR) | 40,688 (18.62%) |


### trip_distance

| Statistic | Value |
|-----------|-------|
| Count | 238,073 |
| Mean | 4.0407 |
| Std Dev | 294.8295 |
| Min | 0.0000 |
| 25th Percentile | 0.9500 |
| Median (50%) | 1.7400 |
| 75th Percentile | 3.4000 |
| Max | 126501.7700 |
| Zeros | 6,225 |
| Negatives | 0 |
| Skewness | 362.1462 |
| Kurtosis | 146418.3215 |
| Outliers (IQR) | 23,124 (9.71%) |


### RatecodeID

| Statistic | Value |
|-----------|-------|
| Count | 218,493 |
| Mean | 1.0341 |
| Std Dev | 0.8650 |
| Min | 1.0000 |
| 25th Percentile | 1.0000 |
| Median (50%) | 1.0000 |
| 75th Percentile | 1.0000 |
| Max | 99.0000 |
| Zeros | 0 |
| Negatives | 0 |
| Skewness | 100.2271 |
| Kurtosis | 11293.6872 |
| Outliers (IQR) | 2,400 (1.10%) |


### PULocationID

| Statistic | Value |
|-----------|-------|
| Count | 238,073 |
| Mean | 154.9400 |
| Std Dev | 70.7632 |
| Min | 1.0000 |
| 25th Percentile | 97.0000 |
| Median (50%) | 143.0000 |
| 75th Percentile | 234.0000 |
| Max | 265.0000 |
| Zeros | 0 |
| Negatives | 0 |
| Skewness | -0.0747 |
| Kurtosis | -1.1367 |
| Outliers (IQR) | 0 (0.00%) |


### DOLocationID

| Statistic | Value |
|-----------|-------|
| Count | 238,073 |
| Mean | 150.3557 |
| Std Dev | 74.4759 |
| Min | 1.0000 |
| 25th Percentile | 75.0000 |
| Median (50%) | 143.0000 |
| 75th Percentile | 233.0000 |
| Max | 265.0000 |
| Zeros | 0 |
| Negatives | 0 |
| Skewness | -0.0774 |
| Kurtosis | -1.2335 |
| Outliers (IQR) | 0 (0.00%) |


### payment_type

| Statistic | Value |
|-----------|-------|
| Count | 238,073 |
| Mean | 1.3085 |
| Std Dev | 0.6612 |
| Min | 0.0000 |
| 25th Percentile | 1.0000 |
| Median (50%) | 1.0000 |
| 75th Percentile | 2.0000 |
| Max | 4.0000 |
| Zeros | 19,580 |
| Negatives | 0 |
| Skewness | 0.3031 |
| Kurtosis | 0.8270 |
| Outliers (IQR) | 1,386 (0.58%) |


### fare_amount

| Statistic | Value |
|-----------|-------|
| Count | 238,073 |
| Mean | 11.6691 |
| Std Dev | 11.7319 |
| Min | -118.0000 |
| 25th Percentile | 5.5000 |
| Median (50%) | 8.0000 |
| 75th Percentile | 13.0000 |
| Max | 903.0200 |
| Zeros | 167 |
| Negatives | 1,295 |
| Skewness | 8.3538 |
| Kurtosis | 292.8045 |
| Outliers (IQR) | 22,775 (9.57%) |


### extra

| Statistic | Value |
|-----------|-------|
| Count | 238,073 |
| Mean | 1.0672 |
| Std Dev | 1.2606 |
| Min | -4.5000 |
| 25th Percentile | 0.0000 |
| Median (50%) | 0.5000 |
| 75th Percentile | 2.5000 |
| Max | 9.0600 |
| Zeros | 112,234 |
| Negatives | 512 |
| Skewness | 0.6882 |
| Kurtosis | -1.0521 |
| Outliers (IQR) | 60 (0.03%) |


### mta_tax

| Statistic | Value |
|-----------|-------|
| Count | 238,073 |
| Mean | 0.4870 |
| Std Dev | 0.0950 |
| Min | -0.5000 |
| 25th Percentile | 0.5000 |
| Median (50%) | 0.5000 |
| 75th Percentile | 0.5000 |
| Max | 1.1000 |
| Zeros | 3,654 |
| Negatives | 1,275 |
| Skewness | -8.0573 |
| Kurtosis | 69.9386 |
| Outliers (IQR) | 4,934 (2.07%) |


### tip_amount

| Statistic | Value |
|-----------|-------|
| Count | 238,073 |
| Mean | 1.5299 |
| Std Dev | 2.2955 |
| Min | -5.0000 |
| 25th Percentile | 0.0000 |
| Median (50%) | 1.0000 |
| 75th Percentile | 2.4600 |
| Max | 117.2800 |
| Zeros | 107,512 |
| Negatives | 13 |
| Skewness | 9.1465 |
| Kurtosis | 256.4003 |
| Outliers (IQR) | 5,993 (2.52%) |


### tolls_amount

| Statistic | Value |
|-----------|-------|
| Count | 238,073 |
| Mean | 0.2209 |
| Std Dev | 1.3438 |
| Min | -19.8700 |
| 25th Percentile | 0.0000 |
| Median (50%) | 0.0000 |
| 75th Percentile | 0.0000 |
| Max | 98.7500 |
| Zeros | 230,341 |
| Negatives | 23 |
| Skewness | 9.6910 |
| Kurtosis | 235.0764 |
| Outliers (IQR) | 7,732 (3.25%) |


### improvement_surcharge

| Statistic | Value |
|-----------|-------|
| Count | 238,073 |
| Mean | 0.2963 |
| Std Dev | 0.0455 |
| Min | -0.3000 |
| 25th Percentile | 0.3000 |
| Median (50%) | 0.3000 |
| 75th Percentile | 0.3000 |
| Max | 0.3000 |
| Zeros | 339 |
| Negatives | 1,291 |
| Skewness | -12.6319 |
| Kurtosis | 160.0985 |
| Outliers (IQR) | 1,630 (0.68%) |


### total_amount

| Statistic | Value |
|-----------|-------|
| Count | 238,073 |
| Mean | 16.4120 |
| Std Dev | 13.1604 |
| Min | -138.1700 |
| 25th Percentile | 9.8000 |
| Median (50%) | 12.8000 |
| 75th Percentile | 18.3600 |
| Max | 903.3200 |
| Zeros | 140 |
| Negatives | 1,294 |
| Skewness | 7.4965 |
| Kurtosis | 226.1593 |
| Outliers (IQR) | 22,037 (9.26%) |


### congestion_surcharge

| Statistic | Value |
|-----------|-------|
| Count | 218,493 |
| Mean | 2.0232 |
| Std Dev | 1.0081 |
| Min | -2.5000 |
| 25th Percentile | 2.5000 |
| Median (50%) | 2.5000 |
| 75th Percentile | 2.5000 |
| Max | 2.5000 |
| Zeros | 39,861 |
| Negatives | 903 |
| Skewness | -1.7622 |
| Kurtosis | 1.6760 |
| Outliers (IQR) | 40,764 (18.66%) |


## 6. Categorical & Timestamp Columns


### store_and_fwd_flag

**Unique Values:** 2

**Most Common Values:**

- `N`: 216,629
- `Y`: 1,864


### tpep_pickup_datetime

- **Min Date:** 2009-01-01 01:11:17
- **Max Date:** 2020-04-30 23:59:42
- **Date Range:** 4137 days


### tpep_dropoff_datetime

- **Min Date:** 2009-01-01 01:11:20
- **Max Date:** 2020-05-01 22:18:13
- **Date Range:** 4138 days


## 7. Potential Anomalies

**Total Anomalies Detected:** 18

### Anomaly 1: high_outlier_count

- **Column:** passenger_count
- **Count:** 40,688
- **Percentage:** 18.6221%

### Anomaly 2: high_outlier_count

- **Column:** trip_distance
- **Count:** 23,124
- **Percentage:** 9.7130%

### Anomaly 3: negative_values

- **Column:** fare_amount
- **Count:** 1,295
- **Percentage:** 0.5440%

### Anomaly 4: high_outlier_count

- **Column:** fare_amount
- **Count:** 22,775
- **Percentage:** 9.5664%

### Anomaly 5: negative_values

- **Column:** extra
- **Count:** 512
- **Percentage:** 0.2151%

### Anomaly 6: negative_values

- **Column:** mta_tax
- **Count:** 1,275
- **Percentage:** 0.5356%

### Anomaly 7: negative_values

- **Column:** tip_amount
- **Count:** 13
- **Percentage:** 0.0055%

### Anomaly 8: negative_values

- **Column:** tolls_amount
- **Count:** 23
- **Percentage:** 0.0097%

### Anomaly 9: negative_values

- **Column:** improvement_surcharge
- **Count:** 1,291
- **Percentage:** 0.5423%

### Anomaly 10: negative_values

- **Column:** total_amount
- **Count:** 1,294
- **Percentage:** 0.5435%

### Anomaly 11: high_outlier_count

- **Column:** total_amount
- **Count:** 22,037
- **Percentage:** 9.2564%

### Anomaly 12: negative_values

- **Column:** congestion_surcharge
- **Count:** 903
- **Percentage:** 0.4133%

### Anomaly 13: high_outlier_count

- **Column:** congestion_surcharge
- **Count:** 40,764
- **Percentage:** 18.6569%

### Anomaly 14: negative_trip_duration

- **Column:** N/A
- **Count:** 29
- **Description:** Trips where dropoff is before pickup

### Anomaly 15: very_short_trips

- **Column:** N/A
- **Count:** 4,256
- **Description:** Trips less than 1 minute

### Anomaly 16: very_long_trips

- **Column:** N/A
- **Count:** 3
- **Description:** Trips more than 24 hours

### Anomaly 17: zero_passengers

- **Column:** N/A
- **Count:** 6,582
- **Description:** Trips with zero passengers

### Anomaly 18: unusually_high_passengers

- **Column:** N/A
- **Count:** 1
- **Description:** Trips with more than 6 passengers


## 8. Summary & Recommendations

### Key Findings

- Data has 6.9946% missing values across 5 column(s)
- Detected 18 potential anomalies requiring investigation
- 9 column(s) show high skewness: passenger_count, trip_distance, RatecodeID, fare_amount, mta_tax, tip_amount, tolls_amount, improvement_surcharge, total_amount

### Recommendations

- Consider imputation or removal of missing values based on business requirements
- Investigate detected anomalies to determine if they are data errors or valid edge cases
- Consider data validation rules to prevent future anomalies
- Apply appropriate transformations (log, sqrt) for highly skewed numerical features
- Consider robust statistical methods that are less sensitive to outliers