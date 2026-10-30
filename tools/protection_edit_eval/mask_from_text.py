#!/usr/bin/env python3
"""Generate an experimental polygon mask from a written clue and a photo."""

from __future__ import annotations

import argparse
import base64
import io
import json
import math
import os
import ssl
import sys
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Sequence

from PIL import Image, ImageDraw, ImageFilter

from evaluate import (
    MAX_MASK_BYTES,
    create_preview,
    load_local_api_key,
    prepare_image,
    validate_paths,
)


def polygon_points(value: object) -> list[tuple[float, float]]:
    if not isinstance(value, list) or not 3 <= len(value) <= 80:
        raise ValueError("마스크 모델이 유효한 윤곽 점을 반환하지 않았습니다.")
    points = []
    for item in value:
        if isinstance(item, dict):
            x, y = item.get("x"), item.get("y")
        elif isinstance(item, list) and len(item) == 2:
            x, y = item
        else:
            raise ValueError("마스크 윤곽 점 형식이 올바르지 않습니다.")
        if not isinstance(x, (int, float)) or not isinstance(y, (int, float)):
            raise ValueError("마스크 윤곽 좌표가 숫자가 아닙니다.")
        if not math.isfinite(x) or not math.isfinite(y) or not 0 <= x <= 1 or not 0 <= y <= 1:
            raise ValueError("마스크 윤곽 좌표가 0~1 범위를 벗어났습니다.")
        points.append((float(x), float(y)))
    return points


def request_polygon(image_path: Path, target: str, model: str) -> tuple[list[tuple[float, float]], float]:
    api_key = os.environ.get("OPENAI_API_KEY")
    if not api_key:
        raise ValueError("텍스트 기반 mask 생성 전 OPENAI_API_KEY 환경 변수를 설정해 주세요.")

    with Image.open(image_path) as opened:
        image = opened.convert("RGB")
    image.thumbnail((2048, 2048), Image.Resampling.LANCZOS)
    image_buffer = io.BytesIO()
    image.save(image_buffer, format="JPEG", quality=88, optimize=True)
    data_url = "data:image/jpeg;base64," + base64.b64encode(image_buffer.getvalue()).decode("ascii")
    instruction = (
        "Find the exact visible object described below and trace its complete visible outer contour as a polygon. "
        'Return the polygon as an array of objects like [{"x":0.1,"y":0.2},...], with 12 to 40 ordered points. '
        "Include the full printed mark and its faint edge, "
        "but exclude unrelated reflections and nearby objects. Coordinates must be normalized to the supplied image: "
        "origin at upper-left, x and y from 0 to 1. If the target is absent or cannot be located confidently, "
        'return found=false and an empty polygon. Return only JSON with keys found, confidence, polygon. '
        f"Target: {target}"
    )
    body = json.dumps({
        "model": model,
        "reasoning_effort": "low",
        "response_format": {"type": "json_object"},
        "messages": [{
            "role": "user",
            "content": [
                {"type": "text", "text": instruction},
                {"type": "image_url", "image_url": {"url": data_url, "detail": "high"}},
            ],
        }],
    }).encode("utf-8")
    request = urllib.request.Request(
        "https://api.openai.com/v1/chat/completions",
        data=body,
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
        method="POST",
    )
    try:
        import certifi
        context = ssl.create_default_context(cafile=certifi.where())
        with urllib.request.urlopen(request, timeout=120, context=context) as response:
            payload = json.loads(response.read())
        content = payload["choices"][0]["message"]["content"]
        result = json.loads(content)
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"마스크 생성 API가 HTTP {error.code}을 반환했습니다.") from None
    except urllib.error.URLError:
        raise RuntimeError("마스크 생성 API에 연결하지 못했습니다.") from None
    except (json.JSONDecodeError, KeyError, IndexError, TypeError):
        raise RuntimeError("마스크 생성 API 응답을 읽지 못했습니다.") from None

    if result.get("found") is not True:
        raise ValueError("사진에서 설명한 단서를 찾지 못했습니다. 다른 설명으로 다시 시도해 주세요.")
    confidence = result.get("confidence")
    if not isinstance(confidence, (int, float)) or not 0 <= confidence <= 1:
        raise ValueError("마스크 생성 모델이 유효한 신뢰도를 반환하지 않았습니다.")
    return polygon_points(result.get("polygon")), float(confidence)


def create_polygon_mask(
    width: int, height: int, points: list[tuple[float, float]], destination: Path, margin_px: int
) -> None:
    editable = Image.new("L", (width, height), 0)
    pixel_points = [(round(x * (width - 1)), round(y * (height - 1))) for x, y in points]
    ImageDraw.Draw(editable).polygon(pixel_points, fill=255)
    if margin_px > 0:
        diameter = margin_px * 2 + 1
        editable = editable.filter(ImageFilter.MaxFilter(diameter))
    alpha = editable.point(lambda value: 255 - value)
    mask = Image.new("RGBA", (width, height), (0, 0, 0, 255))
    mask.putalpha(alpha)
    mask.save(destination, format="PNG", optimize=True)
    if destination.stat().st_size > MAX_MASK_BYTES:
        destination.unlink(missing_ok=True)
        raise ValueError("생성된 mask가 4MB를 초과합니다. 사진 크기를 줄여 다시 시도해 주세요.")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="텍스트 단서와 사진으로 실험용 AI 윤곽 mask를 만듭니다.")
    parser.add_argument("--image", type=Path, required=True, help="저장소 바깥에 둔 입력 사진 경로")
    parser.add_argument("--target", required=True, help="마스크로 지정할 위치 단서를 설명하는 텍스트")
    parser.add_argument("--model", default="gpt-6.1-terra", help="이미지에서 단서 윤곽을 찾는 비전 모델")
    parser.add_argument("--margin-px", type=int, default=24, help="윤곽 바깥으로 넓힐 픽셀 수")
    parser.add_argument("--output-dir", type=Path,
                        default=Path.home() / "Downloads" / "kkokkkok-edit-eval" / "masks")
    return parser


def run(args: argparse.Namespace) -> Path:
    if not args.target.strip():
        raise ValueError("수정할 위치 단서 설명을 비워둘 수 없습니다.")
    if args.margin_px < 0 or args.margin_px > 256:
        raise ValueError("mask 여백은 0~256px 범위로 지정해 주세요.")
    image_path = args.image.expanduser().resolve()
    output_root = args.output_dir.expanduser().resolve()
    validate_paths(image_path, output_root)
    if not os.environ.get("OPENAI_API_KEY"):
        raise ValueError("텍스트 기반 mask 생성 전 OPENAI_API_KEY 환경 변수를 설정해 주세요.")

    run_dir = output_root / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") / uuid.uuid4().hex[:8]
    run_dir.mkdir(parents=True, exist_ok=False)
    source_path = run_dir / "source.png"
    mask_path = run_dir / "mask.png"
    preview_path = run_dir / "mask-preview.png"
    width, height, image_digest = prepare_image(image_path, source_path)
    points, confidence = request_polygon(source_path, args.target, args.model)
    create_polygon_mask(width, height, points, mask_path, args.margin_px)
    create_preview(source_path, mask_path, preview_path)
    (run_dir / "mask-plan.json").write_text(json.dumps({
        "maskModel": args.model,
        "inputSha256": image_digest,
        "orientedImageSize": {"width": width, "height": height},
        "target": args.target,
        "confidence": confidence,
        "polygonNormalized": [{"x": x, "y": y} for x, y in points],
        "marginPx": args.margin_px,
        "maskSource": "vision-generated-polygon",
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return run_dir


def main(argv: Sequence[str] | None = None) -> int:
    load_local_api_key()
    args = build_parser().parse_args(argv)
    try:
        output = run(args)
    except (ValueError, RuntimeError, OSError) as error:
        print(f"오류: {error}", file=sys.stderr)
        return 2
    print(f"AI가 만든 mask와 미리보기를 저장했습니다: {output}")
    print("mask-preview.png에서 표시 위치를 확인한 뒤 evaluate.py --mask-file로 편집을 실행하세요.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
