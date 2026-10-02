from __future__ import annotations

import socket
from types import SimpleNamespace
from uuid import UUID

import pytest
from googleapiclient.errors import HttpError

from conftest import offer_event
from tvde_contract.firestore_storage import CloudOutboxItem
from tvde_contract.google_sheets_projector import GoogleSheetsProjector, ProjectionFailure
from tvde_contract.models import OfferEvent


class FakeRequest:
    def __init__(self, result=None, error=None):
        self.result = result
        self.error = error

    def execute(self, num_retries=0):
        assert num_retries == 0
        if self.error:
            raise self.error
        return self.result


class FakeValues:
    def __init__(self, request):
        self.request = request
        self.calls = []

    def update(self, **kwargs):
        self.calls.append(kwargs)
        return self.request


class FakeService:
    def __init__(self, request):
        self.values_api = FakeValues(request)

    def spreadsheets(self):
        return self

    def values(self):
        return self.values_api


def item(row=7):
    event = OfferEvent.model_validate(offer_event("00000000-0000-0000-0000-000000000001"))
    return CloudOutboxItem("doc", "owner", event.event_id, event, 1, row)


def test_projection_uses_deterministic_row_and_is_safe_to_repeat():
    service = FakeService(FakeRequest({"updatedRows": 1}))
    projector = GoogleSheetsProjector("sheet", "Events", service=service)
    projector.project(item())
    projector.project(item())
    assert [call["range"] for call in service.values_api.calls] == [
        "'Events'!A7:L7",
        "'Events'!A7:L7",
    ]
    assert service.values_api.calls[0]["body"] == service.values_api.calls[1]["body"]


def test_projection_initializes_deterministic_headers():
    service = FakeService(FakeRequest({"updatedRows": 1}))
    projector = GoogleSheetsProjector("sheet", "Events", service=service)

    projector.ensure_headers()

    assert service.values_api.calls[0]["range"] == "'Events'!A1:L1"
    assert service.values_api.calls[0]["body"]["values"] == [GoogleSheetsProjector.HEADERS]


@pytest.mark.parametrize(
    ("status", "code"),
    [(401, "SHEETS_AUTH"), (403, "SHEETS_AUTH"), (429, "SHEETS_QUOTA"), (500, "SHEETS_UNAVAILABLE")],
)
def test_projection_classifies_google_failures(status, code):
    response = SimpleNamespace(status=status, reason="error")
    error = HttpError(response, b'{"error":{"message":"test"}}')
    projector = GoogleSheetsProjector("sheet", "Events", service=FakeService(FakeRequest(error=error)))
    with pytest.raises(ProjectionFailure, match=code):
        projector.project(item())


def test_projection_classifies_timeout_and_invalid_response():
    timeout_projector = GoogleSheetsProjector(
        "sheet", "Events", service=FakeService(FakeRequest(error=socket.timeout()))
    )
    with pytest.raises(ProjectionFailure, match="SHEETS_NETWORK"):
        timeout_projector.project(item())
    invalid_projector = GoogleSheetsProjector(
        "sheet", "Events", service=FakeService(FakeRequest({"updatedRows": 0}))
    )
    with pytest.raises(ProjectionFailure, match="SHEETS_INVALID_RESPONSE"):
        invalid_projector.project(item())
