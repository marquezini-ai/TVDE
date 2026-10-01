# API v1 contract test matrix

The automated suite is the executable subset of this matrix. External-service cases remain fakes until Phase 3B.2.

## Registration

- valid CLIENT and server policy;
- invalid, expired and revoked license;
- repeated registration with same key;
- new key for bound license;
- same idempotency key/body;
- invalid signature;
- role spoof field;
- nonce replay;
- stale timestamp;
- revoked installation.

## Sync

- empty sync;
- one event and event batch;
- same request repeated;
- simulated transport timeout after commit and exact idempotent recovery;
- idempotency key reused with different body;
- same event in different requests;
- same event ID with different payload;
- eight concurrent requests for one event;
- valid delta cursor;
- invalid and expired cursors;
- invalid field, malformed JSON, missing signed header, oversized body and rate limit;
- more than 100 events, event timestamp outside retention bounds and aggregate query longer than 366 days;
- unknown and revoked installation;
- CLIENT cannot send role and no admin endpoint exists.

## Cryptographic binding

- body changed after signature;
- path changed;
- installation/principal changed;
- signature made by another installation key;
- nonce reused;
- body hash mismatch.

## Deferred integration tests

- durable restart of nonce/idempotency/cursor state;
- transactional Firestore concurrency;
- Google projection retry and reconciliation;
- Cloud Run request-size and timeout behavior;
- Android Keystore interoperability vector;
- physical offline/recovery tests on S10 and S25.
