import argparse
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from PIL import Image, ImageDraw

import evaluate
from evaluate import Region, create_mask, crop_box_for_mask, prepare_image, run, validate_paths
from mask_from_text import create_polygon_mask, polygon_points
from preserve_outside import composite_preserved


class RegionTests(unittest.TestCase):
    def test_parses_normalized_region(self):
        self.assertEqual(Region.parse("0.1, 0.2, 0.3, 0.4"), Region(.1, .2, .3, .4))

    def test_rejects_region_outside_image(self):
        with self.assertRaises(argparse.ArgumentTypeError):
            Region.parse("0.9,0.2,0.2,0.4")

    def test_crop_box_keeps_mask_and_source_aspect_ratio(self):
        mask = Image.new("RGBA", (100, 200), (0, 0, 0, 255))
        ImageDraw.Draw(mask).rectangle((40, 80, 59, 119), fill=(0, 0, 0, 0))
        self.assertEqual(crop_box_for_mask(mask, 2.5), (25, 50, 75, 150))


class TextMaskTests(unittest.TestCase):
    def test_validates_normalized_polygon(self):
        self.assertEqual(
            polygon_points([{"x": 0.1, "y": 0.2}, {"x": 0.8, "y": 0.2}, {"x": 0.5, "y": 0.9}]),
            [(0.1, 0.2), (0.8, 0.2), (0.5, 0.9)],
        )
        with self.assertRaises(ValueError):
            polygon_points([{"x": 1.1, "y": 0.2}, {"x": 0.8, "y": 0.2}, {"x": 0.5, "y": 0.9}])

    def test_polygon_mask_only_opens_alpha_inside_shape(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "mask.png"
            points = [(0.2, 0.2), (0.8, 0.2), (0.5, 0.8)]
            create_polygon_mask(100, 100, points, path, margin_px=0)
            with Image.open(path) as mask:
                self.assertEqual(mask.getpixel((50, 40))[3], 0)
                self.assertEqual(mask.getpixel((5, 5))[3], 255)


class PreserveOutsideTests(unittest.TestCase):
    def test_keeps_unmasked_original_pixels_even_with_feathering(self):
        source = Image.new("RGB", (20, 20), (30, 60, 90))
        edited = Image.new("RGB", (10, 10), (240, 180, 120))
        mask = Image.new("RGBA", (20, 20), (0, 0, 0, 255))
        ImageDraw.Draw(mask).rectangle((5, 5, 14, 14), fill=(0, 0, 0, 0))

        result = composite_preserved(source, edited, mask, feather_px=2)

        for y in range(source.height):
            for x in range(source.width):
                if mask.getpixel((x, y))[3] == 255:
                    self.assertEqual(result.getpixel((x, y)), source.getpixel((x, y)))
        self.assertNotEqual(result.getpixel((9, 9)), source.getpixel((9, 9)))

    def test_rejects_different_aspect_ratio(self):
        with self.assertRaisesRegex(ValueError, "가로·세로 비율"):
            composite_preserved(
                Image.new("RGB", (20, 20)),
                Image.new("RGB", (10, 5)),
                Image.new("RGBA", (20, 20), (0, 0, 0, 0)),
            )

    def test_expansion_uses_ai_pixels_in_extra_edge_margin(self):
        source = Image.new("RGB", (20, 20), (30, 60, 90))
        edited = Image.new("RGB", (20, 20), (240, 180, 120))
        mask = Image.new("RGBA", (20, 20), (0, 0, 0, 255))
        ImageDraw.Draw(mask).rectangle((8, 8, 11, 11), fill=(0, 0, 0, 0))

        result = composite_preserved(source, edited, mask, feather_px=0, expand_px=2)

        self.assertNotEqual(result.getpixel((6, 9)), source.getpixel((6, 9)))
        self.assertEqual(result.getpixel((5, 9)), source.getpixel((5, 9)))


class ImagePreparationTests(unittest.TestCase):
    def test_mask_has_transparent_edit_area_and_opaque_background(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "mask.png"
            pixels = create_mask(100, 80, Region(.2, .25, .3, .25), path)
            self.assertEqual(pixels, (20, 20, 50, 40))
            with Image.open(path) as mask:
                self.assertEqual(mask.size, (100, 80))
                self.assertEqual(mask.getpixel((30, 30))[3], 0)
                self.assertEqual(mask.getpixel((10, 10))[3], 255)

    def test_prepare_image_applies_exif_orientation_and_strips_exif(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.jpg"
            cleaned = Path(directory) / "cleaned.png"
            image = Image.new("RGB", (40, 20), (20, 80, 120))
            exif = Image.Exif()
            exif[274] = 6  # Rotate 90 degrees clockwise when displayed.
            exif[271] = "synthetic-camera"
            image.save(source, exif=exif)

            width, height, digest = prepare_image(source, cleaned)

            self.assertEqual((width, height), (20, 40))
            self.assertEqual(len(digest), 64)
            with Image.open(cleaned) as output:
                self.assertEqual(output.format, "PNG")
                self.assertEqual(len(output.getexif()), 0)


class SafetyAndRunTests(unittest.TestCase):
    def test_loads_key_from_local_env_file_without_overriding_environment(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".env").write_text('# local only\nOPENAI_API_KEY="test-key"\nOTHER=value\n', encoding="utf-8")
            with patch.object(evaluate, "REPOSITORY_ROOT", root), patch.dict("os.environ", {}, clear=True):
                evaluate.load_local_api_key()
                self.assertEqual(evaluate.os.environ["OPENAI_API_KEY"], "test-key")
            with patch.object(evaluate, "REPOSITORY_ROOT", root), patch.dict(
                "os.environ", {"OPENAI_API_KEY": "shell-key"}, clear=True
            ):
                evaluate.load_local_api_key()
                self.assertEqual(evaluate.os.environ["OPENAI_API_KEY"], "shell-key")

    def test_rejects_repo_paths_for_photo_or_output(self):
        with tempfile.NamedTemporaryFile(dir=Path(__file__).resolve().parents[2]) as photo:
            with self.assertRaises(ValueError):
                validate_paths(Path(photo.name), Path(tempfile.gettempdir()))
        with tempfile.TemporaryDirectory() as directory:
            outside_photo = Path(directory) / "photo.png"
            Image.new("RGB", (8, 8)).save(outside_photo)
            with self.assertRaisesRegex(ValueError, "저장소 바깥"):
                validate_paths(outside_photo, Path(__file__).resolve().parents[2] / "private-data")

    def test_dry_run_keeps_all_artifacts_outside_repo(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            photo = root / "synthetic.png"
            Image.new("RGB", (32, 24), (120, 140, 160)).save(photo)
            args = type("Args", (), {
                "image": photo,
                "output_dir": root / "results",
                "region": Region(.25, .25, .5, .5),
                "mask_file": None,
                "crop_context_scale": None,
                "target": "synthetic sign",
                "preserve": "synthetic cup",
                "model": "gpt-image-2.5-sunburst",
                "quality": "high",
                "dry_run": True,
            })()

            result = run(args)

            self.assertEqual({p.name for p in result.iterdir()}, {"source.png", "mask.png", "mask-preview.png", "run.json", "review.md"})
            manifest = json.loads((result / "run.json").read_text(encoding="utf-8"))
            self.assertEqual(manifest["status"], "dry-run")
            self.assertNotIn(str(photo), (result / "run.json").read_text(encoding="utf-8"))

    def test_dry_run_accepts_external_alpha_mask_without_region(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            photo = root / "synthetic.png"
            mask = root / "mask.png"
            Image.new("RGB", (32, 24), (120, 140, 160)).save(photo)
            alpha = Image.new("L", (32, 24), 255)
            ImageDraw.Draw(alpha).rectangle((8, 6, 20, 18), fill=0)
            mask_image = Image.new("RGBA", (32, 24), (0, 0, 0, 255))
            mask_image.putalpha(alpha)
            mask_image.save(mask)
            args = type("Args", (), {
                "image": photo,
                "output_dir": root / "results",
                "region": None,
                "mask_file": mask,
                "crop_context_scale": None,
                "target": "synthetic sign",
                "preserve": "synthetic cup",
                "model": "gpt-image-2.5-sunburst",
                "quality": "high",
                "dry_run": True,
            })()

            result = run(args)

            manifest = json.loads((result / "run.json").read_text(encoding="utf-8"))
            self.assertEqual(manifest["maskSource"], "file")
            with Image.open(result / "mask.png") as saved_mask:
                self.assertEqual(saved_mask.size, (32, 24))

    def test_dry_run_prepares_context_crop_from_mask(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            photo = root / "synthetic.png"
            mask = root / "mask.png"
            Image.new("RGB", (100, 120), (120, 140, 160)).save(photo)
            alpha = Image.new("L", (100, 120), 255)
            ImageDraw.Draw(alpha).rectangle((40, 45, 59, 74), fill=0)
            alpha_mask = Image.new("RGBA", (100, 120), (0, 0, 0, 255))
            alpha_mask.putalpha(alpha)
            alpha_mask.save(mask)
            args = type("Args", (), {
                "image": photo,
                "output_dir": root / "results",
                "region": None,
                "mask_file": mask,
                "crop_context_scale": 2.5,
                "target": "synthetic clue",
                "preserve": "synthetic scene",
                "model": "gpt-image-2.5-sunburst",
                "quality": "high",
                "dry_run": True,
            })()

            result = run(args)

            manifest = json.loads((result / "run.json").read_text(encoding="utf-8"))
            self.assertEqual(manifest["editMode"], "crop-and-mask-composite")
            self.assertTrue((result / "edit-source.png").is_file())
            self.assertTrue((result / "edit-mask.png").is_file())

    def test_requires_api_key_before_copying_photo(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            photo = root / "synthetic.png"
            Image.new("RGB", (32, 24), (120, 140, 160)).save(photo)
            args = type("Args", (), {
                "image": photo,
                "output_dir": root / "results",
                "region": Region(.25, .25, .5, .5),
                "mask_file": None,
                "crop_context_scale": None,
                "target": "synthetic sign",
                "preserve": "synthetic cup",
                "model": "gpt-image-2.5-sunburst",
                "quality": "high",
                "dry_run": False,
            })()
            with patch.dict("os.environ", {"OPENAI_API_KEY": ""}):
                with self.assertRaisesRegex(ValueError, "OPENAI_API_KEY"):
                    run(args)
            self.assertFalse((root / "results").exists())


if __name__ == "__main__":
    unittest.main()
