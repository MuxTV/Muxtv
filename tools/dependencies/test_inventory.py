import unittest
from inventory import actions_in


class InventoryTest(unittest.TestCase):
    def test_actions_local_and_pinned(self):
        sha = "a" * 40
        self.assertEqual(actions_in(
            f"      - uses: actions/checkout@{sha} # v7\n"
            "        uses: ./.github/actions/setup-muxtv-jdks\n"
            "        uses: 'actions/setup-java@v5'\n"
            "        # uses: ignored/action@main\n"),
            [f"actions/checkout@{sha}", "./.github/actions/setup-muxtv-jdks",
             "actions/setup-java@v5"])


if __name__ == "__main__":
    unittest.main()
