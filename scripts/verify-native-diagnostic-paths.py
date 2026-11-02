#!/usr/bin/env python3
"""Check build-path rejection without modifying native input bytes."""
import unittest
from pathlib import Path
from native_diagnostic_paths import verify_no_build_paths

class DiagnosticsTest(unittest.TestCase):
    def test_source_and_generated_roots_are_rejected(self):
        for root in [Path('/build/fixture/source'), Path('/build/fixture/generated')]:
            for prefix in [b'\x00', b'\xff', b'diagnostic: ']:
                data = prefix + str(root).encode() + b'/example.cpp\x00'
                with self.assertRaises(ValueError):
                    verify_no_build_paths(data, [root])
    def test_inputs_are_untouched(self):
        data = b'\x7fELF\xff/src/llama.cpp/ggml.c\x00'
        before = bytes(data)
        verify_no_build_paths(data, [Path('/build/fixture')])
        self.assertEqual(data, before)
    def test_ambiguous_roots_rejected(self):
        for root in ['relative', '/', '/a']:
            with self.assertRaises(ValueError):
                verify_no_build_paths(b'input', [Path(root)])
if __name__ == '__main__':
    unittest.main()
