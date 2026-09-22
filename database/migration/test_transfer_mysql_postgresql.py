import importlib.util
import sys
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path


sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location(
    "transfer_mysql_postgresql", Path(__file__).with_name("transfer_mysql_postgresql.py")
)
transfer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(transfer)


class TransferTest(unittest.TestCase):
    def test_foreign_key_order_places_parents_before_children(self):
        order = transfer.parent_first_order(
            {"parent", "child", "grandchild"},
            {"child": {"parent"}, "grandchild": {"child"}},
        )
        self.assertEqual(order, ["parent", "child", "grandchild"])
        with self.assertRaisesRegex(ValueError, "cycle"):
            transfer.parent_first_order({"a", "b"}, {"a": {"b"}, "b": {"a"}})

    def test_mysql_bit_and_datetime_to_postgresql(self):
        zone = timezone(timedelta(hours=8))
        self.assertTrue(transfer.convert(b"\x01", "mysql", "boolean", zone))
        self.assertFalse(transfer.convert(b"\x00", "mysql", "boolean", zone))
        value = transfer.convert(datetime(2026, 9, 22, 12, 30), "mysql", "timestamp with time zone", zone)
        self.assertEqual(value, datetime(2026, 9, 22, 4, 30, tzinfo=timezone.utc))

    def test_postgresql_boolean_and_instant_to_mysql(self):
        zone = timezone(timedelta(hours=8))
        self.assertEqual(transfer.convert(True, "postgresql", "bit", zone), 1)
        value = transfer.convert(datetime(2026, 9, 22, 4, 30, tzinfo=timezone.utc),
                                 "postgresql", "datetime", zone)
        self.assertEqual(value, datetime(2026, 9, 22, 12, 30))


if __name__ == "__main__":
    unittest.main()
