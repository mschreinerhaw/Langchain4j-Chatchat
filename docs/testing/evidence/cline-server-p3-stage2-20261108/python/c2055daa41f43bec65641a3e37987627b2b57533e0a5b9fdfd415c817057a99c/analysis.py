import time
from pathlib import Path
print("sandbox-started", flush=True)
time.sleep(90)
Path("/workspace/outputs/should-not-exist.txt").write_text("late")
