"""Re-export the on-phone YOLO model.

    py -m venv C:/ye/v                       # SHORT path: PyTorch breaks Windows' 260-char path limit
    C:/ye/v/Scripts/python -m pip install torch torchvision --index-url https://download.pytorch.org/whl/cpu
    C:/ye/v/Scripts/python -m pip install ultralytics onnx onnxslim onnxruntime
    C:/ye/v/Scripts/python tools/export_yolo.py
    copy yolo11n.onnx android/app/src/main/assets/

The model is exported with a DYNAMIC input shape. The app keeps each frame's aspect ratio
(letterbox, long side 416, short side rounded up to a multiple of 32), so a portrait 9:16 frame
runs at 416x256: about the same work as the old 320x320 square, with no stretching and more detail.

ONNX (not TFLite): Ultralytics' TFLite/LiteRT export only runs on Linux/macOS; ONNX runs on Android via
ONNX Runtime. Check the decode with tools/verify_yolo_decode.py afterwards.
"""
from ultralytics import YOLO

print(YOLO("yolo11n.pt").export(format="onnx", imgsz=416, dynamic=True, opset=17, simplify=True))
