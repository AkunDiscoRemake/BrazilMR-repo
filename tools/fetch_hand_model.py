#!/usr/bin/env python3
"""Optional manual equivalent of Gradle's prepareHandModel; official version + SHA-256 only."""
from pathlib import Path
import hashlib
import urllib.request

URL = "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
SHA256 = "fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1"
DEST = Path(__file__).resolve().parents[1] / "app/src/main/assets/models/hand_landmarker.task"

def verify(path):
    return hashlib.sha256(path.read_bytes()).hexdigest() == SHA256

def main():
    DEST.parent.mkdir(parents=True, exist_ok=True)
    if DEST.exists():
        if not verify(DEST):
            raise SystemExit("Modelo existente com checksum inválido. Remova-o antes de baixar novamente.")
        print("Modelo verificado:", DEST)
        return
    partial = DEST.with_suffix(".task.part")
    try:
        with urllib.request.urlopen(URL, timeout=60) as source, partial.open("wb") as output:
            total = 0
            while chunk := source.read(65536):
                total += len(chunk)
                if total > 9_000_000:
                    raise ValueError("Modelo excedeu o tamanho esperado")
                output.write(chunk)
        if not verify(partial):
            raise ValueError("Checksum SHA-256 inválido; nenhum modelo foi instalado")
        partial.replace(DEST)
    finally:
        partial.unlink(missing_ok=True)
    print("Modelo oficial instalado e verificado:", DEST)

if __name__ == "__main__":
    main()
