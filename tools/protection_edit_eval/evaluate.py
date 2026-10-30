#!/usr/bin/env python3
"""Run a local, manually-scoped photo edit evaluation against the OpenAI Images API."""

from __future__ import annotations

import argparse
import base64
import hashlib
import io
import json
import math
import os
import ssl
import sys
import urllib.error
import urllib.request
import uuid
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Sequence

import certifi
from PIL import Image, ImageDraw, ImageOps


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
MAX_IMAGE_BYTES = 50 * 1024 * 1024
MAX_MASK_BYTES = 4 * 1024 * 1024


def load_local_api_key() -> None:
    """Read only OPENAI_API_KEY from the ignored repository-root .env file."""
    env_path = REPOSITORY_ROOT / ".env"
    try:
        lines = env_path.read_text(encoding="utf-8").splitlines()
    except FileNotFoundError:
        return
    for line in lines:
        candidate = line.strip()
        if not candidate or candidate.startswith("#") or "=" not in candidate:
            continue
        name, value = candidate.split("=", 1)
        if name.strip() != "OPENAI_API_KEY" or os.environ.get("OPENAI_API_KEY"):
            continue
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
            value = value[1:-1]
        os.environ["OPENAI_API_KEY"] = value
        return


@dataclass(frozen=True)
class Region:
    """Normalized x, y, width, height; origin is the upper-left corner."""

    x: float
    y: float
    width: float
    height: float

    @classmethod
    def parse(cls, value: str) -> "Region":
        try:
            values = [float(part.strip()) for part in value.split(",")]
        except ValueError as error:
            raise argparse.ArgumentTypeError("영역은 x,y,width,height 숫자로 입력해 주세요.") from error
        if len(values) != 4 or not all(math.isfinite(item) for item in values):
            raise argparse.ArgumentTypeError("영역은 x,y,width,height 숫자 4개여야 합니다.")
        region = cls(*values)
        if (
            region.x < 0
            or region.y < 0
            or region.width <= 0
            or region.height <= 0
            or region.x + region.width > 1
            or region.y + region.height > 1
        ):
            raise argparse.ArgumentTypeError("영역은 0~1 범위 안에 있는 양수 크기의 사각형이어야 합니다.")
        return region


def is_within(path: Path, directory: Path) -> bool:
    try:
        path.resolve().relative_to(directory.resolve())
        return True
    except ValueError:
        return False


def validate_paths(image_path: Path, output_root: Path) -> None:
    if not image_path.is_file():
        raise ValueError("입력 사진 파일을 찾을 수 없습니다.")
    if is_within(image_path, REPOSITORY_ROOT):
        raise ValueError("실제 사진은 저장소 바깥 경로에서 선택해 주세요.")
    if is_within(output_root, REPOSITORY_ROOT):
        raise ValueError("실험 결과는 저장소 바깥 폴더에만 저장할 수 있습니다.")


def prepare_image(image_path: Path, destination: Path) -> tuple[int, int, str]:
    original_bytes = image_path.read_bytes()
    if len(original_bytes) > MAX_IMAGE_BYTES:
        raise ValueError("입력 사진은 50MB 이하여야 합니다.")
    digest = hashlib.sha256(original_bytes).hexdigest()
    try:
        with Image.open(image_path) as opened:
            # Some iPhone camera JPEGs are detected as MPO by Pillow because
            # they carry a multi-picture APP2 segment; the primary frame is
            # still a regular JPEG photograph and is safe to normalize here.
            if opened.format not in {"JPEG", "MPO", "PNG", "WEBP"}:
                raise ValueError("JPEG, PNG 또는 WebP 사진을 선택해 주세요.")
            icc_profile = opened.info.get("icc_profile")
            image = ImageOps.exif_transpose(opened).convert("RGB")
    except (OSError, Image.DecompressionBombError) as error:
        raise ValueError("사진을 읽지 못했거나 이미지 크기가 너무 큽니다.") from error
    image.save(destination, format="PNG", optimize=True, icc_profile=icc_profile)
    if destination.stat().st_size > MAX_IMAGE_BYTES:
        destination.unlink(missing_ok=True)
        raise ValueError("방향·위치 메타데이터를 제거한 사진이 50MB를 초과합니다.")
    return image.width, image.height, digest


def create_mask(width: int, height: int, region: Region, destination: Path) -> tuple[int, int, int, int]:
    left = max(0, min(width - 1, math.floor(region.x * width)))
    top = max(0, min(height - 1, math.floor(region.y * height)))
    right = max(left + 1, min(width, math.ceil((region.x + region.width) * width)))
    bottom = max(top + 1, min(height, math.ceil((region.y + region.height) * height)))

    mask = Image.new("RGBA", (width, height), (0, 0, 0, 255))
    ImageDraw.Draw(mask).rectangle((left, top, right - 1, bottom - 1), fill=(0, 0, 0, 0))
    mask.save(destination, format="PNG", optimize=True)
    if destination.stat().st_size > MAX_MASK_BYTES:
        destination.unlink(missing_ok=True)
        raise ValueError("생성된 mask가 4MB를 초과합니다. 사진 크기를 줄여 다시 시도해 주세요.")
    return left, top, right, bottom


def create_preview(source_path: Path, mask_path: Path, destination: Path) -> None:
    with Image.open(source_path) as opened:
        preview = opened.convert("RGBA")
    with Image.open(mask_path) as opened_mask:
        editable = opened_mask.getchannel("A").point(lambda alpha: 100 if alpha == 0 else 0)
    overlay = Image.new("RGBA", preview.size, (255, 50, 50, 0))
    overlay.putalpha(editable)
    Image.alpha_composite(preview, overlay).save(destination, format="PNG", optimize=True)


def crop_box_for_mask(mask: Image.Image, context_scale: float) -> tuple[int, int, int, int]:
    """Return a context crop around editable pixels while retaining the source aspect ratio."""
    if context_scale < 1.5 or context_scale > 8:
        raise ValueError("mask 주변 crop 배율은 1.5~8 범위여야 합니다.")
    editable = ImageOps.invert(mask.convert("RGBA").getchannel("A"))
    bounds = editable.getbbox()
    if bounds is None:
        raise ValueError("mask에 편집할 투명 영역이 없습니다.")
    left, top, right, bottom = bounds
    target_width, target_height = right - left, bottom - top
    source_width, source_height = mask.size
    source_ratio = source_width / source_height
    crop_width = max(target_width * context_scale, target_height * context_scale * source_ratio)
    crop_width = min(source_width, math.ceil(crop_width))
    crop_height = min(source_height, math.ceil(crop_width / source_ratio))
    center_x, center_y = (left + right) / 2, (top + bottom) / 2
    crop_left = max(0, min(source_width - crop_width, round(center_x - crop_width / 2)))
    crop_top = max(0, min(source_height - crop_height, round(center_y - crop_height / 2)))
    return crop_left, crop_top, crop_left + crop_width, crop_top + crop_height


def make_prompt(target: str, preserve: str) -> str:
    return (
        "Edit this photograph to reduce location-identifying information. "
        f"Naturally remove or replace the visible clue: {target}. "
        "The transparent region in the provided mask marks the area to edit. "
        "Blend the replacement into the surrounding perspective, lighting, texture, and focus. "
        "Keep all unmasked content, people, main subjects, composition, and photographic style unchanged. "
        "Do not add text, logos, signs, or new location clues. "
        f"Preserve these details: {preserve}"
    )


REVIEW_TEMPLATE = """# 편집 결과 품질 기록

각 항목을 1~5점으로 평가하고, 문제나 잘된 점을 메모합니다. 원본과 수정본을 나란히 비교하세요.
1점은 사용할 수 없을 만큼 부자연스럽거나 주요 요소가 손상된 상태, 3점은 사용 가능하지만 차이가 눈에 띄는 상태, 5점은 자연스럽고 보존 조건을 충족한 상태로 봅니다. 이 점수는 자동 판정이나 출시 기준이 아닙니다.

- 위치 단서 제거: __ / 5 — 단서가 실제로 사라졌고 주변에 식별 가능한 흔적이 남지 않았나요?
- 수정 자연스러움: __ / 5 — 경계, 원근, 조명, 그림자, 질감이 주변 장면과 자연스럽게 이어지나요?
- 사진 분위기 보존: __ / 5 — 전체 색감, 밝기, 촬영 분위기와 스타일이 유지됐나요?
- 보존 대상 유지: __ / 5 — 인물, 음식, 제품 등 지정한 대상의 모양과 세부가 바뀌지 않았나요?
- 비대상 영역 보존: __ / 5 — 지정 영역 밖의 구도와 세부가 불필요하게 바뀌지 않았나요?

눈에 띄는 아티팩트·새로 생긴 위치 단서:

총평 및 다음 시도에서 바꿀 조건:
"""


def encode_form_field(name: str, value: str, boundary: str) -> bytes:
    return (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="{name}"\r\n\r\n'
        f"{value}\r\n"
    ).encode("utf-8")


def encode_form_file(name: str, filename: str, content_type: str, content: bytes, boundary: str) -> bytes:
    header = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="{name}"; filename="{filename}"\r\n'
        f"Content-Type: {content_type}\r\n\r\n"
    ).encode("utf-8")
    return header + content + b"\r\n"


def request_edit(image_path: Path, mask_path: Path, prompt: str, model: str, quality: str) -> bytes:
    api_key = os.environ.get("OPENAI_API_KEY")
    if not api_key:
        raise ValueError("실제 편집 실행 전 OPENAI_API_KEY 환경 변수를 설정해 주세요.")

    boundary = f"----KkokKkokEditEval{uuid.uuid4().hex}"
    fields = [
        encode_form_field("model", model, boundary),
        encode_form_field("prompt", prompt, boundary),
        encode_form_field("quality", quality, boundary),
        encode_form_field("output_format", "png", boundary),
        encode_form_file("image", "source.png", "image/png", image_path.read_bytes(), boundary),
        encode_form_file("mask", "mask.png", "image/png", mask_path.read_bytes(), boundary),
    ]
    body = b"".join(fields) + f"--{boundary}--\r\n".encode("ascii")
    request = urllib.request.Request(
        "https://api.openai.com/v1/images/edits",
        data=body,
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "Accept": "application/json",
        },
        method="POST",
    )
    try:
        tls_context = ssl.create_default_context(cafile=certifi.where())
        with urllib.request.urlopen(request, timeout=300, context=tls_context) as response:
            payload = json.loads(response.read())
    except urllib.error.HTTPError as error:
        # Do not print response bodies; keep request diagnostics out of local logs.
        raise RuntimeError(f"이미지 편집 API가 HTTP {error.code}을 반환했습니다.") from None
    except urllib.error.URLError:
        raise RuntimeError("이미지 편집 API에 연결하지 못했습니다.") from None
    except (json.JSONDecodeError, KeyError, TypeError):
        raise RuntimeError("이미지 편집 API 응답을 읽지 못했습니다.") from None

    try:
        return base64.b64decode(payload["data"][0]["b64_json"], validate=True)
    except (KeyError, IndexError, TypeError, ValueError):
        raise RuntimeError("편집 결과 이미지가 응답에 없습니다.") from None


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="실제 사진과 지정한 마스크로 보호 편집 품질을 시험합니다.")
    parser.add_argument("--image", type=Path, required=True, help="저장소 바깥에 둔 입력 사진 경로")
    parser.add_argument("--region", type=Region.parse, metavar="X,Y,W,H",
                        help="직접 지정할 사각형 영역의 정규화 좌표 (각 값 0~1)")
    parser.add_argument("--mask-file", type=Path,
                        help="텍스트 기반 마스크 생성기 또는 외부 분석에서 만든 PNG 마스크")
    parser.add_argument("--target", required=True, help="지우거나 자연스럽게 바꿀 위치 단서")
    parser.add_argument("--preserve", required=True, help="그대로 유지해야 할 주요 대상·분위기")
    parser.add_argument("--model", default="gpt-image-2.5-sunburst", help="기본 비교 모델 (편집 정밀도 기준)")
    parser.add_argument("--quality", choices=("low", "medium", "high", "xhigh", "max"), default="high")
    parser.add_argument("--crop-context-scale", type=float,
                        help="mask 주변을 잘라 편집기에 보내고 mask 영역만 원본에 합성 (1.5~8배)")
    parser.add_argument("--output-dir", type=Path,
                        default=Path.home() / "Downloads" / "kkokkkok-edit-eval")
    parser.add_argument("--dry-run", action="store_true", help="API 호출 없이 사진 정리·mask·미리보기만 생성")
    return parser


def run(args: argparse.Namespace) -> Path:
    if not args.target.strip() or not args.preserve.strip():
        raise ValueError("수정 대상과 보존할 내용을 비워둘 수 없습니다.")
    image_path = args.image.expanduser().resolve()
    output_root = args.output_dir.expanduser().resolve()
    supplied_mask = args.mask_file.expanduser().resolve() if args.mask_file else None
    if (args.region is None) == (supplied_mask is None):
        raise ValueError("--region 또는 --mask-file 중 하나만 지정해 주세요.")
    if args.crop_context_scale is not None and not 1.5 <= args.crop_context_scale <= 8:
        raise ValueError("mask 주변 crop 배율은 1.5~8 범위여야 합니다.")
    validate_paths(image_path, output_root)
    if supplied_mask and (not supplied_mask.is_file() or is_within(supplied_mask, REPOSITORY_ROOT)):
        raise ValueError("mask PNG는 저장소 바깥의 파일을 지정해 주세요.")
    if not args.dry_run and not os.environ.get("OPENAI_API_KEY"):
        raise ValueError("실제 편집 실행 전 OPENAI_API_KEY 환경 변수를 설정해 주세요.")

    run_dir = output_root / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") / uuid.uuid4().hex[:8]
    run_dir.mkdir(parents=True, exist_ok=False)
    source_path = run_dir / "source.png"
    mask_path = run_dir / "mask.png"
    preview_path = run_dir / "mask-preview.png"

    width, height, image_digest = prepare_image(image_path, source_path)
    if supplied_mask:
        try:
            with Image.open(supplied_mask) as opened_mask:
                if opened_mask.size != (width, height) or "A" not in opened_mask.getbands():
                    raise ValueError("mask PNG는 방향 보정된 사진과 같은 크기이며 alpha 채널이 있어야 합니다.")
                opened_mask.convert("RGBA").save(mask_path, format="PNG", optimize=True)
        except OSError as error:
            raise ValueError("mask PNG를 읽을 수 없습니다.") from error
        region_px = None
    else:
        region_px = create_mask(width, height, args.region, mask_path)
    create_preview(source_path, mask_path, preview_path)
    prompt = make_prompt(args.target, args.preserve)
    edit_image_path, edit_mask_path = source_path, mask_path
    manifest = {
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "model": args.model,
        "quality": args.quality,
        "inputSha256": image_digest,
        "orientedImageSize": {"width": width, "height": height},
        "maskSource": "file" if supplied_mask else "manual-rectangle",
        "editMode": "full-image",
        "target": args.target,
        "preserve": args.preserve,
        "prompt": prompt,
        "status": "dry-run" if args.dry_run else "pending",
    }
    if args.region:
        manifest["regionNormalized"] = asdict(args.region)
        manifest["regionPixels"] = {
            "left": region_px[0], "top": region_px[1], "right": region_px[2], "bottom": region_px[3]
        }
    crop_box = None
    if args.crop_context_scale is not None:
        with Image.open(source_path) as source_image, Image.open(mask_path) as full_mask:
            crop_box = crop_box_for_mask(full_mask, args.crop_context_scale)
            edit_image_path = run_dir / "edit-source.png"
            edit_mask_path = run_dir / "edit-mask.png"
            source_image.crop(crop_box).save(edit_image_path, format="PNG", optimize=True)
            full_mask.crop(crop_box).save(edit_mask_path, format="PNG", optimize=True)
        manifest["editMode"] = "crop-and-mask-composite"
        manifest["cropContextScale"] = args.crop_context_scale
        manifest["cropBox"] = {"left": crop_box[0], "top": crop_box[1], "right": crop_box[2], "bottom": crop_box[3]}
    manifest_path = run_dir / "run.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (run_dir / "review.md").write_text(REVIEW_TEMPLATE, encoding="utf-8")

    if not args.dry_run:
        image_bytes = request_edit(edit_image_path, edit_mask_path, prompt, args.model, args.quality)
        try:
            with Image.open(io.BytesIO(image_bytes)) as output:
                output.verify()
            with Image.open(io.BytesIO(image_bytes)) as output:
                edited_image = output.convert("RGB")
        except (OSError, Image.DecompressionBombError):
            raise RuntimeError("API 응답이 유효한 이미지가 아닙니다.") from None
        if crop_box is None:
            (run_dir / "edited.png").write_bytes(image_bytes)
        else:
            edited_image.save(run_dir / "edited-crop.png", format="PNG", optimize=True)
            from preserve_outside import composite_preserved

            with Image.open(source_path) as source_image, Image.open(edit_mask_path) as crop_mask:
                source_image = source_image.convert("RGB")
                source_crop = source_image.crop(crop_box)
                final_crop = composite_preserved(source_crop, edited_image, crop_mask.convert("RGBA"), feather_px=12)
                source_image.paste(final_crop, (crop_box[0], crop_box[1]))
                source_image.save(run_dir / "edited.png", format="PNG", optimize=True,
                                  icc_profile=source_image.info.get("icc_profile"))
        manifest["status"] = "completed"
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return run_dir


def main(argv: Sequence[str] | None = None) -> int:
    load_local_api_key()
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        output = run(args)
    except (ValueError, RuntimeError, OSError) as error:
        print(f"오류: {error}", file=sys.stderr)
        return 2
    print(f"실험 파일은 지정한 로컬 결과 폴더에 저장했습니다: {output}")
    if args.dry_run:
        print("미리보기만 만들었고 API 호출은 하지 않았습니다.")
    else:
        print("완료된 결과: edited.png · 입력 사진 해시와 설정: run.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
