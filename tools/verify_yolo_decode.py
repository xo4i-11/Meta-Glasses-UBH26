"""Mirror of the Kotlin ObstacleDetector (letterbox + dynamic shape + decode + NMS), checked against
Ultralytics' own predictor. Also times old (320 stretch) vs new (aspect-kept, long side 416).

    C:/ye/v/Scripts/python tools/verify_yolo_decode.py yolo11n.onnx [old_320.onnx]
"""
import math
import sys
import time

import numpy as np
import onnxruntime as ort
from PIL import Image
from ultralytics import YOLO
from ultralytics.utils import ASSETS

LONG = 416
MIN_SCORE = 0.3


def input_shape(w, h, long=LONG):
    """Same rule as Kotlin: long side = 416, short side scaled and rounded UP to a multiple of 32."""
    if w >= h:
        return long, max(32, math.ceil(h * long / w / 32) * 32)
    return max(32, math.ceil(w * long / h / 32) * 32), long


def letterbox(img, W, H):
    """Scale to fit, keep aspect, centre on grey 114 (what YOLO was trained on)."""
    s = min(W / img.width, H / img.height)
    nw, nh = round(img.width * s), round(img.height * s)
    canvas = Image.new("RGB", (W, H), (114, 114, 114))
    dx, dy = (W - nw) // 2, (H - nh) // 2
    canvas.paste(img.resize((nw, nh), Image.BILINEAR), (dx, dy))
    return canvas, s, dx, dy


def iou(a, b):
    ix = max(0, min(a[2], b[2]) - max(a[0], b[0]))
    iy = max(0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = ix * iy
    u = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / u if u > 0 else 0


def detect(sess, img):
    W, H = input_shape(img.width, img.height)
    lb, s, dx, dy = letterbox(img, W, H)
    x = (np.asarray(lb, dtype=np.float32) / 255.0).transpose(2, 0, 1)[None]
    t = time.perf_counter()
    y = sess.run(None, {sess.get_inputs()[0].name: x})[0][0]  # (84, N)
    ms = (time.perf_counter() - t) * 1000
    dets = []
    for i in range(y.shape[1]):
        sc = y[4:, i]
        c = int(sc.argmax())
        if sc[c] < MIN_SCORE:
            continue
        cx, cy, w, h = y[:4, i]
        # undo letterbox -> normalised 0..1 of the ORIGINAL frame
        x1 = ((cx - w / 2) - dx) / s / img.width
        y1 = ((cy - h / 2) - dy) / s / img.height
        x2 = ((cx + w / 2) - dx) / s / img.width
        y2 = ((cy + h / 2) - dy) / s / img.height
        dets.append((c, float(sc[c]), [x1, y1, x2, y2]))
    keep = []
    for c in {d[0] for d in dets}:
        g = sorted([d for d in dets if d[0] == c], key=lambda d: -d[1])
        while g:
            top = g.pop(0)
            keep.append(top)
            g = [d for d in g if iou(top[2], d[2]) <= 0.5]
    return keep, ms, (W, H)


def old_detect(sess, img):
    x = (np.asarray(img.resize((320, 320), Image.BILINEAR), dtype=np.float32) / 255.0).transpose(2, 0, 1)[None]
    t = time.perf_counter()
    y = sess.run(None, {sess.get_inputs()[0].name: x})[0][0]
    ms = (time.perf_counter() - t) * 1000
    n = sum(1 for i in range(y.shape[1]) if y[4:, i].max() >= 0.4)
    return n, ms


model = sys.argv[1] if len(sys.argv) > 1 else "yolo11n.onnx"
sess = ort.InferenceSession(model)
print("input", sess.get_inputs()[0].shape, "output", sess.get_outputs()[0].shape)
pt = YOLO("yolo11n.pt")
names = pt.names
ok = True
for name in ("bus.jpg", "zidane.jpg"):
    path = str(ASSETS / name)
    img = Image.open(path).convert("RGB")
    for _ in range(3):
        detect(sess, img)  # warm-up
    mine, ms, shape = detect(sess, img)
    ref = pt(path, imgsz=LONG, conf=MIN_SCORE, verbose=False)[0]
    a = sorted((names[c], round(s, 2)) for c, s, _ in mine)
    b = sorted((names[int(c)], round(float(s), 2)) for c, s in zip(ref.boxes.cls, ref.boxes.conf))
    # boxes: match each of ours to the same-label reference box
    rb = [(names[int(c)], (xy / np.array([img.width, img.height] * 2)).tolist()) for c, xy in zip(ref.boxes.cls, ref.boxes.xyxy.numpy())]
    worst = min((max((iou(box, r) for l, r in rb if l == names[c]), default=0) for c, _, box in mine), default=1)
    same = [l for l, s in a if s >= 0.5] == [l for l, s in b if s >= 0.5]  # borderline (<0.5) boxes may differ: resize interpolation
    ok &= same and worst > 0.9
    print(f"\n{name} {img.width}x{img.height} -> input {shape[0]}x{shape[1]}  {ms:.1f} ms")
    print("  app decode :", a)
    print("  ultralytics:", b)
    print(f"  same labels: {same}   worst box IoU vs ultralytics: {worst:.3f}")
    if len(sys.argv) > 2:
        old = ort.InferenceSession(sys.argv[2])
        for _ in range(3):
            old_detect(old, img)
        n, oms = old_detect(old, img)
        print(f"  OLD 320 stretch: {n} raw boxes >=0.4, {oms:.1f} ms")
print("\nPASS" if ok else "\nMISMATCH")
