| # | Probe | Status | Detail | Elapsed | errno |
|---|-------|--------|--------|---------|-------|
| 1 | DNS example.com | gaierror | [Errno -3] Temporary failure in name resolution | 81.6ms | -3 |
| 2 | TCP 1.1.1.1:443 | OSError | [Errno 101] Network is unreachable | 0.2ms | 101 |
| 3 | TCP 192.168.195.221:5221 | OSError | [Errno 101] Network is unreachable | 0.1ms | 101 |
| 4 | HTTPS example.com (urllib) | URLError | <urlopen error [Errno -3] Temporary failure in name resolution> | 44.6ms | None |