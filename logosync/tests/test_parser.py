import pytest

from logosync.core.names import abbreviation_problem, normalize_name
from logosync.core.parser import Record, parse_datagram, parse_text


@pytest.mark.parametrize("text", [
    "( Miami, MIAM )",
    "(Miami,MIAM)",
    "  (  Miami ,   MIAM  )  ",
    "Miami, MIAM",
    "Miami,MIAM\r\n",
    '("Miami", "MIAM")',
    "(MIAM, Miami)",           # swapped order
    "Miami\tMIAM",
])
def test_single_record_variations(text):
    assert parse_text(text).records == [Record("Miami", "MIAM")]


def test_multiple_records_per_datagram():
    for text in ["(Miami, MIAM)(Clemson, CLEM)", "(Miami, MIAM), (Clemson, CLEM)",
                 "Miami, MIAM\nClemson, CLEM", "Miami, MIAM; Clemson, CLEM",
                 '[{"name": "Miami", "abbr": "MIAM"}, {"Team": "Clemson", "Abbreviation": "CLEM"}]']:
        assert parse_text(text).records == [Record("Miami", "MIAM"), Record("Clemson", "CLEM")], text


def test_parentheses_inside_name():
    assert parse_text("( Miami (OH), M-OH )( Miami, MIAM )").records == [
        Record("Miami (OH)", "M-OH"), Record("Miami", "MIAM")]
    assert parse_text("Miami (OH), M-OH").records == [Record("Miami (OH)", "M-OH")]


def test_last_comma_splits():
    assert parse_text("(Washington, D.C., WSH)").records == [Record("Washington, D.C.", "WSH")]


def test_inner_spaces_collapsed_case_kept():
    assert parse_text("( Florida   State , fsu )").records == [Record("Florida State", "fsu")]


def test_malformed_records_reported():
    r = parse_text("(Miami MIAM)(Clemson, )(, CLEM)(Duke, DUKE)")
    assert r.records == [Record("Duke", "DUKE")]
    assert len(r.errors) == 3


def test_bytes_decoding():
    assert parse_datagram("(Montréal, MTL)".encode("utf-8")).records == [Record("Montréal", "MTL")]
    assert parse_datagram("(Montréal, MTL)".encode("cp1252")).records == [Record("Montréal", "MTL")]
    assert parse_datagram(b"\x00(Miami, MIAM)\x00").records == [Record("Miami", "MIAM")]


def test_empty_and_noise():
    assert parse_text("").records == []
    assert parse_text("   \r\n ").errors == []


def test_normalize_name():
    assert normalize_name("  MIAMI  ") == normalize_name("miami")
    assert normalize_name("Montréal") == normalize_name("MONTREAL")
    assert normalize_name("Miami (OH)") != normalize_name("Miami")


@pytest.mark.parametrize("abbr,ok", [
    ("MIAM", True), ("M-OH", True), ("TA&M", True), ("fsu", True), ("A_B", True),
    ("", False), ("MI AM", False), ("A/B", False), ("A:B", False), ("A.B", False),
    ("CON", False), ("nul", False), ("COM1", False), ("LPT9", False), ("X" * 21, False),
])
def test_abbreviation_problem(abbr, ok):
    assert (abbreviation_problem(abbr) is None) == ok
