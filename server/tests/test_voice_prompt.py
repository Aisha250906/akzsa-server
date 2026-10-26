"""Pure-Python tests for the dictation prompt builder and guards (no web framework needed)."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import asr_guard, voice_prompt  # noqa: E402


class PromptBuilderTests(unittest.TestCase):
    def test_english_has_no_instruction_text(self):
        self.assertEqual(voice_prompt.build_asr_prompt("en"), ("", ""))

    def test_english_glossary_only(self):
        prompt, echo = voice_prompt.build_asr_prompt("en", "AKZSA, Rahul")
        self.assertEqual(prompt, "Glossary: AKZSA, Rahul.")
        self.assertEqual(prompt, echo)

    def test_hindi_and_kannada_instructions_unchanged(self):
        self.assertIn("Devanagari", voice_prompt.build_asr_prompt("hi")[0])
        self.assertIn("Kannada script", voice_prompt.build_asr_prompt("kn")[0])

    def test_hinglish_prompt_is_roman_example_not_only_an_instruction(self):
        prompt, _ = voice_prompt.build_asr_prompt("hinglish")
        self.assertIn("code-switched Hindi and English", prompt)
        self.assertIn("Roman letters", prompt)
        self.assertIn("Aaj meeting mein client ka invoice bhejna hai", prompt)
        self.assertTrue(prompt.isascii())

    def test_context_goes_last_and_is_not_part_of_the_echo_reference(self):
        prompt, echo = voice_prompt.build_asr_prompt("hinglish", "NEFEON, Groq", "Kal client ko bill bhejna hai")
        self.assertTrue(prompt.endswith("Kal client ko bill bhejna hai"))
        self.assertIn("Prefer these spellings when clearly spoken: NEFEON, Groq", prompt)
        self.assertNotIn("Kal client", echo)
        self.assertLessEqual(len(prompt), voice_prompt.PROMPT_MAX)

    def test_english_with_context_only_uses_the_context_as_previous_text(self):
        prompt, echo = voice_prompt.build_asr_prompt("en", "", "See you at five tomorrow")
        self.assertEqual(prompt, "See you at five tomorrow")
        self.assertEqual(echo, "")

    def test_prompt_never_exceeds_limit_and_keeps_the_context_end(self):
        prompt, _ = voice_prompt.build_asr_prompt("hi", "x" * 5000, "y" * 5000)
        self.assertLessEqual(len(prompt), voice_prompt.PROMPT_MAX)
        self.assertTrue(prompt.endswith("y" * 20))

    def test_clean_context_is_single_line_tail_starting_at_a_word(self):
        text = "line one\nline two\x00\x07 " + "word " * 100 + "final words here"
        cleaned = voice_prompt.clean_context(text)
        self.assertNotIn("\n", cleaned)
        self.assertLessEqual(len(cleaned), voice_prompt.CONTEXT_MAX)
        self.assertTrue(cleaned.endswith("final words here"))
        self.assertFalse(cleaned.startswith("ord"))

    def test_clean_context_handles_empty(self):
        self.assertEqual(voice_prompt.clean_context(""), "")
        self.assertEqual(voice_prompt.clean_context("   \n  "), "")

    def test_upload_meta_labels_wav_correctly(self):
        self.assertEqual(voice_prompt.upload_meta("audio/wav"), ("akzsa-dictation.wav", "audio/wav"))
        self.assertEqual(voice_prompt.upload_meta("audio/x-wav"), ("akzsa-dictation.wav", "audio/wav"))
        self.assertEqual(voice_prompt.upload_meta("audio/mp4"), ("akzsa-dictation.m4a", "audio/mp4"))
        self.assertEqual(voice_prompt.upload_meta("application/octet-stream"), ("akzsa-dictation.m4a", "audio/mp4"))


class ContextRepeatGuardTests(unittest.TestCase):
    CONTEXT = "Aaj meeting mein client ka invoice bhejna hai"

    def test_verbatim_repeat_of_six_or_more_words_is_rejected(self):
        self.assertTrue(asr_guard.is_context_repeat("Aaj meeting mein client ka invoice bhejna hai", self.CONTEXT))
        self.assertTrue(asr_guard.is_context_repeat("meeting mein client ka invoice bhejna", self.CONTEXT))

    def test_real_continuation_or_short_overlap_is_kept(self):
        self.assertFalse(asr_guard.is_context_repeat("client ka invoice", self.CONTEXT))
        self.assertFalse(asr_guard.is_context_repeat("Aur payment kal tak aa jayegi", self.CONTEXT))
        self.assertFalse(asr_guard.is_context_repeat("hello there", ""))
        self.assertFalse(asr_guard.is_context_repeat("", self.CONTEXT))


if __name__ == "__main__":
    unittest.main()
