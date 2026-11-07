"""Export YOLO11n (COCO-80) for the Android app.

    python -m venv venv && venv/Scripts/pip install ultralytics onnxruntime
    venv/Scripts/python tools/export_yolo.py

Writes android/app/src/main/assets/yolo11n_320.onnx. Input [1,3,320,320] RGB 0..1, output [1,84,2100]
(rows 0-3 = cx,cy,w,h in input pixels, rows 4-83 = class scores). ONNX rather than TFLite because
Ultralytics only exports TFLite on Linux/macOS.
"""
import pathlib
import shutil

from ultralytics import YOLO

out = YOLO("yolo11n.pt").export(format="onnx", imgsz=320, simplify=True)
dst = pathlib.Path(__file__).resolve().parents[1] / "android/app/src/main/assets/yolo11n_320.onnx"
shutil.copy(out, dst)
print("wrote", dst)
