"""설계 문서(docs/backend-design.md)의 ERD가 실제 DB 표와 어긋나지 않는지 확인한다.

표나 칸을 추가하거나 바꿨는데 ERD 그림을 안 고치면 이 테스트가 실패한다.
(is_core, has_card 를 추가하고 ERD 를 고치지 않아 어긋났던 일에서 만들었다)
"""
import re
from pathlib import Path

import pytest

from app import models
from app.database import Base

DOC = Path(__file__).resolve().parent.parent / "docs" / "backend-design.md"


def erd_columns():
    """ERD(mermaid erDiagram)의 표 이름 -> 칸 이름 목록."""
    text = DOC.read_text(encoding="utf-8")
    start = text.index("erDiagram")
    block = text[start : text.index("```", start)]
    tables: dict[str, list[str]] = {}
    current = None
    for line in block.splitlines():
        entity = re.match(r"\s*([A-Z_]+)\s*\{\s*$", line)
        if entity:
            current = entity.group(1).lower()
            tables[current] = []
        elif current and line.strip() == "}":
            current = None
        elif current and line.strip():
            parts = line.split()
            tables[current].append(parts[1])  # "int id PK ..." 에서 두 번째가 칸 이름
    return tables


def model_columns():
    return {table.name: list(table.columns.keys()) for table in Base.metadata.sorted_tables}


def test_every_table_is_in_the_erd_and_nothing_extra():
    assert set(erd_columns()) == set(model_columns()), "ERD 의 표 목록이 실제 DB 표와 다릅니다"


@pytest.mark.parametrize("table", sorted(model_columns()))
def test_erd_lists_exactly_the_columns_of_each_table(table):
    erd = set(erd_columns().get(table, []))
    actual = set(model_columns()[table])
    missing = sorted(actual - erd)
    extra = sorted(erd - actual)
    assert not missing and not extra, (
        f"{table}: ERD 에 없는 칸 {missing}, 코드에 없는 칸 {extra}. docs/backend-design.md 의 ERD 를 고쳐 주세요."
    )


def test_the_core_and_card_columns_are_documented():
    columns = erd_columns()
    assert "is_core" in columns["courses"]
    assert "has_card" in columns["spots"]


def test_models_import_registers_all_tables():
    # models 를 import 하지 않으면 Base 에 표가 등록되지 않아 위 테스트가 비어 버린다
    assert models.Course.__tablename__ in model_columns()


# ---------------------------------------------------------------------------------------------
# API 필드와 DB 칸의 대응표 (6장 "API 필드와 DB 칸의 대응")
# ---------------------------------------------------------------------------------------------
import inspect

from pydantic import BaseModel

from app import recommend, schemas


def response_models():
    """대응표에 있어야 하는 모든 요청·응답 모델(app/schemas.py, app/recommend.py)."""
    found = {}
    for module in (schemas, recommend):
        for name, cls in inspect.getmembers(module, inspect.isclass):
            if issubclass(cls, BaseModel) and cls is not BaseModel and cls.__module__ == module.__name__:
                found[name] = cls
    return found


def mapping_section():
    """대응표 안의 모델 이름 -> 표의 첫 칸(API 필드 이름) 목록, 그리고 표 전체 글."""
    text = DOC.read_text(encoding="utf-8")
    start = text.index("### API 필드와 DB 칸의 대응")
    end = text.index("\n## ", start)
    body = text[start:end]
    sections: dict[str, list[str]] = {}
    current = None
    for line in body.splitlines():
        heading = re.match(r"####\s+.*\(`([A-Za-z]+)`\)", line)
        if heading:
            current = heading.group(1)
            sections[current] = []
        elif line.startswith("#"):
            current = None
        elif current:
            row = re.match(r"\|\s*`([a-z_0-9]+)`\s*\|", line)
            if row:
                sections[current].append(row.group(1))
    return sections, body


def test_every_request_and_response_model_is_in_the_mapping_table():
    sections, _ = mapping_section()
    missing = sorted(set(response_models()) - set(sections))
    extra = sorted(set(sections) - set(response_models()))
    assert not missing, f"대응표에 없는 모델 {missing}. docs/backend-design.md 6장의 대응표에 추가해 주세요."
    assert not extra, f"코드에 없는 모델이 대응표에 있습니다 {extra}"


@pytest.mark.parametrize("name", sorted(response_models()))
def test_mapping_table_lists_exactly_the_fields_of_each_model(name):
    sections, _ = mapping_section()
    fields = set(response_models()[name].model_fields)
    listed = set(sections.get(name, []))
    missing = sorted(fields - listed)
    extra = sorted(listed - fields)
    assert not missing and not extra, (
        f"{name}: 대응표에 없는 필드 {missing}, 코드에 없는 필드 {extra}. docs/backend-design.md 의 대응표를 고쳐 주세요."
    )


def test_every_table_dot_column_in_the_mapping_table_exists_in_the_database():
    _, body = mapping_section()
    tables = model_columns()
    refs = set(re.findall(r"`([a-z_]+)\.([a-z_]+)`", body))
    checked = [(t, c) for t, c in refs if t in tables]
    assert checked, "대응표에 DB 칸(표.칸) 표시가 하나도 없습니다"
    unknown = sorted((t, c) for t, c in checked if c not in tables[t])
    assert not unknown, f"대응표가 가리키는 DB 칸이 실제로 없습니다: {unknown}"


# ---------------------------------------------------------------------------------------------
# 예외 상황 표 (9장 "예외 상황과 동작")
# ---------------------------------------------------------------------------------------------
def exception_section():
    text = DOC.read_text(encoding="utf-8")
    start = text.index("## 9. 예외 상황과 동작")
    end = text.index("\n## ", start + 10)
    return text[start:end]


def cited_tests():
    """예외 상황 표의 "확인" 열이 가리키는 (테스트 파일, 테스트 이름) 목록."""
    return sorted(set(re.findall(r"`(tests/[A-Za-z0-9_]+\.py)::(test_[A-Za-z0-9_]+)`", exception_section())))


def test_the_exception_table_cites_many_tests():
    assert len(cited_tests()) >= 30, "예외 상황 표가 가리키는 테스트가 너무 적습니다"


@pytest.mark.parametrize("file,name", cited_tests())
def test_every_test_cited_in_the_exception_table_exists(file, name):
    path = DOC.parent.parent / file
    assert path.exists(), f"{file} 파일이 없습니다"
    assert re.search(rf"^def {name}\(", path.read_text(encoding="utf-8"), re.MULTILINE), (
        f"{file} 에 {name} 테스트가 없습니다. 테스트 이름을 바꿨다면 docs/backend-design.md 9장을 고쳐 주세요."
    )


def test_the_exception_table_covers_the_situations_the_checklist_names():
    section = exception_section()
    for situation in ("센서 미가동", "연구실 서버에 접속하지 못함", "앱이 우리 서버에 접속하지 못함", "오류값", "값이 오래됨"):
        assert situation in section, f"예외 상황 표에 '{situation}'이 없습니다"
